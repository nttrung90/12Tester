# Kết quả giám sát Dual Storage — 16/11/2026

Thời điểm chốt: 21:28:27 16/11/26 (Việt Nam).

**Các lỗi tái hiện R01/R02/R03 và lỗi phát sinh trong phạm vi rà soát đã được sửa. Bộ kiểm tra cục bộ cuối đạt 13/13 nhóm, không có lỗi thất bại. Chưa nghiệm thu môi trường production.**

## Thay đổi đã kiểm tra

- Migration 093 tiếp nối lịch sử: cho phép metadata `contentLength` ở phép thử quyền upload, vẫn kiểm tra chủ sở hữu, đường dẫn, bucket, thời hạn và cap. Metadata thực sau upload vẫn cần `size` hợp lệ.
- Ghi nhận riêng việc ảnh chính/thumbnail từng commit; giữ quota cho path chưa được xác nhận qua finalize, reconcile, cleanup và ACK. Một báo cáo khác không làm mất quota của bản ghi đã xóa nhưng còn write chưa xác nhận.
- Cặp ảnh đã hoàn tất được giải phóng phần giữ chỗ sau khi đã tính vào U; xóa vật lý không tái tạo phần giữ chỗ đó. Cleanup nhận biết file xuất hiện lại, giữ hạn xóa sớm nhất, và không cộng lại byte ảnh đã xóa ở lease trước.
- Nâng cấp 092→093 chuyển sang Google overflow, đánh dấu metric chưa hợp lệ cho đến lần đối soát mới thành công. Quyền RPC giữ chỗ của ứng dụng được giữ; RPC dọn dẹp chỉ dành cho service.
- Worker đối soát chỉ chấp nhận cờ ACK đúng boolean true cùng dữ liệu thu thập hợp lệ; false/thiếu/sai kiểu dẫn tới fail-safe và mã thoát lỗi.
- Runner PostgreSQL đã sửa thứ tự probe/expiry, giữ outer transaction mở sau rollback savepoint, thêm finalize và delayed commit, tách fixture giữa các phase, sửa tên cột và giữ mã lỗi của caller khi import thiếu URL.

## Bằng chứng kiểm tra cuối

| Kiểm tra | Kết quả | Phạm vi |
|---|---:|---|
| Bộ hồi quy tổng hợp do giám sát chạy | 13/13 nhóm PASS | Log riêng tiền tố supervisor-root |
| Backend unit | 82/82 PASS | Cleanup, reconcile, gateway adapter |
| Ca độc lập Storage/quota/cleanup | 28/28 PASS | PGlite, mô hình thứ tự sự kiện, public submit |
| Ma trận ACK | 12/12 PASS | RPC được tiêm giả lập, không network |
| SQL hardening / quota | 20/20 và 8/8 PASS | PGlite |
| Nâng cấp 091→092 / 092→093 | PASS cả hai | Có cửa sổ trước reconcile đầu tiên |
| Đối chiếu nghiệp vụ / nhiệm vụ / bonus | 9/9, 15/15, 17/17 PASS | PGlite |
| Media probes | 14/14 PASS | Provider được giả lập |
| Hợp đồng runner: cú pháp, bỏ qua, import, từ chối DB sai | 5/5 PASS | Không kết nối PostgreSQL |
| SQL tĩnh trong runner | 46/46 hợp lệ | EXPLAIN trên PGlite; không chứng minh cạnh tranh |
| Gateway HTTP chạy mới | 13/13 PASS | Native Antigravity, gateway-http-recheck.log |
| SQL index | fresh; 32 migrations / 262 functions | Khớp checksum nguồn |
| Android | 170/170 từ lần chạy trước | Không chạy lại; 15 tệp Android được theo dõi vẫn khớp checksum |

Các probe gốc được giữ nguyên; migration 091 và 092 khớp checksum trước sửa. Test quota cũ đã đổi kỳ vọng có giải thích: thiếu thumbnail giữ R=150000; đủ hai path đã vào U thì R=0, không giữ thừa 149950 byte. Kiểm tra completion flags và reconcile lặp vẫn được thực hiện.

## Giới hạn còn phải kiểm chứng khi triển khai

PostgreSQL nhiều kết nối **chưa chạy** vì thiếu TEST_DATABASE_URL; trạng thái NOT RUN được giữ rõ dù lệnh bỏ qua thoát 0. Storage API thật, phiên bản Storage đang triển khai và scheduler xóa vật lý **chưa được kiểm chứng**. Không dùng PASS của PGlite hoặc EXPLAIN để khẳng định khóa, upload thực hay SLA xóa file trên production.

Path chưa từng được quan sát commit có thể giữ quota vô thời hạn sau TTL hoặc absence ACK khi chưa có completion fence đáng tin cậy từ provider. Đây là lựa chọn giữ ngân sách bảo thủ; nó có thể khiến Google overflow sớm dù dung lượng vật lý còn thấp. Path đã xác nhận hoàn tất được giải phóng đúng khi đối soát hoặc xóa đã xác nhận. Chưa tự giải phóng phần chưa xác nhận chỉ dựa vào thời gian.

Không build APK/AAB, không tăng phiên bản, không commit/push/deploy và không reset database production trong lượt này.

Bằng chứng máy đọc: audit/dual-storage-recheck-2026-11-16/SUPERVISOR_FINAL_REVIEW.json. Bộ log cuối: supervisor-root-SUPERVISOR_FINAL_VALIDATION.json và các file supervisor-root-supervisor-final-*.log trong cùng thư mục. Bản này là kết quả nghiệm thu của giám sát; các lần bàn giao trước là lịch sử theo thời điểm chạy.
