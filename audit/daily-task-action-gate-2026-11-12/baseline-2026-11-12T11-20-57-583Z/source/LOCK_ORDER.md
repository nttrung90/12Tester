# Khóa giao dịch và phạm vi kiểm chứng — thưởng thêm cho tester

Áp dụng cho migration 087. Các khóa được giữ đến khi giao dịch kết thúc; helper kế thừa mọi khóa mà hàm gọi và trigger đã lấy. Danh sách dưới đây mô tả các đường thực thi, không đặt ra một thứ bậc chung giả định cho toàn bộ hệ thống.

## Các đường lấy khóa

- **Tạo đợt:** khóa advisory theo chủ app và mã yêu cầu; đọc kết quả cũ để xử lý gửi lại; khóa campaign `FOR UPDATE`; kiểm tra đợt chưa kết toán; khóa ví chủ app `FOR UPDATE`; giữ ngân sách và ghi sổ; tạo round rồi cập nhật bản tóm tắt campaign. Kiểm tra round hiện có dùng `EXISTS`, không phải một câu `FOR UPDATE` riêng.
- **Nộp báo cáo:** giữ nguyên khóa advisory, biên nhận, tra cứu báo cáo cũ và khóa nhiệm vụ của luồng hiện hành. Báo cáo cũ khóa report trước và không nhận snapshot round mới. Nhánh báo cáo app mới khóa campaign và round đang hoạt động bằng `FOR KEY SHARE`, rồi lưu snapshot và report trong cùng giao dịch. Các khóa khác của feedback, checkin và ví trong luồng hiện hành vẫn tồn tại.
- **Duyệt thủ công:** report `FOR UPDATE` → campaign `FOR UPDATE` → các cập nhật thưởng cơ bản/tip trên ví và xử lý nhiệm vụ hiện hành → helper thưởng đợt khóa lại report đã giữ, khóa round `FOR UPDATE`, cập nhật ví tester và sổ thưởng. Vì ví có thể đã được khóa trước round, không thể mô tả toàn bộ hàm bằng thứ tự round → mọi ví.
- **Tự động sau 8 giờ:** report `FOR UPDATE SKIP LOCKED` → campaign `FOR UPDATE` → tip và trạng thái report → xử lý thưởng nhiệm vụ → thưởng đợt. Khóa từ các báo cáo đã xử lý vẫn được giữ trong giao dịch của lượt quét. Nếu lượt quét bỏ qua report đang bị khóa, lượt quét tiếp theo xử lý tiếp; không cộng phần thưởng duyệt thủ công cho report tự động duyệt.
- **Kết thúc campaign:** một cập nhật trạng thái thông thường lấy khóa hàng campaign, thường là `NO KEY UPDATE` nếu không đổi khóa tham chiếu; trigger chuyển round đang hoạt động sang `closing`. Trigger không hoàn ngân sách ngay. `KEY SHARE` tương thích với `NO KEY UPDATE`, nên không được diễn giải rằng khóa snapshot ngăn mọi cập nhật trạng thái.
- **Đóng/hoàn ngân sách:** helper lấy campaign `FOR UPDATE` trước round `FOR UPDATE`, đếm report pending mà không khóa ngược từng report, rồi khóa ví chủ app để kiểm tra và cộng tiền hoàn. Khóa `FOR UPDATE` này phải chờ giao dịch đang giữ `KEY SHARE` khi nhận snapshot hoàn tất, để thấy nghĩa vụ report đã được ghi trước khi hoàn tiền. Ví vượt giới hạn giữ nguyên tiền ký quỹ và trả trạng thái có thể thử lại.
- **Worker:** lấy campaign `FOR UPDATE SKIP LOCKED` trước các round cần đóng, rồi gọi helper hoàn tiền. Campaign đang chờ gỡ được xử lý độc lập với việc round còn `closing` hay đã trả hết suất. Worker giữ report pending và nghĩa vụ nhiệm vụ; chỉ gỡ vật lý khi đã xử lý xong. Round mồ côi không có campaign được xử lý bằng khóa round/ví; `original_campaign_id` vẫn giữ liên kết yêu cầu ban đầu.
- **Admin gỡ app:** khóa campaign trước; xử lý khóa nhiệm vụ và hoàn quỹ nhiệm vụ theo luồng hiện hành; chuyển round sang closing. Nếu còn nghĩa vụ, lưu trạng thái chờ gỡ và lý do. Khi không còn nghĩa vụ, hoàn ngân sách rồi gỡ campaign. Các khóa ví/nhiệm vụ đã lấy được giữ đến cuối giao dịch. Sổ thưởng và mã campaign gốc tồn tại sau khi gỡ.

## Hai lỗi khóa đã được tái hiện và sửa

1. Đóng round từng khóa round rồi chờ campaign, trong khi kết thúc campaign giữ campaign và chờ round. Helper hiện khóa campaign trước round.
2. Worker từng khóa round trước rồi gọi helper khóa campaign, gây vòng chờ với admin gỡ app. Worker hiện khóa campaign trước và bỏ qua campaign đang bận bằng `SKIP LOCKED`.

Không thêm việc khóa report pending trong helper hoàn tiền: điều đó sẽ tạo chiều khóa ngược với duyệt report đang giữ report rồi chờ campaign.

## Bằng chứng đã thực thi

PostgreSQL thật, nhiều kết nối, dữ liệu riêng cho từng tình huống: **7/7 PASS** tại [nhật ký kiểm tra đồng thời](F:/AI/12Tester/audit/tester-bonus-2026-11-11/runs/native-isolated-scenarios-recheck.log).

1. Hai app của cùng chủ cùng giữ ngân sách: một yêu cầu thành công khi ví chỉ đủ một đợt, không âm ví.
2. Hai tester tranh suất cuối: một thưởng thêm, cả hai được duyệt bình thường; không thưởng trùng khi thử lại.
3. Duyệt thủ công gặp lượt tự động sau 8 giờ: report hết hạn duyệt tay, một sự kiện tự động duyệt, một phần thưởng; lặp worker không cộng lại.
4. Duyệt report pending gặp đóng round: giữ nghĩa vụ và hoàn đúng phần dư.
5. Nộp report gặp đóng round, có chốt đồng bộ: snapshot đã nhận giữ được tiền đến khi xử lý report.
6. Worker gặp cập nhật vòng đời campaign: hoàn tất và giữ phương trình ngân sách.
7. Report cũ bị từ chối rồi nộp lại sau khi mở đợt: không lấy snapshot mới.

Supervisor còn chạy các chốt đồng bộ riêng cho nộp/đóng, vòng đời/duyệt, vòng đời/đóng và admin gỡ/worker: các nhật ký `native-core-*.log` trong thư mục kiểm tra đều PASS. Bộ PGlite kiểm chứng hành vi/RLS **17/17 PASS**; đây là kiểm tra đơn kết nối, được phân biệt với PostgreSQL thật.

Phạm vi bằng chứng là các trường hợp đã chạy trên dữ liệu thử nghiệm, không phải chứng minh mọi tổ hợp trigger hoặc tải đồng thời đều không thể deadlock. Khi sửa các đường ví, tip, nhiệm vụ hoặc vòng đời, cần đánh giá cả các khóa mà hàm gọi và trigger giữ sẵn.
