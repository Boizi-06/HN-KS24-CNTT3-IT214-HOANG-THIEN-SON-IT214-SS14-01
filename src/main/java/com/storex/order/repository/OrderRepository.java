package com.storex.order.repository;

import com.storex.order.model.Order;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class OrderRepository {

    private final Map<String, Order> database = new ConcurrentHashMap<>();

    /**
     * Giả lập lưu đơn hàng vào Database.
     * Ném ngoại lệ khi có yêu cầu giả lập lỗi DB (DB Timeout / Connection Lost).
     */
    public Order save(Order order, boolean simulateDbError) {
        if (simulateDbError) {
            throw new RuntimeException("Lỗi Database: Database connection timeout (Connection refused / Deadlock)!");
        }
        database.put(order.getId(), order);
        return order;
    }

    public Optional<Order> findById(String id) {
        return Optional.ofNullable(database.get(id));
    }

    public Map<String, Order> findAll() {
        return database;
    }

    public void clear() {
        database.clear();
    }
}
