# Nghiên cứu phân tải Supabase sang Firebase, giữ trải nghiệm người dùng

Ngày đánh giá: 10/11/2026. Phạm vi: mã nguồn hiện có tại `F:/AI/12Tester`, đối chiếu tài liệu chính thức. Đây là nghiên cứu kiến trúc; chưa thay mã ứng dụng, migration hoặc cấu hình dịch vụ.

## 1. Kết luận

Có thể chuyển một phần việc sang Firebase/Google Cloud. Hướng phù hợp là giữ Supabase làm nơi xác thực và quyết định nghiệp vụ, dùng Firebase để phân phối bản đọc của dữ liệu dùng chung và báo hiệu cập nhật.

Ưu tiên trước mắt: xác nhận và đo cơ chế conditional sync v2 đã có; sau đó thử phân phối một phần dữ liệu dùng chung. Không cần chuyển toàn bộ database hoặc đổi đăng nhập để đạt mục tiêu này.

Chưa có số liệu production mới trong đợt nghiên cứu này: chưa xác nhận lượng egress theo dịch vụ, cấu hình đang bật, migration thực tế, phiên bản APK phổ biến, vùng triển khai và hóa đơn Google Cloud. Vì vậy chưa thể kết luận sẽ giảm bao nhiêu phần trăm hoặc tiết kiệm bao nhiêu tiền. Những nhận định về nguồn tải dưới đây là ứng viên từ mã nguồn, chưa phải bảng xếp hạng theo byte đã đo.

## 2. Những gì mã nguồn cho thấy

| Quan sát | Căn cứ trong repository | Ý nghĩa |
|---|---|---|
| Android dùng Supabase Auth và PostgREST | `data/SupabaseHolder.kt`; `app/build.gradle.kts` | Luồng đăng nhập và dữ liệu nghiệp vụ hiện ở Supabase. Không thấy cài Supabase Realtime trong client này. |
| Firebase có Storage và App Check; chưa thấy SDK Firestore, Firebase Auth hoặc Messaging trong dependency hiện tại | `gradle/libs.versions.toml`, `app/build.gradle.kts`, `firebase.json` | Phân tải database/thông báo cần thêm tích hợp, không phải chỉ bật một tùy chọn. App Check không thay thế đăng nhập người dùng. |
| Ảnh report, thumbnail và icon upload lên Firebase Storage | `util/FirebaseStorageHelper.kt`, `work/ReportUploadWorker.kt` | Byte ảnh vốn đã được phân tải. Chuyển lại nơi lưu ảnh không giải quyết phần JSON/RPC Supabase. |
| Có mã Media Gateway, định tuyến CDN icon và cleanup trên Google Cloud | `backend/media-gateway/`, `util/IconRoutingHelper.kt`, `backend/storage-cleanup/` | Repository có nhiều hơn chức năng upload ảnh đơn thuần, nhưng chưa xác nhận các chế độ này đang được bật thực tế. |
| Feed và detail có cache 3 phút; dashboard có cache 60 giây | `data/CampaignRepository.kt`, hằng số và các hàm đọc tương ứng | Phải so lợi ích mới với cache và cơ chế gộp request đang có, không giả định mọi lần mở màn đều tải đầy đủ. |
| Đã có conditional sync theo khối | migration `085_egress_optimization_sync_v2.sql`; `data/EgressSyncProtocol.kt` | Có thể giữ thời điểm kiểm tra và chỉ gửi phần thay đổi. Đây là phương án ít thay đổi kiến trúc hơn việc nhân bản database. |
| Cờ v2 mặc định false ở client và migration | `data/EgressOptimization.kt`, migration 085, `supabase/operations/egress_sync_v2_rollout.sql` | Có mã không chứng minh production đã áp dụng hoặc bật; phải xác minh trước khi so sánh. |
| Popup có vòng kiểm tra khi app foreground, mặc định 60 giây | `ui/viewmodels/NotificationPopupViewModel.kt` | Là ứng viên nghiên cứu FCM. Tuy nhiên tick có nhiều điều kiện bỏ qua nên không thể suy ra mỗi người luôn gọi mạng mỗi phút. |
| FAQ và hướng dẫn được đọc từ asset trong APK | `data/help/HelpContentRepository.kt` | Hiện không tạo egress Supabase; đưa chúng lên Firebase không tạo tiết kiệm ở Supabase. |

Các đường dẫn Android rút gọn trong tài liệu này nằm dưới `app/src/main/java/com/ntt/crosstest/`.

## 3. Phần nên chuyển và phần nên giữ

| Dữ liệu/công việc | Hướng đề xuất | Mức ưu tiên và điều kiện |
|---|---|---|
| Thông tin hiển thị chiến dịch được phép chia sẻ: tên app, mô tả, icon, liên kết và trường đã duyệt phạm vi công bố | Bản đọc theo phiên bản trên Firebase Hosting/CDN; hoặc Firestore nếu cần đồng bộ từng tài liệu | Ứng viên phân tải chính nếu đo thấy nhiều người đọc cùng dữ liệu. Phải tạo hợp đồng chỉ chứa các trường được phép. |
| Bảng xếp hạng dùng chung | Bản đọc theo phiên bản, cùng thứ tự và thời điểm xác minh như hiện tại | Chỉ chuyển nếu độ mới được bảo toàn. Không thay dữ liệu hiện hành bằng snapshot theo giờ. Hạng riêng của người dùng vẫn cần đối chiếu nghiệp vụ. |
| Thống kê tổng hợp chiến dịch | Có thể làm bản đọc dùng chung ở giai đoạn sau | Tần suất thay đổi cao có thể làm chi phí đồng bộ lớn. Phải giữ ngày kinh doanh VN và chu kỳ thống kê. |
| Báo hiệu có thông báo mới, report thay đổi, cần làm mới cache | Firebase Cloud Messaging (FCM) | Hữu ích cho cập nhật chủ động; chưa coi là khoản giảm polling chắc chắn. Nội dung và quyền vẫn xác nhận từ server. |
| Cờ giao diện, thử nghiệm tính năng, nội dung cấu hình không nhạy cảm | Firebase Remote Config nếu có nhu cầu vận hành | Lợi ích egress có thể nhỏ vì app config đang có cache 10 phút. Không chuyển toàn bộ app config một cách máy móc. |
| Credit, nạp/chi điểm, thưởng/phạt, log giao dịch, slot, tip | Giữ Supabase | Cần giao dịch nhất quán và chống xử lý trùng. |
| Nộp/duyệt/từ chối report, check-in, nhiệm vụ và cộng dồn nợ | Giữ Supabase | Nhiều trạng thái liên kết; nhân bản luồng ghi sang hai nơi làm tăng nguy cơ sai số dư hoặc lệch nhiệm vụ. |
| Phân quyền owner/admin, khóa tài khoản, thông tin cá nhân, mật khẩu test, quyền đọc ảnh | Giữ kiểm soát tại Supabase/backend hiện có | Không đưa vào cache công khai. Nếu cần bản đọc riêng trên Firebase, phải thiết kế thêm danh tính và quyền tương ứng. |
| Thư viện thông báo cá nhân và trạng thái đã đọc/claim/ack | Giữ Supabase trong giai đoạn đầu | FCM chỉ báo hiệu; không thay cơ chế claim chống lặp và outbox ack hiện có. |

### Điểm phải tách trước: feed hiện chưa thuần dùng chung

`get_home_feed()` trong migration 082 trả cả `has_tested_today` theo `auth.uid()`. Danh sách paused/completed cũng khác giữa khách và người đã đăng nhập. Không thể dùng nguyên JSON của một người làm cache chung cho tất cả.

Ngoài ra, `_campaign_json()` trong baseline 062 chứa `test_username`, `test_password`, `admin_note`; model `Campaign` cũng nhận các trường này. Tài liệu này không kết luận quyền công bố hiện tại đúng hay sai; điểm cần chốt là việc nhân bản nguyên object sang endpoint công khai sẽ mở thêm nơi phát tán dữ liệu. Phải lập danh sách trường được phép chia sẻ, bảo toàn phạm vi truy cập hiện có và giữ phần còn lại trong đường đọc có kiểm soát.

## 4. Kiến trúc phân tải được đề xuất

Luồng ghi vẫn đi vào Supabase. Khi dữ liệu dùng chung thay đổi, giao dịch ghi thêm một sự kiện vào bảng hàng đợi bền vững. Worker đọc thay đổi, tạo bản đọc đã lọc trường, xuất sang Google và xác nhận phiên bản đã sẵn sàng. Đây là thiết kế đề xuất, chưa tồn tại đầy đủ trong repository.

```text
Android -- đăng nhập, nghiệp vụ, dữ liệu cá nhân --> Supabase
                                                     |
                                            sự kiện sau thay đổi
                                                     |
                                                   Worker
                                                     |
Android <-- bản đọc dùng chung theo phiên bản -- Firebase/Google CDN

Android <-- tín hiệu cần cập nhật -- FCM
```

Để không tăng độ trễ phát hiện thay đổi:

1. Giữ các sự kiện kiểm tra hiện tại: mở app, resume, kéo làm mới và sau thao tác nghiệp vụ. Dùng cache trên máy để hiển thị ngay như hiện tại.
2. Phản hồi Supabase cho biết phiên bản cần dùng của phần chung và trả phần cá nhân liên quan. Không coi token cũ của client là bằng chứng server chưa thay đổi.
3. Chỉ dùng bản Google khi đúng phiên bản và đúng phạm vi truy cập. Nếu worker chưa xuất xong, thiếu dữ liệu, lỗi tải hoặc phiên bản sai, lấy phần cần thiết từ Supabase. Không đợi một lịch xuất 5–10 phút rồi mới thấy thay đổi.
4. Ghép dữ liệu thành trạng thái nhất quán trước khi hiển thị. Không để số tester thuộc phiên bản mới nhưng trạng thái đã test hoặc ngày nhiệm vụ thuộc bản cũ.
5. Dùng object bất biến theo phiên bản; chỉ công bố dấu mốc sẵn sàng sau khi xuất đủ dữ liệu. Worker phải xử lý retry, sự kiện lặp, sai thứ tự, xóa dữ liệu, đổi quyền và phục hồi khi mất kết nối.

Cách này vẫn có một phản hồi nhỏ từ Supabase khi cần xác minh độ mới, nhưng giảm việc Supabase gửi lại cùng nội dung lớn cho nhiều người. Cold start chưa có cache có thể phải thêm lượt tải Google; cần đo độ trễ P95, tránh thêm chuỗi gọi nối tiếp cho từng khối. Nếu chưa đạt trải nghiệm tương đương thì giữ đường Supabase cho trường hợp đó.

Firebase Hosting phù hợp với bản JSON tĩnh đã lọc và được phép công khai; tài liệu Hosting mô tả cơ chế cache và header điều khiển cache. Với bản đọc yêu cầu đăng nhập, phải dùng đường phục vụ có xác thực hoặc Firestore có rules tương ứng, không dùng URL công khai để thay cho phân quyền. [Tài liệu cache Firebase Hosting](https://firebase.google.com/docs/hosting/manage-cache).

Không cần triển khai lại Firebase Hosting sau mỗi thay đổi bản ghi: có thể dùng endpoint Cloud Run được Hosting rewrite và cache theo phiên bản, hoặc dùng dịch vụ lưu object/CDN thích hợp. Việc chọn hạ tầng cụ thể thuộc thử nghiệm kỹ thuật và chi phí, chưa chốt chỉ từ repository.

## 5. FCM: phù hợp, nhưng không đủ để bảo đảm giữ trải nghiệm khi bỏ polling

FCM có thể báo thiết bị rằng có dữ liệu cần đồng bộ; Firebase niêm yết FCM không thu phí dịch vụ. Worker gửi và việc lấy dữ liệu vẫn có thể phát sinh chi phí. [FCM](https://firebase.google.com/docs/cloud-messaging), [bảng giá Firebase](https://firebase.google.com/pricing).

Đối với popup hiện có, thông điệp nên mang loại sự kiện hoặc phiên bản; app vẫn gọi RPC claim, tuân thủ quota phiên, blocker và ack hiện tại. Không hiện popup trực tiếp từ dữ liệu push rồi bỏ kiểm tra quyền/trạng thái.

FCM có thể giao muộn khi thiết bị ở Doze hoặc offline; việc server nhận message ID không có nghĩa thiết bị đã nhận. Vì vậy nếu giữ kiểm tra định kỳ cùng nhịp hiện tại để bảo đảm phát hiện không chậm hơn, phần request đó vẫn còn. Nếu kéo dài nhịp dự phòng để tiết kiệm thì đã thêm khả năng phát hiện muộn khi push không đến. Chưa nên gọi đó là phương án “không ảnh hưởng trải nghiệm”. [Vòng đời thông điệp FCM](https://firebase.google.com/docs/cloud-messaging/customize-messages/setting-message-lifespan).

Giai đoạn đầu nên dùng FCM bổ sung và đo hiệu quả, gộp các lần đồng bộ bị kích hoạt đồng thời. Muốn giảm lượt kiểm tra rỗng mà giữ hạn phát hiện hiện tại thì nghiên cứu thêm bản tín hiệu phiên bản nhỏ có kiểm soát truy cập; đây là một thiết kế cần kiểm thử riêng, không phải chỉ bật FCM.

## 6. Vì sao chưa nên chuyển dashboard/report sang Firestore

Firestore có thể làm bản đọc, nhưng tự thêm database thứ hai chưa giải quyết độ nhất quán. Dashboard chứa số dư, nhiệm vụ, report chờ duyệt, logs, cảnh báo và dữ liệu cá nhân. RPC hiện có còn ghi nhận đăng nhập hằng ngày; RPC detail có auto-accept report hết hạn. Bỏ đọc Supabase để đọc bản sao có thể vô tình bỏ các hành vi này.

Client hiện dùng Supabase Auth. Để đọc Firestore riêng tư qua SDK, một phương án là backend xác minh phiên Supabase rồi cấp Firebase custom token gắn với đúng user ID; cần thêm sign-in âm thầm, rules, logout và xử lý thay đổi quyền. Không yêu cầu người dùng đăng nhập lần hai, nhưng có thêm vòng đời danh tính cần quản lý. Quyền admin/khóa tài khoản không thể chỉ dựa mãi vào claim cũ. [Firebase custom authentication](https://firebase.google.com/docs/auth/admin/create-custom-tokens).

Firestore tính phí đọc/ghi, lưu trữ, một số lượt đọc index và lưu lượng mạng. Listener có thể phát sinh lại lượt đọc khi kết nối lại; do đó thay polling bằng listener cho toàn bộ danh sách chưa chắc rẻ hơn. Ví dụ giả định 1.000 người, mỗi người nhận 100 document/lần, hai lần/ngày đã tương đương 200.000 document reads/ngày trước cập nhật và các lượt đọc khác. Đây là phép tính minh họa, không phải tải hiện tại của app. [Cách tính phí Firestore](https://firebase.google.com/docs/firestore/pricing).

## 7. Egress chỉ giảm khi giảm dữ liệu rời Supabase

Supabase tính egress dữ liệu gửi từ các dịch vụ, bao gồm Database, Auth và các dịch vụ khác. Firebase gọi Supabase cũng là một client nhận dữ liệu. Vì vậy:

- Android → Cloud Run → Supabase, mỗi request vẫn lấy đầy đủ JSON: egress Supabase nhìn chung vẫn còn, có thể thêm chi phí Google.
- Worker lấy một bản đã thay đổi rồi phục vụ bản đó cho nhiều người từ Google: có cơ sở giảm egress Supabase.
- Worker quét toàn bộ dữ liệu liên tục hoặc tạo snapshot riêng cho từng người: lợi ích có thể giảm mạnh.
- Media Gateway hiện còn gọi Supabase Auth và RPC xác minh grant. Byte ảnh ở Google không có nghĩa mở ảnh tạo ra đúng 0 byte Supabase. Chưa có đo lường chứng minh đây là nguồn tải chính.

Mô hình ước tính, cùng đơn vị byte và cùng cửa sổ đo:

```text
Trước = số lần tải phần chung từ Supabase × kích thước phần chung thực truyền
Sau   = byte đồng bộ worker + byte xác minh phiên bản + byte fallback
Giảm  = Trước − Sau
```

Phải so với baseline conditional sync v2 nếu v2 đã được bật. Không lấy mọi lượt đọc nhân kích thước JSON đầy đủ, vì phản hồi không đổi có thể rất nhỏ. Độ dài JSON sau giải nén không đại diện trực tiếp egress tính phí.

Theo bảng giá Supabase tại ngày nghiên cứu, Pro có 250 GB uncached và 250 GB cached trong hạn mức; phần uncached vượt mức là 0,09 USD/GB. Ví dụ giảm 10 GB đã nằm ngoài hạn mức chỉ giảm khoảng 0,90 USD tiền uncached egress; nếu vẫn trong hạn mức thì chưa giảm khoản phí này. Firebase có chi phí phục vụ riêng. Cần so tổng chi phí và mục tiêu tránh chạm quota, không chỉ nhìn một biểu đồ. [Egress và giá Supabase](https://supabase.com/docs/guides/platform/manage-your-usage/egress).

Remote Config chỉ nên chứa cấu hình không nhạy cảm; cấu hình giao diện không thay giá/quote và kiểm tra nghiệp vụ phía server. [Hướng dẫn Remote Config](https://firebase.google.com/docs/remote-config).

## 8. Thứ tự thực hiện được khuyến nghị

1. Đo baseline trong cùng cửa sổ 3–7 ngày: egress theo dịch vụ, endpoint và sự kiện kích hoạt; byte thực truyền; thời gian P50/P95; người dùng hoạt động và số phiên. Không sử dụng số liệu cũ chưa đối chiếu như baseline hiện hành.
2. Xác minh migration 085, cờ app config và APK thực tế. Kiểm tra conditional sync, cache và gộp request đang hoạt động; đo trước khi thêm database thứ hai. Tách byte đồng bộ worker khỏi byte đến Android trong thống kê.
3. Chọn một khối dùng chung có byte lớn, nhiều người đọc và phạm vi công bố rõ. Thử bản đọc theo phiên bản trên Google. Chưa sao chép toàn bộ dashboard hoặc report.
4. Chạy đối chiếu bản sao với dữ liệu chuẩn trước khi cho app sử dụng; lưu ý giai đoạn đọc cả hai bên có thể tăng chi phí tạm thời. Sau đó phát hành theo nhóm nhỏ, giữ fallback và app cũ dùng hợp đồng cũ.
5. Bổ sung FCM nếu cần cập nhật chủ động. Đo riêng tác động đến số request, pin, độ mới thông báo và tương tác với cơ chế claim/ack; không gộp lợi ích giả định này vào kết quả phân tải phần chung.
6. Chỉ mở rộng sang leaderboard/thống kê/Firestore khi kết quả thực đo chứng minh đáng làm. Không cần triển khai mọi dịch vụ Firebase chỉ vì đã có sẵn project.

## 9. Điều kiện nghiệm thu về trải nghiệm

- Mở app/resume/đổi tab vẫn có nội dung đúng lúc như hiện tại; không thêm màn chờ tải riêng.
- Pull-to-refresh vẫn xác minh server; không chỉ trả snapshot cũ từ Google.
- Nộp, duyệt, từ chối và chi/nhận credit cập nhật theo kết quả giao dịch Supabase; trạng thái cache cũ không ghi đè kết quả mới.
- Giữ nguyên nội dung có thể xem, số lượng và cách truy cập báo cáo/feedback, thứ tự app, quyền và phạm vi hiển thị. Không dùng giảm lịch sử hoặc tăng TTL để đạt KPI.
- Worker trễ, Google lỗi, bản đọc thiếu hoặc sai phiên bản đều có đường phục hồi hữu hạn; không retry vô hạn và không im lặng coi dữ liệu cũ là mới.
- Logout/đổi tài khoản/khóa hoặc mất quyền không để lộ dữ liệu phiên trước; bản sao riêng tư phải được xử lý theo phạm vi quyền.
- Kiểm tra qua mốc ngày VN, hết hạn push/slot, auto-accept report và ghi nhận daily login.
- Đo P50/P95, lỗi, độ mới dữ liệu, egress Supabase và tổng chi phí Google. Chốt ngưỡng hồi quy trước thử nghiệm; chỉ bật rộng khi đạt.

Kết quả nghiên cứu: có phương án phân tải phù hợp, nhưng lợi ích tăng thêm lớn hay nhỏ phụ thuộc vào lượng dữ liệu dùng chung còn được gửi sau tối ưu v2. Bước triển khai hợp lý tiếp theo là đo và thử một bản đọc dùng chung, giữ các giao dịch và quyết định nghiệp vụ tại Supabase.
