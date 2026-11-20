# Supervisor — phiên Antigravity GUI đang thực hiện

Ngày: 12/11/2026. Người dùng yêu cầu tiếp tục điều khiển Antigravity đang mở, thay cho CLI đã kết thúc.

- Quan sát project `12Tester`, branch `main` và model `Gemini 3.8 Flash High` trước khi gửi; model vẫn giữ khi agent làm việc.
- Conversation quan sát được: `Implement Task Guard And Concurrency`, ID `e4afbc3e-90d5-416b-b6ed-7cbc512113dd`.
- Đã giao prompt/kế hoạch gốc, thư mục audit, baseline 370 file/7 package và ba lỗi giảm quota. Agent đã hiện `Working`, rồi lần lượt đọc prompt, kế hoạch, PROGRESS.md, LOCK_ORDER.md và baseline-action-gate-probe.mjs.
- Gửi bổ sung vào hàng đợi cùng conversation: kiểm thử supervisor mới và lỗ hổng giá trị mặc định cho trường readiness/debt bị thiếu; nhánh resume app hơn 30 ngày kiểm tra gate quá muộn. Giao diện xác nhận `Message queued. Sends after agent finishes working`.
- Không thay preset quyền có sẵn; không xử lý xác thực/quyền, không nhập lệnh vào terminal qua UI.

Sau khoảng 14 phút khảo sát, supervisor cập nhật message bổ sung (thêm `SUPERVISOR_REVIEW_NOTES.md`, 16 ca và đường PATCH completed/closed), rồi dùng `Send now` để agent nhận trước khi triển khai. Giao diện xác nhận message mới nằm trong `You said` và agent lại hiển thị `Working`, cùng conversation/model. Lượt khảo sát trước kết thúc không có bản triển khai; không coi nhãn `Worked for 14m` là hoàn tất.

## Kiểm thử supervisor trước sửa

`supervisor-action-gate-acceptance.mjs --baseline`: 12 ca trên DB PGlite mới, load 062–089, một kết nối. **7/12 chưa đạt (lỗi nền dự kiến)**, gồm owner delete/direct DELETE/direct production/manual pause khi chưa đủ nhiệm vụ, cùng owner delete/direct production/manual pause khi còn nợ. Các ca đủ nhiệm vụ và resume thường đạt. Chi tiết JSON/log `supervisor-baseline-acceptance.*`.

Đây không phải kết quả candidate hoặc concurrency. Script không có `--baseline` sẽ load migration hiện tại để kiểm tra sau sửa. Không ghi vào DB/cluster cũ.

Mở rộng kế tiếp: **14 ca, 9/14 chưa đạt trên baseline**. Hai ca bổ sung đã thực thi xác nhận resume app quá 30 ngày vẫn đổi app sang completed khi chưa đủ nhiệm vụ, và khi owner đã đủ nhiệm vụ nhưng tài khoản bị khóa. JSON/log cùng tên hiện chứa lần mở rộng 14 ca; số 12 ca bên trên mô tả lượt đầu.

`supervisor-observe-changes.mjs` lúc 12:01:55Z: chưa có thay đổi trong 370 file nền, chưa có migration mới; 7 package không đổi. Theo dõi tiếp đến khi có triển khai để review, không coi giao việc thành công là hoàn tất tính năng.

## Chuẩn bị kiểm tra PostgreSQL riêng

Supervisor tạo helper `supervisor-native-cluster.mjs`, chỉ dùng đường dữ liệu mới `supervisor-native/pg-data`, loopback `127.0.0.1:55449`, kiểm tra `data_directory` trước query. `initdb` hoàn tất trên cluster mới. Bước kiểm tra kết nối bị môi trường từ chối `connect EACCES 127.0.0.1:55449` trước khi gọi start server. Vì thế chưa có schema/fixture hoặc native concurrency từ supervisor; không nâng quyền, đổi cấu hình quyền hoặc thử đi vòng. Bằng chứng: `supervisor-native-start.log`, `supervisor-native/initdb.log`. Cluster task cũ không bị ghi/reset.
