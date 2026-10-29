# Kế hoạch thưởng cập nhật phiên bản app — 29/10/2026

Tài liệu nghiên cứu source Android/Supabase hiện có và đề xuất triển khai. **Chưa sửa tính năng, chưa tạo migration, chưa deploy.** Các thay đổi chưa commit về gửi báo cáo và nhiệm vụ trong workspace được giữ nguyên.

## 1. Yêu cầu và quyết định đã xác nhận

- Chủ app: thêm mục **“Đã cập nhật phiên bản mới”**, mô tả **“Trả cho mỗi người cập nhật 6 Credit”**, ngay dưới **“Người đang cài”** trong trang quản lý app đã đăng.
- Bấm mục trên mở **màn hình riêng** để nhập số phiên bản, tính năng cập nhật mới và bấm **“Áp dụng”**.
- Tester: thêm nút **“Cập nhật phiên bản mới”**, mô tả **“+6 Credit”**, ngay phía trên **“Mở app kiểm thử”**. Bấm để mở trang Play Store của đúng app.
- Khi đã cài bản đáp ứng yêu cầu, nút chuyển thành **“Đã cập nhật”**. Mỗi tài khoản chỉ nhận thưởng một lần trong một đợt phiên bản.
- Chỉ trả thưởng cho **20 người đầu tiên**. Đủ 20 thì ẩn khối cập nhật ở phía tester; mục quản lý của chủ app vẫn còn để công bố bản tiếp theo.
- **Người dùng đã chọn giữ trước 120 Credit cho mỗi phiên bản** qua câu hỏi trong chat. Tiền giữ được dùng để trả 20 × 6 Credit; không thu thêm phí nền tảng.

Các quy tắc bổ sung ở dưới là **đề xuất để hoàn thiện đặc tả**, chưa phải quyết định riêng đã được người dùng xác nhận: cách hoàn phần tiền chưa dùng, thay đợt khi chưa đủ 20 người, điều kiện tester và ứng xử khi app tạm dừng/kết thúc.

## 2. Hiện trạng đã đối chiếu trong repo

| Thành phần | Bằng chứng | Hệ quả triển khai |
|---|---|---|
| Menu quản lý app | `OwnerAppDetailScreen.kt:470` có mục “Người đang cài”; sau đó là báo cáo tester | Chèn `OwnerActionItem` mới ngay giữa hai mục |
| Quyền chủ thật | `OwnerAppDetailViewModel.kt:46,146,219`; RPC ở `034_admin_tester_flow.sql:8` tách `is_actual_owner` khỏi `can_manage` | Chỉ chủ app thật được giữ tiền/công bố đợt; không dùng quyền xem quản lý của admin để tiêu Credit chủ app |
| Khối mở app test | `CampaignDetailScreen.kt:1483` bắt đầu khối test; nhãn nút tại 1545 | Chèn khối cập nhật ngay trước nút này; giữ luồng mở app/báo cáo độc lập |
| Lifecycle | `CampaignDetailScreen.kt:192` dùng `LifecycleResumeEffect`, kiểm tra launch intent rồi refresh | Bổ sung đọc phiên bản trực tiếp khi resume; biết cài app chưa đủ để biết đã cập nhật |
| Dữ liệu app | `Models.kt:61` có `package_name`, `play_url`, `testing_url`; `CampaignMember` tại 167 có `joined_at` | Có định danh package, URL và mốc tham gia; chưa có đợt cập nhật/receipt thưởng cập nhật |
| Đọc chi tiết | `CampaignRepository.kt:1828` gọi `get_campaign_detail`, lưu cache theo campaign và uid | Mở rộng response với trạng thái đợt và receipt cá nhân; phải xử lý cả đường fallback |
| Cache | `CampaignRepository.kt:301,312` invalidate profile/campaign; cache Credit log riêng tại 192,1052 | Giữ tiền/hoàn tiền/nhận thưởng phải làm mới cả số dư, lịch sử và chi tiết app |
| Package cache | `InstalledAppCache.kt:58,126` đã có receiver và hook package thay thế | Có thể tái sử dụng sự kiện để xóa cache phiên bản; vẫn kiểm tra lại khi resume |
| Package visibility | `AndroidManifest.xml:9` đã khai báo truy vấn launcher | Kiểm tra trên thiết bị Android 11+; không mặc định mở rộng sang quyền xem mọi package |
| Cập nhật 12 Tester | `PlayStoreUpdateChecker.kt:23` gọi `AppUpdateManagerFactory.create(context)` | Đây là luồng cập nhật chính 12 Tester; tính năng mới cần đọc package của app được test |
| Thưởng nhiệm vụ | `044_mission_flow_hardening.sql:561` trừ Credit chủ và cộng tester khi settlement; `039_missions.sql` yêu cầu thưởng tối thiểu 10 | Không tái sử dụng mission cho thưởng cập nhật 6 Credit; khác luật và thời điểm thanh toán |
| Xóa app | `CampaignRepository.kt:1630` còn đường xóa bảng trực tiếp; `041_campaign_retention.sql:108,179,198` có trigger archive và purge | Hoàn tiền phải ở database, bao phủ cả client cũ, admin và retention; chỉ thêm vào nút xóa là thiếu |
| Schema | Hiện có migration `048_mission_report_and_expiration.sql`, full schema đã chứa khối 048 | Migration tính năng này dự kiến 049; kiểm tra số cuối lại trước triển khai, cập nhật schema tổng hợp |

Các số dòng là snapshot khi nghiên cứu; một số file đang được chỉnh ở công việc khác nên có thể dịch chuyển.

## 3. Luồng chủ app

### 3.1. Màn hình công bố phiên bản

Route mới đề xuất: `owner_app/{campaignId}/update_release`.

Hiển thị tên/icon app, form và thông tin ngân sách:

1. **Số phiên bản**: tên hiển thị, ví dụ `1.2.3`.
2. Trong cùng nhóm phiên bản có **Mã bản dựng (versionCode)** dạng số, ví dụ `123`, với hướng dẫn lấy đúng mã của bản đã phát hành trên Play Console. Trường bổ sung này phục vụ so sánh tự động; không tự suy ra `123` từ chuỗi `1.2.3`.
3. **Tính năng cập nhật mới**: nội dung nhiều dòng, bắt buộc, đề xuất tối đa 4.000 ký tự; tên phiên bản tối đa 100 ký tự. Trim và kiểm tra ở cả UI/backend.
4. Thông tin rõ ràng: **“Giữ trước 120 Credit • 6 Credit/người • Tối đa 20 người”**, số dư khả dụng và số dư còn lại dự kiến.
5. Nút **“Áp dụng”**, trạng thái đang xử lý, lỗi có thể thử lại. Không có bước xác nhận phụ bắt buộc; chi phí đã hiện ngay trên form.

`versionName` là chuỗi hiển thị, còn `versionCode` là số dùng xác định quan hệ mới/cũ; không so sánh tên phiên bản theo chuỗi hoặc tự giả định mọi app dùng Semantic Versioning. Có thể điền gợi ý từ app cài trên máy chủ app, nhưng vẫn phải kiểm tra đúng bản đang phân phối cho tester. [Android: Version your app](https://developer.android.com/studio/publish/versioning).

Backend chỉ cho công bố khi: đúng chủ thật, tài khoản hợp lệ, campaign `open`, package hợp lệ, mã bản dựng lớn hơn lần công bố trước của cùng app và đủ ngân sách sau hoàn tiền đợt cũ nếu có. Không cho sửa package của một đợt đã công bố. Tên phiên bản và nội dung được chụp lại trong lịch sử.

### 3.2. Sau khi Áp dụng

- Thành công mới báo **“Đã áp dụng phiên bản …”**, hiển thị số người nhận thưởng `0/20`, đang giữ `120`, đã chi `0`.
- Mục menu giữ nguyên tiêu đề/mô tả theo yêu cầu. Màn riêng hiển thị đợt hiện tại, nội dung, tiến độ và form bản tiếp theo.
- Đề xuất cho phép công bố bản mới ngay cả khi đợt cũ chưa đủ 20 người: kết thúc đợt cũ, hoàn phần chưa dùng rồi giữ 120 cho đợt mới **trong cùng transaction**. Hiển thị rõ số hoàn và số giữ trên form trước khi bấm.
- Nếu số dư sau hoàn vẫn không đủ 120: toàn bộ thao tác thất bại; đợt cũ tiếp tục, không hoàn một nửa hoặc tạo đợt thiếu tiền.
- Double tap, timeout rồi thử lại, quay lại màn hình sau process death phải trả cùng receipt công bố; không giữ thêm 120.
- Không cho “Áp dụng” lại cùng mã bản dựng để mở thêm 20 suất. Việc đăng lại campaign cùng chủ/package cũng không được bỏ qua lịch sử mã phiên bản đã dùng.

## 4. Luồng tester và điều kiện nhận thưởng

### 4.1. Quy tắc đề xuất

- Tester phải đăng nhập, tài khoản không bị khóa, là thành viên campaign và không phải chủ app thật. Admin/moderator đang test app người khác không tự bị coi là chủ vì cờ legacy `is_owner`.
- Đề xuất giới hạn thưởng cho thành viên đã tham gia **trước hoặc tại thời điểm công bố đợt**, để tập trung vào cập nhật của tester đang có. Tester tham gia sau vẫn test thường nhưng không được hứa +6 cho đợt đó. Đây là lựa chọn sản phẩm bổ sung; nếu muốn mở cho thành viên mới, cần sửa rõ điều kiện và ca nghiệm thu tương ứng.
- App chưa cài không hiện lời mời nhận thưởng cập nhật; giữ nút cài/test hiện hành. Khi đọc package thất bại do visibility, hiển thị trạng thái chưa xác minh, không tự kết luận đã cập nhật.
- Bản đã cài có `installedVersionCode >= targetVersionCode` được xem là đáp ứng phiên bản. Dùng `>=` để chấp nhận trường hợp tester đã nhận bản mới hơn.
- Đề xuất tính cả tự động cập nhật: thành viên đủ điều kiện đã có bản đáp ứng có thể được xác nhận và nhận 6 khi mở lại màn chi tiết, dù không bấm nút trước đó. Không bắt buộc click Play Store để nhận thưởng.
- **“20 người đầu tiên” được định nghĩa là 20 tài khoản được backend xác nhận và commit thanh toán đầu tiên**, không phải thứ tự bấm nút hoặc thời điểm cài trên đồng hồ máy. Không giữ suất khi chỉ mở Play Store.

### 4.2. Trạng thái giao diện

| Điều kiện | Khối phía trên “Mở app kiểm thử” | Credit |
|---|---|---|
| Không có đợt còn hiệu lực | Ẩn | Không thanh toán |
| Có đợt còn suất, tester đủ điều kiện, đang cài bản cũ | “Cập nhật phiên bản mới” + “+6 Credit”; có số phiên bản và nội dung cập nhật | Chưa thanh toán |
| Bấm nút | Mở đúng trang Play Store; khi trở lại kiểm tra lại | Chưa thanh toán chỉ vì đã mở link |
| Đã cài bản đáp ứng, đang gửi xác nhận | “Đã cập nhật”, kèm “Đang xác nhận thưởng…” | Chưa hiển thị cộng thành công |
| Backend đã thanh toán | “Đã cập nhật”; phản hồi “Đã nhận +6 Credit” đúng một lần | +6 một lần cho tài khoản/đợt |
| Đã cài bản đáp ứng nhưng lỗi mạng | “Đã cập nhật”; trạng thái “Chưa xác nhận thưởng”, có thử lại | Không cộng lạc quan; không giữ suất offline |
| Đã nhận thưởng rồi mở lại/đổi thiết bị | Đọc receipt server, không tạo thưởng mới | +0 |
| Đợt đủ 20 | **Ẩn toàn bộ khối cập nhật cho mọi tester**, kể cả nhãn của đợt vừa đầy | Lịch sử Credit và receipt vẫn còn |
| Đợt cũ bị thay thế | Không claim đợt cũ; tải đợt mới và kiểm tra lại | Chỉ xét đợt mới hợp lệ |
| Campaign tạm dừng | Đề xuất ẩn hành động cập nhật có thưởng, giữ tiền; tiếp tục cùng đợt khi mở lại | Không claim trong thời gian dừng |
| Campaign production/completed/bị xóa | Đề xuất kết thúc đợt và hoàn phần chưa dùng | Chặn claim mới |

Lượt thứ 20 vẫn nhận phản hồi thành công +6, sau đó khối biến mất theo quy tắc đủ suất. Nếu người thứ 21 đã tải màn hình từ trước, backend trả `FULL`, refresh để ẩn; không ghi “đã nhận +6”.

Receipt thanh toán không bị mất khi gỡ app hoặc cài lại. Nếu đã nhận tiền nhưng máy hiện tại quay về bản cũ, có thể nhắc cập nhật lại mà không có +6; không dùng cờ `paid` để khẳng định phiên bản cài trên mọi máy đều mới.

### 4.3. Mở Play Store và đọc phiên bản

- Chuẩn hóa URL listing theo package snapshot của đợt: `https://play.google.com/store/apps/details?id=<package>`; ưu tiên mở bằng Google Play, fallback browser khi không có Store. Không đi vào nhánh mở trực tiếp app như nút test.
- URL `play_url` chỉ được dùng nếu đúng listing/package. `testing_url` vẫn phục vụ hướng dẫn tham gia closed testing; nó không thay thế listing cập nhật.
- Không giả định Play Store trả callback báo cài thành công. Khi màn chi tiết resume hoặc có sự kiện package replaced, đọc lại `PackageManager.getPackageInfo` ngoài main thread.
- API 28+ dùng `longVersionCode`, API 26–27 có nhánh tương thích. Tách kết quả `not_installed`, `unverifiable`, `installed(code)`; không dùng `0` cho mọi lỗi. [Android: PackageInfo](https://developer.android.com/reference/android/content/pm/PackageInfo).
- Cache phiên bản nếu có phải được xóa theo package replaced/removed; đường xác nhận thưởng dùng kết quả đọc mới. Kiểm tra manifest queries hiện hành trên Android 11+ thay vì cho rằng gọi được launch intent thì mọi metadata đều đọc được. [Android: Package visibility](https://developer.android.com/training/package-visibility/declaring).
- Nếu bản chưa tới track/quốc gia/tài khoản của tester hoặc Play Store mới chỉ có nút “Mở”, giữ trạng thái chưa đạt bản mới; không cộng Credit. Chủ app cần công bố sau khi bản mới đã sẵn sàng cho nhóm tester.

URL listing được Google mô tả tại [Linking to Google Play](https://developer.android.com/distribute/marketing-tools/linking-to-google-play). Luồng Play Core in-app update dùng cho app tích hợp SDK; không dùng checker của 12 Tester làm bằng chứng phiên bản của app khác. [Android: In-app updates](https://developer.android.com/guide/playcore/in-app-updates).

## 5. Ngân sách giữ trước và sổ Credit

### 5.1. Dòng tiền

| Sự kiện | Số dư khả dụng chủ app | Quỹ giữ của đợt | Số dư tester |
|---|---:|---:|---:|
| Công bố thành công | −120 | +120 | 0 |
| Một xác nhận hợp lệ | **0, không trừ lại 6** | −6 | +6 |
| Nhận thưởng đủ 20 | Tổng đã giữ −120 | 0 | Tổng +120 |
| Kết thúc sau 7 người | Hoàn +78 | Về 0 | 7 người đã nhận tổng 42 |
| Thay bằng bản mới sau 7 người | +78 rồi −120, commit cùng nhau | Đợt cũ 0; đợt mới 120 | Không thu hồi 42 đã trả |

Credit đang giữ không còn trong `profiles.credits` khả dụng để tiêu sang nhiệm vụ/tip. Duy trì bất biến cho mỗi đợt:

`reserved_total = remaining_credits + paid_count × 6 + refunded_total = 120`

`0 <= paid_count <= 20`; khi đang nhận thưởng thì phần chưa chi là `6 × (20 − paid_count)`; khi đóng đợt, phần đó được hoàn và remaining bằng 0.

### 5.2. Log và hoàn tiền

- Chủ app có log `app_update_reserve`, amount `-120`, note nêu phiên bản và việc giữ ngân sách.
- Tester có log `app_update_reward`, amount `+6`, note nêu app/phiên bản.
- Hoàn có log `app_update_refund`, amount đúng phần chưa dùng. Số dư `balance` trong log lấy sau cập nhật trong cùng transaction.
- Chi 6 từ quỹ giữ có bản ghi settlement riêng liên kết receipt, owner, tester, release. **Không thêm log -6 làm giảm ví chủ lần nữa**; màn quản lý hiển thị “đã chi” từ các settlement.
- Mỗi reserve/refund/reward có khóa idempotency và định danh đợt/claim để đối soát; không chỉ dựa vào chuỗi note. Có thể dùng bảng liên kết với `credit_logs` mà không đổi model của log cũ.
- Đề xuất hoàn khi thay đợt, app chuyển production/completed hoặc bị xóa. Tạm dừng không hoàn, không tạo quỹ mới khi resume. Không đặt hạn tự động cho đợt trong phạm vi hiện tại vì yêu cầu chưa có số ngày.
- Cho chủ kết thúc sớm đợt đang mở trên màn riêng bằng hành động “Kết thúc đợt và hoàn Credit còn lại”, hiển thị số hoàn. Không cho mở lại cùng versionCode để trả thêm 20 người.
- Hoàn tiền phải idempotent kể cả trigger lifecycle, xóa thủ công và purge cùng chạm vào một đợt. Xóa campaign không cascade mất quỹ/receipt trước khi quyết toán.
- Xóa tài khoản chủ app cần nhánh riêng: đóng đợt trước cascade; nếu ví cũng bị xóa thì ghi disposition của tiền còn giữ theo chính sách xóa tài khoản, không cố hoàn vào profile đã mất. Không bật tính năng trước khi xác minh đường này để tránh lỗi FK hoặc quỹ mồ côi.

## 6. Thiết kế dữ liệu và RPC đề xuất

### 6.1. Các bảng

Tên sau là đề xuất, chưa tạo SQL:

| Bảng | Nội dung và ràng buộc chính |
|---|---|
| `app_update_releases` | id, stable app key, campaign liên kết, snapshot owner/package/app name, version_name, target_version_code, release_notes, status, published_at/closed_at; reward=6, limit=20, reserved=120, paid_count, remaining/refunded; phiên bản đã công bố bất biến |
| `app_update_claims` | release_id, tester_id, observed_version_code, server confirmed_at, ordinal 1..20, reward=6, liên kết reward log; `UNIQUE(release_id,tester_id)` và `UNIQUE(release_id,ordinal)` |
| `app_update_financial_events` | reserve/payout/refund, số tiền, receipt/operation id, log liên quan, snapshot các bên; unique business key để đối soát và chống lặp |
| Stable app control | Bản ghi khóa và high-watermark versionCode theo `(owner_id, package_name)`; không reset lịch sử chỉ vì xóa rồi đăng lại campaign cùng app |

Chỉ một đợt `active` cho stable app key; `UNIQUE(stable_app_key,target_version_code)` ngăn công bố trùng bản. Không lấy riêng `version_name` làm unique. Nếu về sau hỗ trợ chuyển chủ app, cần quy tắc chuyển stable app key và lịch sử riêng.

Receipt, financial event và lịch sử phiên bản không cascade theo campaign. Lưu campaign id snapshot và dùng liên kết nullable `ON DELETE SET NULL` hoặc cơ chế tombstone tương đương. Chính sách xóa tài khoản/ẩn danh cần giữ được các tổng kế toán; không dùng FK profile cascade mù quáng cho quỹ đang giữ.

### 6.2. RPC công bố `publish_app_update`

Input: campaign id, version name, target code, release notes, `client_operation_id` bền vững, expected current release id để phát hiện form cũ.

1. Xác thực `auth.uid()`; tìm operation receipt trước để retry của giao dịch đã commit trả đúng kết quả, kể cả campaign sau đó đã đóng.
2. Khóa campaign → stable app control → đợt hiện tại → profile liên quan theo thứ tự thống nhất. Đọc lại owner/status và high-watermark dưới khóa.
3. Kiểm tra payload/expected release; cùng operation id nhưng payload khác phải lỗi. Mã phiên bản phải lớn hơn high-watermark, không được dùng lại đợt từng hoàn/hủy.
4. Tính số hoàn đợt cũ và số dư khả dụng sau hoàn. Nếu <120 thì rollback toàn bộ.
5. Đóng/hoàn đợt cũ nếu có; trừ 120; tạo đợt mới, financial events/log, cập nhật high-watermark và operation receipt trong một transaction.
6. Trả release snapshot, ngân sách, owner available balance và operation receipt. Trường hợp lỗi sau bước ghi bất kỳ không để lại tiền/đợt một phần.

Nếu công bố hai request khác nhau đồng thời, expected release và khóa stable key phải ngăn tạo hai đợt từ cùng form cũ hoặc tự thay bản vừa tạo mà người dùng chưa thấy.

### 6.3. RPC xác nhận `claim_app_update_reward`

Input tối thiểu: **release id cụ thể**, observed target-package version code. Không nhận owner/user/reward/limit do client chỉ định. Nếu có request id, vẫn dùng unique `(release_id,auth.uid())` làm ràng buộc nghiệp vụ.

1. Xác thực; trả receipt cũ nếu tài khoản/đợt đã được thanh toán. Receipt này được đọc cả khi đợt đã full/replaced hoặc tester đã rời campaign.
2. Với claim mới, khóa campaign → stable app control → release; kiểm tra đợt đúng campaign/package, còn active, campaign open, membership/điều kiện tài khoản và tester khác chủ thật.
3. Kiểm tra observed code đạt target; khóa profile nhận tiền và các hàng kế toán cần thiết. Chỉ đọc từ client là mức kiểm tra thiết bị được nêu tại mục 7.
4. Nếu `paid_count >= 20` hoặc quỹ <6, trả lỗi có mã và trạng thái mới nhất. Không có nhánh tự in thêm Credit.
5. Ghi claim với ordinal tiếp theo; giảm quỹ 6, tăng count, cộng tester 6, ghi reward log/financial event cùng transaction. Count đạt 20 chuyển đợt `exhausted`, đóng nhận claim mới.
6. Trả receipt với `newly_awarded`, reward, số dư mới và release state. Retry trả `newly_awarded=false` cùng receipt; UI không phát hiệu ứng +6 lần nữa.

Không dùng `COUNT(*)` rồi INSERT ở hai request riêng. Khóa, ràng buộc unique và việc cộng tiền trong một transaction bảo vệ cả double tap lẫn tranh suất cuối. Claim đợt cũ sau khi thay bản phải trả `RELEASE_REPLACED`, không tự chuyển sang bản mới.

### 6.4. Đọc, quyền và lifecycle

- Mở rộng `get_campaign_detail` bằng trường nullable chứa current release, trạng thái ngân sách được phép xem, eligibility/reason và receipt của người đang đăng nhập. Màn chủ lấy thêm lịch sử/tiến độ qua endpoint có kiểm tra owner.
- Đường fallback dùng endpoint trạng thái được bảo vệ; không fallback ghi trực tiếp khi RPC mới thiếu. Backend chưa hỗ trợ thì ẩn tính năng mới và báo khả năng hệ thống ở màn quản lý.
- RLS chỉ cho tester đọc nội dung đợt có quyền xem và receipt của mình; chủ đọc tiến độ/lịch sử đúng app. Không công khai danh sách danh tính tester hoặc toàn bộ financial events qua endpoint chung.
- Client không được INSERT/UPDATE/DELETE quỹ, claims, counters, financial events hay credit log trực tiếp. RPC có `SECURITY DEFINER` phải đặt search_path an toàn, fully qualify object, kiểm tra uid/quyền và thu hồi execute mặc định khỏi PUBLIC/anon.
- Helper đóng/hoàn chỉ được gọi nội bộ; không mở quyền tùy ý hoàn quỹ cho authenticated.
- Trigger khi campaign finalized hoặc BEFORE DELETE gọi quyết toán ngay trong transaction, bao phủ đường ghi trực tiếp/admin/purge. Phải giữ chức năng archive của migration 041 và thiết kế thứ tự trigger/lock rõ ràng.
- Các RPC mới và trigger cùng dùng một thứ tự khóa; kiểm tra tương tác với mission/tip/duyệt báo cáo hiện có. Nếu legacy tạo vòng khóa thì sửa điểm giao hoặc retry có giới hạn cho deadlock/serialization bằng cùng operation identity; không giả định sắp UUID trong riêng RPC mới đã giải quyết mọi deadlock của hệ thống.

## 7. Giới hạn xác minh và mức chống gian lận

**Phương án cơ sở xác nhận phiên bản bằng Android PackageManager trên máy tester.** Server bảo vệ quyền, quỹ, số suất và tính duy nhất, nhưng không có bằng chứng độc lập rằng versionCode client gửi là thật. Check chữ ký/nguồn cài trong client cũng không biến giá trị do client gửi thành chứng cứ server tin cậy.

Hệ quả thực tế: client bị sửa hoặc request giả có thể khai versionCode cao để xin thưởng. Không được gọi thiết kế này là “xác minh cập nhật qua Google Play” hoặc “chống gian lận tuyệt đối”. Bấm mở link, gửi timestamp hay thêm nonce một mình không chứng minh cập nhật app đích.

Có hai mức để lựa chọn khi triển khai:

- **Mức cơ sở của kế hoạch này:** kiểm tra bản cài ở client, eligibility ở backend, một lần/tài khoản/đợt, giới hạn quỹ/suất và theo dõi claim bất thường. Hỗ trợ cập nhật tự động nhưng không chứng minh được mọi chuyển đổi từ bản cũ sang bản mới, đặc biệt nếu chưa từng có snapshot bản cũ.
- **Mức xác minh mạnh hơn:** cần app được test tích hợp SDK/endpoint hợp tác để gửi tín hiệu release được kiểm chứng phía server. Phải thiết kế giao thức, danh tính app/tester, chống replay và kiểm tra integrity riêng; không thể ngầm thêm vào tính năng generic hiện tại. Integrity của chính 12 Tester có thể tăng độ tin cậy client nhưng không tự chứng minh phiên bản của một app đích bất kỳ.

Đây là giới hạn kỹ thuật cần biết trước khi triển khai cơ chế trả thưởng tự động, không phải lý do dừng việc lập kế hoạch.

## 8. Các thay đổi Android dự kiến

| File/nhóm | Công việc |
|---|---|
| `ui/screens/OwnerAppDetailScreen.kt` | Mục mới đúng vị trí; chỉ chủ thật được thao tác; mở màn riêng |
| `ui/navigation/NavGraph.kt` | Route và callback công bố; quay về refresh đúng app |
| **Mới:** `AppUpdateReleaseScreen.kt`, `AppUpdateReleaseViewModel.kt` | Form, versionCode, budget preview, operation id bền vững, draft/retry và tiến độ hiện tại |
| `ui/screens/CampaignDetailScreen.kt` và ViewModel | Khối phía trên nút mở app; resume check; trạng thái local/receipt tách nhau; xử lý full/replaced/lỗi mạng |
| **Mới:** `util/InstalledAppVersionReader.kt` | Đọc đúng package/code bằng nhánh API phù hợp, kết quả có kiểu, chạy IO |
| `util/InstalledAppCache.kt` | Invalidate phiên bản theo package replaced/removed; không dùng boolean cài đặt làm kết luận cập nhật |
| `data/Models.kt`, `data/CampaignRepository.kt` | DTO/input/receipt, RPC công bố/claim/đọc; tương thích detail/fallback; invalidate profile/credit log/detail/release |
| `ui/screens/ProfileScreen.kt` nếu cần | Lịch sử diễn đạt giữ trước/hoàn/thưởng rõ ràng; không làm khoản giữ trông như phí mất vĩnh viễn |
| `res/values/strings.xml`, `values-vi/strings.xml`, `values-en/strings.xml` | Toàn bộ nhãn yêu cầu, ngân sách, trạng thái và lỗi có bản dịch |

Cache/phiên đăng nhập:

1. Mọi receipt và state cá nhân phải gắn uid, session generation và release id; logout/đổi tài khoản không dùng lại trạng thái nhận thưởng của tài khoản trước.
2. Giữ sequence/mutation guards của repository/ViewModel; response đợt cũ không ghi đè bản mới sau publish/claim/refund.
3. Claim và publish luôn xác nhận qua server; không dùng cache làm bằng chứng còn suất hay đủ tiền.
4. Khi quay về từ Store, refresh trạng thái release và phiên bản cài; tránh gọi claim lặp vô hạn theo mỗi recomposition/resume. Lưu pending identity để retry đúng đợt; backend unique vẫn là chốt cuối.
5. Receipt thành công phải invalidate cache số dư và credit log. Retry sau timeout đọc lại receipt trước khi báo lỗi/mất thưởng; chỉ hiển thị +6 thành công khi đã có kết quả server.
6. Số suất có thể đổi trên máy khác: refresh khi vào/resume/kéo tải lại, xử lý lỗi FULL tại thao tác. Không hứa ẩn tức thời trên mọi màn đang mở nếu chưa có realtime; polling/realtime là phạm vi mở rộng riêng.

## 9. Kiểm thử và tiêu chí nghiệm thu

| Nhóm | Tình huống | Kết quả cần đạt |
|---|---|---|
| Vị trí UI | Mở quản lý và chi tiết tester | Đúng hai vị trí, đúng nhãn/mô tả; nút chủ mở màn riêng |
| Giữ tiền | Chủ có 119/120/200 Credit | 119 bị chặn; 120 còn 0; 200 còn 80; quỹ đều 120 khi thành công |
| Idempotency publish | Double tap, timeout sau commit, process death rồi retry | Một release, một reserve -120; trả cùng receipt |
| Form cũ | Hai request publish khác nhau từ cùng current release | Không tự thay liên tiếp/giữ tiền nhiều lần ngoài ý định |
| Phiên bản | `1.9`/`1.10`, cùng name khác code, code thấp/trùng/lớn | Quyết định theo numeric code; không tái mở 20 suất cho code đã dùng |
| Chỉ click | Bấm nút rồi quay lại không cập nhật | Không cộng Credit; lời mời cập nhật còn khi đủ điều kiện |
| Thành công | Bản cài đạt target; claim mới hợp lệ | Quỹ 114, tester +6, count 1, chủ không bị trừ thêm 6 |
| Tự động cập nhật | Đủ điều kiện, bản đã đạt trước khi mở màn | Được xác nhận theo quy tắc đề xuất; +6 tối đa một lần |
| Thiếu bằng chứng | Package không đọc được hoặc app chưa cài | Không giả định đã cập nhật; không thanh toán |
| Claim lặp | 10 request cùng tester/đợt; hai thiết bị | Một claim, một log +6, count tăng một |
| Tranh suất | Đã có 19; 10 tester claim đồng thời | Đúng một người nhận 6; count=20, quỹ=0; người còn lại FULL |
| Đủ 20 | Đợt 20/20, client giữ cache cũ | Claim mới bị chặn; refresh ẩn toàn bộ khối tester; chủ vẫn tạo bản tiếp theo |
| Bản tiếp theo | Người đã nhận bản A cập nhật bản B | Được xét thưởng một lần riêng cho B, không nhân bản receipt A |
| Thay đợt | A đã trả 7 người; số dư trước hoàn 42/41 | 42 +78 đủ giữ 120: thành công; 41 +78 thiếu: rollback, A còn hoạt động |
| Tranh thay/claim | Claim A đồng thời publish B | Hoặc A thanh toán trước rồi hoàn ít đi, hoặc B đóng A trước và claim bị chặn; không âm quỹ |
| Đóng/xóa/purge | Thực hiện bằng UI, ghi trực tiếp hợp lệ, admin, retention | Hoàn đúng phần còn lại một lần; receipt/log không mất; archive vẫn đúng |
| Tạm dừng | Pause rồi resume | Không claim khi dừng; giữ nguyên quỹ/đợt/count khi resume |
| Quyền | Chủ tự claim; nonowner publish; giả uid/reward; ghi trực tiếp | Bị chặn; không thay đổi số dư/quỹ |
| Membership | Thành viên mới, rời/rejoin, tài khoản khóa, admin test app khác | Đúng điều kiện đã đặc tả; không dùng cờ quản lý làm quyền tiêu tiền/nhận thưởng sai |
| Mạng/cache | Mất mạng, response đảo thứ tự, logout lúc request đang bay | Không cộng lạc quan, không trộn uid/release, retry không nhân thưởng |
| Thiết bị | API 26–27, 28+, Android 11+; update/reinstall/Store thiếu | Đọc code đúng, xử lý visibility/fallback, không trả lại thưởng khi cài lại |
| Rollback lỗi | Gây lỗi sau reserve, sau ghi claim, sau cộng số dư | Tất cả thay đổi trong transaction cùng rollback |
| Kế toán | Tổng paid/remaining/refunded sau chuỗi publish/claim/close | Luôn khớp 120; log/receipt đủ để đối soát |

Dùng PGlite của repo cho schema, RLS/RPC và tính toán transaction. **Tranh suất và deadlock phải kiểm tra thêm trên PostgreSQL thật bằng nhiều connection**; test tuần tự trên một instance PGlite không đủ chứng minh tính đúng khi đồng thời. Với Android, test logic state/version có ý nghĩa và kiểm tra thiết bị thật cho Play Store/package visibility; source-string audit không thay thế kiểm thử hành vi.

Không có test chức năng mới nào đã được chạy trong lượt nghiên cứu này; chưa build hoặc xác nhận trạng thái production.

## 10. Thứ tự triển khai và bàn giao

1. **Backend và ngân sách:** migration mới sau số cuối thực tế, bảng/constraints/RLS/ACL/RPC/trigger hoàn tiền, read contract, đồng bộ `supabase/full_schema_singapore.sql`. Hoàn tất kiểm thử kế toán, retry, xóa app/tài khoản và đồng thời trước khi mở UI.
2. **Android chủ app:** menu, route, form, budget preview, operation receipt, tiến độ và bản tiếp theo. Không ghi trực tiếp quỹ hoặc số dư.
3. **Android tester:** version reader, khối đúng vị trí, Store intent, resume/claim/receipt, cache/session guards và bản dịch. Không ghép thưởng này vào submit/approve báo cáo test.
4. **Tích hợp:** các ca mục 9, hồi quy report/mission/tip/cache, build debug và kiểm tra thiết bị. Chỉ ghi PASS cho kiểm thử thực chạy.
5. **Phát hành:** backend tương thích trước, xác minh contract/quyền rồi phát hành Android. Tính năng tắt mặc định qua capability/feature flag tới khi xác nhận đủ các điều kiện nghiệm thu.

Theo dõi: lỗi publish/claim theo mã, số đợt active/đã đầy, held/paid/refunded, chênh lệch kế toán, tần suất FULL/retry/deadlock và claim bất thường. Rollback bằng ngừng công bố/claim mới theo flag; vẫn cho đọc receipt và quyết toán/hoàn tiền. Không drop bảng hoặc xóa quỹ đang giữ để rollback giao diện.

Kết quả bàn giao hiện tại là tài liệu này. Phạm vi thực thi tiếp theo là backend + Android + kiểm thử nêu trên, sau khi thống nhất mức xác minh và các quy tắc bổ sung đã đánh dấu là đề xuất.
