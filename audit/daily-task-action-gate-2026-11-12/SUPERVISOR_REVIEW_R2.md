# Review độc lập R2 — chưa nghiệm thu hoàn tất

12/11/2026, tiếp tục sau khi Antigravity báo hoàn tất. Cùng conversation `e4afbc3e-90d5-416b-b6ed-7cbc512113dd`, model yêu cầu vẫn Gemini 3.8 Flash High.

## Lỗi backend đã thực thi

`supervisor-action-gate-acceptance.mjs --bonus --payloads --review --roles --r2` trên DB mới, migration 090 hash `57e1f801f02560307351a6db1d16b572054e3d62101933a5f605b94010a3f370`: **80/84 PASS, 4 FAIL**. JSON/log `supervisor-action-gate-acceptance-bonus-payloads-review-roles-r2.json`, `supervisor-r2-backend.log`; bản SQL theo hash và kết quả theo thời gian trong `supervisor-runs/`.

1. Role authenticated có thể set custom GUC `crosstest.internal_campaign_mutation=true`. Direct UPDATE closed khi B6/D3 vẫn thành công; paused owner_manual B3/D0 cũng giả được owner_deletion_pending. Không coi custom GUC client tự đặt là bằng chứng server. Giữ phép thử spoof flag, không loại ca để có PASS.
2. Gọi lại owner delete sau lần đầu xóa vật lý thành công trả CAMPAIGN_NOT_FOUND. Fixture bật chính sách quota ngay, thêm app khiến nghĩa vụ mới unmet. Phải có receipt/tombstone hoặc reconcile với proof owner/server để nhận biết hành động đã làm, không hoàn/trả thưởng hai lần và không chấp nhận UUID bất kỳ.
3. Gọi lại resume sau lần đầu thành công trả CAMPAIGN_NOT_PAUSED; lúc này chính resume làm tăng quota nên trạng thái mới unmet. Replay phải trả trạng thái đã thực hiện an toàn, không đòi gate cho thao tác đã commit.

### Kiểm tra lại candidate R2 đầu tiên

Candidate hash `937574fa1b0e24457f2796383a963c9763447bb04158c755b8166542b7bd77a6` đạt **84/84** các ca trên (15:38:21Z), kể cả pending cho met thiếu/null. Sau đó thêm ca eligible direct DELETE không bonus/missions: **FAIL `permission denied for table campaign_removal_receipts`**. Trigger đang SECURITY INVOKER ghi vào bảng đã REVOKE authenticated; owner đủ điều kiện vẫn không xóa trực tiếp được. Chạy riêng `--r2` hiện **21/22**, log `supervisor-r2-direct-delete.log`; chạy đầy đủ tất cả flags sẽ là 85 ca. Giữ bảng receipt private, không cấp cho client quyền tự tạo/sửa server proof chỉ để test PASS. Sửa writer/trigger với xác minh caller/operation hợp lệ và bảo toàn RLS/tài chính.

Lượt mở rộng tiếp theo lúc **15:45:53Z**, cùng hash, **85/90 PASS, 5 FAIL**. `supervisor-r2-backend-expanded.log`, JSON tất cả flags và bản theo thời gian/source hash trong supervisor-runs:

- Eligible direct DELETE lỗi quyền receipt như trên.
- Role PostgreSQL authenticated nhưng sub/UID null vẫn gọi confirm_campaign_production thành công trên app của owner khác đã met. `session_user=postgres` từ kết nối test SET ROLE không phải quyền hiệu lực; RPC đang dùng whitelist session_user và `owner_id <> NULL` không từ chối. Yêu cầu xác thực/ownership phải đúng cả khi subject thiếu; đừng tin spoof JWT role hoặc definer/current_user. Test mới production_rpc/noUid=true/fixtureMet=true.
- Helper `_assert_owner_daily_task_action_allowed(owner,today-1)` chấp nhận snapshot hôm qua met=true dù hôm nay unmet. p_today cũ có thể phát sinh khi RPC chờ khóa qua 00:00; helper phải kiểm tra với ngày server thật ở thời điểm quyết định, không lấy chính tham số làm authority. Test stale_server_day.
- Direct PATCH production và manual pause thành công nhưng bản ghi user_daily_tasks hôm nay đã có/chưa chốt vẫn giữ quota cũ B6 thay vì B3. Test persistedQuota seed một row hợp lệ hôm nay trước thao tác, không yêu cầu tạo row mới ngoài contract088. Ca control production RPC cập nhật row đúng. Phải đồng bộ completion/projection của direct clients và giữ an toàn khóa, không chỉ đổi cách tính getter.

Không báo các ca phát hiện đầu tiên là chưa sửa: chúng đã qua trong candidate R2. Đây là phần mở rộng nghiệm thu các quyền/đường hợp lệ và dữ liệu liên quan trong kế hoạch, giữ toàn bộ phép thử. Native concurrency vẫn cần kiểm chứng/ghi giới hạn như bên dưới.

Xác minh quyền hiệu lực bổ sung lúc **15:50:58Z**: không dựa vào login postgres của SET ROLE. Actor thực hiện dưới `SET LOCAL SESSION AUTHORIZATION supervisor_gate_authenticator` (NOINHERIT, chỉ được cấp role authenticated), tiếp tục `SET LOCAL ROLE authenticated`, UID null và JWT role GUC giả service_role. Production RPC vẫn thành công; xem `supervisor-r2-effective-caller.log`, JSON `supervisor-action-gate-acceptance-r2.json`. Fixture chỉ khôi phục postgres sau khi actor call kết thúc để quan sát kết quả. `supervisor-session-context-probe.mjs` kiểm chứng/reset ngữ cảnh fixture; script acceptance hiện ghi actorContext cho ca này. Không bỏ phép thử hoặc thay giả role bằng service_role thật để làm PASS.

## Thứ tự khóa thực tế không khớp báo cáo

- `_request_campaign_removal` và `confirm_campaign_production`: profile FOR UPDATE -> advisory lifecycle -> campaign.
- `resume_campaign`: advisory lifecycle -> campaign -> profile FOR UPDATE.
- Vì vậy hai giao dịch cùng owner có thể chờ profile/advisory của nhau. Không thể gọi đây là một thứ tự advisory -> profile -> campaign đồng nhất như báo cáo hiện tại.
- Đăng app 084/088 dùng campaign_slot_guard khác key; direct status lấy row lock trước trigger. Các luồng duyệt báo cáo/hoàn bonus/finalizer có các khóa sẵn trong LOCK_ORDER.md. Phải thiết kế toàn đường đi, tránh thêm profile trước campaign trong đường đi đối nghịch với report/bonus đang campaign trước profile. Không chỉ thêm advisory ở vài RPC rồi tuyên bố hết deadlock.
- Trigger SECURITY INVOKER chạy bên trong SECURITY DEFINER vẫn có current_user là definer. Không dùng việc đó để suy ra caller service thật. JWT role/custom GUC không là proof. Kiểm tra quyền thật/role được cấp và luồng gọi hợp lệ; bảo toàn admin/system/worker.
- Chưa có bằng chứng PostgreSQL thật nhiều kết nối trong audit. Supervisor gặp EACCES loopback trước start server, không vượt quyền. Nếu phiên Antigravity có quyền chạy DB riêng thì tạo cluster mới trong audit mới, kiểm tra data_directory, loopback; không dùng cluster cũ. Nếu không, ghi rõ chưa chạy, không tuyên bố 100% hoặc ready deployment.

Đối chiếu lại canonical source: candidate R2 đầu tiên đã bỏ profile FOR UPDATE trước advisory ở deletion/production; các owner RPC hiện đi advisory -> campaign -> refresh/profile, nên vòng chờ RPC/resume nêu trên của bản cũ đã được sửa về cấu trúc. Finalizer088 riêng chỉ thấy khóa profiles/ledger, không thấy nó tự khóa/update campaign; lo ngại finalizer -> campaign trước đó là tình huống cần kiểm tra khi ghép luồng, không phải deadlock đã tái hiện của hàm finalizer đơn lẻ. Đường close/refund bonus087 thực sự campaign -> round -> owner profile vẫn cần đối chiếu với mọi thay đổi mới. Không coi phân tích tĩnh là native concurrency PASS, và không thay quy tắc chỉ vì lo ngại chưa có bằng chứng.

## Android còn thiếu yêu cầu đã đọc qua diff

1. Các hàm markProduction/deleteCampaign/togglePauseResume chỉ đánh giá `_uiState.value.dailyTask` cache. Không có fetch fresh ngay lúc xác nhận trước mutation. Cần await dữ liệu mới, fail closed timeout/null/error/day rollover, giữ backend là quyết định cuối.
2. Không có guard chống bấm lặp dùng chung/synchronous claim trước launch cho bốn thao tác. markProduction/pause/resume không fence UID/generation/day/operation sau await. Delete chỉ có opId cũ, còn nhánh stale vẫn sửa isActionLoading của phiên mới. Cần fence cả loading/error/message/callback/cache, luôn propagate cancellation đúng.
3. Menu khi biết blocked lại gọi trực tiếp hàm thực thi mutation để hiển thị lỗi. Nếu state đổi allowed giữa callback Compose và ViewModel, thao tác có thể chạy ngay mà không có confirmation. Tách chức năng hiển thị lý do/refresh khỏi hàm thực thi, không gọi mutation từ nhánh blocked.
4. Resume unmet vẫn map owner_app_inactive_3days / oad_resume_sub_auto, trái yêu cầu thông báo nhiệm vụ thông thường, không nói sai người dùng vắng mặt nhiều ngày. Dùng lý do chung và số lượng hoàn thành/còn thiếu khi có dữ liệu.
5. `confirmCampaignProduction` mới chỉ invalidate cache; chưa dùng refreshDailyTaskAfterCampaignChange / daily task events như delete. Bảo vệ account/day/mutation version trước invalidation/publication, đồng bộ Home/Completed/quản lý app theo domain hiện có. Refresh phụ lỗi sau commit phải stale/pending, không báo thao tác chính thất bại.
6. Unit mới chủ yếu test evaluator, chưa có behavioral ViewModel tests cho fresh read, duplicate taps, switching account, day rollover, request ordering, callback sau timeout và postcommit refresh failure. Bổ sung các test này, không chỉ đổi mock để bộ cũ PASS. Chạy full unit phù hợp vì serializer DailyTaskStatus tác động nhiều nơi.

Supervisor đã thêm `app/src/test/java/com/ntt/crosstest/ui/viewmodels/SupervisorOwnerActionGateReviewTest.kt` với 3 hành vi độc lập: cached met=true nhưng fresh met=false không gọi production RPC; double tap chỉ một mutation đang chờ; response account A không khởi động refresh detail trong account B. MockEngine không kết nối server thật, có giải phóng VM/client. Chưa có kết quả thực thi: lượt Gradle đầu thiếu JAVA_HOME; dùng JDK Android Studio có sẵn thì wrapper bị AccessDenied khi ghi `.gradle/wrapper/...zip.lck` ở user home. Log `supervisor-r2-android-review.log`. Không nâng quyền hoặc đổi cấu hình quyền. Các test phải được chạy khi môi trường phiên được cấp cho phép; giữ assertion hành vi, chỉ sửa harness nếu có lỗi harness thực tế. Full unit suite sẽ tự bao gồm lớp này.

Bổ sung ca thứ 4 vào cùng lớp: actualFreshReadDeadlineShowsPendingInsteadOfSilentCancellation. MockEngine thực sự treo fresh read đến khi bị cancel, test scheduler tiến 5001ms, phải dừng loading, không mutation và có pending/retry message. Test timeout của agent hiện chỉ trả HTTP504 ngay, chưa kiểm tra TimeoutCancellationException của withTimeout(5000). VM đang rethrow mọi CancellationException nên actual deadline có thể kết thúc im lặng (error null). Cần phân biệt deadline cục bộ với cancellation thật của scope; kiểm thử đầy đủ bốn action, không thay actual deadline bằng HTTP504.

Bổ sung ca thứ 5: olderConfirmationReadCannotOverwriteNewerTaskRefresh. Giữ response fresh-read của action ở met=true; chạy refreshDailyTaskState mới hơn trả met=false; sau đó thả response action cũ. Không được overwrite trạng thái mới hoặc mutation. Code action hiện ghi freshTask trực tiếp vào uiState mà chưa tích hợp dailyTaskSequence/latestAppliedTaskSeq của refresh nên thiếu fence request order. Test dùng barrier, không chỉ kiểm tra chuỗi code. Lớp ViewModel supervisor hiện 5 ca; cùng 5 ca serializer/cache là 10 ca mới, phải đưa vào lượt kiểm thử cuối.

Ca thứ 6 inspectingBlockedActionPreservesOtherOperationLoading: production đang chờ response, task refresh mới hơn thành unmet; inspectGate(DELETE) chỉ hiển thị lý do, không được xóa isActionLoading của operation đang chạy. Code inspectGate mới đang copy(isActionLoading=false,error=...), làm mất cờ chờ của thao tác khác. Lớp ViewModel supervisor hiện 6 ca, cache/serializer 5 ca. Các ca bổ sung cần chạy, không coi bản cũ 3/3 là đủ cho lượt cuối.

Thông báo/subtitle unmet hiện chỉ là câu chung, chưa có thông tin số nhiệm vụ đã làm/còn thiếu như kế hoạch. Ca fresh-read supervisor dùng canonical completed=3/effective=6/debt=0, có assert thông báo chứa số còn thiếu 3. Hiển thị số động từ canonical khi dữ liệu hợp lệ; không hard-code q hoặc dùng câu vắng nhiều ngày. Null/pending vẫn dùng thông báo cần tải lại, không bịa số.

Review serializer/cache thêm: DailyTaskStatusSerializer.deserialize giữ rawHistoryReady/rawCarryoverReady/rawDebt=null khi thiếu trường, nhưng serialize lại ghi getter mặc định true/true/0. Đọc lại JSON cache biến dữ liệu thiếu thành hợp lệ, gate có thể từ Blocked chuyển Allowed sau roundtrip. Cần giữ presence/null qua persist hoặc invalidate snapshot thiếu dữ liệu. Supervisor đã thêm `SupervisorDailyTaskStatusCacheReviewTest.kt`, 3 test roundtrip độc lập cho thiếu history/carryover/debt. Chưa chạy được vì blocker Gradle của supervisor ở trên; không coi chúng là PASS. Full suite cần bao gồm và kiểm tra các ca này, không xóa assertion.

Phân biệt met=false với thiếu/null met: thiếu dữ liệu phải pending/cần thử lại, không nói người dùng chưa làm đủ. Code hiện rawMet=null trả UnmetDailyTask và SQL helper thiếu met trả DAILY_TASK_UNMET. Lớp cache review có thêm 2 test missing/null met phải DataPendingOrStale; backend payload cases đã assert mã DAILY_TASK_PENDING cho canonical malformed data. Cập nhật test cũ đang kỳ vọng missing met=unmet theo đúng kế hoạch, không bỏ phép thử.

Server day rollover cần review riêng: hiện `v_today` chụp khi vào RPC trước lúc chờ khóa, helper chỉ so payload với p_today truyền vào. Nếu chờ qua 00:00, không được lấy ngày cũ làm ngày xác nhận hợp lệ; xác định lại ngày server tại điểm quyết định sau khóa và kiểm tra helper với ngày thật, không tin tham số ngày.

## Bảo toàn nguồn và tình trạng phiên bản

Báo cáo agent lúc 20:50 nói phiên bản 1.2.4/44 và package không đổi. Kiểm tra supervisor lúc 22:17 phát hiện version 1.2.5/build45 (mtime21:27) và hai AAB thay đổi (mtime21:32), đều sau giờ báo cáo. **Người dùng đã xác nhận đây là thay đổi riêng của họ.** Giữ nguyên các thay đổi này. **Không tự revert version/build.gradle, AAB hoặc bất kỳ thay đổi riêng nào của người dùng/phiên khác. Không tạo APK/AAB mới cho R2.** Lấy snapshot trạng thái hiện tại trước sửa, giữ baseline đầu task để phân biệt lịch sử.

Supervisor đã lưu snapshot R2 riêng `SUPERVISOR_R2_START.json` (373 file nguồn, 7 package); không thay CURRENT_BASELINE.txt. `supervisor-observe-changes.mjs --r2` kiểm tra bảo toàn so với trạng thái bắt đầu R2; chạy không flag vẫn phản ánh chênh lệch version/AAB so với đầu task và không được dùng để kết luận đã rollback/đã vi phạm bởi actor nào khi chưa có chứng cứ. Bản test supervisor vừa thêm sau snapshot được ghi nhận là nguồn mới hợp lệ.

Migrations 062–089 và cấu hình quyền/launcher vẫn không được sửa; không production/commit/push/secrets/đổi quyền. Chỉ sửa phần gate và lỗi liên quan đã chứng minh, theo toàn bộ prompt/kế hoạch gốc. Cập nhật báo cáo bằng kết quả thực tế, giới hạn chưa chạy và provenance thay đổi; bỏ kết luận 'hoàn thành toàn diện/100%' khi còn phạm vi chưa kiểm chứng.
