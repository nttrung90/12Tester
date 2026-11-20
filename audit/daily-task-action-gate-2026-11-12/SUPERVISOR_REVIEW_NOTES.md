# Phát hiện độc lập cần kiểm tra trên candidate

## Review candidate 090 đầu tiên — 12/11/2026 khoảng 19:30 VN

Chạy `supervisor-action-gate-acceptance.mjs --bonus --payloads --review`: **67/70 PASS, 3 FAIL**. Bằng chứng `supervisor-candidate-review.log`, `supervisor-action-gate-acceptance-bonus-payloads-review.json`.

1. Target app `paused/owner_manual`, owner còn một app open, B=3/D=0/met=false: direct PATCH production thành công vì trigger chỉ gate khi OLD đang đóng góp quota. Production phải gate cả app đã paused theo nghĩa vụ hiện tại, không hồi tố nợ.
2. Cùng fixture paused unmet: direct PATCH status=closed, paused_reason=owner_deletion_pending thành công. Đây là metadata client có thể giả, nhưng `_request_campaign_removal` và BEFORE DELETE dùng chính cặp status/reason đó làm bằng chứng replay để bỏ gate. Phải bảo vệ metadata hoặc dùng receipt/quyền caller thật để chứng minh yêu cầu đã được chấp nhận; không chỉ tin status/reason client ghi.
3. Canonical boundary trả carryover_remaining_tests=-1 với met/readiness/date hợp lệ vẫn cho production. Helper ghi chú strictly zero nhưng code chỉ `if >0`. Giá trị âm phải pending/invalid, không cho qua.

Ca streak=7 đã đủ nhiệm vụ resume hiện PASS sau khi agent tự bổ sung refresh trước kiểm tra streak; không coi đây là lỗi còn mở.

Lượt mở rộng tiếp theo cùng candidate hash `d275a1fb25b691e5d4d52c7672159e64cf8593d4f466f54d66ca1b6942ad7eee`: **69/73 PASS, 4 FAIL**. Đã thêm trọn chuỗi giả pending rồi gọi owner delete: app thực sự bị xóa khi B3/D0, không chỉ ghi được metadata. Hai ca production RPC mới (unmet chặn / met với bonus cho phép) đạt. JSON/log review hiện chứa lượt 73 ca; ba vấn đề nghiệp vụ còn mở như trên, trong đó giả pending được chứng minh bằng hai ca.

Review tĩnh cần hoàn tất trước kết luận: hiện các trigger coi `auth.uid() is null` là service/worker, chưa xác minh caller thực như kế hoạch. Service có UID có thể bị gate sai; caller không UID không tự là bằng chứng service. Cần kiểm thử role thật, không chỉ privileged DB setup.

Đã thực thi xác minh role thật trên candidate hash `5a33c47cb0f44c2d77c552d3ac9848dd563f34107b42c1936e39da264b3f8a5c`: `--roles` có **20/22 PASS, 2 FAIL**. `SET LOCAL ROLE service_role` với JWT sub owner: update completed bị `DAILY_TASK_UNMET` khi owner chưa đủ nhiệm vụ, và bị `ACCOUNT_LOCKED` khi owner khóa. Cùng service_role không UID hoạt động được. Hai ca client authenticated giả JWT role/claims service_role đều vẫn bị chặn; authenticated không UID cũng không ghi được do RLS. Chứng cứ `supervisor-candidate-roles.log`, `supervisor-action-gate-acceptance-roles.json`; bản SQL đã load được lưu theo hash trong `supervisor-runs/`. Đây là lỗi chặn tác vụ hệ thống hợp lệ đã chứng minh, không còn chỉ là lo ngại tĩnh.

Khóa/concurrency: 090 mới chỉ giữ advisory resume cũ, chưa serialize production/delete/direct status với đăng app 084/088. AFTER status/delete trigger mới gọi refresh (campaign -> profile); finalizer đang profile -> auto-pause campaign có thể tạo vòng chờ với direct status. Cần đánh giá/điều chỉnh cả đường thực thi và PostgreSQL thật nếu khả dụng, không dùng PASS PGlite thay bằng chứng này. Supervisor chưa chạy được multi-connection vì EACCES loopback.

Giữ nguyên contract/xử lý tài chính của 088 khi chèn gate: candidate đã viết lại nhiều phần `_request_campaign_removal` (status trả admin, payload pending, reason hoàn tiền và vòng round có remaining=0). Hạn chế thay đổi ngoài gate; review các round đã trả hết suất/closing zero và replay sau xóa vật lý với bằng chứng server, không duplicate refund.

## Đường lách quota qua trạng thái khác

Đã thực thi bằng role `authenticated`, UID owner thật, DB PGlite mới, migration 062–089, fixture canonical ready=true nhưng B=6/D=3/met=false:

- Direct `UPDATE campaigns SET status='completed',paused_reason=null` thành công.
- Direct `UPDATE campaigns SET status='closed',paused_reason=null` thành công.

Giống production/manual pause, hai trạng thái này có thể loại app khỏi nghĩa vụ trước khi thao tác tiếp. Gate chỉ kiểm tra `new.status='production'` sẽ chưa đủ. Cần giữ cron/system/admin hợp lệ và chặn owner tự hạ quota qua mọi chuyển trạng thái tương đương. Đây là cùng phạm vi chống lách gate, không thay công thức quota.

Bằng chứng: `supervisor-baseline-acceptance.json` và `.log`, script `supervisor-action-gate-acceptance.mjs`. Lượt mới nhất có **16 ca; 11 ca chưa đạt trên baseline**, không phải candidate. Hai ca expired resume cũng tái hiện được (unmet và locked).

## Dữ liệu canonical thiếu trường

`DailyTaskStatus` nền mặc định hai readiness=true và debt=0; thiếu trường không thể được coi là đã kiểm tra hợp lệ chỉ vì met=true. Cần test decode JSON thiếu/null, ngày cũ, timeout; backend helper không coalesce missing readiness thành true hoặc missing debt thành 0 để cho qua.

Script supervisor hỗ trợ `--payloads`: fault injection tại boundary `get_daily_task_status` trong transaction riêng (không vô hiệu gate/RLS), để kiểm tra omit/null các trường readiness/debt/met/status_date cùng ngày cũ.

Đã kiểm tra toàn bộ harness trên baseline bằng `--baseline --bonus --payloads`: **66 ca, 56 lỗi nền tái hiện, 10 ca đạt**, không có lỗi tạo fixture. Các ca bonus đủ nhiệm vụ đều đạt, trong khi unmet vẫn đổi app/vòng thưởng. Bằng chứng riêng `supervisor-baseline-acceptance-bonus-payloads.json` và `.log`, giữ nguyên kết quả 16 ca nền. Chưa có kết quả candidate ở thời điểm ghi mục này; 66 ca không phải tuyên bố tính năng đã đạt.

## Kiểm thử thật và bảo toàn

Supervisor chưa thể kết nối native loopback vì `EACCES` trước start server; không đổi quyền hoặc vượt hạn chế. Chỉ khởi tạo cluster mới trong `supervisor-native/pg-data`; không ghi cluster task cũ. Các test PGlite đều một connection, không phải bằng chứng concurrency.

Giữ log/baseline hiện có; nguồn trước task 370 file, 7 package. `supervisor-observe-changes.mjs` báo file nền thay đổi, migration mới, và assert package/migration 062–089/version được bảo toàn. Không APK/AAB, không đổi 1.2.4/build44, không production.
