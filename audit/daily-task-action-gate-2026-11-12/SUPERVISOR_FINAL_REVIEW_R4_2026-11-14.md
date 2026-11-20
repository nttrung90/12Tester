# Kiểm tra lại R4 — ngày 14/11/2026

Mã hiện tại đã vượt qua các kiểm thử dưới đây. Không còn lỗi đã biết trong phạm vi đã kiểm tra. Đã hoàn tất đối chứng với bản trước R4 và rà soát hai báo cáo do Gemini ghi; supervisor đã chỉnh các mô tả vượt quá bằng chứng.

Gemini 3.8 Flash High thực hiện bản sửa trong phiên Antigravity đang mở theo yêu cầu người dùng. Supervisor đọc nguồn, kiểm thử độc lập và đối chiếu bằng chứng. Không đổi model hoặc cấu hình quyền.

## Các lỗi được sửa thêm trong lượt kiểm tra này

1. **Phản hồi tải cũ ghi đè trạng thái mới:** nhánh tải dự phòng của `OwnerAppDetailViewModel` từng tính quyền áp dữ liệu trước các lần chờ tải thông tin phụ. Bản sửa kiểm tra thứ tự phản hồi sau lần chờ cuối, cùng tài khoản, phiên và ngày nghiệp vụ. Ca kiểm thử giữ yêu cầu tải báo giá sau khi đã đọc nhiệm vụ, áp trạng thái mới chưa đủ nhiệm vụ rồi mới thả phản hồi cũ.
2. **Thông báo bịa một nhiệm vụ còn thiếu:** `DailyTaskActionGate` bỏ ép số còn lại tối thiểu bằng 1. Chỉ hiển thị số khi biết chênh lệch dương từ dữ liệu nhiệm vụ; trường hợp chưa biết số dùng thông báo chung và vẫn chặn thao tác.
3. **Khoảng hở giữa kiểm tra nhiệm vụ và đăng app đồng thời:** migration 090 lấy khóa theo chủ app sau khóa hàng campaign và trước kiểm tra canonical, hoàn tiền hay giảm quota. Áp dụng cho gỡ app, xác nhận production, DELETE trực tiếp và UPDATE production/tạm dừng thủ công. Giữ các nhánh admin/hệ thống được phép, kiểm tra quyền và ràng buộc tài chính.

## Kết quả đã thực thi

| Nhóm kiểm tra | Kết quả | Bằng chứng |
|---|---|---|
| Android toàn dự án | 750 ca / 95 bộ, 0 thất bại, 0 lỗi, 0 bỏ qua | Full `testDebugUnitTest` hoàn tất; supervisor quan sát `BUILD SUCCESSFUL in 51s`. Bản chụp [JUnit](F:/AI/12Tester/audit/daily-task-action-gate-2026-11-12/supervisor-runs/android-2026-11-13T19-03-26-279Z/results.json) |
| Backend PGlite | 11/11 chương trình kiểm thử đạt | [Kết quả độc lập](F:/AI/12Tester/audit/daily-task-action-gate-2026-11-12/supervisor-runs/r3-backend-2026-11-13T18-35-13-677Z/results.json) |
| PostgreSQL thực: khóa, quota và worker | 5/5 ca đạt | [Chi tiết từng ca](F:/AI/12Tester/audit/daily-task-action-gate-2026-11-12/supervisor-runs/native-r4-2026-11-13T19-01-31-648Z/review-probes.json) |
| PostgreSQL thực: báo cáo, thưởng và hoàn ngân sách | 7/7 tình huống đồng thời đạt | [Nhật ký tài chính](F:/AI/12Tester/audit/daily-task-action-gate-2026-11-12/supervisor-runs/native-r4-2026-11-13T19-01-31-648Z/bonus-concurrency.log) |

11 chương trình backend bao gồm 90 ca acceptance, quyền biên nhận, helper riêng tư, actor thật, hợp đồng admin resume, vòng đời production 089, ngưỡng tự dừng 1–7 ngày, gỡ app/thưởng, quota tức thời, nợ cộng dồn và đồng bộ egress. PGlite được phân biệt với kiểm thử đồng thời PostgreSQL thực.

Năm ca native mới kiểm tra: tạm dừng trước khi lấy khóa slot gặp tiếp tục; gỡ app sau gate nhưng trước DELETE gặp đăng app; gỡ app sau hoàn escrow nhưng trước UPDATE closed gặp đăng app; worker xử lý yêu cầu gỡ đã chấp nhận khi chủ app có nghĩa vụ mới chưa đủ; finalizer thật giữ khóa profile gặp tiếp tục app. Các ca khẳng định kết quả nghiệp vụ, số dư, hoàn quỹ đúng một lần, gọi lại và quota.

## Tính toàn vẹn và giới hạn

- [Đối chiếu bằng chứng cuối](F:/AI/12Tester/audit/daily-task-action-gate-2026-11-12/SUPERVISOR_FINAL_EVIDENCE_R4.json) đạt: mã migration hiện tại khớp cả lượt backend và native; nguồn Android khớp lượt 750 ca; các assertion supervisor và driver native không bị nới hoặc bỏ qua.
- SQL 090: SHA-256 `d239233de6c67d413c2a5a4668871a5357dcc9822a591eeeb7d917cfb1b1544e`. ViewModel: `509a69688b0f1d815e5f0e48bb06b594783a6bc4af49df61b9bb0e18a7a6cb10`.
- PostgreSQL 17.11 chỉ dùng cụm mới của nhiệm vụ, `127.0.0.1:55444`. Driver xác minh `data_directory` và PID tiến trình do mình khởi tạo trước khi tạo DB tên mới. Đã dừng bình thường, exit 0; không ghi vào cụm của nhiệm vụ cũ.
- Giữ phiên bản **1.2.5/build 45** và **7 tệp APK/AAB** của người dùng; không có tệp gói mới hoặc thay đổi quyền/launcher/migrations 062–089. Không commit/push, không dùng dữ liệu thật.
- Migration 089/090 **chưa áp dụng lên production** trong nhiệm vụ này. Chưa chạy toàn trình trên thiết bị thật; không tạo APK/AAB mới.
- Kết quả native giới hạn ở các tình huống ghi trong nhật ký, không chứng minh mọi lịch chạy hoặc mọi mức tải đều không thể phát sinh deadlock. Các thông báo từ chối nghiệp vụ được mong đợi và phản hồi lúc PostgreSQL đang khởi động không được tính là lỗi kiểm thử.

## Đối chứng trước R4

Đã thực thi [đối chứng bản trước R4](F:/AI/12Tester/audit/daily-task-action-gate-2026-11-12/supervisor-runs/native-r4-before-2026-11-13T19-11-58-612Z/results.json) trong một DB tên mới. Chỉ DB này nhận lại snapshot 090 trước R4; mã nguồn hiện tại và DB đã đạt được giữ nguyên.

| Ca đối chứng | Bản trước R4 | Bản R4 hiện tại |
|---|---|---|
| Gỡ app sau gate nhưng trước DELETE gặp đăng app | Không giữ được create chờ luồng gỡ; assertion chờ khóa thất bại | Create chờ slot của luồng gỡ; đạt |
| Gỡ app đã hoàn escrow, trước UPDATE closed gặp đăng app | Create chờ profile (`transactionid`) trong khi giữ slot; assertion thứ tự khóa thất bại | Create chờ slot (`advisory`) trước khi đụng profile; đạt |

`pass = false`, exit 1 của bản cũ là kết quả âm dự kiến; `expectedCounterexample = true` xác nhận đúng hai điểm lỗi trên. Ca worker của bản cũ thiếu tiền đề vì ca gỡ/hoàn escrow thất bại, không tính là lỗi sản phẩm riêng. Ca tạm dừng/tiếp tục và finalizer vẫn đạt ở bản cũ.

Đối chứng này chứng minh bài kiểm tra phân biệt được hai bản, không phải bằng chứng đã tái hiện SQLSTATE 40P01. Cả lượt đối chứng và lượt bản mới đều dừng PostgreSQL bình thường, exit 0 của tiến trình server. Không có thay đổi mã sản phẩm sau lượt đạt.
