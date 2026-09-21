# BÀI TẬP 1: VÁ LỖ HỔNG "KHO TREO" (ROLLBACK LOGIC TRONG SAGA PATTERN)

---

## 1. MỤC TIÊU BÀI HỌC
- Rèn luyện kỹ năng đọc hiểu mã nguồn kế thừa (legacy code) và phát hiện logic lỗi khi thiếu cơ chế bù trừ (Compensating Transaction) trong Saga.
- Nắm vững cách khôi phục trạng thái dữ liệu khi một bước trong chuỗi Saga bị lỗi (Partial Failure).
- Thiết kế giải pháp xử lý nâng cao khi bước bù trừ trực tiếp gặp sự cố kết nối (Saga Log & Retry).

---

## 2. CẤU TRÚC THƯ MỤC BÀI LÀM

```
Session_14/Bai_Tap_1/
├── pom.xml                                              # Spring Boot 3.2.5
├── README.md                                            # Tóm tắt hướng dẫn & kết quả
├── BaiTap1_VaLoHongKhoTreo.md                          # Báo cáo phân tích chuyên sâu 3 trang đầy đủ luận điểm
└── src
    ├── main
    │   ├── java/com/storex/order/
    │   │   ├── OrderApplication.java                   # Spring Boot entry point
    │   │   ├── client/InventoryClient.java              # Client trừ kho (decrease) và bù kho (restore)
    │   │   ├── controller/OrderController.java          # REST API đặt hàng & retry worker
    │   │   ├── dto/OrderRequest.java                    # Request DTO có cờ giả lập lỗi
    │   │   ├── model/
    │   │   │   ├── Order.java                           # Model đơn hàng
    │   │   │   └── SagaCompensationLog.java             # Bản ghi sự kiện bù trừ (Outbox)
    │   │   ├── repository/
    │   │   │   ├── OrderRepository.java                 # Repository lưu đơn hàng
    │   │   │   └── SagaCompensationLogRepository.java   # Repository lưu Saga Log
    │   │   └── service/OrderService.java                # Lớp nghiệp vụ đã vá lỗi bù trừ & Saga Worker
    │   └── resources/application.yml                    # Cấu hình cổng 8085
    └── test
        └── java/com/storex/order/
            └── OrderServiceRollbackTest.java            # 4 Test cases kiểm chứng đầy đủ 3 kịch bản
```

---

## 3. TÓM TẮT TRẢ LỜI 3 YÊU CẦU CỦA BÀI TẬP

### a) Xác định nguyên nhân gây ra trạng thái "hàng ảo":
- Trong kiến trúc Microservices (Database-per-Service), `Order-Service` và `Inventory-Service` sở hữu 2 cơ sở dữ liệu tách biệt.
- Khi gọi `inventoryClient.decreaseStock(...)`, thao tác trừ kho đã được **COMMIT** ngay lập tức vào `Inventory DB`.
- Khi bước tiếp theo `orderRepository.save(order)` gặp lỗi (DB Timeout), khối `catch (Exception e)` chỉ trả về HTTP 500 mà **hoàn toàn không có lệnh gọi ngược sang Inventory-Service để trả lại hàng**.
- Hậu quả: Đơn hàng không được tạo, nhưng kho hàng đã bị trừ mất vĩnh viễn, sinh ra hiện tượng "cháy hàng ảo" (hàng trong kho thực tế còn nhưng hệ thống báo hết).

### b) Chỉnh sửa mã nguồn để đảm bảo tính nhất quán:
- Trong khối `catch (Exception e)` của bước lưu đơn, ta bổ sung lệnh gọi **Giao dịch bù (Compensating Transaction)**:
  ```java
  inventoryClient.restoreStock(request.getProductId(), request.getQuantity(), false);
  ```
- Đồng thời cập nhật trạng thái đơn thành `FAILED` và trả về thông tin lỗi rõ ràng cho khách hàng.

### c) Giải pháp khi bước bù trừ cũng bị lỗi (Lỗi mạng / Timeout):
- Áp dụng **Saga Pattern** kết hợp **Transactional Outbox / Saga Compensation Log**:
  1. Khi gọi `restoreStock` thất bại, lưu một bản ghi sự kiện bồi thường vào bảng `saga_compensation_log` với trạng thái `PENDING_RETRY`.
  2. Một tiến trình nền (`Scheduled Reconciliation Worker`) định kỳ quét các bản ghi này và thực hiện **Retry với Exponential Backoff**.
  3. API hoàn kho phía `Inventory-Service` được thiết kế có tính **Lũy đẳng (Idempotent)** thông qua `idempotency_key` để tránh việc cộng trùng lặp tồn kho khi retry nhiều lần.
  4. Nếu vượt quá số lần thử lại tối đa (Max Retries), đẩy vào **Dead Letter Queue (DLQ)** và kích hoạt cảnh báo cho đội ngũ vận hành SRE can thiệp.

---

## 4. KẾT QUẢ KIỂM THỬ TỰ ĐỘNG

Chạy kiểm thử:
```bash
mvn clean test -f /Users/boizi/Desktop/Code/IT214/Session_14/Bai_Tap_1/pom.xml
```

Kết quả: **4/4 Test Cases PASSED (100% Thành công)**:
1. `testCreateOrder_Success`: Đặt hàng thành công, tồn kho giảm hợp lệ.
2. `testCreateOrder_DatabaseError_TriggersCompensatingStockRestore`: Lỗi DB lưu đơn -> Kích hoạt hoàn kho, tồn kho được bảo toàn về nguyên trạng (Khắc phục triệt để kho treo).
3. `testCreateOrder_CompensationFails_SavedToSagaLogAndRetried`: Hoàn kho trực tiếp bị timeout -> Lưu Saga Log và Worker retry thành công về 100% tính nhất quán cuối cùng.
4. `testRestApi_CreateOrder_Rollback`: Kiểm thử end-to-end qua MockMvc REST Endpoint.

> Chi tiết báo cáo phân tích sâu 3 trang xin xem tại: [BaiTap1_VaLoHongKhoTreo.md](BaiTap1_VaLoHongKhoTreo.md).
