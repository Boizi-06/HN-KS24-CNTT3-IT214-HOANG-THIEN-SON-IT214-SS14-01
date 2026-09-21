package com.storex.order.controller;

import com.storex.order.dto.OrderRequest;
import com.storex.order.model.Order;
import com.storex.order.model.SagaCompensationLog;
import com.storex.order.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @PostMapping
    public ResponseEntity<Order> createOrder(@RequestBody OrderRequest request) {
        return orderService.createOrder(request);
    }

    @PostMapping("/retry-compensations")
    public ResponseEntity<?> retryCompensations() {
        int retriedCount = orderService.retryPendingCompensations();
        return ResponseEntity.ok(Map.of(
                "message", "Đã thực hiện bù trừ lại thành công",
                "compensatedCount", retriedCount
        ));
    }

    @GetMapping("/saga-logs")
    public ResponseEntity<List<SagaCompensationLog>> getSagaLogs() {
        return ResponseEntity.ok(orderService.getSagaCompensationLogRepository().findAll());
    }

    @GetMapping("/stock/{productId}")
    public ResponseEntity<?> getStock(@PathVariable String productId) {
        int stock = orderService.getInventoryClient().getStock(productId);
        return ResponseEntity.ok(Map.of(
                "productId", productId,
                "availableStock", stock
        ));
    }
}
