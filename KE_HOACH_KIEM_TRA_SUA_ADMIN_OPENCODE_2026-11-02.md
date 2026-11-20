# Kế hoạch kiểm tra và sửa admin — 02/11/2026

Người dùng yêu cầu kiểm tra lại, giao OpenCode CLI dùng `google/antigravity-gemini-3.8-flash --variant high` sửa lỗi và kiểm tra đến khi hoàn tất. Giữ nguyên các thay đổi có sẵn; không commit/push hoặc triển khai Supabase production.

## Kết quả kiểm tra ban đầu

- Toàn bộ 499 test Android cũ và build debug: đạt.
- Worker: đạt.
- 9 ca độc lập bổ sung: đều phát hiện lỗi ở luồng chính sách nghiệp vụ, gồm cả yêu cầu qua ngày hiệu lực và process bị hủy khi RPC còn chờ. Xem `audit/admin-expansion-recheck-2026-11-02/independent-policy-extended-before.log` và XML snapshot.
- Bộ backend đầy đủ dừng ở `run-admin-audit-hardening-test.mjs`: fixture check-in cố định ngày 2026-11-01 trong khi report được tạo bằng `now()`. Backend xử lý ngày report theo giờ Việt Nam, làm assertion sai sau khi qua ngày.

## Các chặng sửa

1. Chặn lệnh lập/hủy chính sách đã xếp hàng dưới tài khoản A nếu UID hoặc generation thực đã đổi trước lúc gọi repository. Dùng kiểm tra phiên thực tại thời điểm gửi và nhận; không dựa riêng vào collector đã cập nhật hay chưa.
2. Khi hủy lịch gặp CAS conflict, tải lại đúng revision/target cho lần xác nhận tiếp theo. Giữ lý do, không tự gửi lại và không lặp mãi revision cũ.
3. Lưu yêu cầu lập/hủy đã xác nhận trước RPC bằng primitive/JSON trong SavedStateHandle theo chủ sở hữu. Giữ UUID và payload bất biến khi mất phản hồi, khôi phục ứng dụng, sửa nháp hoặc đóng dialog. Có thao tác thử lại rõ ràng để đối chiếu yêu cầu gốc; không tự tạo yêu cầu thay thế khi kết quả cũ chưa rõ.
4. Sửa fixture ngày của test backend theo cùng ngày report/giờ Việt Nam. Giữ các assertion về clawback, metadata, cảnh báo và check-in; không sửa migration cũ để chiều theo fixture.
5. Sửa TLS hiệu lực của worker: tham số `sslmode=disable`, `sslmode=no-verify` trong URL có thể ghi đè cấu hình xác minh TLS khi thư viện pg tạo client. Kiểm chứng bằng client pg thật được cài sẵn, không kết nối DB; giữ các cấu hình TLS hợp lệ dùng được.
6. Chạy lại các ca độc lập không sửa assertion, các test có liên quan, toàn bộ Android và build APK; chạy backend đầy đủ, kiểm tra diff và bàn giao báo cáo mới.

## Nghiệm thu

- [x] Chụp checkpoint nguồn và ghi nhận lỗi bằng test.
- [x] OpenCode sửa bằng đúng model/high; metadata thực tế đã được kiểm tra.
- [x] Ca độc lập mới và hồi quy Android đạt: 511 test / 68 suite, 0 lỗi hoặc bỏ qua; APK build thành công.
- [x] Backend đầy đủ và worker đạt.
- [x] Codex review bản sửa, kiểm tra giữ nguyên phạm vi, cập nhật báo cáo và APK.

Checkpoint và log nằm trong `audit/admin-expansion-recheck-2026-11-02`. Không dùng kết quả cũ để thay cho lần nghiệm thu này.

Đã hoàn tất. Xem `KET_QUA_KIEM_TRA_SUA_ADMIN_2026-11-02.md`. Có 12 ca độc lập mới: 9 ca tái hiện ban đầu và 3 ca bổ sung trong review, đã đưa vào bộ test hồi quy chính. Khi Gemini bị quota chặn ở vòng cuối, Codex chốt guard giữ yêu cầu hủy gốc theo lựa chọn trước của người dùng, rồi chạy lại toàn bộ kiểm thử; không thay cấu hình model/quota.
