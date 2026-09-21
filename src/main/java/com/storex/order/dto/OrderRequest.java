package com.storex.order.dto;

import java.io.Serializable;

public class OrderRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String productId;
    private int quantity;
    private boolean simulateDbError;
    private boolean simulateCompensateError;

    public OrderRequest() {
    }

    public OrderRequest(String productId, int quantity) {
        this.productId = productId;
        this.quantity = quantity;
    }

    public OrderRequest(String productId, int quantity, boolean simulateDbError, boolean simulateCompensateError) {
        this.productId = productId;
        this.quantity = quantity;
        this.simulateDbError = simulateDbError;
        this.simulateCompensateError = simulateCompensateError;
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

    public boolean isSimulateDbError() {
        return simulateDbError;
    }

    public void setSimulateDbError(boolean simulateDbError) {
        this.simulateDbError = simulateDbError;
    }

    public boolean isSimulateCompensateError() {
        return simulateCompensateError;
    }

    public void setSimulateCompensateError(boolean simulateCompensateError) {
        this.simulateCompensateError = simulateCompensateError;
    }
}
