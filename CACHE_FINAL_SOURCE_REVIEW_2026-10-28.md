# Gate cuối: source review và evidence chính xác

Tiếp tục trong phạm vi cache đã được người dùng yêu cầu sửa tới hết. Giữ mọi thay đổi Firebase/mission/người dùng, không commit/push/deploy/backend thật. Đây là đối chiếu source với các gate cũ, không thêm tính năng.

## 1. Chạy đúng 29 probe đã đóng băng, tránh trùng class

Hiện các class Independent* đã được copy vào `app/src/test` (bộ 25 case cũ). Nếu vẫn dùng independent.init.gradle để thêm `.tmp/independent-tests` có cùng FQN thì sẽ trùng source. Không xóa test hay bỏ assertion để build xanh.

Bộ verifier cuối có namespace class **FrozenCache***, được tạo bằng cách chỉ đổi tên class từ 29 probe hiện có, giữ nguyên body/assertion. Source tại `.tmp/cache-final-2026-10-28/frozen-tests/`; manifest SHA256 `frozen-verifier-hashes.csv`.

```powershell
$env:JAVA_HOME='C:\Users\nguye\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2'
$env:ANDROID_HOME='C:\Users\nguye\AppData\Local\Android\Sdk'
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat -I .tmp/cache-final-2026-10-28/frozen.init.gradle :app:testDebugUnitTest --tests 'com.ntt.crosstest.data.FrozenCache*' --offline --no-daemon
```

Kết quả riêng: `.tmp/cache-final-2026-10-28/frozen-results/`, `frozen-html/`. Đồng bộ các test 29 case mới nhất vào regression duy trì; official copies cũ còn thiếu các case metadata, liveness clear, Home qua midnight.

## 2. Source còn cần đóng, kể cả khi các probe đã xanh

- **Snapshot main-safe:** `clear()` còn synchronized(diskLock) trong khi save/load giữ chính khóa đó cho encode/read/write/prefs commit. Main vẫn có thể chờ IO. Đã có probe liveness 3s trong FrozenSnapshotClear. Tách điểm commit memory/epoch/tombstone ngắn khỏi IO. Clear phải chặn writer cũ commit RAM nhưng không chờ disk/staging trên main.
- **Clear sau new save:** clearDisk hiện chỉ check clearGen==generation. Nếu save mới hợp lệ cùng gen thắng trước queued cleanup, cleanup có thể xóa file/prefs mới. Dùng token/sequence/tombstone đã commit để cleanup chỉ xóa bản trước clear; không xóa writer mới. Giữ cả cold-start epoch rebase và clear tombstone tests.
- **Budget mọi map:** evictOldEntries mới có ở một số writer (profile/owner/tester/stats và seed từ detail). Các writer trực tiếp getCampaign/getMembers/getCheckins/getFeedbacks/getCampaignReports/myRank vẫn cần cùng policy. Không chỉ fix map đang bị probe. Centralize bounded cache hoặc gọi đúng ở mọi writer.
- **Drawable ngoài LRU:** PlayStoreHelper.getInstalledUserApps vẫn load icon cho mọi app, InstalledAppCache giữ nguyên list đó. DTO iconDrawable đã nullable: cache danh sách metadata/package, icon load on demand trên IO qua LRU 120. Giữ chức năng upload icon khi chọn installed app (CreateCampaignViewModel.selectInstalledApp đang dùng iconDrawable); chuyển lookup của icon được chọn sang IO/fallback đúng, không làm mất icon của app khi tạo campaign.
- **Owner state:** Home đã track renderedUserId, nhưng purge cần phủ mọi state cá nhân: myRank/missedStreak/reward và request action state cũng không được giữ từ A sang B. Dùng reset theo owner với whitelist dữ liệu public giữ lại; không suy owner từ nullable profile.
- **Clock nhất quán:** writer dùng injected monotonicNow nhưng nhiều isFresh vẫn default SystemClock. Đảm bảo mọi đường đọc TTL dùng cùng clock đã inject; các dữ liệu daily và UI/snapshot giữ ngày đầu lượt tải, không đóng dấu ngày mới lên response cũ. Home qua midnight đã có FrozenPublication probe; rà tester detail cùng nguyên tắc.

## 3. Báo cáo hiện tại cần viết lại từ evidence thật

`BAO_CAO_CACHE_VERIFY_2026-10-28.md` hiện có các tên test không tồn tại và bảng số lượng từng class không khớp XML (ví dụ bảng PendingReportStore 3 thay vì 6, ReportSubmitPolicy 6 thay vì 10, EgressOptimization 48 thay vì 30). Một số mô tả method/domain cũng không đúng source (`slots` vs `user_slots`).

Sau khi source ổn định và tất cả checks xong, rewrite báo cáo bằng XML/log thật và tên method thật. Không tự diễn giải tên test cho hay, không dùng bảng 159 test cũ để tuyên bố các case mới đã chạy. B07 còn call sites chưa eviction thì không ghi đã phủ toàn bộ. Phân biệt unit suite, Frozen verifier (có overlap hành vi), build/lint, source review và gate thiết bị.

Chạy cuối tuần tự, không hai Gradle cùng outputs Windows: full unit suite với baseline.init để buộc test chạy thật, Frozen 29, assembleDebug, lintDebug. Đọc diagnostics lint (abortOnError=false). C06 vẫn chưa đo vì adb không có thiết bị/emulator; không khẳng định hiệu năng/ANR/OOM thực trên thiết bị.
