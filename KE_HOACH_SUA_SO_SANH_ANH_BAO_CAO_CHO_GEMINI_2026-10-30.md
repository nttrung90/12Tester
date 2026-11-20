# Kế hoạch sửa so sánh ảnh báo cáo cho Gemini

Ngày: 30/10/2026. Workspace: `F:\AI\12Tester`.

## 1. Mục tiêu và phạm vi

Ảnh khác nhau không còn bị kết luận “trùng 100%” hoặc bị chặn chỉ vì trùng/gần trùng dHash. Chủ app vẫn có công cụ xem hai ảnh và cảnh báo bố cục tương tự, nhưng không được suy diễn điểm hash thành tỷ lệ nội dung/tính năng đã test.

Triển khai mã nguồn Android, migration mới, kiểm thử và hướng dẫn phát hành. Không tự triển khai production, không sửa dữ liệu báo cáo hoặc số dư thực tế. Không cần thay thuật toán dHash đang lưu để giải quyết lỗi này.

Đọc trước `BAO_CAO_KIEM_TRA_SO_SANH_ANH_2026-10-30.md` và kiểm thử `ImageHashCollisionAuditTest.kt`. Workspace đang có nhiều thay đổi chưa commit; giữ nguyên công việc khác. Kiểm tra AGENTS.md nếu có và diff trước khi sửa.

## 2. Quyết định thiết kế bắt buộc

### 2.1 Tách bằng chứng trùng chính xác khỏi ước lượng

- Giữ `image_hash` là dHash 16 ký tự hex để tương thích dữ liệu và client cũ. Không nhét SHA-256 hoặc hash phiên bản khác vào trường này.
- dHash chỉ trả kết quả bố cục: gần giống / khác / không đủ dữ liệu. Không hiển thị điểm dHash như phần trăm ảnh giống nhau; không chuyển thành xác suất gian lận.
- Hash bằng nhau, kể cả `0000000000000000`, không phải bằng chứng trùng ảnh. Đầu vào null, rỗng, sai độ dài hoặc không phải hex phải là UNKNOWN, không coi là “khác ảnh 0%”.
- `isBlockingDuplicate` không được true từ dHash. Bỏ hoặc thay bằng thuộc tính biểu thị bằng chứng chính xác tại mọi nơi sử dụng, không chỉ đổi tên enum.
- Không sửa giả bằng cách ép 100 xuống 99, tăng kích thước hash rồi giữ nguyên ngữ nghĩa, hoặc đổi ngưỡng <=5 xuống 0.
- Không áp dụng phạt, tự từ chối hay kết luận tester gian lận từ ảnh gần giống.

### 2.2 Hai mức so sánh

**Danh sách/thẻ báo cáo:** dùng hash có sẵn, không tự tải hai ảnh đầy đủ cho từng thẻ. Hiện “Bố cục gần giống — cần xem ảnh” hoặc trạng thái không đủ dữ liệu. Với hash khác, chỉ mô tả khác bố cục; không kết luận đã test tính năng mới.

**Khi mở so sánh:** tải ảnh qua ScreenshotManager/cache hiện có, thực hiện trên dispatcher nền, so thêm màu và thay đổi theo vùng. Tối thiểu đưa ra trạng thái “Phát hiện khác biệt”, “Gần giống, cần kiểm tra” hoặc “Không đủ dữ liệu”; hiển thị hai ảnh rõ ngày/ngữ cảnh và cho phóng to như hiện có.

Đề xuất triển khai nhẹ: chuẩn hóa ảnh về kích thước làm việc có giới hạn, giữ tỷ lệ ảnh, chia ô và đo chênh RGB chuẩn hóa từng ô. Kết hợp thống kê toàn ảnh với ô thay đổi lớn nhất để thay đổi cục bộ không bị trung bình toàn ảnh che mất. Không kéo giãn hai tỷ lệ ảnh khác nhau thành cùng một bố cục. Ghi rõ kích thước, cách xử lý alpha, ngưỡng và phiên bản thuật toán trong code/report. Kiểm định bằng fixture trước khi chốt ngưỡng; các kết quả này chỉ là gợi ý, không dùng để chặn.

So sánh ảnh đã resize/thumbnail không đủ để tuyên bố “giống hệt”. Chỉ dùng nhãn “Trùng file” nếu có bằng chứng bytes/SHA-256 của đúng file; hoặc “Trùng pixel ảnh giải mã” nếu kiểm tra toàn bộ pixel ở kích thước giải mã gốc, cùng dimensions, không subsample. Nếu pipeline hiện tại không cung cấp đủ bằng chứng, bỏ nhãn trùng chính xác và phần trăm 100%, không cần tải thêm full-resolution chỉ để đạt nhãn đó. Hai ảnh đã nén khác bytes có thể vẫn gần giống; không ép kết luận trùng.

### 2.3 Quy tắc chọn ảnh đối chiếu

Tách thành helper có unit test, dùng chung thẻ owner, dialog và màn nộp:

- Cùng userId, campaignId và submission_context.
- Với mission: cùng missionId; không ghép mission với app_test. Không đoán mission khi dữ liệu không xác định.
- So sánh các ngày: lấy báo cáo gần nhất ở ngày trước ngày báo cáo hiện tại theo `Asia/Ho_Chi_Minh`, có screenshotUrl. Parse timestamp thành instant/ngày, không so chuỗi hoặc dùng `take(10)` để quyết định ngày.
- Không chọn chính report hiện tại; thứ tự input không ảnh hưởng kết quả. Định nghĩa tie-break ổn định khi timestamp bằng nhau.
- Ngoại lệ nộp lại báo cáo hôm nay bị từ chối: mốc đối chiếu là chính ảnh bị từ chối, được gắn nhãn riêng; không coi đó là ảnh “ngày trước”.
- Ảnh đã hết hạn: có thể cảnh báo bố cục từ hash hợp lệ, nhưng so chi tiết là unavailable; không vượt qua quy tắc expiry để tải ảnh.
- Nếu danh sách lịch sử mới tải một phần, ghi đúng “chưa có ảnh đối chiếu trong dữ liệu đã tải”, không tuyên bố đây chắc chắn là báo cáo đầu tiên. Kiểm tra nguồn dữ liệu và phân trang, không tải toàn lịch sử vô hạn.

## 3. Backend: sửa điều kiện chặn sai

Đọc lại chuỗi migration hiện hành; `064_explicit_report_context.sql` hiện định nghĩa `submit_tester_report_internal_v2`, có hai truy vấn `lower(image_hash) = lower(p_image_hash)` trên toàn bảng rồi ném `SAME_SCREENSHOT_REJECTED`.

- Thêm migration số kế tiếp còn trống lúc triển khai (không mặc định 065 nếu đã có file mới). Không sửa baseline 062, không sửa/xóa migration đã tồn tại hoặc thư mục archive.
- Loại bỏ việc từ chối chỉ vì trùng dHash ở cả nhánh tạo mới và cập nhật, cùng mọi đường RPC thực tế còn áp dụng điều kiện đó. Rà cả hàm cũ có thể gọi từ client cũ; sửa theo luồng gọi hiện hành, không sửa archive.
- Giữ validation định dạng dHash, xác thực, quyền sở hữu URL/media, kiểm tra ảnh bắt buộc, status/review, mission context, quota, thưởng, giao dịch, receipts/idempotency và cancellation.
- Giữ yêu cầu thay ảnh khi nộp lại báo cáo bị từ chối nếu ảnh vẫn là cùng tài nguyên cũ, theo các kiểm tra URL/media có sẵn. Không thay thành kiểm tra toàn bảng screenshotUrl thiếu quyền truy cập hoặc lộ dữ liệu.
- Bổ sung kiểm thử: hai user/campaign khác nhau có cùng dHash vẫn nộp được nếu các điều kiện còn lại hợp lệ; một user dùng hai file khác cùng dHash cũng không bị từ chối vì hash.
- Không thêm cơ chế chống sao chép toàn hệ thống dựa trên SHA client tự khai. `PendingReportStore.screenshotSha256` hiện là kiểm tra toàn vẹn file cục bộ, chưa phải bằng chứng server xác minh.

Giới hạn chấp nhận rõ ràng trong bản sửa: khi một ảnh cũ được upload thành URL mới, hệ thống có thể chỉ cảnh báo, chưa chặn chắc chắn việc dùng lại ảnh. Nếu cần hard-block trường hợp này, đó là hạng mục tiếp theo: server xác minh digest của object upload và bind với owner/object/version, có thiết kế riêng. Không giữ false-positive dHash chỉ để che giới hạn này.

## 4. Android: các điểm cần rà và sửa

| Khu vực | Công việc |
|---|---|
| `util/ImageHashHelper.kt` | Tách kết quả heuristic khỏi exact; validate hash; không tự suy diễn UNKNOWN thành DIFFERENT; bỏ ngữ nghĩa dHash 100%/blocking |
| Helper mới cho chọn báo cáo | Lọc cùng tester/campaign/context/mission, ngày Việt Nam, timestamp lỗi và thứ tự input |
| Helper mới cho so chi tiết | Tính trên nền, bounded memory, màu + vùng, kết quả có mức bằng chứng; hủy được |
| `ui/screens/OwnerAppDetailScreen.kt` | Dùng helper chọn báo cáo; bỏ “100 - similarity = tính năng mới”; reset kết quả/loading khi inputs thay đổi |
| `ui/screens/CampaignDetailScreen.kt` | Cảnh báo mềm dHash; không khóa nút nộp vì dHash; giữ các điều kiện chặn hợp lệ khác |
| `ui/components/ImageViewerComponents.kt` | So chi tiết khi mở; trạng thái đang kiểm tra/không đủ dữ liệu; không hiển thị kết quả cũ khi chuyển cặp ảnh |
| `util/ScreenshotManager.kt` và cache liên quan | Ưu tiên tái sử dụng API hiện có; giữ auth/sessionGeneration/expiry; không recycle bitmap dùng chung |
| `res/values`, `values-vi`, `values-en` | Cập nhật đầy đủ cảnh báo, nhãn, accessibility; không hardcode tiếng Việt trong Kotlin |
| Repository/models/workers | Rà callsite và thay kiểu kết quả nếu cần; không tự ý đổi contract nộp báo cáo hoặc pending payload |

LaunchedEffect/remember phải gắn đúng cặp report, URL, hash và thông tin expiry. Hủy tác vụ cũ khi đổi cặp; reset state trước khi tính; dùng finally phù hợp để không kẹt loading. Không để kết quả của user/session cũ xuất hiện sau logout. Không tăng egress bằng cách tải full ảnh khi cuộn danh sách.

## 5. Trình tự triển khai

1. Chụp danh sách diff hiện có, đọc code thật và ghi contract so sánh mới.
2. Sửa kiểu kết quả dHash, helper chọn report và unit tests thuần logic.
3. Sửa migration/backend tests để bỏ hard-block theo dHash.
4. Tích hợp UI cả ba nơi; thêm so chi tiết có giới hạn khi mở dialog; cập nhật ba bộ strings.
5. Chạy kiểm thử mục tiêu rồi các suite báo cáo/mission/idempotency/cache liên quan. Không chỉnh test để hợp thức hóa lỗi còn tồn tại.
6. Review diff, ghi báo cáo kết quả/giới hạn và hướng dẫn phát hành backend trước hoặc cùng đợt app. App mới dùng backend cũ vẫn có thể bị từ chối sai; backend mới không sửa được nhãn 100% trên app cũ.

## 6. Ma trận nghiệm thu

| Tình huống | Kết quả bắt buộc |
|---|---|
| Hai file khác nhưng dHash bằng nhau | Không kết luận trùng chính xác, không chặn nộp vì hash |
| Trắng so với xanh | Không mô tả trùng ảnh; so chi tiết phát hiện khác màu |
| Đổi chữ/số/vùng nhỏ đủ nhìn thấy trong fixture | Không khẳng định trùng tuyệt đối; thử thống kê vùng, công bố giới hạn độ phân giải |
| Một pixel khác trước bước resize | Không được khẳng định exact chỉ từ ảnh resize dù thuật toán không phát hiện thay đổi |
| Ảnh thực sự giống hệt | Chỉ kết luận exact khi có bằng chứng chính xác; nếu chỉ dHash thì cảnh báo mềm |
| Cùng ảnh JPEG nén lại | Không mặc định exact; không false hard-block theo dHash |
| Ảnh khác tỷ lệ/kích thước/xoay | Không kéo giãn để tạo kết luận exact sai |
| Hash thiếu/sai, tải lỗi, hết hạn | UNKNOWN/unavailable; không hiển thị 0% khác hay 100% giống |
| Đổi cặp ảnh khi đang tải, logout | Không hiển thị kết quả cũ; không kẹt loading |
| App_test và mission cùng ngày | Không chọn nhầm ngữ cảnh |
| Hai timestamp khác ngày UTC nhưng cùng ngày VN, và ngược lại | Chọn đúng ngày Việt Nam |
| Nộp lại ảnh mới có dHash gần ảnh bị từ chối | Không chặn chỉ vì dHash; yêu cầu ảnh mới hợp lệ vẫn còn |
| Trùng dHash với user khác | Không SAME_SCREENSHOT_REJECTED chỉ vì hash |
| Retry/idempotency/client cũ | Không thưởng hai lần, không đổi context, RPC vẫn tương thích |
| Cuộn danh sách báo cáo | Không phát sinh tải cặp ảnh đầy đủ cho từng card |

Kiểm thử `ImageHashCollisionAuditTest.kt` hiện chứng minh lỗi cũ. Giữ assertion chứng minh va chạm hash, nhưng đổi assertion hành vi sang không blocking/không exact theo contract mới; không xóa fixture chỉ để test xanh. Không bắt buộc helper toán học nội bộ bỏ điểm 100 nếu vẫn cần diagnostic; điều bắt buộc là không dùng điểm đó như phần trăm nội dung hoặc bằng chứng chặn.

## 7. Xác minh và bàn giao

- Android: test helper hash/comparison/selector mới, audit collision, report submission, background worker, RPC compatibility và thumbnail/cache liên quan; assembleDebug hoặc kiểm tra build tương đương.
- Backend: thêm test migration mới vào PGlite harness hiện có và script package; chạy test mới cùng report-context, idempotency/receipt, mission và media liên quan. Harness phải thật sự apply migration mới, không chỉ grep SQL.
- Ví dụ môi trường Windows: `$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'`; chạy `gradlew.bat` với test filter thích hợp. Nếu bị sandbox chặn Gradle cache, xin quyền qua cơ chế công cụ, không đổi cấu hình toàn máy.
- Ghi chính xác lệnh, số test, pass/fail, những kiểm tra chưa chạy. Không tuyên bố đã kiểm tra production hoặc cặp ảnh thực nếu chưa có.
- Bàn giao `BAO_CAO_SUA_SO_SANH_ANH_BAO_CAO_2026-10-30.md`: nguyên nhân, file sửa, migration, bằng chứng kiểm thử, hạn chế, thứ tự deploy và rollback. Rollback phải ghi rõ quay về code cũ có thể đưa false-positive trở lại.

Hoàn tất khi mọi màn không còn ngữ nghĩa “dHash bằng nhau = ảnh trùng 100%”, backend không chặn vì dHash, mốc đối chiếu đúng ngày/ngữ cảnh và các luồng nộp/thưởng cũ vẫn qua kiểm thử.
