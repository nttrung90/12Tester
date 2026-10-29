# Kiểm tra lại và kế hoạch sửa thưởng cập nhật phiên bản — 29/10/2026

## 1. Kết luận và phạm vi

**Còn lỗi, cần sửa trước khi phát hành tính năng.** Đã xác nhận 10 nhóm vấn đề bên dưới bằng đọc source và probe đi qua RPC/logic production. Các luồng cơ bản giữ 120 Credit, chi 6 Credit từ quỹ, giới hạn 20 và chống claim trùng tuần tự đang vượt qua bộ test hiện có; điều này chưa bao phủ quyền helper, chuyển trạng thái, cache, retry và response đảo thứ tự.

Lượt này **chưa sửa code tính năng, chưa tạo migration sửa, chưa deploy, chưa commit/push**. Chỉ tạo tài liệu và nguồn kiểm tra tạm trong `.tmp/app-update-recheck/`. Giữ nguyên các thay đổi chưa commit của Antigravity và công việc khác.

Đối chiếu:

- Kế hoạch: `KE_HOACH_THUONG_CAP_NHAT_PHIEN_BAN_2026-10-29.md`.
- Bàn giao: `BAO_CAO_BAN_GIAO_THUONG_CAP_NHAT_PHIEN_BAN_2026-10-29.md`.
- Implementation: migration `050_app_update_reward.sql`, Android repository/models/screens/ViewModels liên quan.
- Snapshot số migration cuối: **050**. Bản sửa dự kiến **051**, phải kiểm tra lại số cuối trước khi tạo. Không mặc định sửa migration 050 dù báo cáo bàn giao nói chưa deploy; lượt kiểm tra này không truy cập production để xác nhận.

Không coi giới hạn xác minh versionCode do client báo là lỗi mới: giới hạn đó đã được nêu trong kế hoạch. Những lỗi bên dưới vẫn xảy ra với client hợp lệ và dữ liệu giả có kiểm soát.

## 2. Kiểm thử đã thực chạy

| Kiểm tra | Kết quả thực tế | Bằng chứng |
|---|---|---|
| `node supabase/tests/run-app-update-reward-test.mjs` | PASS 20/20 tình huống hiện có | Output trong lượt kiểm tra; được chạy lại trong toàn bộ backend suite |
| `npm --prefix supabase/tests test` | PASS cả 8 runner | `.tmp/app-update-baseline-backend.log` |
| Android `AppUpdateRewardTest` hiện có | PASS; build/test thành công | `.tmp/app-update-baseline-android.log` |
| `gradlew.bat testDebugUnitTest assembleDebug --console=plain` không thêm nguồn probe | **219 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESSFUL**; assembleDebug thành công/UP-TO-DATE | `.tmp/app-update-recheck/baseline-android-all.log`, `baseline-android-summary.json` |
| Probe DB bổ sung | **8 tình huống: 7 không đạt, 1 đạt**; exit 1 vì có điều kiện không đạt | `.tmp/app-update-recheck/database-probes.mjs`, `database.log`, `results.json` |
| Probe Android bổ sung | **7 tests, 7 failures do assertion**, compile thành công | `.tmp/app-update-recheck/android.log`, `android-results.xml`, `android-results.json` |

Probe DB nạp schema hiện có và migration 045→050 vào **PGlite local**, sử dụng dữ liệu giả. Khi gọi RPC, probe dùng `SET LOCAL ROLE authenticated` và uid giả, thay vì chỉ đổi JWT setting trong phiên superuser. Quyền bảng baseline được mô phỏng trước khi migration 050 thu hồi quyền ghi trên các bảng mới. Không kết nối Supabase thật.

Các campaign của probe DB dùng cùng chủ, một slot: các campaign khác để paused và mở lần lượt từng campaign theo từng scenario. Không tắt trigger tài chính/lifecycle của tính năng đang kiểm tra.

Probe Android dùng Robolectric/MockEngine với `CampaignRepository`, `CampaignDetailUiState`, `CampaignDetailViewModel`, `AppUpdateReleaseViewModel` thật. Nguồn nằm ngoài source set thường, được thêm bằng init script tạm. Probe response đảo thứ tự cố ý giữ response đọc đối soát để quan sát trạng thái sai trước khi request kế tiếp sửa lại nó.

Lệnh tái hiện từ thư mục gốc, PowerShell:

```powershell
node .tmp/app-update-recheck/database-probes.mjs

$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --init-script .tmp/app-update-recheck/android-sources.gradle testDebugUnitTest --tests com.ntt.crosstest.data.AppUpdateRecheckProbeTest --console=plain
```

Hiện hai lệnh trên phải báo lỗi nghiệp vụ/assertion theo kết quả đã lưu, **không phải test PASS cho bản sửa**. Không đổi expected để biến hành vi sai thành đúng. Có thể cần quyền sử dụng cache Gradle ngoài workspace theo môi trường chạy.

Chưa kiểm tra UI trên thiết bị thật/Play Store, chưa kiểm chứng tranh chấp bằng PostgreSQL nhiều connection. Không suy ra bảo đảm đồng thời từ việc test PGlite tuần tự chạy xanh.

## 3. Danh sách lỗi và kế hoạch sửa

### R01 — P1: Tài khoản khác có thể đóng và hoàn quỹ của chủ app

**Bằng chứng:** `supabase/migrations/050_app_update_reward.sql:192` định nghĩa `_settle_campaign_app_update` bằng `SECURITY DEFINER`, không kiểm tra người gọi hoặc trạng thái campaign. Dòng **260** cấp `EXECUTE` cho `authenticated`.

**Tái hiện DB01:** người lạ không phải chủ, không phải tester, gọi helper với campaign id của người khác. RPC chạy thành công, release chuyển `active → closed`, hoàn 120 về chủ. Caller không lấy được 120 nhưng có thể tự ý hủy đợt, làm mất cơ hội nhận thưởng và gây gián đoạn tính năng.

**Sửa:** thu hồi execute helper khỏi `PUBLIC`, `anon`, **`authenticated`**. Chỉ để trigger/RPC đã kiểm tra owner gọi nội bộ với quyền definer; nếu cần service role thì cấp đúng phạm vi. Không mở lại helper generic làm endpoint công khai. Kiểm tra ACL của các helper mới khác.

**Nghiệm thu:** tài khoản thường, tester, chủ app gọi helper trực tiếp đều bị từ chối quyền; trigger và RPC kết thúc đợt hợp lệ vẫn hoàn đúng một lần. Assert dưới role thật, không chỉ dưới superuser có uid giả.

### R02 — P1: Tạm dừng rồi hoàn tất không tự hoàn Credit

**Bằng chứng:** trigger tại `050_app_update_reward.sql:274` chỉ quyết toán khi **`OLD.status = 'open'`** và `NEW.status` là completed/production.

**Tái hiện DB02:** công bố → paused → completed. Kết quả: release vẫn `active`, remaining **120**, refunded **0**. Luồng paused → production cũng nằm ngoài điều kiện trên; đây là đường thao tác có thể xảy ra trong quản lý app.

**Sửa:** xử lý mọi chuyển trạng thái hợp lệ sang completed/production, không phụ thuộc trước đó phải open. Helper quyết toán idempotent theo trạng thái/quỹ của release, dùng cùng logic cho status trigger, delete, admin và retention. Tạm dừng vẫn giữ quỹ theo đặc tả.

**Dữ liệu đã phát sinh:** chuẩn bị truy vấn preflight tìm campaign finalized còn quỹ active. Chỉ sửa/hoàn qua helper được bảo vệ và transaction sau khi được phép triển khai; không chỉnh số dư tùy tiện hoặc ghi log thiếu liên kết. Lượt này chưa chạy preflight production.

**Nghiệm thu:** open/paused → completed/production; update lặp; xóa sau finalized; purge. Tổng hoàn bằng đúng phần chưa chi, không có quỹ treo và không hoàn hai lần.

### R03 — P1: Không xóa được tài khoản chủ app sau khi dùng tính năng

**Bằng chứng:** `app_update_releases.owner_id` tại `050_app_update_reward.sql:27` có FK `ON DELETE RESTRICT` tới `profiles`. Release được giữ lại sau quyết toán nên FK tiếp tục chặn xóa chủ.

**Tái hiện DB06:** tạo tài khoản giả → công bố → đóng và hoàn quỹ đầy đủ → xóa `auth.users`. Transaction bị lỗi `app_update_releases_owner_id_fkey` khi cascade đến profiles. Không chỉ xảy ra lúc còn tiền đang giữ.

**Sửa:** tích hợp lifecycle xóa tài khoản: quyết toán quỹ còn sống trước khi profile bị xóa; giữ snapshot định danh/lịch sử tài chính không phụ thuộc FK bắt buộc vào profile sống. Có thể tách `owner_id_snapshot` khỏi FK nullable `owner_profile_id ON DELETE SET NULL`, hoặc cơ chế tương đương. Giữ chính sách xóa ví khả dụng đang có; không tự thêm chính sách tịch thu/hoàn mới hoặc cascade xóa lịch sử tài chính để tránh FK.

**Nghiệm thu:** xóa account có đợt active, closed, exhausted; có/không có tester đã nhận; campaign deletion cascade và retention. Không lỗi FK, không quỹ mồ côi, không trả lại khoản đã chi, audit còn đối soát được.

### R04 — P2: Receipt công bố không gắn payload và operation id không bền vững

**Bằng chứng backend:** `050_app_update_reward.sql:330` tìm operation theo id/owner rồi trả ngay release trước đó, không so campaign/version/notes. Bảng operations không lưu request fingerprint; dòng **527** bỏ qua conflict mà không xác minh đây có phải cùng giao dịch.

**Tái hiện DB03:** công bố campaign A/code10/opX; sau đó công bố campaign B/code11/opX. Backend trả **success/replay của A**, không báo payload conflict dù người dùng đang yêu cầu B. Không phải bằng chứng bị trừ tiền hai lần; lỗi đã xác nhận là receipt sai ngữ cảnh/ý định.

**Bằng chứng Android:** `AppUpdateReleaseViewModel.kt:29,106` tạo UUID ngẫu nhiên và đổi UUID mỗi lần load/refresh. Không có SavedStateHandle/pending operation bền vững. **Probe `publishOperationIdentityMustSurviveRefreshWhileRetryIsPossible` FAIL**: form giữ nguyên nhưng operation id đổi sau refresh.

**Sửa:**

1. Operation key theo owner + operation id; lưu normalized payload/fingerprint, expected state và receipt giao dịch. Cùng id khác payload trả mã xung đột, không báo thành công của request khác.
2. Khóa/đăng ký operation đúng cách và kiểm tra lại dưới khóa trước khi giữ tiền. Không chỉ lookup trước khi chờ campaign/control lock rồi `ON CONFLICT DO NOTHING` ở cuối.
3. Android lưu pending operation cùng payload bất biến qua refresh/process recreation. Retry request chưa rõ kết quả dùng đúng identity; sửa nội dung thành một ý định mới phải có identity mới rõ ràng. Không dùng mã đang pending cho form đã đổi nội dung.
4. Sau commit, trả receipt gốc kể cả đợt/campaign đã chuyển trạng thái; không đòi client đoán lại expected state để đọc kết quả đã thực hiện.

**Nghiệm thu:** timeout sau commit, refresh khi pending, process death, cùng id khác campaign/code/notes, hai lần gọi cùng operation đồng thời, operation id trùng ở hai owner. Một reserve duy nhất cho một ý định; lỗi không làm mất receipt.

### R05 — P2: Form cũ vẫn thay được đợt mới; thứ tự khóa chưa thống nhất

**Bằng chứng:** `050_app_update_reward.sql:396` chỉ kiểm tra expected release khi **cả expected và actual đều khác null**. Không phát hiện form tải khi chưa có đợt, sau đó có đợt khác được tạo/đóng.

**Tái hiện DB04:** đợt code10 được công bố; request từ form cũ kỳ vọng không có đợt, gửi expected=null và code11. Request thành công, đợt code10 bị superseded mà không có lỗi form cũ.

**Sửa CAS:** so sánh đầy đủ expected state với actual, bao gồm null. API phải phân biệt “không có đợt lúc mở form” với “client không gửi token concurrency”. Có thể dùng control revision/watermark thay vì chỉ release id. Android gửi token đã tải, kể cả khi đợt exhausted; không lọc expected id chỉ khi active như `AppUpdateReleaseViewModel.kt:126`. Khi conflict, giữ draft và yêu cầu tải lại budget/đợt.

**Khóa — xác nhận qua source, chưa stress-test:** publish khóa campaign → control → release → profile (`:350,393,413,420`); close khóa release trước rồi mới update control (`:790` và bên dưới). Hai đường có thể tạo vòng chờ control/release khi chạy đồng thời. Claim cũng không khóa campaign/control trước release theo quy ước trong kế hoạch. Không khẳng định đã xảy ra deadlock trên PostgreSQL thật trong lượt này.

**Sửa khóa:** quy định một thứ tự chung cho publish/claim/close/status/delete/account deletion. Kiểm tra cả các luồng mission/tip/credit khác. RPC cần đọc campaign key trước có thể đọc không khóa để tìm id, sau đó khóa theo thứ tự và đọc lại mọi điều kiện. Retry deadlock/serialization có giới hạn phải dùng cùng operation identity, không thay nó bằng transaction mới không liên quan.

**Nghiệm thu:** form expected null/non-null, đợt đã full/closed, hai publish từ cùng snapshot, publish đối đầu close/claim/status/delete bằng nhiều connection thật. Không silent replace; không âm/quỹ sai; retry nhận receipt đúng.

### R06 — P1: Cache làm hiện lại đợt đủ 20/đã đóng và giữ sai số dư

**Bằng chứng:** `CampaignRepository.kt:2449` claim chỉ invalidate profile/log khi `newlyAwarded=true`, không invalidate campaign/detail. `closeAppUpdateRelease` tại **2469** cũng không xóa detail. `CampaignDetailViewModel.kt:1149` gọi `refresh()` mặc định, nên có thể đọc lại snapshot chưa hết TTL.

**Probe Android đã FAIL:**

- `twentiethClaimMustNotReloadCachedNineteenClaimSnapshot`: server nhận người thứ20 và trả exhausted, nhưng `getCampaignDetailPage(false)` vẫn trả **active 19/20** trong cache.
- `closeReleaseMustInvalidateOwnerDetailCache`: sau close/hoàn, detail vẫn active với quỹ cũ.
- `receiptReplayMustRefreshStaleProfileBalance`: receipt replay trả số dư **106**, nhưng profile đọc lại vẫn **100** vì replay không invalidate.

**Sửa:** trả campaign identity trong receipt hoặc truyền context đã xác minh vào repository để invalidate đúng detail/release/profile/log/dashboard sau mọi mutation thành công. Đối soát replay thành công cũng phải làm mới số dư và trạng thái, chỉ hiệu ứng +6 là phụ thuộc `newlyAwarded`. Không để detail cached mang `my_profile` cũ ghi đè profile vừa làm mới; dùng mutation guards và authoritative refresh hoặc merge receipt đúng ngữ cảnh.

**Nghiệm thu:** 19→20 ẩn ngay trên máy nhận thứ20; close về quản lý không hiện quỹ cũ; retry sau mất response hiển thị số dư đúng; không phát +6 hai lần. Cache còn TTL và request cũ đang bay đều không phục hồi đợt đã kết thúc.

### R07 — P2: UI mời thưởng cả người không đủ điều kiện; lỗi nhận thưởng không được hiển thị

**Bằng chứng:** `CampaignDetailUiState.showAppUpdateBlock` tại `CampaignDetailViewModel.kt:122` không xét `rel.isEligible`, membership, trạng thái campaign hay tài khoản khóa. `checkInstalledAppVersion` cũng không xét eligibility trước auto-claim.

**Đối chứng DB07 PASS:** backend read contract đã trả `is_eligible=false`, reason `joined_after_release`. Nhưng **hai probe Android** cho tester tham gia sau đợt và campaign paused đều FAIL: UI vẫn cho hiện offer +6. Đây là lỗi client bỏ qua contract backend, không phải server trả nhầm eligibility.

`updateClaimError` được gán ở ViewModel nhưng **không có nơi đọc/hiển thị ở CampaignDetailScreen**. Khi gặp FULL/REPLACED/mất mạng, catch chỉ lưu lỗi; không refresh đợt để ẩn nút và không có nút thử lại. Người đã cập nhật có thể chỉ thấy “Đã cập nhật” trong khi chưa nhận được Credit, không biết lý do.

**Sửa:**

- Điều kiện hiển thị/auto-claim dựa trên eligibility và trạng thái server, cờ chủ thật, bản cài xác minh được và trạng thái tài khoản. Không thay bằng cờ quản lý legacy.
- Tách local updated, đang xác nhận, đã được trả, lỗi/chưa trả. Hiển thị lỗi có mã, nút thử lại khi phù hợp và không hứa +6 cho người đã hết điều kiện.
- FULL/REPLACED/CLOSED/PAUSED phải tải trạng thái authoritative và ẩn/chuyển khối phù hợp, không chờ TTL. Retry lỗi mạng đọc bản cài lại, giữ đúng release identity, không cộng lạc quan.
- Chuẩn hóa chuỗi Việt/Anh, không đưa raw exception/kỹ thuật không cần thiết vào UI.

**Nghiệm thu:** late join, paused, nonmember, locked tester, người thứ21 đang giữ cache cũ, bản đã cài nhưng RPC lỗi, retry sau timeout. Hiển thị đúng lý do và không phát request nhận thưởng lặp vô hạn.

### R08 — P1: Response nhận thưởng của bản cũ có thể đánh dấu nhầm bản mới

**Bằng chứng:** `CampaignDetailViewModel.kt:1133` sau khi await RPC lấy **appUpdate hiện tại** rồi copy `hasClaimed`, count, status và receipt của response, không so release id/uid/session generation. `checkInstalledAppVersion` tại **1082** cũng giữ `currentRel` qua suspend mà không kiểm tra lại trước auto-claim hoặc publish state.

**Probe `oldClaimResponseMustNotMarkNewReleaseAsClaimed` FAIL:** claim A đang chờ → detail refresh sang B → response A tới → B bị đánh dấu hasClaimed/exhausted bằng receipt A. Probe giữ request đọc đối soát kế tiếp để quan sát trạng thái sai; không thay logic production bằng bản sao trong test.

**Sửa:** chụp uid, session generation, release id/code và sequence cho mọi job đọc version/claim/owner load/publish/close; kiểm tra lại trước khi ghi state sau mỗi suspend. Chỉ merge receipt khi identity khớp. Response giao dịch đã commit của A vẫn là kết quả hợp lệ cho A nhưng không được gắn lên B hay tài khoản mới. Hủy/đánh dấu obsolete version-check khi đợt/package/session đổi; luôn truyền tiếp CancellationException.

**Khoảng trống lifecycle xác nhận qua source:** màn chi tiết tại `CampaignDetailScreen.kt:195` chỉ gọi đọc version từ ResumeEffect với key campaignId/package. Nếu appUpdate mới đến sau khi package đã được render từ cache, thay đổi release không làm effect chạy lại. `Unverifiable` cũng không xóa versionCode cũ. Cần kích hoạt đọc khi release/target mới đến trong trạng thái resumed, không chỉ khi package đổi; Unknown không được giữ kết luận “Đã cập nhật” cũ như kết quả vừa xác minh. Chưa chạy thử UI lifecycle trên thiết bị để kết luận tần suất xảy ra.

**Nghiệm thu:** claim A trả sau refresh B, đổi tài khoản lúc request đang bay, owner load cũ trả sau publish/close mới, version read cũ trả sau thay release, metadata đến muộn, lỗi visibility sau lần đọc thành công. Không gắn nhầm receipt và không dùng snapshot local cũ để auto-claim.

### R09 — P2: Màn chủ phục hồi đợt exhausted cũ, quên watermark bản đã đóng

**Bằng chứng:** `050_app_update_reward.sql:1124` chỉ chọn release status active/exhausted rồi order theo published_at. Bản exhausted cũ không được đổi superseded khi publish mới vì publish chỉ xử lý active. Android `AppUpdateReleaseViewModel.kt:104` fallback lịch sử cũng chọn active/exhausted và validation chỉ lấy code từ currentRelease.

**Tái hiện DB08:** A/code10 đầy20 → publish B/code20 → đóng B. Màn chi tiết chủ trả **A/code10/exhausted** như đợt hiện tại. Nếu không có A exhausted thì currentRelease có thể null sau reload, khiến validation quên high-watermark20 mặc dù backend vẫn chặn code thấp/trùng.

**Sửa:** dùng control/revision và trạng thái đợt hiện tại làm nguồn xác định current, tách **latest release/history/highest_version_code** thành contract riêng. Đóng B không phục hồi A. UI dùng watermark server để validate dù mọi đợt đã closed; không suy watermark từ release đầu tiên còn active/exhausted. Sắp lịch sử có thứ tự xác định khi timestamp trùng.

**Nghiệm thu:** A full → B closed → mở lại màn; đóng đợt duy nhất; xóa rồi đăng lại cùng owner/package; validation luôn biết code cao nhất, tiến độ/quỹ không nhảy về đợt cũ.

### R10 — P2: Chủ app bị khóa vẫn có thể giữ tiền/công bố bản mới

**Bằng chứng:** publish tại `050_app_update_reward.sql:350` kiểm tra chủ và campaign nhưng không kiểm tra `profiles.is_locked`. Claim có kiểm tra tester locked nên hai phía đang bất nhất.

**Tái hiện DB05:** đặt owner `is_locked=true`, gọi publish bằng role authenticated. Vẫn success và tạo release/giữ tiền.

**Sửa:** kiểm tra trạng thái tài khoản trong transaction trước reserve, dưới profile lock thích hợp; receipt replay đã commit là đường đọc kết quả cũ, không bị biến thành một reserve mới. UI không cho thao tác mới khi tài khoản bị khóa. Việc hoàn quỹ/đóng/xóa tài khoản phải giữ được theo chính sách hiện hành, không chặn quyết toán một cách máy móc vì locked.

**Nghiệm thu:** locked owner publish mới bị chặn và số dư không đổi; replay hợp lệ đọc được receipt cũ; khóa tài khoản đồng thời publish có kết quả tuần tự rõ ràng.

## 4. Thứ tự triển khai sửa

1. **Backend bảo vệ và lifecycle — R01, R02, R03, R10:** migration mới, ACL helper, hoàn quỹ mọi đường finalized/delete/account delete, quyền owner/tài khoản. Giữ audit và archive hiện có. Có preflight cho quỹ treo/FK; không chạy production trong lượt sửa mặc định.
2. **Contract và transaction — R04, R05, R09:** receipt/payload/idempotency/CAS, watermark/current/history, thống nhất lock order và tương thích endpoint; đồng bộ full schema. Bổ sung test role thật và nhiều connection.
3. **Repository và client state — R06, R08:** invalidation theo campaign/release/uid, replay reconciliation, sequence/session guard, pending operation bền vững, version read theo lifecycle/release.
4. **Giao diện và lỗi — R07 cùng phần UI của các mục khác:** giữ vị trí/nhãn yêu cầu, phân biệt updated/paid/error, retry và xử lý full/replaced, budget preview/current/history đúng; song ngữ.
5. **Hồi quy và bàn giao:** đưa probe cần thiết thành test chính thức đi qua logic production; chạy lại 8 runner, unit tests, assembleDebug, kiểm tra đa connection và thiết bị nếu có. Ghi rõ phần chưa có môi trường; không đánh dấu PASS thay cho kiểm thử chưa chạy.

Không mở thêm phạm vi về SDK xác minh mạnh, thưởng test thường, tip, phí nhiệm vụ, cơ chế ảnh hoặc production deployment. Bảo toàn các sửa mission 048/049, receipt, latency, thumbnail và idempotency báo cáo hiện có.

## 5. Tiêu chí nghiệm thu tổng hợp

| Nhóm | Phải đạt sau sửa |
|---|---|
| Quyền | Client không gọi được helper quyết toán nội bộ; owner thật thao tác đúng app; locked owner không công bố mới |
| Giữ/chi/hoàn | 120 = remaining + 6 × paid_count + refunded, hoặc biến thể mở rộng đã đặc tả cho account deletion; không trừ chủ lần hai; không trả lại khoản đã chi |
| Đóng/xóa | Mọi chuyển sang completed/production hoàn đúng; xóa tài khoản không lỗi FK; delete/purge không mất lịch sử hoặc quỹ |
| Publish | Cùng ý định trả cùng receipt; cùng id khác payload lỗi; form cũ kể cả expected=null bị chặn; không tự tạo hai đợt |
| Claim | Một lần/tài khoản/đợt, tối đa20; đúng một người thắng khi 10 người tranh suất cuối; các retry khác nhận kết quả nghiệp vụ ổn định |
| Cache | Sau người thứ20 và sau close, khối cũ không phục hồi; replay sửa số dư; request cũ không ghi lại snapshot đã hết hiệu lực |
| UI | Người không eligible không thấy lời hứa +6; đã cập nhật nhưng chưa trả có trạng thái/lỗi/retry rõ; FULL/REPLACED ẩn/chuyển đúng |
| Identity | Receipt/version result của A không gắn lên B; không dùng state của uid cũ; cancellation được truyền tiếp |
| Watermark | Đợt đã closed vẫn giữ mã cao nhất; màn chủ không hồi sinh exhausted cũ |
| Triển khai | Backend mới và Android tương thích; migration mới không đè lịch sử; full schema và test runners đồng bộ |

Ràng buộc 20 người và khóa hàng cần test tranh chấp thật. Trường hợp hai request claim cùng tester cùng lúc: unique constraint hiện ngăn trả tiền hai lần, nhưng lookup receipt chỉ nằm trước release lock nên request thua có thể gặp unique violation thay vì receipt cũ. Khi sửa phải re-check dưới khóa hoặc xử lý conflict rồi đọc receipt, không bỏ unique constraint. Nhận xét này dựa vào source, chưa được xác minh bằng hai connection thật trong lượt này.

## 6. Nguồn bàn giao cho lượt sửa tiếp

- Tài liệu này là danh sách lỗi/đặc tả sửa chính.
- `.tmp/app-update-recheck/database-probes.mjs` và `results.json`: DB01–DB08, trong đó DB07 là đối chứng backend eligibility đúng.
- `.tmp/app-update-recheck/android/com/ntt/crosstest/data/AppUpdateRecheckProbeTest.kt`: bảy probe Android đang FAIL.
- `.tmp/app-update-recheck/android-sources.gradle`: đăng ký nguồn probe tạm, không thay source set thường của dự án.
- `.tmp/app-update-recheck/android-results.xml`, `android-results.json`, `android.log`: kết quả bảy assertion đã lưu riêng; không bị lần chạy unit test thường ghi đè.
- `.tmp/app-update-recheck/baseline-android-summary.json`, `baseline-android-all.log`, `.tmp/app-update-baseline-backend.log`: đối chiếu baseline đang xanh.

Không coi báo cáo bàn giao trước đó là bằng chứng mọi contract đã đúng. Ví dụ tài liệu đó mô tả chữ ký claim có bốn tham số, trong khi implementation thực tế là `claim_app_update_reward(uuid,bigint)`; khi sửa phải lấy source/catalog/DTO thật làm căn cứ và cập nhật lại tài liệu cuối cùng.
