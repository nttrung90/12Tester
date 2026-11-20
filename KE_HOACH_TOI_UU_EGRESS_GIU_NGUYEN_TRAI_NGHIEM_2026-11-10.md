# Kế hoạch tối ưu egress, giữ nguyên trải nghiệm CrossTest / 12Tester

- Ngày: 10/11/2026.
- Trạng thái: kế hoạch triển khai, chưa sửa ứng dụng hoặc database.
- Căn cứ: kế hoạch ngày 09/11/2026, mã nguồn hiện có trong workspace và tài liệu chính thức.
- Phạm vi: Android, Supabase RPC, cache, đo lưu lượng và kiểm thử tương thích.
- Giới hạn đánh giá: chưa đo trực tiếp trên Supabase đang chạy; phải xác nhận định nghĩa RPC thực tế trước triển khai.

## 1. Quyết định chính

Giữ lịch đồng bộ và dữ liệu người dùng đang nhận. Giảm byte truyền bằng cách loại tải trùng, kiểm tra thay đổi ngay trong RPC và chỉ trả lại các khối đã đổi. Lần tải đầu vẫn nhận đủ dữ liệu cần thiết.

Không dùng việc kéo dài thời gian dữ liệu cũ hoặc cắt lịch sử để đạt KPI. Không cam kết tỷ lệ tiết kiệm trước khi đo baseline.

Các ràng buộc nghiệm thu:

| Hành vi | Yêu cầu sau tối ưu |
|---|---|
| Mở app | Snapshot hiện ngay như hiện tại; đồng bộ theo các điều kiện hiện tại |
| Resume / đổi tab | Giữ các sự kiện kiểm tra cập nhật cần thiết; hợp nhất lời gọi trùng |
| Độ mới dữ liệu | Không nới TTL hiện tại của feed, dashboard, detail trong đợt này |
| Kéo để làm mới | Vẫn kiểm tra server ngay; không chỉ đọc lại cache |
| Submit / duyệt / từ chối | Kết quả đúng ngay theo phản hồi server, đồng bộ đầy đủ phần bị ảnh hưởng |
| Thay đổi từ người khác | Được phát hiện ở lần đồng bộ vốn có; không thêm cửa sổ chờ 5–10 phút |
| Báo cáo và feedback | Giữ phạm vi, số lượng và khả năng truy cập hiện tại; không thêm thao tác Xem thêm |
| Bảng xếp hạng, credit, gói nạp, slot | Giữ thời điểm sẵn sàng dữ liệu hiện tại; không tạo thời gian chờ mới do tách tải |
| Thứ tự app / gợi ý app tiếp theo | Giữ thuật toán và dữ liệu đầu vào tương đương |
| App cũ | Tiếp tục dùng RPC cũ với hợp đồng cũ |
| Offline / tài khoản | Giữ khả năng hiển thị cache; không trộn dữ liệu giữa tài khoản hoặc phiên |

Không thể chứng minh trước rằng một thay đổi phần mềm hoàn toàn không có hồi quy. Các ràng buộc trên là điều kiện phát hành: hạng mục không đạt phải sửa hoặc tắt, không đánh đổi trải nghiệm để đạt egress.

## 2. Những đề xuất của kế hoạch cũ cần thay thế

| Đề xuất cũ | Quyết định mới | Lý do |
|---|---|---|
| Feed/dashboard TTL 10 phút, detail 5 phút | Giữ TTL hiện tại: feed 3 phút, dashboard 60 giây, detail 3 phút theo mã đã đọc | Tránh tăng độ trễ phát hiện thay đổi từ owner/admin/thiết bị khác |
| Gate Home/detail bằng thời gian riêng trong ViewModel | Không thêm cổng thời gian thứ hai | Tránh bỏ qua invalidation và làm lệch thời điểm đồng bộ |
| Snapshot mới thì bỏ đồng bộ cold start | Hiện snapshot ngay, vẫn áp dụng điều kiện đồng bộ hiện tại | Snapshot không chứng minh server chưa thay đổi |
| Giảm report 200 xuống 10/20 | Giữ giới hạn/hành vi hiện tại của RPC cũ và phiên bản đầu của RPC mới | App cũ và màn hiện tại chưa có luồng thay thế tương đương |
| Feedback giới hạn 50 | Chưa áp dụng | Tránh mất khả năng xem dữ liệu đang có |
| Ngừng gửi logs/packages rồi tải khi mở dialog | Chỉ bỏ truyền lại nếu client đã giữ bản hiện hành | Tránh lịch sử trống, gói mặc định và thời gian chờ mới |
| Tắt leaderboard mỗi dashboard | Giữ tải đầu tương đương; chỉ bỏ truyền lại khi đã xác minh không đổi | Không tạo độ trễ mới khi mở tab lần đầu |
| Thêm default parameters vào tên RPC cũ | Thêm RPC tên mới | Tránh xung đột overload và thay đổi hành vi của app cũ |
| Giảm forceRefresh diện rộng | Giữ yêu cầu kiểm tra server; thay phản hồi đầy đủ bằng phản hồi có điều kiện | forceRefresh có thể đang bảo đảm đúng nghiệp vụ |

## 3. Mốc mã nguồn phải đối chiếu

Các đường dẫn dưới đây tính từ thư mục F:/AI/12Tester:

- `app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt`: CacheEntry, deduped, invalidation, getHomeFeed, getMyDashboard, getCampaignDetailPage, getNextCampaignCandidates.
- `app/src/main/java/com/ntt/crosstest/data/Models.kt`: Campaign, MyDashboardResult, MyDashboardParams, CampaignDetailResult.
- `app/src/main/java/com/ntt/crosstest/data/SessionCacheManager.kt`: snapshot, ngày VN, generation và scope tài khoản.
- `app/src/main/java/com/ntt/crosstest/ui/viewmodels/HomeViewModel.kt`: đồng bộ Home, trạng thái nhiệm vụ, notification, leaderboard, ghi snapshot.
- `app/src/main/java/com/ntt/crosstest/ui/viewmodels/ProfileViewModel.kt`: đọc dashboard, credit logs, slot.
- `app/src/main/java/com/ntt/crosstest/ui/viewmodels/CampaignDetailViewModel.kt`: chọn báo cáo hôm nay, reward, prefetch gợi ý app.
- `app/src/main/java/com/ntt/crosstest/ui/viewmodels/OwnerAppDetailViewModel.kt`: report, thống kê, duyệt và từ chối.
- Các screen Home/Profile/CampaignDetail và luồng ReportUploadWorker/ReportSubmitManager có liên quan.
- Định nghĩa get_campaign_detail mới nhất tìm thấy trong migrations: `081_admin_flow_audit_fixes.sql`.
- Định nghĩa get_home_feed/get_my_dashboard mới nhất tìm thấy: `082_carryover_flow_audit_fixes.sql`.
- Phải đọc cả các thay đổi nghiệp vụ sau đó, gồm migration 084; không chép lại baseline 062 để thay hàm hiện tại.

Khi triển khai, dùng `pg_get_functiondef` lấy định nghĩa thực tế, so với repository và rà soát các dependency. Số migration mới phải lấy theo trạng thái repository lúc đó, không mặc định 085 còn trống.

## 4. Giai đoạn A — Đo đúng, chốt hợp đồng hiện tại

Thời gian dự kiến: 0,5–1 ngày thiết lập; baseline sử dụng thực tế tối thiểu 3–7 ngày nếu chưa có dữ liệu đáng tin cậy.

### A1. Tách nguồn egress và cửa sổ đo

- Tách Database/Data API, Storage, Realtime, Auth và các nguồn khác trong báo cáo Supabase; không cộng lẫn với Firebase.
- Dùng cùng thời gian bắt đầu/kết thúc và múi giờ cho request, egress, người dùng hoạt động và phiên app.
- Xác nhận stats_reset; lấy chênh lệch bộ đếm giữa hai mốc thay vì suy ra toàn bộ lịch sử từ một ảnh chụp.
- Đọc đầy đủ query và đối chiếu API logs để xác định RPC 9.364 calls. `SELECT $1 AS json_data` không tự chứng minh số tham số hoặc tên RPC.
- Làm rõ mâu thuẫn 1,57 GB / 7,3 ngày ~215 MB/ngày với baseline ~58 MB/ngày trong kế hoạch cũ.

### A2. Đo tại ứng dụng và qua API

Ghi metadata gọn: endpoint, nguồn kích hoạt (cold_start/resume/pull_refresh/mutation/prefetch/tab), trạng thái cache, request có được gộp không, byte nhận thực tế ở tầng mạng, byte JSON giải nén và thời gian.

- Phân biệt phản hồi mạng với dữ liệu lấy từ cache. Không dùng độ dài JSON giải nén để đại diện trực tiếp cho egress tính phí.
- Đối soát tổng theo service với dashboard Supabase; kiểm tra cơ chế nén thực tế, không giả định bật nén là một khoản tiết kiệm mới.
- Debug/staging có đo chi tiết. Nếu cần production, chỉ dùng mẫu metadata tổng hợp, có cờ tắt và mức lấy mẫu; không ghi JWT, nội dung report, mật khẩu test, URL ký hoặc thông tin nhận diện trực tiếp.
- Kiểm tra ảnh thực sự nằm ở dịch vụ nào trước khi đề xuất tối ưu Storage. URL ngắn không phản ánh kích thước ảnh được tải.

SQL ước tính JSON cần cast, ví dụ `octet_length(public.get_home_feed()::text)`. Khi giả lập JWT/role phải chạy trong transaction rõ ràng. Đo RPC có side effect như daily login/auto accept trên staging với fixture; nếu dùng transaction kiểm tra thì kết thúc ROLLBACK. Không coi đây là phép đo byte HTTP.

### A3. Lập danh sách bên sử dụng dữ liệu

Với từng field, ghi: màn sử dụng, thời điểm cần, cache dùng chung, side effect, mutation liên quan. Phải bao gồm các field không hiển thị nhưng dùng chọn myTodayReport, quyền, reward, app update, trạng thái đã test và pause reason.

Lưu bộ tình huống đối chiếu: tester mới, tester lâu năm, owner nhiều report, admin/moderator, tài khoản bị khóa, campaign có attachment/nhiệm vụ/update/đẩy app.

Đầu ra A: baseline theo endpoint/trigger, bảng phụ thuộc dữ liệu, bộ trạng thái UI chuẩn, số byte và thời gian P50/P95. Chưa đủ dữ liệu thì chưa công bố tỷ lệ tiết kiệm toàn dự án.

## 5. Giai đoạn B — Loại request trùng, giữ nguyên thời điểm cập nhật

Thời gian dự kiến: 1–2 ngày, triển khai client trước được.

### B1. Kiểm tra và củng cố single-flight đang có

Repository đã có `deduped`; phải đo hiệu quả trước, không thêm một lớp tương tự theo cảm tính.

- Các yêu cầu đang chạy đồng thời, cùng endpoint/tham số/user/generation/ngày/phiên bản mutation có thể dùng chung một kết quả.
- Khóa phải bao gồm tất cả tham số làm đổi nội dung hoặc phạm vi dữ liệu. Không gộp các campaign, quyền, filter hoặc phiên khác nhau.
- Request bắt đầu trước mutation không được dùng để đáp ứng yêu cầu kiểm tra sau mutation; chụp mutation version và bỏ phản hồi lỗi thời.
- Refresh bắt buộc được ghép với request đang chạy chỉ khi request đó đáp ứng cùng hàng rào cập nhật; không nuốt một pull-to-refresh cần dữ liệu mới.
- Không cache lỗi và không đặt last-success khi chỉ đọc cache cũ hoặc request thất bại.

### B2. Hợp nhất refresh ở Profile và các callback trùng

Giữ cả hai ý nghĩa: quay lại ứng dụng và mở lại tab Hồ sơ. Có thể đưa chúng về một hàm requestRefresh(reason), hợp nhất các lần xảy ra đồng thời; không chỉ xóa một callback rồi làm mất tình huống còn lại.

Không dùng debounce dài hoặc TTL ViewModel mới. Khi chỉ có một sự kiện hợp lệ, bắt đầu kiểm tra như hiện tại. Phân biệt tiết kiệm request thật với việc chỉ giảm số coroutine/UI update.

### B3. Giữ mutation và refresh an toàn

Lập ma trận dependency tối thiểu:

| Thao tác | Phần phải được kiểm tra/cập nhật |
|---|---|
| Submit report / upload hoàn tất | report detail, trạng thái đã test, feed stats, daily task, profile/credit/reward liên quan, mission, gợi ý app |
| Duyệt / từ chối | report, pending count, stats, credit/log/reward của các bên, notification và mission liên quan |
| Đọc / xóa notification | danh sách, unread count, snapshot; giữ đồng bộ các trường khác vốn được refresh |
| Nạp / chi credit | số dư, logs, quest/rank nếu chịu ảnh hưởng, quote/slot/campaign liên quan |
| Sửa profile / campaign / tham gia / rời | dữ liệu và tên/avatar liên quan, membership/stats, quyền và snapshot |
| Admin khóa/tạm dừng/cấu hình | quyền, account status, campaign, nhiệm vụ, giá/quote và dữ liệu phụ thuộc |

Ma trận này là điểm bắt đầu, phải hoàn thiện từ SQL và code. Chưa chứng minh dependency thì giữ luồng refresh hiện tại; giai đoạn C vẫn giảm byte dù tiếp tục kiểm tra nhiều khối.

Đầu ra B: request đồng thời trùng được hợp nhất, không giảm độ mới dữ liệu, không thay TTL. Không đặt mục tiêu giảm 50–70% calls nếu chưa đo được phần trùng thực sự.

## 6. Giai đoạn C — Đồng bộ có điều kiện trong cùng một request

Thời gian dự kiến: 3–5 ngày, gồm backend, client và kiểm thử hợp đồng. Đây là phần tối ưu chính.

### C1. Thêm RPC phiên bản mới, giữ nguyên RPC cũ

Tên dự kiến, cần kiểm tra chưa tồn tại:

- `get_home_feed_sync_v2`.
- `get_my_dashboard_sync_v2`.
- `get_campaign_detail_sync_v2`.

RPC mới nhận token của các khối mà client còn giữ bản dữ liệu hợp lệ. Server trả khối thay đổi ngay trong cùng response, không làm chuỗi “gọi kiểm tra rồi gọi lấy dữ liệu” cho tình huống thông thường.

Giữ hai RPC Home chạy song song như hiện tại trước; không gộp Home thành dự án mới trong đợt đầu.

Ví dụ hợp đồng dashboard rút gọn:

```json
{
  "schema_version": 2,
  "business_date": "2026-11-10",
  "blocks": {
    "profile": {"token": "opaque-p2", "changed": true, "value": {"id": "...", "credits": 123}},
    "credit_logs": {"token": "opaque-l1", "changed": false},
    "notifications": {"token": "opaque-n2", "changed": true, "value": []}
  }
}
```

`changed=false` nghĩa là dùng lại đúng giá trị đã xác nhận. `changed=true, value=[]` là danh sách thực sự rỗng, phải thay bản cũ. Không dùng mảng rỗng để biểu diễn “không tải”. Ví dụ chỉ minh họa giao thức, không phải danh sách field đầy đủ.

### C2. Chia khối theo dữ liệu đang có

| Endpoint | Khối dự kiến |
|---|---|
| Home feed | campaign lists, owner names, stats |
| Dashboard | profile, my/joined campaigns, leaderboard, today/pending counts, warnings, credit logs, packages, notifications, owner stats, slots, quest summary, daily task |
| Campaign detail | campaign/quyền, testers, feedbacks và aggregate, reports và aggregate, profiles liên quan, trạng thái cá nhân/check-in, warnings, app update |

Có thể gộp các field có dependency chặt vào một khối để giảm rủi ro. Không chia nhỏ chỉ để đạt số byte đẹp nếu làm phức tạp tính nhất quán.

Lần đầu không có dữ liệu: trả tất cả các khối của hợp đồng tương đương RPC cũ. Giữ giới hạn report 200 và toàn bộ field hiện có. Chưa áp dụng giới hạn 10/20/50.

Leaderboard, logs, packages và slot được tải vào thời điểm vốn có; lần sau chỉ truyền lại khối đã đổi. Nhờ đó không thêm thời gian chờ khi mở tab/dialog. Không dùng gói nạp mặc định như thể đó là dữ liệu đã xác nhận từ server.

### C3. Sinh token chính xác trước, tối ưu CPU sau

Phiên bản đầu ưu tiên tạo giá trị khối đầy đủ theo logic hiện hành, chuẩn hóa thứ tự và tạo fingerprint phía server từ chính giá trị đó. Đối chiếu token rồi chỉ đưa các khối đổi vào response.

- Chọn hash đủ mạnh, kiểm tra extension/schema thực tế; token là giá trị mờ đối với client và không phải bằng chứng quyền truy cập.
- Token và value phải xuất phát từ cùng bản dữ liệu đã tính. Không đọc token một lần và đọc payload lần khác trong lúc dữ liệu có thể đổi.
- Không đưa thời gian sinh response hoặc giá trị ngẫu nhiên vào fingerprint của khối không đổi.
- Bao phủ mọi field có ảnh hưởng đến UI/nghiệp vụ, thứ tự, ngày VN, quyền, membership và schema version.
- Chuẩn hóa mảng bằng thứ tự nghiệp vụ hiện tại kèm ID phá hòa khi cần; không thay thứ tự daily shuffle/push đang hiển thị.
- Kiểm tra quyền từ JWT hiện tại trước khi trả dữ liệu hoặc xác nhận không đổi. Client không tự chọn user/scope bằng tham số.
- Giữ quan hệ nhất quán giữa các khối phụ thuộc trong một response; nếu chưa bảo đảm được thì gom thành một khối và kiểm thử concurrent mutation.

Không dùng `max(updated_at)` hoặc tổng count làm token duy nhất: chúng có thể bỏ sót xóa, đổi profile, sửa nội dung và đổi quyền. Không triển khai trigger revision toàn database ngay đợt đầu vì dễ thiếu đường cập nhật và tăng write contention.

Cách tính fingerprint từ payload đầy đủ chủ yếu tiết kiệm mạng, có thể chưa giảm công việc database. Đo CPU, mean/P95 SQL và độ trễ API; chỉ tối ưu bằng revision/dependency table ở đợt sau nếu cần và đã có kiểm thử đủ các đường ghi.

### C4. Cache phải lưu token cùng giá trị

- Khóa theo user đã xác thực, session generation, endpoint/tham số, business date, schema và phạm vi campaign/filter/quyền.
- Token chỉ được gửi khi client còn giữ đầy đủ đúng giá trị tương ứng. Evict payload thì evict token cùng lúc.
- Sau response hợp lệ, merge các khối vào bản hoàn chỉnh rồi phát UI một lần. Không phát trạng thái trung gian kiểu report mới nhưng count cũ.
- Cập nhật các cache phụ mà repository đang seed như hiện tại; không bỏ seed rồi phát sinh loạt request bù ngoài dự kiến.
- Ghi snapshot/token thành một bộ nhất quán. Timestamp phản ánh lần xác minh server thành công; đọc cache không được tự gia hạn độ mới.
- Sau mutation: bỏ token các khối phụ thuộc, hoặc gửi yêu cầu bỏ qua so sánh cho các khối đó; giữ kiểm tra generation/mutation trước khi commit.
- Nếu nhận “không đổi” nhưng thiếu giá trị, sai schema hoặc payload hỏng: yêu cầu full một lần, không hiển thị rỗng; có giới hạn retry để tránh vòng lặp.
- Đổi tài khoản, logout, mất quyền hoặc tài khoản bị khóa phải xử lý giống hiện tại; không giữ quyền hay dữ liệu nhạy cảm chỉ vì token khớp.

### C5. Giữ side effect và force refresh

- `get_my_dashboard` đang ghi nhận daily login; `get_campaign_detail` có auto accept expired reports. Luồng mới phải bảo toàn thời điểm/ngữ nghĩa này trước khi chốt dữ liệu và fingerprint.
- Không trả “không đổi” chỉ từ token client rồi bỏ qua daily login, deadline, ngày VN hoặc auto accept.
- Audit cả các helper/quest/lifecycle có side effect. Không đánh dấu RPC là STABLE nếu thực tế có ghi dữ liệu.
- Pull-to-refresh luôn đi tới server. Nếu server xác nhận mọi khối không đổi thì kết thúc refresh thành công và giữ nguyên UI; không bắt buộc truyền lại toàn bộ JSON để thể hiện rằng đã kiểm tra.
- Có tùy chọn full response cho phục hồi cache/debug và các tình huống cần đối soát.
- Không thêm polling hoặc kết nối Realtime diện rộng trong đợt này. Điều đó có chi phí riêng và không cần để giữ hành vi hiện tại.

### C6. Tương thích và fallback

- RPC cũ và JSON cũ giữ nguyên. RPC v2 không dùng overload cùng tên với tham số default chồng lấn.
- Tất cả giá trị request bắt buộc, đặc biệt boolean false, phải xuất hiện thực sự trên wire; dùng DTO không có default bị bỏ qua hoặc JSON tường minh. Không đổi serializer toàn ứng dụng chỉ để chữa một request.
- Kiểm tra grants, search_path, auth và schema cache sau migration. API mới không được mở rộng quyền so với API cũ.
- Khi RPC v2 chưa tồn tại: nhận diện đúng lỗi thiếu RPC, ghi nhớ capability theo backend/phiên bản với thời hạn kiểm tra lại hoặc reset khi bật cờ; dùng legacy. Không probe lỗi trên mọi resume và cũng không tắt vĩnh viễn sau khi backend đã nâng cấp.
- Không coi lỗi mạng, thiếu quyền hay lỗi nghiệp vụ là “RPC chưa triển khai”. Không tự động gọi legacy sau mọi lỗi vì có thể nhân đôi công việc/side effect và che lỗi thật.
- Cờ vận hành độc lập cho feed/dashboard/detail v2. Tắt cờ đưa app về đường cũ và xóa metadata v2 cần thiết, không phải phát APK để rollback.

## 7. Giai đoạn D — RPC riêng cho gợi ý app tiếp theo

Thời gian dự kiến: 1–2 ngày.

Hiện `getNextCampaignCandidates()` gọi lại toàn bộ Home bằng forceRefresh=true. Thay bằng RPC mới `get_next_campaign_candidates_v2(p_current_campaign_id)`.

- Trả toàn bộ tập ứng viên hợp lệ mà thuật toán hiện tại có thể chọn; không tùy tiện limit top 1 hoặc top 10.
- Giữ điều kiện status=open, loại app hiện tại/app của chính user, loại app đã test hôm nay theo checkins và report đúng submission_context/report_date/status.
- Bản đầu trả Campaign đầy đủ cho từng ứng viên cùng installed_count; bỏ paused/completed, stats không cần và các phần khác của Home. Tránh phải gọi thêm lấy chi tiết chỉ để hiển thị gợi ý.
- Giữ thứ tự và thuật toán `chooseNextCampaign`, ưu tiên tester mới và kiểm tra app đã cài trên thiết bị.
- Tiếp tục loại PendingReportStore phía client: server chưa thấy report đang upload.
- Mỗi lần prefetch hợp lệ vẫn kiểm tra server mới như hiện tại. Request lỗi không được chuyển thành ứng viên đoán từ cache cũ.
- Giữ cách xử lý trường hợp không có gợi ý/lỗi như hiện tại; thiếu RPC dùng đường cũ với cơ chế capability ở C6.

Không thay bằng `get_next_untested_campaign` trả một app, vì client hiện cần tập ứng viên để chọn theo trạng thái cài đặt và điều kiện tester.

## 8. Giai đoạn E — Tối ưu tùy chọn sau khi C/D có số liệu

Chỉ làm hạng mục chứng minh được lợi ích bổ sung, không đưa vào đường bắt buộc của bản đầu.

### E1. Bỏ campaign lặp bằng dictionary có đủ dữ liệu

Trong một response mới, có thể dùng map campaign theo ID và các danh sách ID. Dictionary phải chứa hợp của mọi campaign cần cho response, kể cả my/joined campaign không xuất hiện trong public feed.

Không map ID từ dashboard sang feed độc lập rồi chấp nhận mất app khi feed lỗi, cache bị evict hoặc hai response khác phiên. Nếu chưa đảm bảo tính tự chứa thì giữ object đầy đủ. Đo khoản tiết kiệm vì tăng cấu trúc response cũng có overhead.

### E2. Projection rút gọn chỉ khi an toàn với bên sử dụng

Tách CampaignListItem và CampaignDetail nếu thật sự cần. Cache/list item không được coi là detail đầy đủ. Cần giữ admin_pause_reason_code và mọi field dùng cho trạng thái/điều hướng tương ứng.

Chỉ bỏ field khi chứng minh không có màn cần nó trước thời điểm dữ liệu đầy đủ vốn được tải. Nếu phát sinh thêm một lượt đợi khi mở detail/form thì chưa đáp ứng yêu cầu trải nghiệm và chưa phát hành hạng mục đó.

### E3. Report và ảnh

Giữ phạm vi report/feedback hiện tại trong đợt này. Chỉ cân nhắc truyền thay đổi theo report ID hoặc tự tải nối tiếp nếu bảo toàn tìm kiếm/lọc/pending cũ, scroll và báo cáo theo nhiệm vụ mà không thêm thao tác. Đây là dự án riêng, không phải điều kiện để hoàn thành tối ưu C/D.

Chỉ tối ưu thumbnail/cache ảnh nếu A xác nhận nguồn egress đó thuộc Supabase và đang tải thừa. Không giảm độ phân giải ảnh phục vụ duyệt/so sánh bằng chứng.

## 9. Kiểm thử bắt buộc trước phát hành

### 9.1. Tương đương dữ liệu và UI

- Với fixture cố định, so sánh giá trị RPC cũ với trạng thái tái dựng từ v2: tất cả field, danh sách, thứ tự, counts, quyền và report đang thao tác.
- Gọi full lần đầu; gọi không thay đổi; thay đúng một domain; thay nhiều domain; xóa toàn bộ danh sách; trường null; profile/avatar đổi; feedback/report/attachment bị sửa/xóa.
- Không dùng hai lần gọi liên tiếp trên dữ liệu đang biến động làm bằng chứng sai khác; cần fixture, snapshot và kiểm soát side effect.
- Không chạy shadow gọi cả RPC cũ lẫn mới thường xuyên trên production: nó làm tăng egress và có thể lặp side effect. Nếu cần chẩn đoán, chỉ mẫu nhỏ, có kiểm soát.

### 9.2. Cache và đồng thời

- Cold start có/không snapshot; snapshot schema cũ; thiếu token; token có nhưng payload mất; cache bị hỏng/evict; offline rồi có mạng.
- Resume trước/sau các mốc TTL cũ, đổi tab Profile, pull-to-refresh, hết hạn trong khi màn đang mở; không tạo polling mới.
- Hai request trùng; request cũ về sau mutation; upload nền hoàn tất khi Home đang tải; logout/login/đổi user khi request đang chạy.
- Qua 00:00 VN, đổi chu kỳ thống kê, push hết hạn, mission/update hết hạn, auto accept đến hạn mà không có thao tác người dùng.
- Thiết bị khác duyệt/từ chối report, đổi credit/profile, admin khóa tài khoản hoặc tạm dừng app; kiểm tra tại lần sync hiện tại vốn có.

### 9.3. Nghiệp vụ và màn hình

- Submit app_test và mission, retry, pending upload, bị từ chối, thưởng và hoàn trả; không gợi ý app vừa nộp.
- Tester có nhiều báo cáo trong ngày, owner/admin cũng tham gia test, pending cũ nằm ngoài các bản mới nhất.
- Owner duyệt/từ chối, count tổng chính xác; ảnh nhiều attachment, report context, report_date và chính sách thưởng được giữ.
- Notification đủ 50 như hiện tại, đọc/xóa/đọc tất cả/deep link; count không bị tính từ danh sách bị cắt. Nếu thêm unread count server, xác định rõ ngữ nghĩa toàn bộ hay cửa sổ 50 và kiểm thử riêng.
- Mở Bảng xếp hạng, Hồ sơ, credit history, topup, slot và form: dữ liệu sẵn sàng như baseline, không nháy trống hoặc xuất hiện gói mặc định sai.
- Gợi ý app: cùng tập ứng viên và cùng đầu vào local phải chọn cùng kết quả với cách cũ.
- Giữ các lỗi nghiệp vụ, thông báo lỗi, retry và hành vi offline hiện tại.

### 9.4. Tương thích triển khai

- APK cũ + backend mới: API cũ và dữ liệu không đổi.
- APK mới + backend cũ: fallback hoạt động, không lặp probe mỗi lần refresh.
- APK mới + backend mới: first load, unchanged, partial update, recovery full đều đúng.
- API qua PostgREST thật, không chỉ gọi SQL: kiểm tra boolean false, JWT, grants, schema cache và kiểu JSON.
- Chạy `assembleDebug`, `testDebugUnitTest` và các regression Supabase liên quan trên staging có dữ liệu giả lập. Nếu thiếu dependency offline thì ghi nhận rõ và chạy đầy đủ khi môi trường đáp ứng; không coi chưa chạy là đã đạt.

## 10. KPI, điều kiện tiếp tục và dừng

Các tỷ lệ dưới đây là mục tiêu thử nghiệm sau khi có baseline, không phải số đo hay lời hứa.

| Chỉ số | Mục tiêu / ràng buộc |
|---|---|
| API egress / người dùng hoạt động / ngày | Mục tiêu giảm 30–50% sau C+D; điều chỉnh theo tỷ lệ dữ liệu thực sự không đổi |
| API egress / cùng kịch bản sử dụng | Giảm có thể tái lập trên tester, owner và admin; ghi riêng từng nhóm |
| Response không có khối thay đổi | Nhỏ hơn ít nhất 90% so với full response tương ứng khi full đủ lớn; đo cả overhead thực tế |
| Byte của một chuỗi request | Tính cả probe, retry, recovery, telemetry và request tải bù; không chỉ tính response chính |
| Cold start / mở tab / mở detail | P50/P95 không có hồi quy đáng kể so baseline cùng thiết bị/mạng |
| Độ trễ cập nhật dữ liệu từ xa | Không thêm TTL/gate; cùng thời điểm sync phải nhận cùng trạng thái nghiệp vụ |
| Đúng dữ liệu và quyền | Không chấp nhận sai report, count, số dư, quyền hoặc scope tài khoản |
| CPU / SQL / API errors | Không vượt ngân sách đã chốt; giảm byte không được đổi lấy database nghẽn |

Sau A, chốt ngưỡng định lượng: đề xuất điều tra/dừng rollout khi P95 tăng quá max(50 ms, 5% baseline) có thể tái lập, hoặc lỗi API tăng trên 0,2 điểm phần trăm với mẫu đủ. Mọi lỗi tính đúng/quyền/số dư dừng ngay, không chờ đủ mẫu. Với lượng user nhỏ, dùng thêm kịch bản lặp có kiểm soát, không kết luận từ một ngày ít hoạt động.

Cách dự báo: tổng byte = tổng theo endpoint của (số request × byte trung bình thực nhận), cộng traffic phụ. Với conditional sync, đo riêng tỷ lệ full/partial/unchanged và kích thước từng loại. So sánh tổng Supabase theo cùng service/cửa sổ để kiểm tra mức tiết kiệm có phản ánh trên usage thực tế.

Nếu phần lớn dữ liệu đổi ở mọi lần gọi, conditional sync sẽ tiết kiệm ít hơn. Khi đó tập trung D và dữ liệu lặp ở E; không tự tăng TTL hoặc cắt report để đạt KPI. Không cộng các phần trăm tiết kiệm của từng giai đoạn như thể độc lập.

## 11. Rollout và rollback

1. Lưu baseline và bộ fixture. Chụp định nghĩa RPC, grants và cấu hình đang chạy để phục hồi.
2. Phát hành B độc lập, đo request gộp và kiểm tra không mất sự kiện cập nhật.
3. Deploy RPC v2 lên staging rồi production, chưa bật app mới. Giữ nguyên RPC cũ.
4. APK mới mặc định legacy; bật riêng feed v2 cho tài khoản nội bộ, rồi nhóm tester/owner đại diện. Sau đó dashboard, detail và candidates lần lượt, không bật đồng loạt.
5. Với khoảng 30 user, ưu tiên cohort cố định có đủ vai trò; có thể mở 10% → 50% → 100% nếu phân phối hỗ trợ. Mỗi nấc cần đủ kịch bản và dữ liệu, không chỉ đủ thời gian.
6. Đo 3–7 ngày khi ổn định, đối chiếu cả byte/người dùng/phiên và các điều kiện trải nghiệm.

Rollback: tắt cờ endpoint v2 để quay về legacy; bỏ token v2 và tải đầy đủ khi cần, giữ snapshot hợp lệ để tránh màn trống. Không drop RPC cũ hoặc thay đổi bảng phá tương thích. Cờ phải thực sự được app đọc ở mốc vận hành phù hợp, có giá trị mặc định legacy; kiểm thử rollback như một luồng phát hành.

Không tự động fallback hàng loạt khi có timeout vì có thể nhân đôi tải đúng lúc server chậm. Dùng cờ vận hành, retry có giới hạn và xử lý lỗi hiện tại. Schema v2 additive có thể giữ lại khi rollback; chỉ gỡ sau khi không còn consumer.

## 12. Thứ tự công việc và thời lượng

| Thứ tự | Công việc | Thời gian công việc dự kiến | Điều kiện hoàn thành |
|---|---|---:|---|
| A | Instrumentation, baseline, dependency và hợp đồng | 0,5–1 ngày + cửa sổ quan sát | Giải thích được egress và nguồn request chính |
| B | Hợp nhất request/callback trùng | 1–2 ngày | Giữ đúng resume/tab/mutation, đo được phần tải trùng |
| C1 | Sync v2 cho Home feed | 1 ngày | Full/unchanged/partial bằng dữ liệu cũ, giữ stats/order |
| C2 | Sync v2 cho dashboard | 1–2 ngày | Giữ daily login, dữ liệu preloaded và profile/log/notification |
| C3 | Sync v2 cho detail | 1–2 ngày | Giữ report/quyền/attachment/reward/auto accept |
| D | Candidates riêng | 1–2 ngày | Giữ cùng gợi ý, giảm byte của prefetch |
| QA | Hồi quy tổng thể, thiết bị thật, rollout | 1–2 ngày + quan sát | Đạt điều kiện dữ liệu, thời gian và lỗi |
| E | Tối ưu thêm dựa trên số đo | Ước lượng sau C+D | Chỉ làm nếu lợi ích bổ sung đủ rõ |

Tổng dự kiến A–D và QA: khoảng 7–12 ngày công, chưa gồm thời gian quan sát và chờ phát hành. Điều chỉnh sau A; không ép vào 1–2 ngày bằng cách bỏ kiểm thử đồng thời/tương thích.

Đợt phát hành đầu nên hoàn thành A+B, sau đó triển khai lần lượt C và D. Kế hoạch này hoàn tất khi byte thực tế giảm có kiểm chứng và toàn bộ điều kiện trải nghiệm đạt; không cần triển khai E nếu mức tiết kiệm đã phù hợp.

## 13. Tài liệu đối chiếu

- [Supabase — Manage Egress usage](https://supabase.com/docs/guides/platform/manage-your-usage/egress): phân loại và đối soát lưu lượng.
- [PostgreSQL — CREATE FUNCTION](https://www.postgresql.org/docs/current/sql-createfunction.html): chữ ký hàm, overload và thay thế định nghĩa.
- [PostgREST — Functions as RPC](https://docs.postgrest.org/en/stable/references/api/functions.html): tham số, default và schema cache.
- [PostgreSQL — pg_stat_statements](https://www.postgresql.org/docs/current/pgstatstatements.html): query chuẩn hóa và bộ đếm.
- [PostgreSQL — String functions](https://www.postgresql.org/docs/current/functions-string.html): octet_length và phép đo chuỗi.
- [Kotlin — encodeDefaults](https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/kotlinx.serialization.json/-json-builder/encode-defaults.html): giá trị default có thể không xuất hiện trong JSON.
