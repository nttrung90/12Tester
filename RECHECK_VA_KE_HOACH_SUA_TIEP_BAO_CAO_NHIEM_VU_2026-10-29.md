# Kiểm tra lại và kế hoạch sửa tiếp Báo cáo nhiệm vụ — 29/10/2026

## 1. Kết luận

**Bản sửa chưa đạt: còn 6 nhóm lỗi đã có bằng chứng thực thi.** Hai vấn đề cần ưu tiên là mất nhãn nhiệm vụ ở lịch sử tester và mất ngữ cảnh nhiệm vụ khi mở màn với nhiệm vụ đã hết hạn hoặc lỗi tải ngữ cảnh.

Kiểm tra working tree tại HEAD `dfbc09d940866f2461dbd7b0b9b9f90eb03f6fb9`, bao gồm migration 048 và các thay đổi chưa commit. Đối chiếu với `KE_HOACH_BAO_CAO_NHIEM_VU_VA_AN_NHIEM_VU_HET_HAN_2026-10-29.md`. Chỉ thêm tài liệu và probe trong `.tmp/mission-report-recheck/`; **không sửa mã tính năng, không deploy và không gọi Supabase/Firebase production**.

| Mã | Mức | Lỗi còn lại | Bằng chứng |
|---|---|---|---|
| R01 | P1 | RPC chi tiết app bỏ metadata nhiệm vụ, lịch sử tester không nhận diện được “Báo cáo nhiệm vụ” | RPC thật trên PGlite dưới quyền authenticated |
| R02 | P1 | Nhiệm vụ hết hạn/lỗi tải ngữ cảnh vẫn có thể enqueue thành báo cáo thường | Hai probe chạy `CampaignDetailViewModel.startSubmit` và `ReportSubmitManager` thật |
| R03 | P2 | Gửi bổ sung nhận xét cho báo cáo đã duyệt bị chặn do kiểm tra nhiệm vụ trước phân biệt loại thao tác | Hai tình huống RPC thật trên PGlite |
| R04 | P2 | Không truyền missionId thì vẫn sửa được ảnh/nội dung báo cáo nhiệm vụ pending sau hạn | RPC thật trên PGlite |
| R05 | P2 | Response cũ từ bước tải ngữ cảnh nhiệm vụ ghi đè refresh mới | Probe coroutine điều khiển thứ tự response, chạy ViewModel/repository thật |
| R06 | P2 | Lệnh `npm test` chính bị hỏng; test 048 chưa được đăng ký vào lệnh này | Chạy npm test và kiểm tra bộ đọc mirror/package.json |

Không nên dựa vào kết luận “hoàn thành toàn bộ” trong báo cáo bàn giao để phát hành trước khi xử lý R01/R02 và hoàn thành các cổng kiểm thử còn lại.

## 2. Những phần đã chạy và kết quả

| Kiểm thử | Kết quả trong lượt này | Giới hạn |
|---|---|---|
| `:app:testDebugUnitTest` chạy lại cùng `:app:assembleDebug --rerun-tasks` | **205 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESSFUL** | Bộ tests hiện có, chưa gồm probe audit bổ sung |
| `run-mission-report-and-expiration-test.mjs` | PASS 14 nhóm hiện có | Chưa bao phủ các nhánh lỗi mới nêu dưới đây |
| `run-mission-flow-hardening.mjs` | PASS | Kiểm tra upgrade 043→044, không chứng minh toàn bộ hành vi ở schema cuối 048 |
| `run-thumbnail-migration.mjs` | PASS | Kiểm thử migration 045 theo phạm vi runner |
| `run-home-stats-cycle-test.mjs` | PASS 14 nhóm | Kiểm thử migration 047 theo phạm vi runner |
| `run-backend-cleanup-hardening.mjs` | PASS 5 nhóm | Không thay thế kiểm thử ngữ cảnh nhiệm vụ |
| `npm test` trong `supabase/tests` | **FAIL** trước khi chạy SQL assertions tỷ lệ nhiệm vụ ngày | Chi tiết R06; hai runner sau không được npm chạy vì dùng `&&` |
| Bốn probe DB bổ sung | **4/4 tình huống không đạt mong muốn** | Gồm R01, hai trường hợp R03, R04; exit 1 có chủ đích khi phát hiện lỗi |
| Bốn probe Android bổ sung | **1 PASS, 3 FAIL bằng assertion, không lỗi setup ở lần cuối** | Timer hết hạn PASS; hai nhánh R02 và một nhánh R05 FAIL |

Các cải tiến đã xác nhận: submit lưu metadata đúng trong trường hợp thông thường; endpoint báo cáo của chủ app trả metadata; backend loại nhiệm vụ quá hạn khỏi danh sách; full còn hạn không bị loại chỉ vì đủ người; timer thực của HomeViewModel tự loại lần lượt hai thẻ theo hai deadline mà không cần một lượt refresh mạng mới.

Probe timer dùng đồng hồ monotonic giả và scheduler điều khiển thủ công. Đây là kiểm thử logic ViewModel, **chưa phải kiểm tra Compose trên máy/emulator**, cũng chưa kiểm tra trực quan bộ lọc, lifecycle resume, khởi động lại thiết bị hoặc đồng hồ tường lệch. Không gọi các phần đó là đã xác minh toàn diện.

## 3. Lỗi và phương án sửa

### R01 — P1: API chi tiết app không trả danh tính nhiệm vụ

**Vị trí:** `supabase/migrations/034_admin_tester_flow.sql:116` là định nghĩa `get_campaign_detail` đang có hiệu lực; migration 048 chỉ cập nhật `get_campaign_reports_for_owner`, chưa override API chi tiết. Android dùng fast path tại `CampaignDetailViewModel.kt:306–315`, sau đó `applyDetail` lấy lịch sử từ `detail.reports`.

**Tái hiện trên schema cuối:** tester nhận nhiệm vụ → nộp thành công → đọc cùng report qua hai endpoint:

```text
submit receipt: submission_context = mission
get_campaign_reports_for_owner: submission_context = mission
get_campaign_detail.reports: submission_context = null, mission_id = null
```

API chi tiết đang dựng từng trường JSON và chưa thêm trường mới. Android decode các field bị thiếu thành null; `TesterReport.isMissionReport` trở thành false. Vì thế chủ app có thể thấy nhãn đúng trong khi báo cáo hôm nay/lịch sử của tester không có nhãn. Sửa nhãn Compose hoặc gọi resolveMissionContext không sửa được từng bản ghi lịch sử.

**Sửa tiếp:** tạo migration mới override `get_campaign_detail` dựa trên bản 034 hiện hành, bổ sung đầy đủ context, identity, snapshot và các field báo cáo cần dùng. Kiểm kê các API/dashboard dựng report JSON, dùng contract hoặc serializer chung nếu phù hợp. Giữ các quyền owner/admin/moderator đã sửa trước đây và giữ giới hạn dữ liệu.

**Đạt khi:** cùng report trả cùng metadata qua submit receipt, API chi tiết, API chủ app và select trực tiếp; tester xem lịch sử vẫn có nhãn và tên nhiệm vụ sau khi mission đã ẩn/xóa mềm. Có test cho tester, owner, moderator và quyền người không liên quan.

### R02 — P1: mất ý định gửi nhiệm vụ và âm thầm chuyển sang báo cáo thường

**Vị trí:** `CampaignDetailViewModel.kt:426–440`, `:491–496`, `:817–829`, `:867`.

`resolveMissionContext()` catch mọi exception thành null; khi nhiệm vụ đã expired thì `isMission=false` nếu chưa có báo cáo nhiệm vụ hôm nay. `startSubmit()` chỉ chặn hạn khi `state.isMissionReport && activeMission != null`, rồi chỉ truyền missionId theo cùng cờ đó. `initialMissionId` không được giữ làm ràng buộc bắt buộc của request.

Hai probe đều khởi tạo ViewModel thật bằng `initialMissionId="mission"`, dùng server giả cho phản hồi mạng, rồi gọi `startSubmit()`:

1. Chi tiết nhiệm vụ trả `expired`, tester đã nhận: `missionDeadlinePassed=true` nhưng manager vẫn nhận `SubmitState(stage=SENDING, isMissionReport=false)`.
2. Chi tiết nhiệm vụ trả HTTP 500: `isMissionReport=false`, không có lỗi chặn, manager cũng nhận request báo cáo thường.

Đây là bằng chứng request được enqueue với ngữ cảnh sai; probe Android không gửi tới backend thật. Khi backend cũng không còn mission đủ điều kiện, request thiếu missionId sẽ được xử lý như test thường. Nếu backend vẫn tìm được mission active thì nó có thể tự phân loại đúng, nhưng điều đó không bảo đảm ý định gửi và lỗi thời hạn cho các trường hợp còn lại.

**Sửa tiếp:**

1. Tách **ý định gửi** (mission cụ thể từ route hoặc report hiện có) khỏi trạng thái tải context và khả năng được thực hiện.
2. Có state rõ ràng cho loading/error/expired/paused/completed; lỗi tải context không được biến thành “không có nhiệm vụ”. Giữ missionId qua mọi trạng thái, kể cả sau khi thẻ ẩn.
3. Khi chưa xác minh được hoặc đã hết hạn, chặn submit nhiệm vụ và hiện lỗi/retry thích hợp. Nếu request nhiệm vụ được gửi lên server thì luôn mang đúng missionId đã chọn.
4. Muốn gửi test thường sau khi nhiệm vụ kết thúc phải là thao tác người dùng chọn rõ ràng; không tự fallback. Không lấy việc route chứa missionId làm bằng chứng người dùng đã join.
5. Khi lỗi nhiệm vụ trả về từ worker, làm mới state nhiệm vụ và giữ ngữ cảnh lỗi; đưa lỗi hủy coroutine ra ngoài thay vì catch thành null.
6. Với `applyDetail`, cập nhật/clear state mission theo kết quả đã xác minh; không dùng null vừa để chỉ “không có mission” vừa để giữ dữ liệu cũ.

**Đạt khi:** hai probe hiện tại chuyển PASS; bổ sung trường hợp response mission null, mission A bị thay bởi B, xóa mềm và đổi phiên. Không có request nhiệm vụ bị bỏ missionId. Intent được lưu bền trong pending/receipt và không mất sau retry/restart.

### R03 — P2: kiểm tra nhiệm vụ chặn cả việc bổ sung nhận xét đã được phép

**Vị trí:** migration 048 `:210–243` và `:254–284` xác định mission trước khi phân nhánh report hiện có ở `:369`; Android `resolveMissionContext` chưa tách participant completed khỏi lượt gửi nhiệm vụ mới.

**Trường hợp A đã tái hiện:** submit mission → chủ app gọi `approve_tester_report` thật → participant chuyển `completed` → nộp comment bổ sung với cùng missionId và clientSubmitId mới. RPC trả **`MISSION_NOT_JOINED`**, chưa tới nhánh chỉ cập nhật comment của report approved.

**Trường hợp B đã tái hiện:** report test thường đã approved → tester mới nhận một nhiệm vụ cùng app → gửi bổ sung comment **không truyền missionId**. Auto-resolution chọn nhiệm vụ rồi trả **`REPORT_ALREADY_APPROVED_AS_APP_TEST`**, dù người gửi không yêu cầu chuyển loại báo cáo.

Hai trường hợp đều xung đột với luồng cho phép bổ sung nhận xét của report approved. Đây không phải đề xuất cho phép dùng báo cáo cũ nhận thưởng nhiệm vụ mới.

**Sửa tiếp:** sau receipt replay và kiểm tra quyền, đọc/khóa report hiện có để xác định đúng thao tác: nộp mới, sửa pending, nộp lại rejected hay bổ sung comment approved. Với approved, chỉ cho sửa trường đã được phép, giữ ảnh/rating/context/mission identity và không phát sinh thưởng; không bắt participant còn `joined`, không tự đổi sang nhiệm vụ mới. Yêu cầu chuyển approved thường thành mission phải bị chặn như hiện tại, nhưng sửa comment thường hợp lệ phải thành công. Android cũng phải phân biệt thao tác bổ sung với nộp nhiệm vụ mới.

**Đạt khi:** hai tình huống trên thành công cho comment hợp lệ; metadata, ảnh, rating và số dư không đổi. Explicit request chuyển sang mission khác vẫn bị từ chối; không tạo settlement lần hai.

### R04 — P2: đường implicit bỏ qua deadline khi sửa mission report pending

**Vị trí:** migration 048 `:254–284` và nhánh pending `:535–564`. Nhánh rejected có kiểm tra lại mission cũ tại `:411–423`, nhưng nhánh pending không có kiểm tra tương ứng.

**Tái hiện:** nộp mission report pending khi còn hạn → fixture đặt mission thành expired với `ends_at` trong quá khứ → gọi submit với ảnh/hash mới và clientSubmitId mới, **không truyền p_mission_id** (đường tương thích client cũ).

Kết quả: RPC trả `success=true`, `submission_context=mission`; ảnh/hash và nội dung được cập nhật sau hạn trong khi `mission_submitted_at` vẫn giữ mốc nộp trước đó. Kiểm tra explicit missionId không bảo vệ được đường implicit này.

**Sửa tiếp:** với report đã mang mission identity, mọi lần sửa minh chứng/nộp lại phải kiểm tra đúng nhiệm vụ đã lưu, trạng thái, participant và thời hạn, dù request có missionId hay không. Không tự chọn nhiệm vụ khác. Receipt replay đã commit phải được xử lý trước và vẫn thành công sau hạn; phân biệt replay với một request mới thay đổi minh chứng. Nếu chỉ cho bổ sung comment sau hạn, tách rõ thao tác đó và đóng băng ảnh/rating theo đặc tả thay vì cho cập nhật toàn bộ nhánh pending.

**Đạt khi:** probe sau hạn bị chặn và ảnh/hash cũ giữ nguyên; explicit và implicit có cùng kết quả cho expired/paused/deleted. Replay đúng clientSubmitId cũ vẫn trả receipt. Kiểm thử chạm đúng deadline, nộp lại rejected và không nhân đôi Credit.

### R05 — P2: guard refresh nằm trước một bước suspend mới

**Vị trí:** `CampaignDetailViewModel.kt:308–315` và nhánh fallback `:379–384`.

Code kiểm tra sequence/generation/uid rồi mới `resolveMissionContext()`, là thao tác mạng suspend. Sau khi response mission về, code publish detail mà không kiểm tra lại token. Đây là khoảng trống được thêm khi nối bước tải mission vào luồng refresh cũ.

Probe giữ UID và session cố định, điều khiển response bằng `CompletableDeferred`, không dùng sleep để đoán thời điểm:

1. Refresh A đã lấy detail “Old snapshot”, bị giữ ở request mission.
2. Refresh B hoàn thành và publish “New snapshot”.
3. Thả response mission của A; đợi chính job refresh A hoàn tất.
4. UI quay về **“Old snapshot”**; assertion mong “New snapshot” FAIL.

**Sửa tiếp:** kiểm tra lại sequence, session generation và uid sau mọi bước suspend, ngay trước publish ở cả hai nhánh. CancellationException phải được truyền tiếp. Xét mutation version để thao tác submit/duyệt không bị response bắt đầu trước thao tác ghi đè. Giữ identity mission/report của snapshot nhất quán, không ghép detail cũ với mission mới.

**Đạt khi:** probe A/B chuyển PASS; thêm kiểm thử đổi user trong lúc chờ context, response trước submit về sau submit và hủy ViewModel. Không chỉ thêm delay hoặc bắt exception rộng.

### R06 — P2: cổng kiểm thử chính bị hỏng và không bao gồm tính năng mới

**Vị trí:** `supabase/tests/run-daily-task-ratio.mjs:24–26` và `supabase/tests/package.json:6`.

Chạy `npm test` dừng tại:

```text
AssertionError: mirror equals migration 044 byte for byte
```

Runner đang lấy toàn bộ nội dung sau marker 044 rồi so với migration 044, giả định 044 là phần cuối. Full schema vừa được bổ sung 045–048 nên parser lấy dư. Probe mirror độc lập tách đúng từng marker cho thấy **cả năm đoạn 044–048 đều khớp migration tương ứng**; đây là lỗi bộ đọc kiểm thử, không phải chứng cứ full schema bị trộn/hỏng.

Ngoài ra, `package.json` vẫn chỉ chạy ba runner cũ, chưa gọi `run-mission-report-and-expiration-test.mjs`. Vì vậy sửa parser mà không đăng ký test mới vẫn để cổng kiểm thử chính bỏ qua tính năng 048.

**Sửa tiếp:** tách mirror theo marker kế tiếp hoặc parser chung cho toàn bộ migration, không giả định một số migration cố định là cuối cùng. Giữ kiểm tra byte-for-byte cho từng đoạn. Đăng ký runner 048 và các regression mới vào lệnh kiểm thử chuẩn; kiểm tra cả bootstrap schema cuối và upgrade từ 047 có dữ liệu nghiệp vụ.

**Đạt khi:** `npm test` thực sự chạy được SQL assertions tỷ lệ nhiệm vụ ngày, các runner cũ và runner mới; fail đúng khi cố ý làm sai một mirror. Không xóa assertions hoặc cắt bỏ migrations mới khỏi full schema chỉ để lệnh xanh.

## 4. Khoảng trống của báo cáo và bộ test hiện có

- Báo cáo bàn giao nói đã hoàn thành toàn bộ, nhưng chưa kiểm tra API chi tiết tester có metadata. Test DB 048 chỉ đọc endpoint của chủ app ở ca metadata.
- Test `testHomeViewModel_pruneExpiredMissions` hiện dựng ViewModel rồi tự lọc một list bằng helper repository; không gọi `pruneExpiredMissions`, không kiểm tra UI state và không chạy timer. Probe audit đã kiểm tra timer thật riêng và PASS, nhưng không vì thế suy rộng sang mọi lifecycle.
- Test mới chưa kiểm tra ViewModel ở hai tình huống hết hạn ngay khi mở màn/lỗi tải mission. Hai trường hợp này đều bị probe audit bắt.
- Guard cũ được kiểm tra trước thao tác mạng mới nên unit tests chỉ kiểm tra token/generation riêng không bắt được đảo thứ tự response.
- Việc 14 case DB và 205 unit tests đều PASS không bảo đảm những nhánh nói trên đúng. Số test hiện có thực chạy trong lượt này là 205, không phải 204 như báo cáo bàn giao.
- Chưa có bằng chứng trong lượt audit này về PostgreSQL nhiều kết nối, race/lock tại đúng ranh giới thời gian production, UI trực quan trên thiết bị và chuỗi thông báo hoàn chỉnh. Các điểm đó là phạm vi cần bổ sung, chưa tự gán thành lỗi đã tái hiện.

## 5. Thứ tự sửa tiếp

1. **Ưu tiên R01/R02:** sửa contract đọc báo cáo và bảo toàn ý định nhiệm vụ. Giữ các probe thất bại, đưa vào test suite chính sau khi chuẩn hóa fixture.
2. **R03/R04:** tách các loại thao tác submit ở backend, kiểm tra mission theo report đã lưu; đồng bộ Android để gửi đúng loại thao tác. Giữ chính sách thưởng và các sửa receipt/thumbnail/latency đang có.
3. **R05:** hoàn thiện guard sau suspend ở tất cả đường publish detail/context và thêm test đảo response/đổi phiên.
4. **R06:** sửa parser mirror và cổng npm trong cùng đợt, để toàn bộ thay đổi được kiểm tra bằng lệnh chuẩn.
5. Kiểm tra số migration tiếp theo thực tế. Nếu 048 chưa áp dụng ở môi trường nào và quy trình dự án cho phép, có thể hoàn thiện 048 trước deploy; nếu đã triển khai thì tạo migration mới tiếp theo (hiện dự kiến 049), không chỉ sửa file 048 tại local rồi cho rằng server sẽ tự cập nhật. Đồng bộ full schema theo phương án đã chọn.
6. Chạy lại đúng các probe lỗi → tests DB/Android liên quan → build → xác minh UI trên thiết bị/emulator. Sửa báo cáo bàn giao theo kết quả thực chạy. Không deploy production trong lượt kiểm tra này.

Các bất biến phải giữ: full không chặn tester đã nhận; expires ẩn với mọi vai trò; lịch sử không bị xóa; report nhiệm vụ không nhân đôi check-in/thưởng test; settlement đúng identity và một lần; không gắn hồi tố report nộp trước join; không đổi chính sách duyệt sau hạn, phí hoặc số Credit ngoài phạm vi.

## 6. Evidence và cách chạy lại

Evidence Git-ignore tại `.tmp/mission-report-recheck/`:

- `database-probes.mjs`, `database-results.json`: bốn tình huống DB, assert kết quả mong muốn bằng trường pass và exit 1 nếu còn lỗi.
- `tests/com/ntt/crosstest/data/MissionReportRecheckProbeTest.kt`: bốn probe qua production ViewModel/repository/manager; 1 PASS và 3 FAIL ở lần chạy cuối.
- `recheck.init.gradle`: thêm test source tạm, không chỉnh build script dự án.
- `results/TEST-com.ntt.crosstest.data.MissionReportRecheckProbeTest.xml`, `android-probe-results.json`, `android-probes.log`: kết quả audit Android cuối cùng.
- `baseline-android.log`, `baseline-android-results.json`: build thành công và 205 test gốc PASS trước khi thêm probe.
- `npm-test.log`: lỗi cổng npm chuẩn.
- `snapshot.mjs`, `source-hashes.json`, `schema-mirrors.json`: snapshot source chính và đối chiếu mirror 044–048.

Lệnh từ thư mục gốc, với JDK Android Studio đã cấu hình:

```powershell
node .tmp/mission-report-recheck/database-probes.mjs
node .tmp/mission-report-recheck/snapshot.mjs
.\gradlew.bat -I .tmp/mission-report-recheck/recheck.init.gradle :app:testDebugUnitTest --tests com.ntt.crosstest.data.MissionReportRecheckProbeTest --console=plain
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --rerun-tasks --console=plain
npm --prefix supabase/tests test
```

Probe audit hiện FAIL là kết quả phát hiện lỗi, không phải lỗi compiler. Khi triển khai bản sửa, phải chuyển những case này sang PASS với cùng ý nghĩa nghiệp vụ; không đổi expected thành hành vi sai đang quan sát chỉ để kết quả xanh.
