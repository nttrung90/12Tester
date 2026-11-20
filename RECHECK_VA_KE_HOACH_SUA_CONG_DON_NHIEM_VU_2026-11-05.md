# Kiểm tra lại cộng dồn nhiệm vụ chưa hoàn thành — 05/11/2026

## Kết luận

Công thức cộng dồn thông thường đang cho kết quả đúng trong các ca đã kiểm tra trên mã hiện tại đến migration 079. Tuy nhiên, chưa thể kết luận tính năng hoạt động hoàn toàn đúng: đã tái hiện 3 nhóm lỗi hành vi và 1 lỗi trong cách chạy kiểm thử.

Lỗi ưu tiên cao nhất là **báo cáo Nhiệm vụ App bị tính vào chỉ tiêu test thường và làm giảm phần thiếu chuyển tiếp**. Trong một ca thử, người dùng còn 1 nhiệm vụ cũ, chỉ nộp báo cáo mission, rồi bật lại app thành công. Điều này trái với FAQ hiện có, trong đó chỉ tiêu Trang chủ dựa trên báo cáo thường đủ điều kiện.

Lần này chỉ kiểm tra, tạo bằng chứng và lập kế hoạch. **Chưa sửa mã nghiệp vụ, chưa tạo migration sửa lỗi, chưa triển khai lên máy chủ.**

## Phạm vi và bằng chứng

- Nạp toàn bộ migration hiện có 062–079 theo thứ tự vào cơ sở dữ liệu PGlite dùng riêng cho kiểm tra; không ghi vào Supabase thật.
- Chạy `node audit/carryover-recheck-2026-11-05/recheck.mjs`, kết thúc mã 0.
- Có **19 ca kiểm tra**: 14 ca xác nhận hành vi đúng và 5 ca tái hiện lỗi hoặc lỗ hổng kiểm thử. Chữ `PASS` ở ca mang tên `defect reproduced` nghĩa là đã tái hiện được lỗi, không có nghĩa sản phẩm hết lỗi.
- Các ngày lịch sử và checkpoint được tạo bởi tài khoản quản trị cơ sở dữ liệu trong dữ liệu thử. Không dùng dữ liệu giả này để suy luận lịch sử production.
- Ca mission gọi thật `submit_tester_report_v3` với tài khoản authenticated, rồi gọi thật `resume_campaign`. Ca hiển thị đối chiếu dữ liệu RPC với nhánh Compose; chưa chạy kiểm thử ảnh giao diện trên thiết bị.

Bằng chứng:

- [Mã tái hiện](audit/carryover-recheck-2026-11-05/recheck.mjs)
- [Kết quả có cấu trúc](audit/carryover-recheck-2026-11-05/results.json)
- [Nhật ký chạy](audit/carryover-recheck-2026-11-05/recheck.log)

## Những phần đã kiểm tra đúng

Ký hiệu: B là chỉ tiêu cơ bản hôm nay; C là phần thiếu đã chốt từ hôm qua; A là số app khác khả dụng; D là số app khác nhau đã kiểm thử đủ điều kiện.

| Trường hợp | Kết quả thực tế |
|---|---|
| B=6, ngày đầu làm 4 | Chuyển tiếp 2 |
| Ngày sau C=2, làm 5/8 | Chuyển tiếp 3 |
| Ngày kế C=3, làm đủ 9/9 | Phần thiếu về 0 |
| Làm vượt chỉ tiêu | Không âm, không tạo lượt dư cho ngày sau |
| Thiếu liên tục 7 ngày, mỗi ngày thiếu 2 và đủ nguồn app | Nợ tích lũy 14 |
| C=8, A=5, D=5 | Đạt chỉ tiêu khả dụng, vẫn bảo lưu 3 |
| C=8, A=0, D=0 | Miễn chỉ tiêu hôm nay, giữ nguyên 8 |
| Tắt cộng dồn | Giữ nợ cũ; chỉ phần test vượt chỉ tiêu cơ bản làm giảm nợ |
| Bật lại cộng dồn | Nối tiếp đúng phần còn bảo lưu |
| Không còn app được tính chỉ tiêu | Chuyển sang member, giữ nợ; test tự nguyện giảm nợ |
| Checkin/báo cáo trùng cùng app | Chỉ tính một app mỗi ngày |
| Báo cáo thường pending/approved/rejected | Pending và approved được tính; rejected bị loại khỏi số đếm hiện tại |
| Đã chốt ngày rồi có thêm sự kiện | Không ghi lại ngày đã chốt |
| Bỏ lỡ chốt 3 ngày, chạy lại nhiều lần | Chốt bù đúng thứ tự, không nhân đôi nợ hoặc trừ Credit |
| Hôm qua chưa chốt | Trả trạng thái đang chờ, không coi phần thiếu chưa xác định là đã hết |

Số nhiệm vụ còn lại của owner có thể gồm cả chỉ tiêu hôm nay và phần cũ. Khi thiếu nguồn app, hệ thống giảm chỉ tiêu khả dụng và bảo lưu phần thiếu cũ theo công thức hiện có; không cộng mọi phần cơ bản bất khả thi vào nợ. Đây là quy tắc đã được mô tả trong tài liệu bàn giao trước.

Thành viên thuần không có app được tính chỉ tiêu chỉ cần đăng nhập theo quy tắc hiện tại; không tự phát sinh chỉ tiêu test cơ bản như owner.

## Các lỗi đã xác nhận

### C01 — P1: Báo cáo mission làm giảm nợ test thường và có thể mở lại app

Nguồn: `supabase/migrations/066_push_and_carryover_hardening.sql:292`, hàm `trg_journal_report_event`. Điều kiện đủ chỉ kiểm tra trạng thái pending/approved và khác chủ app, không kiểm tra `submission_context`. Hàm này vẫn là định nghĩa đang hoạt động sau migration 079. Getter hiện tại đếm các sự kiện `is_qualifying=true` trong nhật ký.

Tái hiện bằng RPC thật:

1. Owner có B=6, C=8, D=0, tổng còn phải làm 14.
2. Chỉ gửi báo cáo `submission_context='mission'`, trạng thái pending.
3. Máy chủ trả D=1, số còn lại giảm xuống 13. Theo hợp đồng FAQ, một báo cáo mission riêng không được thay thế báo cáo thường.
4. Trong biến thể dữ liệu thử còn C=1 và các app tự tạm dừng, tài khoản ở mode member: cùng báo cáo mission làm phần thiếu về 0.
5. Gọi `resume_campaign` bằng chính tài khoản đó: trả `status='open'`.

Không phải lỗi phép cộng B+C. Nguồn đầu vào bị phân loại sai nên cả số nợ, điều kiện đạt và điều kiện bật lại app đều có thể sai. Không khẳng định mọi tài khoản đang bị ảnh hưởng; cần đối soát dữ liệu thật để xác định phạm vi.

### C02 — P2: Thẻ nhiệm vụ che phần thiếu còn bảo lưu

Nguồn: `app/src/main/java/com/ntt/crosstest/ui/components/DailyTaskCard.kt:164–220` và nhánh member ở dòng 463.

- C=8, A=5, D=5: máy chủ trả `met=true`, còn 3. Thẻ đi vào nhánh hoàn thành gọn rồi `return` trước phần giải thích cộng dồn. Người dùng thấy hoàn thành 5/5 và không thấy 3 nhiệm vụ đang bảo lưu trên thẻ.
- Sau khi tự tạm dừng các app, tài khoản chuyển thành member. Nếu đã đăng nhập, thẻ hoàn thành đăng nhập cũng che C=8 còn nguyên.

Nợ trong cơ sở dữ liệu vẫn còn. Lỗi là thông tin hiển thị thiếu, đặc biệt gây khó hiểu khi người dùng bấm tiếp tục test app và bị chặn vì còn nợ.

### C03 — P2: Chốt tự động không tự phục hồi được khoảng thiếu quá dài

Nguồn: `079_owner_auto_pause_debt_and_timestamp_guard.sql:492`, dòng 544 và điều kiện bắt buộc có ngày liền trước đã chốt ở dòng 700.

Tái hiện: lịch sử có coverage đáng tin 60 ngày, checkpoint đã chốt cách đây 40 ngày, thiếu 39 ngày tiếp theo. Chạy `apply_daily_inactivity_penalty` hai lần vẫn không chốt thêm ngày nào cho người này; trạng thái hôm nay luôn `carryover_ready=false`.

Nguyên nhân: bộ điều phối chỉ quét 31 ngày gần nhất; ngày đầu trong cửa sổ lại thiếu tiền nhiệm ở ngoài cửa sổ. Điều kiện an toàn từ chối chốt là đúng, nhưng không có cơ chế tiến dần từ checkpoint hợp lệ ở xa hơn. Đây là lỗi phục hồi khi gián đoạn dài, không phải lỗi phát sinh sau vài ngày thiếu nhiệm vụ bình thường. Hiện có thể cần quản trị viên chốt bù từ mốc sớm hơn; không được chữa bằng đặt nợ về 0.

### C04 — P2: Một phần bộ kiểm thử cộng dồn không xác minh đúng mã mới nhất

Nguồn: `supabase/tests/run-push-and-carryover-baseline062-regression-test.mjs:43–44`.

Bộ này nạp tất cả migration rồi chạy lại 066 để kiểm tra tính ổn định của coverage marker. Việc đó đồng thời thay thế getter và finalizer mới bằng định nghĩa cũ. Đã đối chiếu checksum định nghĩa hai hàm trước/sau và xác nhận khác nhau.

Ngoài ra, `run-daily-task-carryover.mjs` cố ý kiểm tra schema lịch sử đến 053; có ích cho hợp đồng lịch sử nhưng không chứng minh bản 079. Không kết luận toàn bộ kiểm thử hiện có vô hiệu: các suite mới về auto-pause vẫn kiểm tra mã mới, nhưng chưa thay thế ma trận cộng dồn đầy đủ.

## Kế hoạch sửa

### Bước 1 — Khóa đúng hợp đồng và bổ sung kiểm thử thất bại

1. Sửa loader kiểm thử để tách kiểm tra chạy lại migration 066 vào cơ sở dữ liệu riêng hoặc chạy trước các migration mới hơn. Sau khi nạp mã cuối phải xác nhận getter/finalizer đúng phiên bản cuối.
2. Giữ các suite schema lịch sử, đặt tên rõ và thêm suite cộng dồn chạy toàn bộ migration hiện hành vào lệnh kiểm thử mặc định.
3. Chuyển các ca tái hiện lỗi trong lần kiểm tra này thành yêu cầu nghiệm thu: mission không tăng D hoặc trả nợ test thường; thẻ vẫn hiện nợ khi `met=true`; khoảng thiếu dài có tiến triển và cuối cùng chốt được.

### Bước 2 — Sửa phân loại nguồn tính nhiệm vụ trước

1. Tạo migration mới sau 079, không sửa đè migration đã ban hành.
2. Phân loại rõ báo cáo thường và mission ngay khi ghi sự kiện. Chỉ báo cáo thường đủ điều kiện mới tham gia chỉ tiêu/nghĩa vụ test thường; tiếp tục khử trùng theo app/ngày.
3. Kiểm tra cả các checkin được tạo tự động từ báo cáo để không có đường vòng tính mission trở lại; giữ hành vi checkin hợp lệ hiện có.
4. Xử lý dữ liệu journal cũ bằng đối soát nguồn: bản ghi còn nguồn thì xác minh context, bản ghi thiếu nguồn phải được đánh dấu chưa xác minh. Không suy ra một cách mặc định rằng dữ liệu không rõ loại là báo cáo thường.
5. Không tính lại và ghi đè hàng loạt các ngày đã chốt hoặc thay đổi Credit lịch sử. Lập danh sách ngày/tài khoản bị ảnh hưởng, ghi phương án điều chỉnh có dấu vết riêng và đối soát trước khi áp dụng.
6. Kiểm tra lại server vẫn từ chối bật lại app khi còn phần thiếu hoặc lịch sử đang chờ, kể cả khi hôm nay đã đạt chỉ tiêu khả dụng hay tài khoản chuyển sang member.

### Bước 3 — Hiển thị phần thiếu trong mọi trạng thái liên quan

1. Thẻ hoàn thành gọn vẫn phải hiện phần thiếu bảo lưu nếu còn lớn hơn 0.
2. Thành viên từng là owner vẫn thấy phần thiếu cũ và cách hoàn thành bằng test thường tự nguyện.
3. Phân biệt “đã đạt chỉ tiêu hôm nay” với “đã trả hết phần thiếu”. Ví dụ: “Đã test 5/5 app khả dụng; còn 3 nhiệm vụ bảo lưu”.
4. Không hiển thị 0 như đã hết nợ khi nguồn chuyển tiếp chưa sẵn sàng. Đồng bộ nội dung các ngôn ngữ và kiểm tra trường hợp cỡ chữ lớn.

### Bước 4 — Phục hồi chốt ngày theo từng đợt có tiến triển

1. Tìm ngày thiếu sớm nhất sau checkpoint đáng tin của từng người, kể cả ngoài cửa sổ 31 ngày.
2. Giới hạn số ngày/người xử lý mỗi lần chạy để tránh một transaction quá lớn; lưu tiến độ để lần sau tiếp tục.
3. Giữ điều kiện tiền nhiệm liền kề, kiểm tra coverage, khóa chống chạy trùng, tính bất biến ngày đã chốt và tính đúng số dư Credit.
4. Nếu lịch sử trước coverage không đủ bằng chứng, báo quản trị viên đối soát bằng cơ chế checkpoint có audit hiện có. Không tự tạo checkpoint nợ 0 để vượt qua khoảng thiếu.
5. Có chẩn đoán người bị kẹt, ngày thiếu đầu tiên và lý do để phát hiện sớm.

## Tiêu chí nghiệm thu

- Ca 7 ngày thiếu, các ma trận nguồn app, bật/tắt và chuyển mode vẫn cho kết quả như bảng ở trên.
- Gửi mission bằng RPC thật không tăng số test thường, không làm giảm nợ test thường và không giúp vượt điều kiện bật lại app.
- Báo cáo thường pending/approved vẫn được tính; rejected không được tính ở ngày hiện tại; trùng app không nhân đôi.
- Thẻ hoàn thành, thẻ được miễn và thẻ member đều thể hiện đúng nợ còn bảo lưu; trạng thái đang chờ không bị hiểu là hết nợ.
- Chốt bù 3, 31 và 40 ngày có tiến triển, nối đúng phần thiếu, chạy lại không trừ/phạt/ghi nợ hai lần.
- Ngày thiếu bằng chứng vẫn chờ đối soát; dữ liệu lịch sử đã chốt và Credit không bị ghi lại ngoài quy trình điều chỉnh được thiết kế riêng.
- Chạy toàn bộ kiểm thử database hiện hành, kiểm thử Android liên quan và build APK. Kiểm tra cạnh tranh trên PostgreSQL nhiều kết nối và nghiệm thu luồng chuyển ngày trên môi trường thử trước khi triển khai.

Chưa có quyền truy cập dữ liệu hoặc lịch chạy thực tế của máy chủ trong lần kiểm tra này. Kết luận dựa trên mã trong workspace và tái hiện cục bộ; chưa xác nhận máy chủ thật đang dùng chính xác những migration nào.
