package com.storex.order.service;

import com.storex.order.client.InventoryClient;
import com.storex.order.dto.OrderRequest;
import com.storex.order.model.Order;
import com.storex.order.model.SagaCompensationLog;
import com.storex.order.repository.OrderRepository;
import com.storex.order.repository.SagaCompensationLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final InventoryClient inventoryClient;
    private final OrderRepository orderRepository;
    private final SagaCompensationLogRepository sagaCompensationLogRepository;

    public OrderService(InventoryClient inventoryClient,
                        OrderRepository orderRepository,
                        SagaCompensationLogRepository sagaCompensationLogRepository) {
        this.inventoryClient = inventoryClient;
        this.orderRepository = orderRepository;
        this.sagaCompensationLogRepository = sagaCompensationLogRepository;
    }

    /**
     * PHƯƠNG THỨC ĐẶT HÀNG ĐÃ ĐƯỢC CHỈNH SỬA & VÁ LỖ HỔNG "KHO TREO":
     *
     * Luồng xử lý Saga:
     * 1. Gọi Inventory Service để trừ số lượng tồn kho (T1).
     * 2. Tạo đối tượng đơn hàng ở trạng thái PENDING.
     * 3. Lưu đơn hàng vào cơ sở dữ liệu Order DB (T2).
     *
     * XỬ LÝ LỖ HỔNG (YÊU CẦU B & C):
     * - Nếu bước 3 (lưu đơn) gặp lỗi (DB Timeout, Deadlock, v.v.):
     *   -> KÍCH HOẠT GIAO DỊCH BÙ (Compensating Transaction C1): Gọi restoreStock để trả lại kho!
     * - Nếu chính việc gọi hoàn kho cũng bị lỗi mạng / timeout:
     *   -> Ghi nhận sự kiện vào Saga Compensation Log (Transactional Outbox) để cơ chế Retry định kỳ xử lý!
     */
    public ResponseEntity<Order> createOrder(OrderRequest request) {
        String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        log.info("========== BẮT ĐẦU QUY TRÌNH ĐẶT HÀNG (SAGA): ĐƠN HÀNG {} ==========", orderId);

        // BƯỚC 1: Gọi Inventory Service để trừ kho
        boolean stockDecreased = inventoryClient.decreaseStock(request.getProductId(), request.getQuantity());
        if (!stockDecreased) {
            log.warn("[Saga Step 1 Thất bại] Sản phẩm {} không đủ tồn kho để trừ.", request.getProductId());
            return ResponseEntity.badRequest().body(null); // Hết hàng trong kho (HTTP 400)
        }
        log.info("[Saga Step 1 Thành công] Đã trừ kho {} sản phẩm {}", request.getQuantity(), request.getProductId());

        // BƯỚC 2: Tạo đơn hàng
        Order order = new Order();
        order.setId(orderId);
        order.setProductId(request.getProductId());
        order.setQuantity(request.getQuantity());
        order.setStatus("PENDING");

        // BƯỚC 3: Lưu đơn hàng vào Database
        try {
            // Lưu vào DB (hỗ trợ cờ giả lập lỗi DB để test)
            orderRepository.save(order, request.isSimulateDbError());
            log.info("[Saga Step 2 Thành công] Đơn hàng {} đã được lưu vào Database thành công.", orderId);

        } catch (Exception e) {
            // 💥 PHÁT HIỆN SỰ CỐ: Lưu đơn thất bại
            log.error("💥 [LỖI HỆ THỐNG] Lưu đơn hàng {} thất bại: {}. BẮT ĐẦU KÍCH HOẠT BƯỚC BÙ (COMPENSATION)!",
                    orderId, e.getMessage());

            // GIAO DỊCH BÙ (COMPENSATING TRANSACTION): Hoàn lại hàng vào kho
            try {
                // Gọi sang InventoryService để trả lại đúng số lượng hàng đã trừ
                inventoryClient.restoreStock(request.getProductId(), request.getQuantity(), request.isSimulateCompensateError());
                log.info("✅ [BÙ TRỪ THÀNH CÔNG] Đã hoàn trả lại {} sản phẩm {} vào kho!",
                        request.getQuantity(), request.getProductId());

            } catch (Exception compensateException) {
                // ⚠️ BẪY NGUY HIỂM (YÊU CẦU C): Chính bước bù trừ cũng bị lỗi mạng / Timeout!
                log.error("🚨 [SỰ CỐ NGHIÊM TRỌNG] Hoàn kho trực tiếp thất bại: {}. " +
                        "Đang ghi nhận vào SAGA COMPENSATION LOG để Retry bất đồng bộ!",
                        compensateException.getMessage());

                // Ghi lại sự kiện cần bồi thường vào bảng Saga Log / Outbox
                SagaCompensationLog compensationLog = new SagaCompensationLog(
                        "SAGA-LOG-" + UUID.randomUUID().toString().substring(0, 8),
                        orderId,
                        "INVENTORY_SERVICE",
                        "RESTORE_STOCK",
                        request.getProductId(),
                        request.getQuantity(),
                        compensateException.getMessage()
                );
                sagaCompensationLogRepository.save(compensationLog);
                log.info("📝 [SAGA LOG RECORDED] Đã lưu bản ghi bồi thường {} vào cơ sở dữ liệu để Retry sau.",
                        compensationLog.getId());
            }

            // Đánh dấu đơn hàng thất bại và trả về HTTP 500
            order.setStatus("FAILED");
            order.setCancelReason("Lỗi lưu đơn hàng vào DB: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(order);
        }

        log.info("🎉 [SAGA HOÀN THÀNH] Đơn hàng {} hoàn thành xuất sắc!", orderId);
        return ResponseEntity.ok(order);
    }

    /**
     * CƠ CHẾ RETRY ĐỊNH KỲ (SCHEDULED RECONCILIATION WORKER):
     * Quét các bản ghi bồi thường chưa thành công (PENDING_RETRY) và thực hiện bù lại.
     */
    public int retryPendingCompensations() {
        List<SagaCompensationLog> pendingLogs = sagaCompensationLogRepository.findPendingLogs();
        log.info("[Saga Worker] Tìm thấy {} bản ghi bù trừ đang chờ xử lý lại...", pendingLogs.size());

        int successCount = 0;
        for (SagaCompensationLog logItem : pendingLogs) {
            try {
                log.info("[Saga Worker] Đang Retry bù kho cho đơn hàng {} (Sản phẩm: {}, Số lượng: {})...",
                        logItem.getOrderId(), logItem.getProductId(), logItem.getQuantity());

                // Thực hiện hoàn kho không ném lỗi (giả sử mạng đã phục hồi)
                inventoryClient.restoreStock(logItem.getProductId(), logItem.getQuantity(), false);

                // Cập nhật trạng thái log thành công
                logItem.setStatus("COMPENSATED");
                logItem.setUpdatedAt(LocalDateTime.now());
                sagaCompensationLogRepository.save(logItem);
                successCount++;
                log.info("[Saga Worker] ✅ Bù trừ thành công cho bản ghi {}", logItem.getId());

            } catch (Exception ex) {
                logItem.setRetryCount(logItem.getRetryCount() + 1);
                logItem.setErrorMessage(ex.getMessage());
                logItem.setUpdatedAt(LocalDateTime.now());
                sagaCompensationLogRepository.save(logItem);
                log.error("[Saga Worker] ❌ Retry thất bại cho bản ghi {}: {}", logItem.getId(), ex.getMessage());
            }
        }
        return successCount;
    }

    public OrderRepository getOrderRepository() {
        return orderRepository;
    }

    public InventoryClient getInventoryClient() {
        return inventoryClient;
    }

    public SagaCompensationLogRepository getSagaCompensationLogRepository() {
        return sagaCompensationLogRepository;
    }
}
