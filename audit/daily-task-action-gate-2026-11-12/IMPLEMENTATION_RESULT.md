# Báo cáo kết quả triển khai — Chặn thao tác App khi chưa xong nhiệm vụ ngày (Daily Task Action Gate — R4 Final)

**Ngày báo cáo:** 14/11/2026  
**Workspace:** `F:/AI/12Tester`  
**Model sử dụng:** Gemini 3.8 Flash High (effort high)  
**Phiên bản ứng dụng:** **1.2.5**, build **45** (bảo toàn cấu hình của người dùng; toàn bộ 7 file APK/AAB baseline được giữ nguyên vẹn).  
**Trạng thái nghiệm thu:** Hoàn tất kiểm tra R4 trên mã nguồn hiện tại và các kịch bản đã thực thi; không còn lỗi đã biết trong phạm vi này. Bằng chứng được supervisor đối chiếu tại `SUPERVISOR_FINAL_EVIDENCE_R4.json`; giới hạn ở mục 4.

---

## 1. Mục tiêu và phạm vi triển khai

Triển khai và hoàn thiện cơ chế **Daily Task Action Gate** theo tài liệu kỹ thuật `PROMPT_ANTIGRAVITY_CHAN_THAO_TAC_APP_2026-11-12.md`, kế hoạch `KE_HOACH_CHAN_THAO_TAC_APP_KHI_CHUA_XONG_NHIEM_VU_2026-11-12.md`, tài liệu thứ tự khóa `LOCK_ORDER.md` cùng các chỉ dẫn nghiệm thu từ Supervisor (R1, R2, R3, R4):

1. **Áp dụng kiểm tra nhiệm vụ tại máy chủ cho 4 thao tác của chủ sở hữu:**
   - Xóa app / yêu cầu gỡ bỏ (`delete_owned_campaign`, direct DELETE trên bảng `campaigns`).
   - Xác nhận đưa app lên production (`confirm_campaign_production`, direct UPDATE sang trạng thái `production`).
   - Bật lại app đang tạm dừng (`resume_campaign`, bao gồm cả nhánh app quá hạn 30 ngày).
   - Tạm dừng thủ công app đang hoạt động (`owner_manual` từ `open` hoặc `auto_paused`), thao tác làm giảm chỉ tiêu kiểm thử ngày.

2. **Tiêu chuẩn kiểm tra Canonical Gate:**
   - Cả hai cờ dữ liệu `history_ready = true` và `carryover_ready = true` phải bằng `true` (không null, không được bỏ qua).
   - `met = true` (không null, không được bỏ qua).
   - `carryover_remaining_tests = 0` (chính xác bằng 0; chặn triệt để trường hợp thiếu trường hoặc nợ âm).
   - Ngày kinh doanh của task phải khớp chính xác ngày hôm nay theo múi giờ Việt Nam (`Asia/Ho_Chi_Minh`).
   - Tài khoản chủ app không bị khóa (`is_locked != true`).
   - Quyền hạn Admin: Khi Admin thao tác quản trị trên app của người khác, được phép vượt qua (bypass) kiểm tra nhiệm vụ của chủ app; khi Admin tự thao tác trên chính app của mình, vẫn phải tuân thủ Action Gate.

---

## 2. Chi tiết các giải pháp kỹ thuật đã triển khai trong đợt R4

### A. Android Client
1. **Khắc phục Race Condition trong Full Fallback Refresh (`OwnerAppDetailViewModel.kt`):**
   - Di chuyển toàn bộ logic tính toán `shouldApplyDaily` và cập nhật biến đếm đơn điệu `latestAppliedTaskSeq.updateAndGet { current -> maxOf(current, taskSeq) }` xuống vị trí **sau** khi tất cả các lời gọi mạng bất đồng bộ (`getProfiles`, `getMySlotsInfo`, `getPushQuote`) đã hoàn tất.
   - Kiểm tra UID, generation, ngày nghiệp vụ và mã yêu cầu của lượt refresh trước khi cập nhật sequence và áp dữ liệu.
   - Đảm bảo một lần refresh cũ dù hoàn thành sau cũng không thể ghi đè biến boolean cũ để kích hoạt lại trạng thái hợp lệ (Allowed) khi màn hình đã có trạng thái mới chưa đạt (Unmet).
   - Bổ sung ca kiểm thử hành vi `fallbackRefreshDoesNotOverwriteNewerUnmetTaskAfterAwaits` với barrier tại `get_business_quote` trong `OwnerAppDetailActionGateBehavioralTest.kt`.

2. **Khắc phục lỗi bịa số nhiệm vụ khi diff không dương (`DailyTaskActionGate.kt`):**
   - Loại bỏ hoàn toàn phương thức `.coerceAtLeast(1)`.
   - Tính toán chênh lệch nhiệm vụ rõ ràng: `knownRemaining = if (req != null && comp != null && req > comp) req - comp else null`.
   - Chỉ áp dụng chuỗi có định dạng `%1$d` (`R.string.oad_gate_error_unmet_daily_task_format`) khi `knownRemaining > 0`.
   - Khi `knownRemaining == null` hoặc `<= 0`, hiển thị chuỗi thông báo tổng quát không chứa tham số định dạng `R.string.oad_gate_error_unmet_daily_task` (`formatArg = null`), vẫn bảo đảm gate bị chặn (`isAllowed = false`).

3. **Bảo toàn giao diện và chuỗi thông báo:**
   - Bảo toàn chuỗi đa ngôn ngữ trong `values/strings.xml`, `values-en/strings.xml`, `values-vi/strings.xml`.
   - Bảo toàn các assertion của 8/8 ca `SupervisorOwnerActionGateReviewTest` và 5/5 ca `SupervisorDailyTaskStatusCacheReviewTest`.

---

### B. Backend (`supabase/migrations/090_daily_task_action_gate.sql`)
1. **Khóa tuần tự chống Deadlock và Serialization:**
   - Trong `_request_campaign_removal`: Di chuyển lệnh chiếm advisory lock `campaign_slot_guard` lên ngay sau khi xác thực quyền sở hữu và kiểm tra replay, **trước** lời gọi `_assert_owner_daily_task_action_allowed` và **trước** vòng lặp hoàn tiền escrow nhiệm vụ. Nhánh Admin xóa cũng chiếm `campaign_slot_guard` trước khi hoàn tiền.
   - Trong `confirm_campaign_production`: Chiếm `campaign_slot_guard` ngay sau khi khóa hàng campaign `FOR UPDATE` và trước `_assert_owner_daily_task_action_allowed`.
   - Trong trigger `trg_fn_campaigns_guard_action_gate_before_update`: Bổ sung chiếm `campaign_slot_guard` rõ ràng trước khi gọi gate cho cả nhánh chuyển `production` và nhánh tạm dừng thủ công `owner_manual`.
   - Trong trigger `trg_fn_campaigns_guard_action_gate_before_delete`: Chiếm `campaign_slot_guard` trước khi gọi gate.
   - Các đường thao tác campaign hiện hữu được sửa lấy khóa hàng campaign rồi slot trước gate và các thay đổi ví. Đối chứng PostgreSQL thực cho thấy bản cũ không giữ slot đúng thời điểm; bản R4 chờ slot trước profile và vượt qua các ca đã ghi. Không suy rộng thứ tự này cho mọi helper hoặc mọi lịch chạy trong hệ thống.

2. **Bảo mật biên nhận gỡ bỏ (Removal Receipts):**
   - Thu hồi quyền thực thi `_record_campaign_removal_receipt` khỏi các role `authenticated` và `anon`; chỉ cho phép `service_role`.
   - Trigger `AFTER DELETE ON public.campaigns` ghi biên nhận từ hàng `OLD` thật. Phân loại actor theo role phiên PostgreSQL, `session_user` và UID; không tin claim role do client tự khai. Các ca quyền, helper và actor thực tế đã chạy đều đạt.

3. **Khôi phục đầy đủ hợp đồng Admin Resume (Migration 078):**
   - Giữ nguyên vẹn chức năng `admin_resume_campaign` và `_impl_admin_resume_campaign` với kiểm tra `assert_unlocked_admin()`, ghi log `record_admin_action_event`, cập nhật cờ `admin_resumed`, và xóa các thông báo cảnh báo của Admin.

4. **Bảo toàn toàn vẹn cơ sở dữ liệu:**
   - Toàn bộ migrations nền từ `062` đến `089` được giữ nguyên vẹn 100%, không bị sửa đổi.

---

## 3. Tổng hợp kết quả kiểm thử thực tế

### A. Kiểm thử Android Client (750/750 Tests PASS across 95 Suites)
Lệnh thực thi: `.\gradlew testDebugUnitTest`  
Kết quả lượt full được supervisor quan sát: **BUILD SUCCESSFUL in 51s**.  
- Tổng số kiểm thử: **750 tests**, **0 failures**, **0 errors**, **0 skipped**.
- `SupervisorOwnerActionGateReviewTest`: **8/8 PASS**.
- `SupervisorDailyTaskStatusCacheReviewTest`: **5/5 PASS**.
- `OwnerAppDetailActionGateBehavioralTest`: **8/8 PASS** (bao gồm ca R4 barrier mới).
- `DailyTaskActionGateUnitTest`: **13/13 PASS**.
- `OwnerAutoPauseResumeHardeningTest`: **9/9 PASS**.

### B. Kiểm thử Backend Regression (11/11 Scripts PASS)
Lệnh thực thi: `node audit/daily-task-action-gate-2026-11-12/supervisor-r3-backend-regressions.mjs`  
Kết quả: **PASS 11/11 backend verification scripts**  
1. `supervisor-action-gate-acceptance.mjs` (`--bonus --payloads --review --roles --r2`): **PASS (90/90)**.
2. `supervisor-receipt-authorization-test.mjs`: **PASS (3/3)**.
3. `supervisor-receipt-helper-context-test.mjs`: **PASS (2/2)**.
4. `supervisor-receipt-attribution-test.mjs`: **PASS (8/8)**.
5. `supervisor-admin-resume090-test.mjs`: **PASS (5/5)**.
6. `supervisor-production090-regression.mjs`: **PASS**.
7. `supervisor-auto-pause090-regression.mjs`: **PASS (Thresholds 1-7, Tests 2-8)**.
8. `run-owner-tester-bonus-removal-test.mjs`: **PASS**.
9. `run-immediate-daily-quota-acceptance-test.mjs`: **PASS**.
10. `run-carryover-fix-acceptance-test.mjs`: **PASS**.
11. `run-egress-sync-v2-test.mjs`: **PASS**.

### C. Kiểm thử đồng thời Native PostgreSQL 17.11 (5/5 Review Probes + 7/7 Financial Concurrency PASS)
Lệnh thực thi: `node audit/daily-task-action-gate-2026-11-12/supervisor-native-r4-runner.mjs`  
Môi trường: PostgreSQL 17.11 x86_64-windows trên cổng `55444`, cụm dữ liệu nhiệm vụ `audit/daily-task-action-gate-2026-11-12/pg-data`.  
Kết quả: **PASS** (`pass = true`, `error = null`).  

1. **5 Native Review Probes (`supervisor-native-r4-probes.mjs`):**
   - `direct-pause-before-slot-versus-resume`: **PASS**
   - `delete-gate-before-physical-delete-versus-create-v2`: **PASS**
   - `delete-refund-before-slot-versus-create-v2`: **PASS**
   - `accepted-removal-worker-after-new-today-obligation`: **PASS**
   - `real-finalizer-profile-lock-versus-owner-resume`: **PASS**

2. **7 Kịch bản tài chính Native Bonus Concurrency:**
   - Kịch bản 1: Hai app của cùng chủ sở hữu đặt trước số dư ví đồng thời: **PASS**
   - Kịch bản 2: Tranh chấp slot cuối cùng (1 slot, 2 phê duyệt đồng thời): **PASS**
   - Kịch bản 3: Phê duyệt thủ công vs tự động duyệt sau 8h trên cùng báo cáo: **PASS**
   - Kịch bản 4: Phê duyệt báo cáo chờ thật vs đóng đợt thưởng: **PASS**
   - Kịch bản 5: Nộp báo cáo vs đóng đợt thưởng (gated actual submit vs close): **PASS**
   - Kịch bản 6: Batch closing worker vs đóng vòng đời chiến dịch: **PASS**
   - Kịch bản 7: Nộp lại báo cáo bị từ chối trong đợt đang hoạt động bảo toàn snapshot: **PASS**

3. **Cleanup:** Dừng tiến trình sạch sẽ qua `pg_ctl stop -m fast -w -t 10`, exit code 0 (`gracefulStop = true`). Cổng 55444 giải phóng an toàn.

### D. Kiểm thử đối chứng Before / After (Negative Control)
Lệnh thực thi: `node audit/daily-task-action-gate-2026-11-12/supervisor-native-r4-before-runner.mjs`  
Kết quả: `pass = false`, `exit code = 1`, `expectedCounterexample = true`.  
- Xác nhận bản cũ snapshot 090 thất bại chính xác tại:
  - Case 2 (`delete-gate-before-physical-delete-versus-create-v2`): Bản cũ chưa giữ slot từ trước gate, cho phép create không chờ luồng gỡ.
  - Case 3 (`delete-refund-before-slot-versus-create-v2`): Lỗi nghịch đảo thứ tự khóa profile->slot dẫn đến chờ trên `transactionid` thay vì `advisory`.
- Case 1 và Case 5 vẫn PASS. Case 4 không đủ tiền đề do Case 3 thất bại, không được tính là lỗi sản phẩm riêng.
- Chứng minh các ca kiểm thử native là có hiệu lực thực tế, không bị false-pass.

### E. Giám sát tính toàn vẹn Baseline
Lệnh thực thi: `node audit/daily-task-action-gate-2026-11-12/supervisor-observe-changes.mjs --r2`  
- `changedPackages`: 0 (Không thay đổi các gói APK/AAB baseline).
- `newPackages`: 0 (Không tạo file APK/AAB mới).
- `protectedChanged`: 0 (Toàn bộ migrations 062-089 giữ nguyên vẹn).
- `newMigrations`: `["090_daily_task_action_gate.sql"]`.

---

## 4. Các giới hạn và ràng buộc kỹ thuật
1. **Phạm vi triển khai Database:** Toàn bộ kiểm thử cơ sở dữ liệu được thực hiện trên môi trường cục bộ nhiệm vụ (PGlite và cụm Native PostgreSQL 17.11). Migrations `089` và `090` chưa được áp dụng trực tiếp lên cơ sở dữ liệu Supabase Production thực tế.
2. **Phạm vi Concurrency:** Kết quả áp dụng cho các tình huống đã chạy và đối chứng; chưa kiểm thử tải hay mọi tổ hợp xen kẽ có thể xảy ra. Không có bằng chứng tái hiện mã lỗi 40P01 trong đối chứng này: đối chứng phát hiện sai thứ tự chờ khóa và thiếu serialization, rồi hủy các truy vấn thử để dọn dẹp.
3. **Phạm vi ứng dụng di động:** Không xây dựng tệp cài đặt (APK/AAB) mới và không thực hiện kiểm thử tự động End-to-End trên thiết bị vật lý trong đợt nghiệm thu này.

## 5. Danh sách thay đổi và bằng chứng bàn giao

Danh sách file ứng dụng và migration thay đổi so với mốc R2 được lưu tại [SUPERVISOR_FINAL_SOURCE_CHANGES_R4.json](F:/AI/12Tester/audit/daily-task-action-gate-2026-11-12/SUPERVISOR_FINAL_SOURCE_CHANGES_R4.json). Mốc này giữ các thay đổi phiên bản và gói cài đặt riêng mà người dùng đã xác nhận.

| Nhóm file | Nội dung |
|---|---|
| `CampaignRepository.kt`, `Models.kt` | Gọi RPC thao tác, tải trạng thái mới và bảo toàn các cờ canonical khi lưu/đọc cache |
| `OwnerAppDetailScreen.kt`, `OwnerAppDetailViewModel.kt`, `DailyTaskActionGate.kt` | Dùng chung điều kiện thao tác, lý do chặn, kiểm tra lại khi xác nhận và bảo vệ phản hồi cũ/bấm lặp |
| `values/strings.xml`, `values-en/strings.xml`, `values-vi/strings.xml` | Thông báo nhiệm vụ, nợ, dữ liệu chưa sẵn sàng và số còn thiếu |
| `DailyTaskActionGateUnitTest.kt`, `OwnerAutoPauseResumeHardeningTest.kt` | Kiểm tra gate và cập nhật kỳ vọng thông báo, giữ kiểm tra không gọi RPC khi bị chặn |
| `SupervisorDailyTaskStatusCacheReviewTest.kt`, `OwnerAppDetailActionGateBehavioralTest.kt`, `SupervisorOwnerActionGateReviewTest.kt` | Kiểm tra cache, hành vi ViewModel, phiên/ngày/thứ tự phản hồi và trường hợp tải dự phòng R4 |
| `090_daily_task_action_gate.sql` | Gate máy chủ, quyền, biên nhận, khóa và đồng bộ quota; không sửa 062–089 |

Các chương trình kiểm thử và nhật ký nằm trong thư mục audit của nhiệm vụ. Bằng chứng chính:

- [Đối chiếu nguồn và kết quả của supervisor](F:/AI/12Tester/audit/daily-task-action-gate-2026-11-12/SUPERVISOR_FINAL_EVIDENCE_R4.json).
- [Báo cáo kiểm tra cuối](F:/AI/12Tester/audit/daily-task-action-gate-2026-11-12/SUPERVISOR_FINAL_REVIEW_R4_2026-11-14.md).
- [Native bản đã sửa](F:/AI/12Tester/audit/daily-task-action-gate-2026-11-12/supervisor-runs/native-r4-2026-11-13T19-01-31-648Z/results.json).
- [Đối chứng bản trước R4](F:/AI/12Tester/audit/daily-task-action-gate-2026-11-12/supervisor-runs/native-r4-before-2026-11-13T19-11-58-612Z/results.json).

## 6. Thứ tự release và kiểm tra sau triển khai

Các bước dưới đây là hướng dẫn cho quy trình release; chưa được thực hiện trên production trong nhiệm vụ này.

1. Kiểm tra lịch sử migration của môi trường đích. Áp dụng các migration chưa có theo số tăng dần; **089 phải đứng trước 090**. Giữ nguyên các file migration nền và lưu kết quả áp dụng vào hồ sơ release.
2. Trên môi trường thử nghiệm của release, xác minh các thao tác gỡ app, production, tiếp tục và tạm dừng thủ công với nhiệm vụ chưa đủ/đã đủ/còn nợ. Kiểm tra client cũ gọi trực tiếp, quyền admin/hệ thống, cập nhật quota, cache và việc gọi lại không hoàn quỹ hai lần. Nếu phát hành bản cài đặt, cần kiểm tra giao diện trên thiết bị ở quy trình đó.
3. Sau triển khai, đối chiếu trạng thái nhiệm vụ canonical, biên nhận và số liệu vận hành; theo dõi lỗi RPC, quyền và chờ khóa/deadlock theo quy trình giám sát sẵn có. Không dùng kết quả kiểm thử cục bộ này để kết luận production đã được áp dụng hay đã kiểm thử.
