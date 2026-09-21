package com.storex.order;

import com.storex.order.client.InventoryClient;
import com.storex.order.dto.OrderRequest;
import com.storex.order.model.Order;
import com.storex.order.model.SagaCompensationLog;
import com.storex.order.repository.OrderRepository;
import com.storex.order.repository.SagaCompensationLogRepository;
import com.storex.order.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class OrderServiceRollbackTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private InventoryClient inventoryClient;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private SagaCompensationLogRepository sagaCompensationLogRepository;

    @Autowired
    private MockMvc mockMvc;

    private static final String TEST_PRODUCT = "PROD_TEST_LAPTOP";

    @BeforeEach
    public void setUp() {
        orderRepository.clear();
        sagaCompensationLogRepository.clear();
        // Đặt lại tồn kho ban đầu cho sản phẩm test là 10
        inventoryClient.setStock(TEST_PRODUCT, 10);
    }

    /**
     * KỊCH BẢN 1 (HAPPY PATH):
     * Đặt hàng thành công:
     * - Trừ kho 2 sản phẩm (10 -> 8).
     * - Đơn hàng được lưu thành công vào cơ sở dữ liệu với trạng thái PENDING.
     * - Trả về HTTP 200 OK.
     */
    @Test
    @DisplayName("Kịch bản 1: Đặt hàng thành công -> Trừ kho và lưu đơn hàng hợp lệ")
    public void testCreateOrder_Success() {
        OrderRequest request = new OrderRequest(TEST_PRODUCT, 2, false, false);

        ResponseEntity<Order> response = orderService.createOrder(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("PENDING", response.getBody().getStatus());

        // Kiểm tra tồn kho đã bị trừ từ 10 xuống 8
        assertEquals(8, inventoryClient.getStock(TEST_PRODUCT),
                "Tồn kho thực tế phải giảm từ 10 xuống 8 sau khi đặt hàng thành công");
    }

    /**
     * KỊCH BẢN 2 (VÁ LỖ HỔNG KHO TREO - YÊU CẦU B):
     * Lưu đơn hàng bị lỗi Database (simulateDbError = true):
     * - Bước 1: Trừ kho thành công (10 -> 7).
     * - Bước 2: Lưu DB quăng Exception.
     * - KHẮC PHỤC: Khối catch tự động kích hoạt GIAO DỊCH BÙ (restoreStock).
     * - KẾT QUẢ: Tồn kho được hoàn trả lại đúng 10, KHÔNG BỊ "HÀNG ẢO"!
     */
    @Test
    @DisplayName("Kịch bản 2: Lưu đơn lỗi DB -> Tự động kích hoạt bù trừ hoàn kho (Khắc phục cháy hàng ảo)")
    public void testCreateOrder_DatabaseError_TriggersCompensatingStockRestore() {
        int initialStock = inventoryClient.getStock(TEST_PRODUCT); // 10
        OrderRequest request = new OrderRequest(TEST_PRODUCT, 3, true, false);

        ResponseEntity<Order> response = orderService.createOrder(request);

        // Đơn hàng thất bại với HTTP 500
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("FAILED", response.getBody().getStatus());

        // KHẲNG ĐỊNH QUAN TRỌNG NHẤT:
        // Tồn kho không bị trừ oan! Nhờ có bước bù trừ (Compensating Transaction),
        // số lượng tồn kho vẫn bảo toàn ở mức 10!
        assertEquals(initialStock, inventoryClient.getStock(TEST_PRODUCT),
                "Tồn kho phải được hoàn trả lại đúng 10, giải quyết dứt điểm lỗi kho treo!");
    }

    /**
     * KỊCH BẢN 3 (GIẢI PHÁP NÂNG CAO SAGA LOG - YÊU CẦU C):
     * Khi chính bước bù trừ trực tiếp cũng bị lỗi mạng (simulateCompensateError = true):
     * - Bước hoàn kho trực tiếp thất bại do Network Timeout.
     * - Hệ thống ghi nhận vào bảng SAGA COMPENSATION LOG (Outbox).
     * - Khi bộ quét định kỳ (Worker) chạy lại, thực hiện Retry và hoàn trả kho thành công!
     */
    @Test
    @DisplayName("Kịch bản 3: Bù trừ trực tiếp bị lỗi mạng -> Lưu Saga Log và Retry thành công")
    public void testCreateOrder_CompensationFails_SavedToSagaLogAndRetried() {
        OrderRequest request = new OrderRequest(TEST_PRODUCT, 4, true, true);

        // Thực hiện tạo đơn
        ResponseEntity<Order> response = orderService.createOrder(request);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());

        // Do bước bù trực tiếp bị timeout, tồn kho tạm thời chưa được trả (10 - 4 = 6)
        assertEquals(6, inventoryClient.getStock(TEST_PRODUCT));

        // Nhưng hệ thống ĐÃ LƯU BẢN GHI BỒI THƯỜNG VÀO SAGA LOG
        List<SagaCompensationLog> pendingLogs = sagaCompensationLogRepository.findPendingLogs();
        assertEquals(1, pendingLogs.size(), "Bắt buộc phải có 1 bản ghi bù trừ PENDING_RETRY");
        SagaCompensationLog logItem = pendingLogs.get(0);
        assertEquals("INVENTORY_SERVICE", logItem.getTargetService());
        assertEquals("RESTORE_STOCK", logItem.getAction());
        assertEquals(4, logItem.getQuantity());

        // Giả lập Worker định kỳ quét và Retry khi mạng phục hồi
        int retriedSuccess = orderService.retryPendingCompensations();
        assertEquals(1, retriedSuccess, "Worker phải retry thành công 1 bản ghi");

        // Sau khi Retry thành công: Tồn kho đã được khôi phục về đúng 10!
        assertEquals(10, inventoryClient.getStock(TEST_PRODUCT),
                "Sau khi Worker retry, tồn kho được bảo toàn về đúng 10 (Eventual Consistency)!");

        // Trạng thái bản ghi chuyển sang COMPENSATED
        assertEquals("COMPENSATED", logItem.getStatus());
        assertTrue(sagaCompensationLogRepository.findPendingLogs().isEmpty(), "Không còn bản ghi nào bị treo");
    }

    /**
     * Kiểm tra endpoint REST API qua MockMvc
     */
    @Test
    @DisplayName("Kiểm tra REST API: Gọi POST /api/orders bù trừ thành công")
    public void testRestApi_CreateOrder_Rollback() throws Exception {
        String jsonRequest = "{\"productId\":\"" + TEST_PRODUCT + "\",\"quantity\":2,\"simulateDbError\":true,\"simulateCompensateError\":false}";

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value("FAILED"));

        // Xác nhận tồn kho vẫn giữ nguyên 10
        assertEquals(10, inventoryClient.getStock(TEST_PRODUCT));
    }
}
