# Kiểm tra so sánh ảnh báo cáo — 30/10/2026

## Kết luận

Đã xác nhận bằng mã nguồn và 3 kiểm thử Android: điểm 100% hiện tại là tỷ lệ bit dHash giống nhau, không phải tỷ lệ pixel/nội dung ảnh giống nhau. Hai ảnh khác nhau có thể nhận 100%. Đây không phải lỗi làm tròn: chỉ một bit khác đã cho 98%.

Phạm vi lần này: kiểm tra, thêm kiểm thử tái hiện; chưa sửa logic ứng dụng, chưa chạy migration hoặc xác minh dữ liệu production. Không có cặp ảnh thực tế của người dùng để xác định chi tiết nào bị mất trong từng trường hợp.

## Phát hiện

1. `ImageHashHelper.kt:61`: thu nhỏ ảnh xuống 9×8, chuyển mức xám rồi chỉ lưu 64 phép so sánh sáng/tối ngang. Chi tiết nhỏ, màu sắc và mức độ chênh sáng bị mất. Hash bằng nhau không chứng minh ảnh bằng nhau.
2. `ImageHashHelper.kt:165`: điểm = `(64 - distance) * 100 / 64`. `compare` phân loại distance <= 5 là DUPLICATE; `isBlockingDuplicate` cũng dựa trên phân loại đó. Các ngưỡng chưa chứng minh được một người có thực sự test hay không.
3. `OwnerAppDetailScreen.kt:2162` và `ImageViewerComponents.kt:420`: ưu tiên hash lưu trong DB; ảnh dù được mở để xem cũng không được đối chiếu chi tiết để xác nhận kết luận 100%.
4. `OwnerAppDetailScreen.kt:2101`: chọn báo cáo trước theo cùng user và createdAt nhỏ hơn. Không bắt buộc khác ngày hoặc cùng submission_context/mission. Vì vậy đây là báo cáo trước đó, không nhất thiết ngày test trước tương ứng.
5. `OwnerAppDetailScreen.kt:2327`: dùng `100 - similarity` để diễn đạt tính năng mới. Khoảng cách hash không đo lượng tính năng mới được kiểm thử.
6. `CampaignDetailScreen.kt:2888`: có thể chặn nộp lại báo cáo bị từ chối dựa trên dHash gần giống, kể cả ảnh đã thay đổi.
7. `supabase/migrations/064_explicit_report_context.sql:474` và `:560`: kiểm tra hash bằng nhau trên toàn bảng tester_reports (nhánh cập nhật chỉ loại id hiện tại), không giới hạn người nộp/campaign. Va chạm dHash có thể dẫn tới SAME_SCREENSHOT_REJECTED giữa các tài khoản hoặc ứng dụng. Đây là kết luận về migration trong workspace, chưa xác minh phiên bản đang triển khai.

## Kiểm thử tái hiện

File: `app/src/test/java/com/ntt/crosstest/audit/ImageHashCollisionAuditTest.kt`.

- Bitmap trắng và bitmap xanh có pixel khác nhau nhưng điểm 100%, isBlockingDuplicate=true.
- Thay đổi một pixel trên ảnh gradient mà vẫn giữ quan hệ sáng/tối: điểm 100%.
- Hash bằng nhau cho 100%; khác một bit cho 98%, loại trừ giả thuyết làm tròn lên 100%.

Chạy `:app:testDebugUnitTest --tests com.ntt.crosstest.audit.ImageHashCollisionAuditTest --offline`: BUILD SUCCESSFUL, 3 kiểm thử thành công với Robolectric native graphics. Đây là kiểm thử mô tả lỗi hiện tại, không phải chứng nhận thuật toán đã được sửa.

## Hướng sửa đề xuất

- Giữ dHash như tín hiệu sàng lọc nhẹ; không dùng riêng nó để kết luận ảnh trùng hoặc tự chặn nộp.
- Tách dấu vân tay chính xác (SHA-256 của file đã upload, hoặc pixel giải mã chuẩn hóa nếu cần so nội dung) khỏi dấu vân tay cảm nhận. SHA file chỉ xác nhận trùng file, không phát hiện mọi bản ảnh đã nén lại.
- Khi cần đánh giá ảnh gần giống, so chi tiết theo vùng trên ảnh có độ phân giải đủ cao, kết hợp màu và cấu trúc. Hiển thị rõ đây là ước lượng; kiểm định ngưỡng bằng cặp ảnh thực tế trước khi dùng để chặn.
- Dữ liệu cũ chỉ có dHash phải hiện “bố cục gần giống, cần xem ảnh”, không suy diễn thành trùng tuyệt đối. Không sửa bằng cách đơn thuần ép 100 xuống 99.
- Xác định rõ chính sách phát hiện dùng lại ảnh trong cùng tester/campaign và giữa nhiều tài khoản; backend phải dùng bằng chứng trùng chính xác để chặn, thay vì hash cảm nhận bằng nhau trên toàn bảng.
- Chọn báo cáo đối chiếu theo ngày Việt Nam và ngữ cảnh app_test/mission; bỏ nhãn phần trăm tính năng mới.
- Bổ sung bộ ảnh kiểm định: đổi chữ/số, đổi màu, thay đổi nội dung một vùng, ảnh giống hệt, ảnh nén lại, cùng giao diện nhưng thao tác khác, và báo cáo khác nhiệm vụ cùng ngày.
