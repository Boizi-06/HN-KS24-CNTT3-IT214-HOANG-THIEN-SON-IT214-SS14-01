package com.storex.order.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Giả lập Client kết nối sang Inventory-Service (hoặc FeignClient / RestTemplate).
 * Quản lý kho hàng của các sản phẩm và hỗ trợ trừ kho (decrease) cũng như hoàn kho (restore).
 áhdkjhsfdakfas */
@Component
public class InventoryClient {

    private static final Logger log = LoggerFactory.getLogger(InventoryClient.class);

    // Bảng lưu tồn kho giả lập: ProductId -> Available Stock
    private final Map<String, Integer> stockMap = new ConcurrentHashMap<>();

    public InventoryClient() {
        // Khởi tạo tồn kho ban đầu cho một số mặt hàng
        stockMap.put("PROD_IPHONE15", 10);
        stockMap.put("PROD_MACBOOK", 5);
        stockMap.put("PROD_DEFAULT", 100);
    }

    /**
     * Bước 1 trong Saga: Trừ số lượng tồn kho.
     *
     * @param productId mã sản phẩm
     * @param quantity số lượng cần trừ
     * @return true nếu trừ kho thành công, false nếu không đủ hàng
     */
    public synchronized boolean decreaseStock(String productId, int quantity) {
        int currentStock = stockMap.getOrDefault(productId, 50);
        log.info("[InventoryService] Yêu cầu trừ kho sản phẩm: {}, số lượng: {}. Tồn kho hiện tại: {}",
                productId, quantity, currentStock);

        if (currentStock < quantity) {
            log.warn("[InventoryService] Sản phẩm {} không đủ hàng (Hiện có: {}, Yêu cầu: {})",
                    productId, currentStock, quantity);
            return false;
        }

        stockMap.put(productId, currentStock - quantity);
        log.info("[InventoryService] Đã trừ kho thành công! Tồn kho mới của {}: {}",
                productId, stockMap.get(productId));
        return true;
    }

    /**
     * BƯỚC BÙ TRỪ (COMPENSATING TRANSACTION):
     * Hoàn trả lại số lượng tồn kho đã trừ khi đơn hàng gặp sự cố tạo thất bại.
     *
     * @param productId mã sản phẩm
     * @param quantity số lượng cần cộng trả lại kho
     * @param simulateNetworkTimeout cờ giả lập lỗi mạng / timeout
     * @return true nếu bù trừ thành công
     */
    public synchronized boolean restoreStock(String productId, int quantity, boolean simulateNetworkTimeout) {
        log.info("[InventoryService - COMPENSATING] Nhận yêu cầu HOÀN KHO sản phẩm: {}, số lượng hoàn trả: {}",
                productId, quantity);

        if (simulateNetworkTimeout) {
            log.error("[LỖI MẠNG BÙ TRỪ] Không thể kết nối tới InventoryService để hoàn kho (504 Gateway Timeout)!");
            throw new RuntimeException("Lỗi kết nối mạng khi hoàn kho: Connection timed out to InventoryService");
        }

        int currentStock = stockMap.getOrDefault(productId, 0);
        stockMap.put(productId, currentStock + quantity);
        log.info("[InventoryService - COMPENSATING] Hoàn trả kho THÀNH CÔNG! Tồn kho khôi phục của {}: {}",
                productId, stockMap.get(productId));
        return true;
    }

    public int getStock(String productId) {
        return stockMap.getOrDefault(productId, 0);
    }

    public void setStock(String productId, int stock) {
        stockMap.put(productId, stock);
    }
}
