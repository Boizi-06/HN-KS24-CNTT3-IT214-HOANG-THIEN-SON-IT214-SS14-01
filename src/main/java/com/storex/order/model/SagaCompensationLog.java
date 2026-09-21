package com.storex.order.model;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Saga Compensation Log (hoặc Transactional Outbox):
 * Lưu lại thông tin hành động bù trừ khi gọi mạng sang Inventory-Service bị lỗi/timeout.
 * Đảm bảo tính nhất quán cuối cùng (Eventual Consistency) qua cơ chế Retry định kỳ.
 */
public class SagaCompensationLog implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    private String orderId;
    private String targetService; // "INVENTORY_SERVICE"
    private String action;        // "RESTORE_STOCK"
    private String productId;
    private int quantity;
    private String status;        // "PENDING_RETRY", "COMPENSATED", "FAILED_PERMANENT"
    private int retryCount;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public SagaCompensationLog() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.status = "PENDING_RETRY";
        this.retryCount = 0;
    }

    public SagaCompensationLog(String id, String orderId, String targetService, String action,
                               String productId, int quantity, String errorMessage) {
        this.id = id;
        this.orderId = orderId;
        this.targetService = targetService;
        this.action = action;
        this.productId = productId;
        this.quantity = quantity;
        this.errorMessage = errorMessage;
        this.status = "PENDING_RETRY";
        this.retryCount = 0;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getTargetService() {
        return targetService;
    }

    public void setTargetService(String targetService) {
        this.targetService = targetService;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return "SagaCompensationLog{" +
                "id='" + id + '\'' +
                ", orderId='" + orderId + '\'' +
                ", targetService='" + targetService + '\'' +
                ", action='" + action + '\'' +
                ", productId='" + productId + '\'' +
                ", quantity=" + quantity +
                ", status='" + status + '\'' +
                ", retryCount=" + retryCount +
                ", errorMessage='" + errorMessage + '\'' +
                '}';
    }
}
