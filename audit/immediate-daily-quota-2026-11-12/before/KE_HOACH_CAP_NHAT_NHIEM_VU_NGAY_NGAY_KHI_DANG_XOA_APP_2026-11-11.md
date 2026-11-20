# Kế hoạch cập nhật nhiệm vụ ngay khi đăng thêm hoặc xóa app

Ngày: 11/11/2026. Trạng thái: đã khảo sát mã và lập kế hoạch; chưa triển khai sửa mã, chưa build APK.

## 1. Yêu cầu đã xác nhận

- Đăng app thành công thì app đó được tính nhiệm vụ ngay trong ngày, không đợi sang ngày sau.
- Xóa/gỡ app thành công thì giảm ngay phần nhiệm vụ tương ứng của ngày hiện tại.
- Người dùng đã xác nhận: **tài khoản vừa đăng ký, đăng app ngay trong ngày đăng ký cũng áp dụng chỉ tiêu ngay**.
- Số lượt trên mỗi app lấy từ cấu hình nhiệm vụ ngày đang có hiệu lực. Ví dụ tỷ lệ là 12 thì 1 app = 12, 2 app = 24, 3 app = 36 lượt/ngày. Không cố định con số 12 trong mã.
- Giữ riêng phần thiếu chuyển tiếp từ những ngày trước. Đăng/xóa app chỉ làm thay đổi phần chỉ tiêu cơ bản hôm nay.

Ví dụ dưới đây giả sử đủ app khác để kiểm thử, chưa có nợ cũ:

| Thao tác | App được tính chỉ tiêu | Chỉ tiêu cơ bản hôm nay |
|---|---:|---:|
| Đang có 1 app | 1 | 12 |
| Đăng thêm 1 app | 2 | 24 ngay sau khi máy chủ xác nhận |
| Đăng thêm app thứ ba | 3 | 36 |
| Xóa 1 trong 3 app | 2 | 24 |
| Xóa tiếp 1 app | 1 | 12 |
| Xóa app cuối cùng | 0 | 0 lượt test cơ bản; nợ cũ còn lại vẫn được bảo lưu |

## 2. Hiện trạng đã đối chiếu

Hàm tính chỉ tiêu mới nhất nằm trong [082_carryover_flow_audit_fixes.sql](supabase/migrations/082_carryover_flow_audit_fixes.sql):

- `get_daily_task_status` đang yêu cầu app của chủ có `created_at < v_day_start` ở cả nhánh hiện tại và nhánh lịch sử. Vì vậy app vừa đăng hôm nay chưa được cộng vào số app phải làm nhiệm vụ.
- Chỉ tiêu cơ bản được tính bằng `tests_per_active_app × active_app_count`; công thức này có thể giữ, cần sửa cách xác định số app.
- Finalizer còn miễn toàn bộ ngày tạo tài khoản và ghi phần thiếu bằng 0. Nếu chỉ sửa bộ đếm, tài khoản mới có thể thấy chỉ tiêu nhưng đến chốt ngày lại được miễn hết. Cần sửa đồng bộ theo lựa chọn của người dùng.
- Đăng app qua `create_campaign_v2` trong migration 084 có chống gửi trùng và quyết toán phí. Repository đã xóa cache liên quan sau thành công; cần bảo đảm trạng thái đang hiển thị trên Home cũng nhận kết quả mới ngay.
- Xóa app qua luồng mới ở migration 087 có thể xóa ngay hoặc trả `pending_removal`, đồng thời chuyển app sang `closed` để chờ giải quyết báo cáo/quỹ thưởng. App đã đóng theo yêu cầu gỡ bỏ phải được loại khỏi chỉ tiêu ngay, không đợi xóa vật lý xong.
- Migration 085 có đồng bộ điều kiện V2; kế hoạch phải hoạt động với cả đường đồng bộ cũ và V2.

Đây là kết quả đọc mã, chưa phải kết quả chạy kiểm thử hành vi cho tính năng mới.

## 3. Quy tắc tính sau thay đổi

Ký hiệu:

- **q**: số lượt test cho mỗi app theo chính sách có hiệu lực trong ngày.
- **K**: số app của người dùng đang thuộc diện tính nhiệm vụ ở thời điểm xét.
- **B = q × K**: chỉ tiêu cơ bản hôm nay.
- **C**: phần thiếu đã chốt, chuyển từ hôm qua.
- **D**: số app khác nhau đã test đủ điều kiện hôm nay.

Khi cộng dồn đang bật, tổng chỉ tiêu danh nghĩa là **B + C**. Áp dụng tiếp quy tắc giới hạn theo nguồn app khả dụng và bảo lưu nợ hiện có. Ví dụ B=24 nhưng chỉ có 20 app khả dụng thì giao diện phải phân biệt 24 chỉ tiêu cơ bản với chỉ tiêu thực tế theo chính sách; không đổi quy tắc thiếu nguồn app trong đợt này.

Các nguyên tắc bắt buộc:

1. Đăng thêm một app đủ điều kiện: K tăng 1, B tăng q ngay. Xóa/gỡ một app đang được tính: K giảm 1, B giảm q ngay.
2. Tính lại từ trạng thái app trên máy chủ, không thực hiện phép cộng/trừ mù trên số chỉ tiêu lưu ở điện thoại. Gửi lại cùng một yêu cầu đăng không được cộng thêm lần nữa.
3. Giữ D khi đăng/xóa app. Ví dụ đã test 5 app, chỉ tiêu từ 12 lên 24 thì vẫn là 5/24; giảm lại còn 12 thì là 5/12. Nếu D vượt chỉ tiêu mới, phần còn lại chặn ở 0, không tạo lượt dư chuyển sang ngày mai.
4. Không trừ trực tiếp q khỏi C khi xóa app. C chỉ giảm theo lượt test hợp lệ và công thức trả nợ hiện có.
5. Chỉ giảm chỉ tiêu sau khi máy chủ đã chấp nhận gỡ bỏ: `removed`, hoặc `pending_removal` với app đã chuyển `closed`. Thao tác thất bại không thay đổi chỉ tiêu. Khi dọn xóa vật lý sau đó, không giảm lần thứ hai.
6. Tiếp tục giữ bộ điều kiện về vòng đời app hiện có: app `open` và app bị dừng do `owner_inactive` vẫn được tính theo quy tắc hiện hành; không đưa app đã đóng/gỡ bỏ vào K. Kiểm tra riêng các trạng thái tạm dừng, hết slot, hết thời hạn và hoàn tất để không thay đổi chúng ngoài yêu cầu này.
7. Tài khoản thường đăng app trong ngày đăng ký phải nhận nhiệm vụ, được chốt phần thiếu và áp dụng chuỗi thiếu/phạt theo chính sách như chủ app khác. Thành viên mới chưa có app và vai trò được miễn theo chính sách vẫn theo quy tắc tương ứng.
8. Mỗi app đăng trong ngày được tính đủ q ngay, kể cả đăng cuối ngày; không chia tỷ lệ theo số giờ. Mốc chuyển ngày dùng giờ Việt Nam. Đây là cách thực hiện yêu cầu cộng ngay toàn bộ nhiệm vụ cho app vừa đăng.

## 4. Sửa backend

### 4.1. Migration mới và ngày bắt đầu áp dụng

- Thêm migration mới sau 087, dự kiến `088_immediate_daily_quota_on_campaign_changes.sql`; kiểm tra lại số thứ tự khi bắt đầu thực hiện.
- Có mốc ngày hiệu lực được lưu phía máy chủ, dùng thống nhất cho cách đếm mới và quy tắc ngày đăng ký. Không hard-code ngày 11/11/2026 làm ngày triển khai.
- Nên triển khai mã trước rồi áp dụng từ đầu một ngày Việt Nam đã chọn, để không thay đổi quy tắc giữa một ngày đang hoạt động.
- Ngày trước mốc hiệu lực vẫn tính theo quy tắc cũ nếu cần chốt bù. Ngày đã chốt luôn trả snapshot cũ; không tính lại lịch sử hoặc số dư Credit.

### 4.2. Bộ đếm app hiện tại và lịch sử

- Với ngày áp dụng quy tắc mới, app được tính kể từ khi đăng thành công trong ngày đó, thay vì yêu cầu đăng trước đầu ngày.
- Sửa cùng lúc nhánh hiện tại và nhánh dựng lịch sử theo `campaign_status_journal`. Ngày quá khứ phải xét trạng thái đúng ở cuối ngày được tính, không lấy trạng thái hiện tại.
- Giữ giới hạn tuổi app và các điều kiện vòng đời hiện có. App đăng rồi gỡ trong cùng ngày phải cho kết quả cuối ngày phù hợp với số app còn được tính.
- Giữ bảo vệ timestamp và quyền ghi. Client không được sửa ngày đăng để tác động chỉ tiêu.
- Các nơi đọc chỉ tiêu, trạng thái đạt và điều kiện tiếp tục test app phải cùng dùng kết quả máy chủ này.

### 4.3. Đồng bộ sau đăng/gỡ app

- Sau khi tạo app, xóa app hoặc chuyển app sang đóng để chờ gỡ, làm mới trạng thái ngày của đúng chủ app bằng cơ chế `refresh_daily_completion` hiện có hoặc cơ chế tương đương.
- Chỉ cập nhật dữ liệu ngày chưa chốt, không tự chạy quyết toán/phạt hoặc đóng sổ hôm nay khi người dùng vừa đăng/xóa app.
- Xác định lại B, chỉ tiêu thực tế, trạng thái đạt và phần còn lại; không sửa C của ngày trước, không reset D hoặc chuỗi thiếu đã xác nhận.
- Bảo đảm phần thay đổi app và dữ liệu ngày nhất quán trong transaction. Dùng thứ tự khóa phù hợp với khóa theo chủ app, hồ sơ và slot đang có để tránh xử lý đồng thời bị lệch.
- Giữ nguyên ý nghĩa receipt tài chính và chống gửi trùng của `create_campaign_v2`; không lưu trạng thái nhiệm vụ động vào receipt rồi phát lại snapshot cũ ở lần retry.
- Không thay đổi phí đăng app, quỹ thưởng, hoàn quỹ hoặc Credit đã kiếm được vì chỉ tiêu tăng/giảm.

### 4.4. Tài khoản đăng app trong ngày đăng ký

- Getter lấy C=0 từ ranh giới tạo tài khoản, nhưng vẫn tính B theo app vừa đăng.
- Finalizer chỉ giữ nhánh miễn ngày đăng ký cho đối tượng còn được miễn. Chủ app thường đã có app được tính phải đi qua chốt nhiệm vụ bình thường.
- Nhánh này phải bắt đầu với chuỗi thiếu 0 và không đòi một bản ghi hôm qua trước khi tài khoản tồn tại. Không tạo checkpoint quá khứ giả để vượt kiểm tra tiền nhiệm.
- Nếu đến cuối ngày còn thiếu, ghi đúng carry-out và phạt/chuỗi thiếu theo chính sách. Từ ngày sau nối tiếp phần thiếu này như các tài khoản khác.
- Tiếp tục giữ bảo vệ checkpoint của từng người khi chốt bù nhiều người, không phát sinh khoản trừ cho ngày trước mốc đã xác nhận.

## 5. Cập nhật Android và cache

Các tệp trọng tâm: `CampaignRepository.kt`, `CreateCampaignViewModel.kt`, `OwnerAppDetailViewModel.kt`, `HomeViewModel.kt`, điều hướng quay về Home và `SessionCacheManager`.

- Tận dụng phần invalidation đã có; sau thành công, lấy lại `get_my_daily_task_status` hoặc block `daily_task` phù hợp và cập nhật Home ngay khi quay về, không chờ TTL hoặc kéo làm mới thủ công.
- Thông báo thay đổi trạng thái ngày cho phiên hiện tại sau đăng/gỡ app. Đường gỡ chờ kết toán cũng phải đi qua cập nhật này.
- Chỉ làm mới các khối cần thiết; giữ cơ chế tiết kiệm dữ liệu của đồng bộ V2. Token và dữ liệu nền phải nhất quán để không nhận `changed=false` khi dữ liệu cần hiển thị đã bị xóa khỏi cache.
- Chặn phản hồi cũ bằng tài khoản, thế hệ phiên, ngày Việt Nam và phiên bản thay đổi. Kết quả 12 của yêu cầu bắt đầu trước khi đăng thêm app không được ghi đè kết quả 24 mới hơn.
- Nếu app đã đăng/gỡ thành công nhưng lần lấy chỉ tiêu mới bị lỗi mạng, vẫn ghi nhận thao tác app thành công; đánh dấu chỉ tiêu đang cập nhật và thử lại. Không báo đăng app thất bại vì lỗi đọc chỉ tiêu sau commit.
- Khi chủ app đang dùng thiết bị khác hoặc app bị quản trị viên gỡ, kết quả máy chủ phải đúng ngay; thiết bị nhận thay đổi qua kênh đồng bộ theo chủ app/chu kỳ đồng bộ hiện có. Không mở thêm luồng tải lại toàn bộ dữ liệu chỉ để đổi một chỉ tiêu.
- Giao diện thể hiện chỉ tiêu cơ bản, nợ cũ và chỉ tiêu thực tế khi thiếu nguồn. Không reset thanh tiến độ đã làm; giữ dòng nợ bảo lưu kể cả sau khi hoàn thành chỉ tiêu mới.

## 6. Kiểm thử và tiêu chí nghiệm thu

Kiểm thử mới phải nạp toàn bộ migration hiện hành, gọi thật API đăng/gỡ app và đọc lại trạng thái ngày. Không chỉ kiểm tra phép nhân trong một hàm giả.

| Ca | Kết quả cần đạt |
|---|---|
| q=12, đang 1 app, đăng thêm 1 app trong ngày | K=2, B=24 ngay |
| Tài khoản cũ đăng app đầu tiên | B=12 ngay, chuyển sang trạng thái chủ app |
| Đăng app thứ ba; xóa lần lượt | 36 → 24 → 12; không nhân đôi khi dọn xóa vật lý |
| Xóa app cuối, đang có nợ cũ | B=0, nợ cũ còn lại được bảo lưu theo công thức hiện có |
| Tài khoản mới đăng app ngay ngày đăng ký | B=12, chốt thiếu đúng; không miễn nhầm, không chờ hôm qua không tồn tại |
| Tài khoản mới không đăng app | Vẫn theo quy tắc miễn/nghĩa vụ thành viên mới |
| Đã làm D=5 rồi đăng/gỡ | Giữ D=5; hiện 5/24 rồi 5/12 với giả thiết đủ nguồn và không nợ cũ |
| D đã vượt chỉ tiêu sau khi gỡ | Đạt, phần còn lại bằng 0; không âm, không cộng lượt dư sang mai |
| Có C, bật/tắt cộng dồn | Không mất nợ cũ, không reset số đã test |
| Nguồn app chỉ A=20 nhưng B=24 | Giữ B=24; chỉ tiêu thực tế và giải thích đúng chính sách giới hạn nguồn |
| Đăng thất bại vì phí/slot/chính sách hoặc gỡ thất bại | Không thay đổi chỉ tiêu |
| Gửi lại cùng request đăng app | Chỉ một app được tính, không cộng nhiệm vụ lần hai |
| Hai thao tác đăng/gỡ đồng thời hoặc hai thiết bị | K và chỉ tiêu cuối bằng dữ liệu thực tế, không mất cập nhật |
| `pending_removal`, gỡ bởi chủ/admin, xóa vật lý sau kết toán | Giảm ngay khi app đóng hợp lệ; không ảnh hưởng nghĩa vụ báo cáo/quỹ còn lại |
| App bị `owner_inactive` và các trạng thái vòng đời khác | Giữ đúng tiêu chí hiện có, không lách nợ/bật lại app bằng thay đổi bộ đếm |
| Đăng lúc 23:59; xóa lúc 00:00 | Ngày Việt Nam và journal chính xác, không tác động ngược ngày trước |
| Ngày trước mốc áp dụng đang chờ chốt và ngày đã chốt | Dùng quy tắc đúng thời kỳ; snapshot và Credit cũ không thay đổi |
| Tỷ lệ admin đổi sang 15 từ ngày sau | Dùng đúng q của từng ngày; không hard-code 12 hoặc nhầm với số tester mục tiêu của app |
| Cache V1/V2, lỗi đọc chỉ tiêu sau commit, phản hồi đến sai thứ tự | Home cập nhật đúng, không đảo ngược thao tác đã thành công hoặc lộ dữ liệu chéo tài khoản |

Chạy kiểm thử SQL mới, bộ hồi quy cộng dồn/dừng-bật lại app, kiểm thử nghiệp vụ đăng app, gỡ app/quỹ thưởng và đồng bộ V2; sau đó chạy toàn bộ kiểm thử SQL và Android unit phù hợp. Kiểm tra giao dịch đồng thời trên PostgreSQL nhiều kết nối trước khi đưa lên máy chủ thật. **Không build APK trong phạm vi yêu cầu hiện tại.**

## 7. Thứ tự thực hiện

1. Viết ca tái hiện app đăng hôm nay chưa được tính và ca tài khoản mới bị miễn nhầm; khóa các bất biến nợ, Credit và lịch sử.
2. Thêm migration, mốc hiệu lực, sửa bộ đếm, chốt ngày và làm mới dữ liệu ngày sau thay đổi app.
3. Đồng bộ UI/cache cho đăng thành công, gỡ ngay và gỡ chờ kết toán; xử lý retry và phản hồi cũ.
4. Chạy ma trận kiểm thử, rà quyền và các luồng tài chính hiện có; ghi báo cáo nghiệm thu với đúng phiên bản mã đã kiểm tra.
5. Nghiệm thu trên môi trường thử và chuẩn bị triển khai backend/ứng dụng theo mốc hiệu lực. Việc phát hành hoặc build APK là công việc riêng theo yêu cầu tiếp theo.
