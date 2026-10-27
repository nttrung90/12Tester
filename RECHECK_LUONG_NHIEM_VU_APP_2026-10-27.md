# Kiểm tra lại bản sửa luồng Nhiệm vụ App — 27/10/2026

## Kết luận

**Bản sửa Gemini chưa đạt. Còn 6 lỗi đã có bằng chứng tái hiện, bao gồm hai lỗi crash mới khi mở màn hình.**

Kiểm tra working tree tại HEAD `020e550e0f3fdd63f11d1c72419409e499365879`, gồm migration `044_mission_flow_hardening.sql` và các thay đổi Android chưa commit. Không sửa production, không deploy, không gọi Supabase/Firebase thật.

Kết quả thực thi:

- `cd supabase/tests; npm test`: PASS cả ba runner, gồm 2016 SQL assertions của tỷ lệ nhiệm vụ ngày, 5 nhóm hardening 043 và các case 044 hiện có.
- Chạy lại bộ Android hiện có với `:app:testDebugUnitTest --rerun`: PASS, task test thực sự chạy.
- Chạy toàn bộ Android tests cùng 3 probe mới: **66 tests, 63 PASS, 3 FAIL, 0 errors, 0 skipped**. Cả ba probe fail bằng assertion kiểm tra hành vi đúng, không phải lỗi biên dịch hoặc setup.
- Probe database bổ sung: xác nhận ba vấn đề R03/R04/R05 dưới đây. Có thêm một quan sát phụ về cờ suppress notification, chưa xếp thành lỗi độc lập trong luồng app.

Các cải tiến đã xác nhận: nhánh `hasJoined` đã đứng trước `isFull`; paused mission được tính vào unique index; create đồng bộ hết hạn trước kiểm tra trùng; báo cáo trước join bị từ chối thưởng mission; lỗi thanh toán có outbox và retry được khi mission còn đủ điều kiện; query đã order trước limit; có state lỗi và guard cho một số lượt load. Các điểm đó chưa đủ để kết luận toàn luồng hoạt động đúng.

## R01 — P1: mở màn tạo nhiệm vụ crash ngay trong constructor

**Vị trí:** `app/src/main/java/com/ntt/crosstest/ui/viewmodels/MissionCreateViewModel.kt:46–53`.

`init { loadInitialData() }` chạy trước initializer của `loadSequence`. `loadInitialData()` gọi `loadSequence.incrementAndGet()` đồng bộ ngay đầu hàm, lúc field còn null.

Probe `createMissionScreenMustConstructWithoutNullPointer` khởi tạo ViewModel thật với repository/Supabase client thật và MockEngine, nhận:

```text
NullPointerException: Cannot invoke AtomicLong.incrementAndGet()
because this.loadSequence is null
```

**Tác động:** vào “Mở nhiệm vụ” có thể làm app văng; màn error/retry chưa có cơ hội hoạt động. Lỗi này không phụ thuộc phản hồi mạng.

**Sửa:** đưa mọi field dùng lúc khởi tạo, đặc biệt `loadSequence`, lên trước khối `init`. Giữ regression test khởi tạo ViewModel thật, không chỉ tạo `MissionCreateUiState`.

## R02 — P1: màn quản lý app của chủ cũng crash khi chạy Main.immediate

**Vị trí:** `OwnerAppDetailViewModel.kt:64–91`, field `missionLoadSequence` tại dòng 539 và lần dùng tại 542.

Constructor launch coroutine trên `viewModelScope`. Khi xây ViewModel trên main thread, Main.immediate cho phép coroutine chạy ngay: `refresh()` → `loadMission()` → `missionLoadSequence.incrementAndGet()`. Field được khai báo sau khối init nên chưa được khởi tạo.

Probe `ownerScreenMustConstructOnImmediateMainWithoutNullPointer` dùng dispatcher chạy ngay, xác nhận uncaught exception:

```text
NullPointerException: Cannot invoke AtomicLong.incrementAndGet()
because this.missionLoadSequence is null
```

**Tác động:** ảnh hưởng màn OwnerAppDetail của chủ app, không chỉ màn quản lý nhiệm vụ con. Nếu dispatcher trì hoãn coroutine đến sau khi constructor kết thúc thì lỗi có thể bị che; vì thế test dùng dispatcher trì hoãn không đủ.

**Sửa:** đưa field sequence lên trước khối init. Test cả khởi tạo trên main chạy ngay và tải dữ liệu bất đồng bộ. Không chữa bằng cách thêm delay hoặc chỉ đổi dispatcher để né thứ tự khởi tạo.

## R03 — P1: migration 044 thu hồi quyền RPC mà Android vẫn đang gọi

**Vị trí:** `supabase/migrations/044_mission_flow_hardening.sql:814–815`; `CampaignRepository.kt:454–460` và `:1363–1367`.

044 thu hồi EXECUTE `auto_accept_expired_reports()` khỏi `authenticated`, chỉ giữ `service_role`. Android vẫn gọi RPC này bằng phiên người dùng; catch biến mọi lỗi thành 0 nên ứng dụng không báo việc cơ chế fallback đã mất.

Database probe đã kiểm tra quyền và thực sự gọi bằng `SET LOCAL ROLE authenticated`:

```text
has_function_privilege(...): false
permission denied for function auto_accept_expired_reports
```

**Tác động:** khi cron không khả dụng, cơ chế tự duyệt từ client trước đây không còn chạy; báo cáo quá 8 giờ có thể nằm pending, mission reward/retry không được xử lý qua đường fallback này. Nếu cron đã được cấu hình và chạy đúng thì tác động được giảm, nhưng audit chưa xác minh môi trường triển khai. README hiện vẫn mô tả không có pg_cron thì client có thể tự kích hoạt.

**Sửa:** thống nhất contract Android/backend. Có thể giữ RPC kích hoạt được kiểm soát cho authenticated, trong khi helper retry vẫn chỉ dành backend; hoặc thay bằng cơ chế server khác có lịch triển khai rõ. Không chỉ nuốt permission error và coi 0 là thành công. Thêm test ACL/call bằng vai trò authenticated, không chỉ gọi mọi RPC với quyền postgres trong PGlite.

## R04 — P1: nâng cấp 044 tự dừng nhiệm vụ cũ bằng lý do hết Credit sai

**Vị trí:** `044_mission_flow_hardening.sql:42–50`.

Migration chọn mission mới nhất mỗi campaign rồi đổi toàn bộ mission sống cũ hơn sang `stopped_no_credits`. Không xét participant đang làm, không thông báo người bị ảnh hưởng và không lưu quyết định xử lý trùng vào audit nghiệp vụ.

Probe tạo dữ liệu như hệ thống trước 044: hai mission còn hạn cùng campaign, mission cũ có tester, chủ có **10.000 Credit**. Áp dụng 044 cho kết quả:

```text
mission cũ: stopped_no_credits
credits chủ: 10000
participant: joined
notifications được thêm: 0
```

**Tác động:** tester nhiệm vụ cũ mất khả năng nhận thưởng; UI giải thích sai rằng chủ hết Credit. Chủ có thể không nhìn thấy mission cũ do RPC quản lý lấy mới nhất. Đây là thay đổi dữ liệu nghiệp vụ trong migration, không chỉ preflight đồng bộ hết hạn.

**Sửa:** preflight phát hiện xung đột và đưa ra phương án hòa giải có audit, đúng reason và thông báo trước khi xây unique index. Không tự chọn bản mới nhất rồi gán trạng thái thiếu tiền. Giữ lịch sử participants/credit logs; không tự hoàn hoặc thu phí khác chính sách. Test nâng cấp từ schema 043 thật với dữ liệu trùng và tester đang làm.

## R05 — P1: job không còn đủ điều kiện chặn các job retry mới mãi

**Vị trí:** `044_mission_flow_hardening.sql:339–361` và `:539–548`.

Retry lấy tối đa 50 job cũ nhất `pending/failed`. Khi mission đã paused/expired/deleted, hoặc participant đã completed bằng báo cáo khác, helper trả `NO_ELIGIBLE_MISSION` trước khi cập nhật job. Job vẫn failed/pending, attempt_count không tăng, created_at không đổi và không có next_attempt_at/backoff. Nó tiếp tục chiếm vị trí trong mọi batch.

Probe tối giản dùng limit 1:

1. Job A cũ hơn thuộc mission đã paused.
2. Job B mới hơn thuộc mission open, đủ Credit và đủ điều kiện thanh toán.
3. Gọi `retry_failed_mission_settlements(1)` hai lần: cả hai trả 0; A vẫn attempt_count=1, B vẫn failed.
4. Gọi với limit 2: B được trả thành công ngay.

Với batch mặc định 50, cùng lỗi xuất hiện khi có 50 job cũ không còn đủ điều kiện. Đây không chỉ là vấn đề mất thưởng sau ends_at M04; ngay cả mission mới còn hạn cũng bị các job cũ chặn.

**Sửa:** xử lý trạng thái job dựa trên identity đã lưu, có trạng thái terminal/skipped cho trường hợp kết thúc và backoff/next_attempt_at cho trường hợp tạm dừng; query chọn job đến hạn retry. Không để một tập job không tiến triển giữ toàn bộ batch. Giữ chính sách pause/delete/expiry đang có, phân biệt với retry lỗi kỹ thuật. Thêm test 50 job không tiến triển + job thứ 51 đủ điều kiện.

## R07 — P2: danh sách tải trước join ghi đè trạng thái join đã commit

**Vị trí:** `HomeViewModel.kt:974–988`, `:1007–1035`; liên quan `CampaignRepository.kt:2171–2181`.

Sequence guard mới chỉ phân biệt hai lần refresh. Join thành công không vô hiệu hóa sequence của lượt list đang bay, và list publication không kiểm tra mutation version đã đổi sau join.

Probe `listStartedBeforeJoinMustNotUndoSuccessfulJoin` dùng HomeViewModel/repository thật, MockEngine và CompletableDeferred để điều khiển thứ tự, không dùng sleep:

1. Tải mission `hasJoined=false`.
2. Bắt đầu list refresh thứ hai, giữ response cũ chưa cho về.
3. Join trả success; xác nhận UI đã `hasJoined=true`.
4. Thả response list cũ `hasJoined=false`.
5. UI trở lại `hasJoined=false`; assertion fail.

Probe dùng UID null cố định và phản hồi server giả để kiểm tra chính đường publish/join; không gọi xác thực hoặc backend thật. UID/generation không đổi trong toàn tình huống, nên đây là thiếu bảo vệ trước mutation trong cùng phiên, không phải một test đổi tài khoản.

**Tác động:** nhận thành công nhưng nút quay về “Nhận nhiệm vụ”, count/status cũng có thể lùi về cũ. Cơ chế xử lý ALREADY_JOINED có thể chữa ở lần bấm sau nhưng không ngăn lỗi ban đầu.

**Sửa:** phối hợp token refresh với mutation/version hoặc hủy/vô hiệu hóa lượt list cũ khi join commit, sau đó authoritative refresh phù hợp. Áp dụng cùng nguyên tắc với owner load đang bay trước pause/delete. Hiện `togglePauseResumeMission`, `deleteMission` không tăng missionLoadSequence; `create()` cũng chưa có guard phiên như phần initial load. Các nhánh này cần test riêng sau khi sửa hai crash; lượt audit này chưa tái hiện runtime chúng.

## Vì sao các test hiện có vẫn PASS

- Test M01 sao chép một khối `when` trong test, không chạy nhánh từ production. Nó không phát hiện được nếu UI lại đổi thứ tự.
- Test M08 chỉ dựng các UiState và assert field đã gán, không kích hoạt lỗi mạng qua ViewModel.
- Test M09 chỉ tăng repository generation; không đổi uid thật, không đảo response và không chạy mutation xen refresh.
- Không test constructor của MissionCreateViewModel hoặc OwnerAppDetailViewModel, nên không bắt được R01/R02.
- Runner backend 044 nạp full schema **đã bao gồm 044**, sau đó chạy 044 lại và gọi là upgrade path. Điều đó kiểm tra rerun trên database sạch, chưa chứng minh upgrade 043 có dữ liệu trùng an toàn.
- Phần lớn RPC tests gọi dưới quyền postgres, nên không phát hiện R03.

Đây là khoảng trống về độ bao phủ, không phải bằng chứng các cải tiến đã làm đều sai.

## Evidence và lệnh chạy

Evidence Git-ignore tại `.tmp/mission-recheck/`:

- `tests/com/ntt/crosstest/data/MissionRecheckProbeTest.kt`: ba probe Android kiểm tra production.
- `recheck.init.gradle`: thêm riêng test source; buộc task test thực chạy, không sửa build script dự án.
- `all-tests.log` và `results/*.xml`: 66 tests, 3 failures bằng assertion.
- `database-probes.mjs`, `database-results.json`: probe DB độc lập.
- `source-hashes.json`: source snapshot của các file chính.

Chạy tại root với JDK/SDK đã cấu hình:

```powershell
.\gradlew.bat -I .tmp/mission-recheck/recheck.init.gradle :app:testDebugUnitTest --console=plain
node .tmp/mission-recheck/database-probes.mjs
```

Android probes assert hành vi đúng nên đang FAIL. DB script assert hành vi lỗi để xác nhận tái hiện nên exit 0; không coi đó là PASS chất lượng bản sửa. Sau khi sửa cần đảo DB assertions thành mong muốn đúng và thêm vào runner chính.

Lượt chạy Gradle đầu bị sandbox chặn ghi file khóa ở cache Gradle; đã chạy lại với quyền mở rộng được công cụ phê duyệt, không còn bị chặn. Chưa chạy Android UI trên máy/giả lập, PostgreSQL nhiều kết nối, assemble hoặc lint trong lượt recheck này; không lấy báo cáo cũ làm kết quả mới.

## Các điểm cần ghi đúng khi bàn giao tiếp

- M04 vẫn là chính sách hiện tại: duyệt sau ends_at không trả mission reward. Không tự đổi trong đợt sửa lỗi còn sót.
- Báo cáo Gemini nói có ký quỹ và hoàn Credit thừa khi hết hạn. Source mission hiện trừ phí nền tảng không hoàn lại và lấy thưởng trực tiếp từ số dư chủ khi settlement; chưa có cơ chế escrow/refund như mô tả. Cần sửa báo cáo, không dùng lập luận đó để thay nghiệp vụ.
- M07 đã sửa order-before-limit và ưu tiên mission cá nhân, nhưng vẫn LIMIT 100 chung, chưa có phân trang. Nếu chính người dùng có hơn 100 mission trong cửa sổ 14 ngày, phần cá nhân vẫn có thể bị cắt. Không ghi “không bao giờ mất mission của tôi” như một bảo đảm đã được kiểm chứng.
- Probe phụ R06 cho thấy auto-accept giữ `app.suppress_report_notify=1` đến hết transaction thay vì reset về 0 như bản trước. Chưa chứng minh ảnh hưởng tới RPC app hiện tại do cờ local tự reset khi kết thúc transaction; không xếp thành lỗi chính cần ưu tiên trong kết luận này.

## Thứ tự sửa tiếp

1. Sửa R01/R02 trước; giữ ba probe Kotlin và bổ sung kiểm thử owner load xen pause/delete.
2. Sửa R05 và R03 để cơ chế trả thưởng/retry hoạt động và có quyền gọi phù hợp.
3. Thay xử lý trùng dữ liệu của migration bằng phương án R04; test upgrade thật từ 043 với dữ liệu có xung đột.
4. Sửa R07 và hoàn thiện guard cho các mutation, sau đó chạy lại toàn bộ tests cùng probe.
5. Đồng bộ full schema với migration cuối cùng và sửa lại báo cáo kiểm thử/nghiệp vụ cho khớp source. Chưa deploy 044 hiện tại trước khi xử lý các lỗi chặn nêu trên.
