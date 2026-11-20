# Nghiệm thu phần máy chủ

Đã kiểm tra bản cuối migration 087 có SHA-256 `bf61272a3e3d2cfda482c4e6f44722468b14bc2599fc011e0cbffd8566c95fc6`, bao gồm luồng chủ app tự xóa. Bản đã áp dụng vào PostgreSQL thử nghiệm khớp hoàn toàn mã nguồn này. Không áp dụng thay đổi lên máy chủ thật.

Phần bổ sung cuối đã qua 8/8 nhóm kiểm tra chủ app tự xóa, 7/7 tình huống PostgreSQL đồng thời chạy lại, và ca chủ app xóa gặp worker có chốt đồng bộ. Đối soát yêu cầu vẫn hoạt động sau khi gỡ app. Toàn bộ `npm test --prefix supabase/tests` chạy lại cũng PASS. Nhật ký: `final-owner-removal.log`, `final-native-owner-integrated.log`, `final-native-owner-worker.log`, `final-owner-integrated-database-regressions.log` trong thư mục `runs/`.

## Kết quả đã chạy

- 17/17 nhóm kiểm tra nghiệp vụ, phân quyền và ngân sách trên bản cuối: `runs/final-owner-integrated-bonus-behavior.log`.
- 7/7 tình huống nhiều kết nối PostgreSQL thật: `runs/native-isolated-scenarios-recheck.log`.
- Kiểm tra dữ liệu giả lập và dọn dữ liệu riêng của bài kiểm tra: `runs/final-run-campaign-tester-bonus-concurrency-fixture-test.log`.
- Toàn bộ lệnh `npm test --prefix supabase/tests`: PASS, `runs/final-database-regressions.log`. Các bài kiểm tra cũ có phạm vi migration riêng; không coi kết quả của bài kiểm tra ghim migration cũ là bằng chứng cho 087.
- Đồng bộ dữ liệu và thưởng nhiệm vụ trên toàn bộ migration hiện tại: `runs/final-run-egress-sync-v2-test.log`, `runs/final-run-mission-reward-modes-test.log`.
- Biên nhận v2/v3 từ 086 gửi lại sau cập nhật, kể cả khi app đã kết thúc: 4/4 PASS, `runs/final-review-migration-replay.log`.
- Đối soát sau khi gỡ vật lý, giữ lịch sử thưởng và hoàn phần dư: PASS, `runs/final-review-deferred-removal.log`.
- Giới hạn tổng thưởng lớn, vị trí thư viện mã hóa và quyền đọc ngân sách theo thiết lập cấp quyền cũ: PASS, các nhật ký `final-review-large-valid-award.log`, `final-review-extension-schema.log`, `final-review-bonus-privacy.log`.
- Các tình huống có chốt đồng bộ riêng về nộp/đóng, vòng đời/duyệt, vòng đời/đóng và admin gỡ/worker: PASS trong các nhật ký `native-core-*.log` trước thay đổi lưu mã campaign gốc; bộ kiểm tra 7 tình huống và nghiệp vụ được chạy lại sau thay đổi đó.

Supervisor sửa một truy vấn kiểm tra dùng cột không tồn tại `auto_accepted`, thay bằng kiểm tra chính xác một sự kiện `auto_accept` và không có sự kiện duyệt tay trong `report_review_events`. Không đổi nghiệp vụ để khớp bài kiểm tra. Script tạo migration tạm đã được gỡ khỏi thư mục kiểm thử; migration cuối cùng tự đầy đủ.

## Hoàn nguyên có ngân sách

Trước khi hoàn nguyên cấu trúc, phải ngừng mở đợt mới và đóng việc nhận thêm nghĩa vụ. Giữ hệ thống xử lý báo cáo đang chờ: duyệt tay trong cửa sổ hợp lệ hoặc chờ lượt tự động sau 8 giờ; một lần gọi tự động không bảo đảm xử lý báo cáo chưa hết hạn hoặc đang bị khóa. Chạy tiếp worker và hoàn phần ngân sách còn dư. Trường hợp ví chưa nhận được tiền hoàn phải giữ quỹ và xử lý tiếp, chưa được xóa cấu trúc.

Chỉ chuyển sang lưu trữ lịch sử và gỡ cấu trúc khi không còn nghĩa vụ pending, ngân sách chưa quyết toán hoặc yêu cầu tài chính chưa đối soát. Giữ bản lưu sổ round, khoản thưởng, sự kiện, mã yêu cầu và mã campaign gốc. Tài liệu này bổ sung điều kiện cho phần hướng dẫn hoàn nguyên trong báo cáo backend, không phải lệnh tự động triển khai hay hoàn nguyên.
