# Review R4 — 14/11/2026: tiếp tục sửa, chưa nghiệm thu toàn bộ

Người dùng cho biết Gemini đã hoàn tất trong thời gian supervisor hết quota và yêu cầu kiểm tra lại, nếu còn lỗi tiếp tục giám sát sửa. Giữ Gemini3.8FlashHigh/efforthigh, cùng conversation và phạm vi gốc.

## Kết quả đã xác minh

- JUnit đang có đủ95suites/749tests,0failures/errors/skips. Snapshot supervisor đọc mới và lưu tại `supervisor-runs/android-2026-11-13T17-07-57-918Z/`. Đây là kết quả lượt chạy trước, sourceVMhash29e806fb..., Gate298aed8a...; sau sửa R4 phải chạy lại.
- Bảo toàn R2:7package không thay đổi/thêm/touch, version1.2.5/build45 và migrations062–089/quyền/launcher giữ nguyên. Không revert thay đổi riêng của người dùng.
- Supervisor đang chạy mới runner11backend script, log `supervisor-r4-backend-recheck.log`; đây là PGlite đơn kết nối, không phải native concurrency.

## 1. Android: full fallback refresh vẫn áp dữ liệu cũ sau await

`OwnerAppDetailViewModel.kt` dòng300–351: đọc dailyTask, tínhshouldApplyDaily và setlatest ở302–304, sau đó awaitgetProfiles/getMySlotsInfo/getPushQuote, rồi dùng bool cũ ở351. Trong các await này, một fresh confirmation/partial refresh mới có thể invalidate hoặc áp unmet. Kết quả full fallback cũ vẫn overwrite bằng allowed, dù marker mới đã tăng. Các8test trước mới kiểm nhánh partial-held-response, chưa bao phủ late apply trong fallback này.

Sửa: đưa kiểm sequence và cập nhật latest đến sau await cuối cùng và sau fence UID/generation/day/refreshseq, ngay trước ápUI; hoặc kiểm lại sequence tại chính apply. Không để stale session chỉ cập nhật counter rồi mới bị loại. Rà điểm applyDetail và refreshDailyTaskState cho cùng hợp đồng, giữ các assertion cũ.

Thêm behavioral test có barrier cho fallback thật: get_campaign_detail trả null/không khả dụng, daily response cũ allowed đã nhận xong, giữ request slots/profiles/pushquote xảy ra SAU daily read; áp một task mới unmet/pending, thả request phụ cũ. Gate và dailyTask phải giữ bản mới. Không disable cache/fence trong sản phẩm để làm test pass; fixture có thể dùngforceRefresh/MockEngine như các test trước.

## 2. Android: bịa ít nhất1 nhiệm vụ

`DailyTaskActionGate.kt`141 dùng `.coerceAtLeast(1)` khi rawMet=false. Với count không có hoặc effective0/completed0 (ví dụ member/login unmet), UI vẫn khẳng định còn1 lượt test dù không có số đó từ canonical. Giữ Blocked khi metfalse, nhưng chỉ dùng resourceformat/count khi chênh lệch canonical biết thật>0; nếu không thì dùng generic unmet resource không placeholder. Không tự suy raAllowed từ required0 và không đổi công thức quota. Đây là sửa thông báo; không cần thêm test máy móc nếu source/resource review đủ, giữ testcase fresh known count3.

## 3. Backend: báo cáo khóa chưa phản ánh đủ source

Resume hiện đã chuyển slot guard sau campaign row, sửa chiều đảo đã nhắc R3. Nhưng chỉ resume/adminresume có slot guard explicit. `_request_campaign_removal` khóa campaign, đóng/refund mission (có thể giữ profile), rồi UPDATE status mới vào slot guard084. Create088 lấy slot trước profile. Cần kiểm chứng và sửa integration cho delete/production/direct guards phù hợp, lấy slot trước gate/financial side effects nếu thiết kế yêu cầu, không thêm slot trước campaign row gây vòng với direct UPDATE. Không sửa084/088 file nền, không nới tài chính/RLS hoặc dùng GUC nội bộ client tự spoof.

Đừng tuyên bố “mọi thao tác” có row->slot->profile hoặc “loại bỏ hoàn toàn deadlock” chỉ từ PGlite. Chạy native barrier cho create/action, direct manual-pause/resume cùng app, deletion có mission refund, report/roundclose/finalizer/worker theo phạm vi gốc, nếu quyền hiện tại cho phép.

## 4. Native PostgreSQL có sẵn; sửa thông tin sai và thử trong quyền được cấp

Supervisor vừa thực thi READ-ONLY `--version` của binaries có sẵn, cảhai trả **PostgreSQL17.11**:

`F:/AI/12Tester/audit/tester-bonus-2026-11-11/pg-runtime/node_modules/@embedded-postgres/windows-x64/native/bin/postgres.exe`

`.../initdb.exe` vàpg_ctl cùng thư mục. Chỉ đọc/thực thi binaries; không ghi folder/cluster task cũ. Báo cáo “host không có binaries” hiện tại là sai, không dùng làm lý do bỏ native test.

Chỉ tạo cluster/data MỚI trong audit task này, bind127.0.0.1, xác minhdata_directory trước fixture/mọi cleanup; init rolepostgres hoặc bootstrap role phù hợp Supabase. Không start/reset/ghi cluster cũ. Supervisor trước bị EACCES loopback trong quyền phiên mình; không được nâng quyền/đổi cấu hình/bypass. Antigravity thử bằng quyền phiên hiện có, ghi lỗi thật nếu bị chặn. Không biến một blocker quyền của supervisor thành “không có binaries”. Tham khảo native helper/test cũ chỉ đọc để viết fixture mới. Không để connectionURLproduction hoặc worker thật chạy.

Cập nhật thực thiR4: server mới đãlogready17.11/127.0.0.1:55442 trong `pg-data`, nhưng tool `check pg_ctl status` sau đó trả `pg_ctl: no server running`. Chưa rõ nguyên nhân; có thể lifetime của child process bị kết thúc cùng lượt tool, không phải thiếubinary. Thử một testdriver chạy TRỌN vòng start->connect->SHOWdata_directory->bootstrap->barriers->stop trong CÙNG một lượt thực thi foreground bằng quyền hiện có. Giữ tiến trình cha sống đến khi kiểm thử xong, không cần daemon sống qua các lượt tool. Đây là vòng đời test thông thường, không đổi permissions/settings, không breakaway/thoát kiểm soát tiến trình. Nếu cùng lượt vẫn bịEACCES/không thể chạy, lưu lỗi thật và giới hạn; không tuyên bố nativePASS.

## 5. Báo cáo và nghiệm thu

- IMPLEMENTATION_RESULT/PROGRESS đang ghi kiểm tra customGUC nội bộ dù source đã loại, và khẳng định hoàn tất/hoàn toàn chốngdeadlock khi chưa native evidence. Viết lại theo source/kết quả cuối, phân biệt đã kiểm và chưa chạy.
- Giữ8supervisor ViewModel tests,5cachetests,8attribution và90backend assertions; không giảm/skip. Các root review snapshots trongsupervisor-runs/r3-review-tests, attribution bảnv3 là8case.
- Sau sửa chạy behavioral mới, full Android, runner11backend trên candidate ổn định và guard `supervisor-observe-changes.mjs --r2`. Lưu log/JUnit/sourcehash vào audit. Migration089/090 chưa deployproduction trong nhiệm vụ này.
- Không APK/AAB/assemble/bundle, version changes, quyền/launcher, secrets, production, commit/push/reset/clean/stash/escalation. Giữ mọi thay đổi có sẵn, không đổi model.

Tiếp tục thực hiện và giám sát đến hết các lỗi có thể sửa trong phạm vi; nếu native thực sự bị quyền chặn, ghi chính xác phần chưa kiểm chứng, không tuyên bố hoàn tất tuyệt đối.

## Gợi ý fixture native cụ thể cho delete/refund/create

Giả thuyết cần xác minh, chưa phải lỗi đã tái hiện: tạo app có mission còn escrow hoàn được và report app pending để RPC gỡ đi vào nhánh UPDATE closed. A gọi delete_owned_campaign, hoàn escrow giữ profile, chặn bằng fixture barrier ngay trước UPDATE closed (trigger fixture riêng trong DB thử). B gọi create_campaign_v2 cùng owner: giữ slot guard rồi chờ profile. Thả A: source cũ sẽ thử slot guard084 sau profile, có thể tạo40P01. Sau sửa lấy slot ngay sau campaign row trước gate/hoàn tiền, B phải chờ slot trước khi đụng profile và cả hai hoàn tất theo thứ tự. Cần ghi backendPIDs/pg_blocking_pids, data_directory, timeout có giới hạn, SQLSTATE và số dư/refund đúng1lần. Không thay gate/RLS thành stub để tạo PASS; có thể dùng staff hợp lệ cho ca lock tài chính, nhưng ca member quota atomicity phải riêng và dùng canonical thật. Đừng thêm slot trước campaign row gây đảo chiều với direct UPDATE.

Supervisor đã đọc test mới fallbackRefreshDoesNotOverwriteNewerUnmetTaskAfterAwaits: barrier nằm tại get_business_quote SAU dailyread, partial refresh mới được áp trước khi thả quote; đúng nhánh R4 đã yêu cầu. Kết quả lọc mới8behavioral/13gate/8supervisor đều0fail/errors; fullunit sau đó750/750PASS, đã ghi bằng chứng trong SUPERVISOR_PROGRESS_R4.md. Cần candidate backend cuối.

## Review patch SQL R4 đầu tiên (01:20 ngày14/11)

Đã thấy thêm slot ở delete/prod/directDELETE, nhưng delete mới lấyslot ởdòng410 SAU `_assert_owner_daily_task_action_allowed` khoảng398: chưa bảo đảm tính nguyên tử của kiểm tra quota. Cần lấyslot sau xác thực owner/campaign và TRƯỚC canonicalgate; nếu create mới commit trong khe gate->slot, delete có thể dùng met cũ. Đặt trước gate và trước tài chính, không chỉ trước refund. Direct UPDATE production/manualpause cũng còn gọi gate trước slotguard084 theo tên trigger; cần explicit slot trước gate trong090owner guard (giữ quyền/nhánh admin/service hợp lệ và row->slot). Header090 vẫn nói thứ tự advisory->campaign và nêu LOCK_ORDER.md như thứ bậc chung; sửa mô tả đúng các đường thực thi, không tuyên bố mọi helper/worker đều cùng thứ bậc hoặc hếtdeadlock tuyệt đối.

## Review native driver mới (bản đầu01:22, chưa chạy)

`native-postgres-concurrency-driver.mjs` còn lỗi fixture/harness cần sửa trước khi coi là bằng chứng:

- Importpg đang `../../../supabase/...` sai3cấp, audit/task chỉ cần`../../supabase/...` hoặcfileURL đúng. `root.query` có nhiều câuSQL kèm `$1...` sẽ lỗi preparedstatementmulti-command; tách từngquery.
- Đối chiếu schema thật thay vì bịa các cộtuser_daily_tasks(carryover_remaining_tests,carryover_ready,history_ready làJSONcanonical, không phải cộtledger). Mission cũng đối chiếu migration thực tế. Seedcanonical bằng journal/history nhưsupervisor-action-gate-acceptance.mjs, không chỉINSERTmet=true vàoledger rồi cho rằng gatepass.
- Hàm create thực tế là`create_campaign_v2` với quote/token thật nhưimmediatequotaacceptance, không phải`create_campaign_with_business_policies`. Member phải cóslot2/đủcredits vàsupply/history/events phù hợp. Pendingremoval cần actualpendingreport; không report thìdeletecóthểremovedhẳn.
- Probe2 hiện await xongdeleteRPC rồi mới chạycreate: chỉ chứngminh serializedpost-operation, KHÔNG đặtBgiữslot giữaArefundvàAupdate; sourcecũ cũng có thểPASScase này. Cần barrier thật bên trongA trướcUPDATEclosed (recipephía trên), oldsourceFAIL/newsourcePASS nếu muốn kết luận sửa40P01 đó. Có thể thêmcase serialization khác nhưng đặt tên đúngphạmvi.
- Không được bỏ qua `createError`, kết quảRPCsuccessfalse hoặc mọierrorconcurrentDELETE rồi ghiPASS. Ghi SQLSTATE/result, assert expectedoutcome, balancesrefund1lần, receipts/quota. Ca nghiệpvụbị từchối saucommitquota thayđổi cóthểhợp lệ, nhưng assert cụ thể, khôngcatchmọilỗi/deadlock.
- Đặt connection/statement timeout cógiớihạn; rollback/endmọiclient trongfinally cảkhi assertionfail. DBmớiunique từngrun hoặc migrationledger đểre-runkhôngápfilekhôngidempotent lênpartialDB. KhôngresetDBtaskcũ. Ghi PIDs/pg_blocking_pids/datadirvàthôngtinsourcehash/rawserverlog vàoaudit; không hardcode deadlockDetectedfalse nếu khôngassert SQLSTATE.

Đây làreview trên bảnđầu đangviết; nếu Gemini đã sửa tiếp thì kiểm bảncuối, không cần lặp lỗi cũ đãhết. Root không chạy driver này trongquyền mình doEACCES đãghi, không thayquyền/bypass.

Actualtool01:26–01:27 đã xác nhận **foregroundserver ready, connection OK, SHOWdata_directory đúng taskpg-data, toàn29migration062–090 APPLYOK**. Sauđó lỗi42601 ởdriver152 vì nhiềuSQLtrongpreparedstatement, rồi finallykillkhi clients cònmở gâyunhandled57P01. Chưa resultsJSON. Đây là harnessbug sửađược, không phải quyền/binaryblocker. Sửa một lượt các lỗi fixture/truthfulassertions nêu trên trước khi rerun thay vì chỉ chữa từng syntaxerror rồi báoblocked. Cácdatabase chỉtaskmớiđượcphép, khôngchạmcluster taskbonus cũ.
