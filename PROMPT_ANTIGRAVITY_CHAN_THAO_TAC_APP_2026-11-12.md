# Thực hiện và sửa hoàn chỉnh điều kiện nhiệm vụ ngày cho thao tác app

Người dùng chỉ định Antigravity CLI, Gemini 3.8 Flash mức high. Chỉ dùng model
`gemini-3.8-flash-high`, effort `high`; không thay model hoặc giao model khác.

Workspace `F:\AI\12Tester`. Đọc và thực hiện toàn bộ:
`KE_HOACH_CHAN_THAO_TAC_APP_KHI_CHUA_XONG_NHIEM_VU_2026-11-12.md`.

Người dùng yêu cầu triển khai thực sự, giám sát và sửa đến khi hoàn tất các kiểm
tra trong phạm vi. Không dừng sau kế hoạch, skeleton hoặc chỉ làm UI. Có thể sửa
các lỗi liên quan đã chứng minh. Chạy test phù hợp, đọc diff, tìm lỗi còn sót,
sửa rồi chạy lại kiểm tra cần thiết. Không chỉ kết luận dựa trên tìm chuỗi source.

Yêu cầu trọng tâm: người chưa hoàn thành nhiệm vụ ngày không xóa app, xác nhận
production hoặc tiếp tục nhận tester. Backend là quyết định bắt buộc, UI đồng bộ.
Kiểm tra trước side effect và trước giảm quota, bảo toàn 088 và lỗi production đã
sửa tại 089. Chặn lách qua manual pause theo kế hoạch, không hồi tố tạo nợ cho
app cũ đã tạm dừng. Không vô hiệu hóa guard tài chính, quyền hoặc test để có PASS.

Giữ mọi thay đổi có sẵn; repository rất nhiều modified/untracked. Chụp status và
fingerprint nguồn trước sửa, không reset/clean/stash/checkout toàn repo. Không
sửa baseline 062 hoặc migration 063–089; thêm migration số kế tiếp còn trống.
Không đổi versionName/versionCode. Không tạo APK/AAB hoặc chạy assemble/bundle.
Có thể compile Kotlin và unit test không đóng gói APK. Không deploy/fixture
production, không gửi thông báo thật, không commit/push hoặc đổi tài khoản.

Không đổi cấu hình quyền, không dùng skip permissions, không vượt sandbox,
không đọc/in secrets. Chỉ dùng quyền của phiên được cấp. Nếu xác thực/quyền/quota
ngăn thực hiện, ghi blocker chính xác và phần còn lại; không nói đã xong.

Các fixture PostgreSQL riêng của task cũ chỉ là nguồn tham khảo; không reset hoặc
ghi vào cluster/DB cũ. Nếu test concurrency thì tạo DB/cluster riêng trong thư mục
audit mới, chỉ bind loopback, xác minh data_directory trước thao tác. Không giả
concurrency bằng test PGlite một connection.

Báo cáo `audit/daily-task-action-gate-2026-11-12/IMPLEMENTATION_RESULT.md` phải ghi:
model/effort thực dùng, file sửa, quy tắc, ca test và kết quả thực tế, review và lỗi
đã sửa, kiểm tra chưa chạy, migration chưa áp production, phiên bản không đổi và
không build APK. Nếu hoàn tất, supervisor sẽ review độc lập và có thể giao sửa
tiếp vào cùng conversation.
