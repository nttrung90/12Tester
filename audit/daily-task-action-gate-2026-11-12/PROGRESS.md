# Tiến độ thực hiện — Daily Task Action Gate (R4 — 14/11/2026)

## Thông tin phiên làm việc
- **Model:** Gemini 3.8 Flash High (effort high).
- **Workspace:** `F:/AI/12Tester`.
- **Conversation ID:** `e4afbc3e-90d5-416b-b6ed-7cbc512113dd`.
- **Phiên bản ứng dụng:** **1.2.5**, build **45** (bảo toàn cấu hình của người dùng; toàn bộ 7 file APK/AAB baseline được giữ nguyên vẹn, không build mới).
- **Trạng thái nghiệm thu:** Hoàn tất kiểm tra R4 trong phạm vi mã nguồn và các kịch bản đã thực thi; không còn lỗi đã biết trong phạm vi này. Bằng chứng tại `SUPERVISOR_FINAL_EVIDENCE_R4.json`; các giới hạn được nêu ở mục 3.

---

## 1. Chi tiết thực hiện và khắc phục các mục kỹ thuật R4

### A. Android Client
1. **Khắc phục Race Condition trong Full Fallback Refresh (`OwnerAppDetailViewModel.kt`):**
   - **Vấn đề trước R4:** Logic fallback refresh kiểm tra điều kiện `shouldApplyDaily` và ghi nhận `latestAppliedTaskSeq` trước các thao tác bất đồng bộ (`getProfiles`, `getMySlotsInfo`, `getPushQuote`), sau đó áp dụng biến boolean đã tính từ trước vào UI state. Khi một lần refresh khác áp dụng trạng thái chưa hoàn thành (unmet) sau khi daily task được đọc nhưng trước khi quote hoàn tất, lần fallback cũ quay lại ghi đè UI state thành hợp lệ (Allowed).
   - **Xử lý R4:**
     - Chuyển toàn bộ việc kiểm tra `shouldApplyDaily` và cập nhật `latestAppliedTaskSeq.updateAndGet { current -> maxOf(current, taskSeq) }` xuống **sau** khi tất cả network awaits hoàn tất.
     - Đặt sequence update sau kiểm tra UID, generation, ngày nghiệp vụ và mã yêu cầu của lượt refresh; loại bỏ phản hồi thuộc phiên, ngày hoặc lượt tải cũ.
     - Áp dụng cơ chế tương tự trong cả nhánh `applyDetail` và `refreshDailyTaskState`.
   - **Kiểm chứng hành vi:** Bổ sung ca kiểm thử hành vi `fallbackRefreshDoesNotOverwriteNewerUnmetTaskAfterAwaits` trong `OwnerAppDetailActionGateBehavioralTest.kt` với barrier tại `get_business_quote` mô phỏng việc chặn fallback sau khi đọc daily task, kích hoạt refresh unmet mới, rồi mở barrier để xác nhận task cũ không thể ghi đè. Toàn bộ 8 ca kiểm thử hành vi PASS.

2. **Khắc phục lỗi bịa số nhiệm vụ khi diff không dương (`DailyTaskActionGate.kt`):**
   - **Vấn đề trước R4:** Sử dụng `.coerceAtLeast(1)` dẫn đến việc khi `diff <= 0` hoặc thiếu trường `effective_required_tests`, hệ thống bịa ra thông báo "còn thiếu 1 nhiệm vụ".
   - **Xử lý R4:**
     - Loại bỏ hoàn toàn `.coerceAtLeast(1)`.
     - Tính `knownRemaining = if (req != null && comp != null && req > comp) req - comp else null`.
     - Chỉ sử dụng chuỗi định dạng động `R.string.oad_gate_error_unmet_daily_task_format` với tham số `formatArg = knownRemaining` khi `knownRemaining > 0`.
     - Khi `knownRemaining == null` hoặc không dương, trả về chuỗi thông báo tổng quát `R.string.oad_gate_error_unmet_daily_task` với `formatArg = null` (vẫn đảm bảo `isAllowed = false`).
   - **Kiểm chứng:** Toàn bộ 13 ca kiểm thử trong `DailyTaskActionGateUnitTest` PASS.

3. **Bảo toàn đầy đủ Review Assertions:**
   - Bảo toàn các assertion của 8/8 ca trong `SupervisorOwnerActionGateReviewTest`.
   - Bảo toàn các assertion của 5/5 ca trong `SupervisorDailyTaskStatusCacheReviewTest`.
   - Toàn bộ suite Android: **750 tests across 95 suites: 750 PASS, 0 failures, 0 errors, 0 skipped**. Lượt full được supervisor quan sát: **BUILD SUCCESSFUL in 51s**.

---

### B. Backend Database (`supabase/migrations/090_daily_task_action_gate.sql`)
1. **Khóa chống Deadlock và Căn chỉnh thứ tự khóa (LOCK_ORDER):**
   - **Vấn đề trước R4:** `_request_campaign_removal` chưa lấy `campaign_slot_guard` trước gate và hoàn tiền; trigger có thể lấy khóa này quá muộn. `create_campaign_v2` đồng thời có thể xen giữa gate và mutation hoặc giữ slot rồi chờ profile đang bị luồng gỡ giữ.
   - **Xử lý R4:**
     - Trong `_request_campaign_removal`: Di chuyển `pg_advisory_xact_lock(hashtextextended('campaign_slot_guard', hashtext(v_owner_id::text)))` lên ngay sau khi xác thực quyền sở hữu và kiểm tra replay, **trước** lời gọi `_assert_owner_daily_task_action_allowed` và **trước** vòng lặp hoàn tiền escrow nhiệm vụ (`_check_and_refund_mission_escrow`). Nhánh admin xóa cũng chiếm `campaign_slot_guard` trước khi hoàn tiền.
     - Trong `confirm_campaign_production`: Đặt `campaign_slot_guard` ngay sau khi khóa hàng campaign `FOR UPDATE` và trước `_assert_owner_daily_task_action_allowed`.
     - Trong `trg_fn_campaigns_guard_action_gate_before_update`: Bổ sung chiếm `campaign_slot_guard` rõ ràng trước khi gọi gate cho cả nhánh chuyển sang `production` và nhánh tạm dừng thủ công `owner_manual`.
     - Trong `trg_fn_campaigns_guard_action_gate_before_delete`: Chiếm `campaign_slot_guard` trước khi kiểm tra gate.
   - **Header Comment:** Cập nhật chú thích header của migration 090 mô tả chính xác đường đi thực tế của các luồng, không đưa ra tuyên bố tuyệt đối sai lệch về miễn nhiễm deadlock toàn hệ thống.
   - **Bảo toàn Migrations nền:** Toàn bộ migrations từ `062` đến `089` giữ nguyên vẹn, không sửa đổi.

---

### C. Kiểm thử đồng thời Native PostgreSQL 17.11 (Native Concurrency Probes)
1. **Môi trường và cơ chế vận hành:**
   - Sử dụng binary native PostgreSQL 17.11 có sẵn tại `audit/tester-bonus-2026-11-11/pg-runtime/node_modules/@embedded-postgres/windows-x64/native/bin/postgres.exe`.
   - Vận hành cụm dữ liệu phân tách riêng cho task tại `audit/daily-task-action-gate-2026-11-12/pg-data` trên cổng `55444`, listen trên `127.0.0.1`.
   - Test runner `supervisor-native-r4-runner.mjs` chạy dưới tiến trình Node.js foreground trong quyền hạn phiên hiện có, sở hữu vòng đời của `postgres.exe`, xác minh hàng rào thư mục dữ liệu (`SHOW data_directory`) và `postmaster.pid`.
   - Tự động tạo cơ sở dữ liệu mới với tên định danh duy nhất (`action_gate_native_test_r4_<timestamp>`), bootstrap toàn bộ 29 migrations (062–090).

2. **Kết quả 5 Native Review Probes (`supervisor-native-r4-probes.mjs`): 5/5 PASS:**
   - `direct-pause-before-slot-versus-resume`: **PASS** (Conn B `resume_campaign` chờ transaction lock của Conn A đang tạm dừng; sau khi Conn A release, resume thành công trạng thái `open`).
   - `delete-gate-before-physical-delete-versus-create-v2`: **PASS** (Conn B `create_campaign_v2` chờ advisory lock `campaign_slot_guard` của Conn A trước; xác nhận `wait_event = 'advisory'`; sau release hoàn tất xóa vật lý và tạo mới).
   - `delete-refund-before-slot-versus-create-v2`: **PASS** (giữ Conn A tại barrier sau khi đã hoàn 500 credits escrow và trước UPDATE closed/pending_removal. Conn B `create_campaign_v2` chờ slot của A, `wait_event = 'advisory'`, thay vì chờ profile. Kiểm tra đúng số dư và replay xóa trả `replayed = true`).
   - `accepted-removal-worker-after-new-today-obligation`: **PASS** (worker thật dưới role `service_role` hoàn tất yêu cầu `pending_removal` đã được chấp thuận khi chỉ tiêu ngày của chủ app tăng và chưa hoàn thành; không hoàn tiền trùng, tạo receipt hợp lệ. Ca này không giả lập sang ngày hôm sau).
   - `real-finalizer-profile-lock-versus-owner-resume`: **PASS** (Hàm chốt sổ thật `finalize_daily_tasks_for_date` giữ khóa profile, cạnh tranh với `resume_campaign` của chủ app; hoàn thành an toàn không xung đột).

3. **Kết quả 7 kịch bản tài chính Native Bonus Concurrency (`run-campaign-tester-bonus-concurrency-test.mjs`): 7/7 PASS:**
   - Kịch bản 1: Hai app của cùng chủ sở hữu đặt trước số dư ví đồng thời: **PASS**.
   - Kịch bản 2: Tranh chấp slot cuối cùng (1 slot, 2 phê duyệt đồng thời): **PASS**.
   - Kịch bản 3: Phê duyệt thủ công vs tự động duyệt sau 8h trên cùng báo cáo: **PASS**.
   - Kịch bản 4: Phê duyệt báo cáo chờ thật vs đóng đợt thưởng: **PASS**.
   - Kịch bản 5: Nộp báo cáo vs đóng đợt thưởng (gated actual submit vs close): **PASS**.
   - Kịch bản 6: Batch closing worker vs đóng vòng đời chiến dịch: **PASS**.
   - Kịch bản 7: Nộp lại báo cáo bị từ chối trong đợt đang hoạt động bảo toàn snapshot: **PASS**.

4. **Đóng tiến trình sạch sẽ (Cleanup):**
   - Đóng toàn bộ kết nối client.
   - Dừng cụm database an toàn qua `pg_ctl stop -m fast -w -t 10`, exit code 0 (`gracefulStop = true`).
   - Cổng 55444 được giải phóng hoàn toàn (`ECONNREFUSED`).

---

### D. Kiểm thử đối chứng Before / After (Negative Control)
1. **Mục đích:** Thực thi bộ kiểm thử độc lập đối với mã nguồn snapshot `090_daily_task_action_gate.sql` trước khi sửa R4 trong một database độc lập mới để chứng minh các kịch bản kiểm tra thực sự phát hiện lỗi serialization và không bị false-pass.
2. **Thực thi:** Chạy `node audit/daily-task-action-gate-2026-11-12/supervisor-native-r4-before-runner.mjs`.
3. **Kết quả đối chứng:**
   - Trạng thái chung: `pass = false`, `exit code = 1`, `expectedCounterexample = true`.
   - **Case 1 (`direct-pause-before-slot-versus-resume`):** PASS (hành vi cơ bản đã đúng trước R4).
   - **Case 2 (`delete-gate-before-physical-delete-versus-create-v2`):** **FAIL** (`Expected backend 6244 to wait for 25392` - bản cũ chưa giữ slot từ trước gate, cho phép create không chờ luồng gỡ).
   - **Case 3 (`delete-refund-before-slot-versus-create-v2`):** **FAIL** (`Create must wait on slot BEFORE owner profile, actual 'transactionid', expected 'advisory'` - nghịch đảo thứ tự khóa profile->slot trước R4).
   - **Case 4 (`accepted-removal-worker-after-new-today-obligation`):** Không đủ tiền đề để nghiệm thu vì Case 3 thất bại; artifact ghi `pass = false`. Không tính đây là lỗi sản phẩm thứ ba.
   - **Case 5 (`real-finalizer-profile-lock-versus-owner-resume`):** PASS.
4. **Kết luận đối chứng:** Bộ kiểm thử phản ánh chính xác sự khác biệt trước và sau khi sửa R4; toàn bộ 5/5 ca đều PASS trên bản R4 hiện tại trong khi bản cũ thất bại đúng 2 điểm nghẽn then chốt đã phân tích.

---

## 2. Bảng tổng hợp kiểm thử nghiệm thu R4

| Thành phần kiểm thử | Phạm vi / Lệnh thực thi | Kết quả | Ghi chú |
|---|---|---|---|
| **Android Unit Tests** | `.\gradlew testDebugUnitTest` | **750/750 PASS** (95 suites, 0 fail, 0 err, 0 skip) | Bao gồm 8 ca review, 5 ca cache, 8 ca behavioral; giữ các assertion |
| **Backend Regression** | `node supervisor-r3-backend-regressions.mjs` | **11/11 PASS** (11 script suites) | Acceptance 90 ca, receipts, admin contract, quota, carryover, egress |
| **Native PG Review Probes** | `node supervisor-native-r4-runner.mjs` | **5/5 PASS** | Barrier advisory locks, slot serialization, worker dọn dẹp, finalizer |
| **Native Financial Concurrency** | Runner native trong DB `action_gate_native_test_r4_*` | **7/7 PASS** | Toàn bộ 7 kịch bản tài chính native bonus concurrency |
| **Before/After Control** | `node supervisor-native-r4-before-runner.mjs` | **expectedCounterexample = true** | Tái hiện chính xác 2 điểm lỗi trước R4; chứng minh test không false-pass |
| **Bảo toàn Baseline** | `node supervisor-observe-changes.mjs --r2` | **0 package touched, 0 protected changed** | Version 1.2.5, build 45, 7 file APK/AAB baseline, migrations 062-089 bảo toàn |

---

## 3. Các giới hạn và phạm vi áp dụng thực tế
1. **Môi trường cơ sở dữ liệu:** Các kiểm thử native PostgreSQL và PGlite được thực hiện trên môi trường cục bộ nhiệm vụ. Migrations `089` và `090` chưa được áp dụng trực tiếp lên cơ sở dữ liệu Supabase Production thực tế.
2. **Phạm vi Concurrency:** Kết quả áp dụng cho các tình huống đã chạy và đối chứng; chưa kiểm thử tải hay mọi tổ hợp xen kẽ có thể xảy ra.
3. **Ứng dụng di động:** Không thực hiện đóng gói APK/AAB mới và không thực hiện kiểm thử tự động End-to-End trên thiết bị vật lý trong đợt kiểm tra này.

## 4. Đối chiếu cuối của Supervisor

Supervisor xác nhận mã hiện tại khớp các lượt Android, backend và native đã đạt; các assertion reviewer được giữ nguyên. Đối chứng bản cũ thất bại đúng hai ca serialization, trong khi bản R4 đạt; cả hai lượt PostgreSQL đều dừng bình thường. Không sửa mã sản phẩm sau lượt đạt.

Báo cáo đã được supervisor rà lại, chỉnh mô tả thứ tự hoàn escrow/barrier, phạm vi chờ khóa, ý nghĩa ca phụ thuộc và giới hạn kiểm thử. [Bằng chứng cuối](F:/AI/12Tester/audit/daily-task-action-gate-2026-11-12/SUPERVISOR_FINAL_EVIDENCE_R4.json), [danh sách file thay đổi](F:/AI/12Tester/audit/daily-task-action-gate-2026-11-12/SUPERVISOR_FINAL_SOURCE_CHANGES_R4.json), và [báo cáo bàn giao](F:/AI/12Tester/audit/daily-task-action-gate-2026-11-12/IMPLEMENTATION_RESULT.md) được lưu trong audit của nhiệm vụ.
