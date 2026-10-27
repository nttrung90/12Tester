# BÁO CÁO SỬA LỖI CÒN SÓT LUỒNG NHIỆM VỤ APP (R01 - R07)

*Ngày thực hiện: 27/10/2026*  
*Người thực hiện: Antigravity (Pair Programming)*  
*Căn cứ tài liệu: F:\AI\12Tester\RECHECK_LUONG_NHIEM_VU_APP_2026-10-27.md*  
*Mục tiêu: Xử lý dứt điểm 6 lỗi còn sót (R01, R02, R03, R04, R05, R07) và quan sát phụ R06; chạy kiểm thử thực tế và xác minh toàn bộ probe đạt 100% PASS.*

---

## 1. TỔNG QUAN KẾT QUẢ SỬA LỖI

| Mã lỗi | Mức độ | Mô tả lỗi | Trạng thái | Giải pháp kỹ thuật đã triển khai |
| :--- | :--- | :--- | :--- | :--- |
| **R01** | P1 | Mở màn tạo nhiệm vụ crash `NullPointerException` ngay trong constructor | **ĐÃ SỬA** | Đưa khai báo `loadSequence = AtomicLong(0L)` lên trước khối `init` trong `MissionCreateViewModel.kt`. |
| **R02** | P1 | Màn quản lý app của chủ crash `NullPointerException` khi chạy trên Main.immediate | **ĐÃ SỬA** | Đưa khai báo `missionLoadSequence = AtomicLong(0L)` lên đầu class trước khối `init` trong `OwnerAppDetailViewModel.kt`. |
| **R03** | P1 | Migration 044 thu hồi quyền RPC `auto_accept_expired_reports` mà Android đang gọi dự phòng | **ĐÃ SỬA** | Cấp lại `GRANT EXECUTE` cho vai trò `authenticated, service_role` trong `044_mission_flow_hardening.sql` và `full_schema_singapore.sql`. |
| **R04** | P1 | Nâng cấp 044 tự dừng nhiệm vụ cũ bằng lý do hết Credit sai (`stopped_no_credits`), thiếu audit và thông báo | **ĐÃ SỬA** | Thiết kế lại preflight: Ưu tiên giữ mission có tester đang tham gia; mission trùng lặp chuyển sang `expired` (không gán thiếu tiền); lưu audit vào bảng `mission_reconciliation_events`; gửi thông báo cho chủ app và tester bị ảnh hưởng. |
| **R05** | P1 | Job không đủ điều kiện (paused/expired) làm nghẽn vĩnh viễn hàng đợi retry các job mới | **ĐÃ SỬA** | Bổ sung `next_attempt_at` và cơ chế backoff cho `mission_settlement_jobs`; job của mission tạm dừng (`paused_by_creator`) được lùi lịch 5 phút; job kết thúc chuyển `skipped`; query retry chỉ lấy job đến hạn `next_attempt_at <= now()`. |
| **R06** | Quan sát | `auto_accept_expired_reports` để cờ `app.suppress_report_notify=1` kéo dài | **ĐÃ SỬA** | Bổ sung lệnh reset cờ về `'0'` trước khi hàm trả về. |
| **R07** | P2 | Response danh sách cũ tải trước join ghi đè trạng thái đã join; thiếu guard mutation tương tự | **ĐÃ SỬA** | Thêm `missionMutationVersion`, tăng sequence và version khi join thành công hoặc ALREADY_JOINED; response cũ bị đánh dấu stale và hủy bỏ; đồng bộ nguyên tắc sequence cho pause, resume, delete và create. |

---

## 2. CHI TIẾT THAY ĐỔI THEO TỪNG VẤN ĐỀ

### 2.1. R01 & R02 — Sửa thứ tự khởi tạo thuộc tính trong ViewModel (Android)
- **Files sửa:**
  - `app/src/main/java/com/ntt/crosstest/ui/viewmodels/MissionCreateViewModel.kt`
  - `app/src/main/java/com/ntt/crosstest/ui/viewmodels/OwnerAppDetailViewModel.kt`
- **Nguyên nhân gốc:**
  - Trong Kotlin, các property initializer và khối `init` chạy theo thứ tự xuất hiện văn bản (textual order). Khi khối `init` kích hoạt hàm tải dữ liệu ngay lập tức (`loadInitialData()`, hoặc coroutine chạy ngay trên `Dispatchers.Main.immediate`), các trường `AtomicLong` (`loadSequence` và `missionLoadSequence`) nằm phía sau khối `init` nên có giá trị `null`, gây văng ứng dụng với lỗi `NullPointerException: Cannot invoke AtomicLong.incrementAndGet() because this.loadSequence is null`.
- **Giải pháp:**
  - Trong `MissionCreateViewModel.kt`: Di chuyển `private val loadSequence = java.util.concurrent.atomic.AtomicLong(0L)` lên trước khối `init`.
  - Trong `OwnerAppDetailViewModel.kt`: Di chuyển `private val missionLoadSequence = java.util.concurrent.atomic.AtomicLong(0L)` lên dòng 61 (cùng vị trí với `refreshSequence`), xóa bỏ khai báo trùng lặp ở cuối file.
  - Bổ sung kiểm tra `CancellationException` trong các khối catch coroutine.

### 2.2. R03 — Thống nhất quyền gọi RPC tự duyệt giữa Android và Backend
- **Files sửa:**
  - `supabase/migrations/044_mission_flow_hardening.sql`
  - `supabase/full_schema_singapore.sql`
- **Nguyên nhân gốc:**
  - Migration 044 trước đây thực hiện:
    `revoke execute on function public.auto_accept_expired_reports() from public, anon, authenticated;`
    `grant execute on function public.auto_accept_expired_reports() to service_role;`
    Làm Android gọi RPC bằng phiên đăng nhập (`authenticated`) bị Postgres chặn với lỗi `permission denied`. Do code Android catch lỗi và trả về 0, cơ chế tự duyệt fallback khi không có `pg_cron` bị vô hiệu hóa trong im lặng.
- **Giải pháp:**
  - Khôi phục quyền gọi cho người dùng đã đăng nhập:
    ```sql
    revoke execute on function public.auto_accept_expired_reports() from public, anon;
    grant execute on function public.auto_accept_expired_reports() to authenticated, service_role;
    ```
  - Trong khi đó, helper thanh toán retry nội bộ `retry_failed_mission_settlements` vẫn được giới hạn chặt chẽ chỉ cấp quyền cho `service_role` (hàm `auto_accept_expired_reports` là `SECURITY DEFINER` nên tự động thực thi được retry mà không cần mở quyền trực tiếp cho client).

### 2.3. R04 — Hòa giải xung đột nhiệm vụ trùng lặp trong migration nâng cấp
- **Files sửa:**
  - `supabase/migrations/044_mission_flow_hardening.sql`
  - `supabase/full_schema_singapore.sql`
- **Nguyên nhân gốc:**
  - Migration 044 trước đây tự ý đổi toàn bộ mission cũ hơn sang `stopped_no_credits`, khiến UI hiển thị sai rằng chủ app hết tiền, làm mất quyền lợi của tester đang tham gia và không có thông báo hay audit.
- **Giải pháp:**
  1. Tạo bảng audit chuyên biệt: `public.mission_reconciliation_events`.
  2. Đoạn preflight hòa giải trước khi tạo unique index:
     - Quét các campaign có nhiều hơn 1 mission active (`open`, `full`, `paused_by_creator`).
     - **Thuật toán ưu tiên giữ lại (winner):** Ưu tiên mission có số tester đang tham gia hợp lệ (`joined`) nhiều nhất; nếu bằng nhau thì giữ mission tạo mới nhất.
     - **Mission trùng lặp bị đóng:** Chuyển trạng thái sang `expired` (kèm `ends_at = least(ends_at, now())`), **tuyệt đối không gán `stopped_no_credits`** và không trừ/hoàn tiền trái chính sách.
     - **Ghi nhận Audit & Bắn thông báo:** Lưu sự kiện hòa giải vào `mission_reconciliation_events` và tạo thông báo trong bảng `notifications` cho chủ app cùng các tester liên quan.

### 2.4. R05 — Chống Starvation trong hàng đợi Retry trả thưởng nhiệm vụ
- **Files sửa:**
  - `supabase/migrations/044_mission_flow_hardening.sql`
  - `supabase/full_schema_singapore.sql`
- **Nguyên nhân gốc:**
  - Khi một mission bị chủ app tạm dừng (`paused_by_creator`), hoặc mission hết hạn/đã xóa, job trả thưởng cũ trong `mission_settlement_jobs` bị trả về `NO_ELIGIBLE_MISSION` nhưng không cập nhật `attempt_count` hay lịch retry. Job này tiếp tục đứng đầu danh sách theo `created_at asc`, chiếm trọn batch và chặn đứng toàn bộ các job retry mới hợp lệ khác.
- **Giải pháp:**
  1. Thêm cột `next_attempt_at timestamptz not null default now()` và index retry:
     `idx_mission_settlement_jobs_retry on public.mission_settlement_jobs (next_attempt_at asc, created_at asc) where status in ('pending', 'failed')`.
  2. Trong `_settle_mission_reward(p_report_id)`:
     - Khi retry một job đã tồn tại, kiểm tra trạng thái mission và participant theo thông tin identity đã lưu của job.
     - Nếu mission đã hết hạn hoặc participant đã hoàn thành: Chuyển job sang trạng thái kết thúc `status = 'skipped'` kèm `skip_reason`.
     - Nếu mission đang tạm dừng (`paused_by_creator`): Tăng `attempt_count = attempt_count + 1`, lùi lịch retry `next_attempt_at = now() + interval '5 minutes'`, ghi nhận `last_error = 'MISSION_PAUSED_BY_CREATOR'`.
     - Nếu lỗi kỹ thuật (DB/trigger): Tăng `attempt_count`, áp dụng exponential backoff cho `next_attempt_at`.
  3. Trong `retry_failed_mission_settlements`:
     - Query chọn: `where status in ('pending', 'failed') and attempt_count < 10 and next_attempt_at <= now() order by next_attempt_at asc, created_at asc limit p_limit`.
     - Các job bị paused hoặc lỗi sẽ tự động nhường chỗ cho các job mới đến hạn, chấm dứt hoàn toàn hiện tượng nghẽn hàng đợi (starvation).

### 2.5. R06 — Reset cờ Suppress Report Notification
- **Files sửa:**
  - `supabase/migrations/044_mission_flow_hardening.sql`
  - `supabase/full_schema_singapore.sql`
- **Giải pháp:**
  - Ở cuối hàm `auto_accept_expired_reports()`, trước khi `return`, bổ sung:
    `perform set_config('app.suppress_report_notify', '0', true);`
    Đảm bảo cờ cấu hình transaction-local được dọn sạch sẽ.

### 2.6. R07 — Phối hợp Token phiên & Chống Race Condition khi Mutation
- **Files sửa:**
  - `app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt`
  - `app/src/main/java/com/ntt/crosstest/ui/viewmodels/HomeViewModel.kt`
  - `app/src/main/java/com/ntt/crosstest/ui/viewmodels/OwnerAppDetailViewModel.kt`
  - `app/src/main/java/com/ntt/crosstest/ui/viewmodels/MissionCreateViewModel.kt`
- **Giải pháp:**
  1. Trong `CampaignRepository.kt`:
     - Trong `getOpenMissions()`, bổ sung kiểm tra `mutVer == getMutationVersion("quests")` trước khi ghi đè vào `cachedOpenMissions`, ngăn response danh sách cũ ghi đè bộ đệm sau khi đã có mutation join.
  2. Trong `HomeViewModel.kt`:
     - Thêm `private val missionMutationVersion = AtomicLong(0L)`.
     - Khi `refreshMissions()` khởi chạy: lưu lại snapshot `initialMutation = missionMutationVersion.get()`.
     - Khi `joinMission()` thành công hoặc gặp `ALREADY_JOINED`: thực hiện `missionMutationVersion.incrementAndGet()` và `missionRefreshSequence.incrementAndGet()`, đồng thời cập nhật `isMissionsLoading = false`.
     - Response danh sách đang bay từ trước thời điểm join khi hoàn thành sẽ kiểm tra thấy sequence / mutation version không còn khớp, tự động bỏ qua (`discard`), không ghi đè trạng thái `hasJoined = true` đã commit.
  3. Trong `OwnerAppDetailViewModel.kt` & `MissionCreateViewModel.kt`:
     - Áp dụng cơ chế sequence bump tương tự khi `togglePauseResumeMission`, `deleteMission` và thêm guard phiên người dùng cho `createMission()`.

---

## 3. DANH SÁCH FILE THAY ĐỔI

1. `app/src/main/java/com/ntt/crosstest/ui/viewmodels/MissionCreateViewModel.kt`: Sửa R01 (vị trí `loadSequence`), guard phiên trong `create()`.
2. `app/src/main/java/com/ntt/crosstest/ui/viewmodels/OwnerAppDetailViewModel.kt`: Sửa R02 (vị trí `missionLoadSequence`), sequence bump cho pause/resume/delete.
3. `app/src/main/java/com/ntt/crosstest/ui/viewmodels/HomeViewModel.kt`: Sửa R07 (mutation version, sequence coordination, chống ghi đè sau join).
4. `app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt`: Sửa R07 (kiểm tra `mutVer` trước khi cache `getOpenMissions`).
5. `supabase/migrations/044_mission_flow_hardening.sql`: Sửa R03, R04, R05, R06 (`mission_reconciliation_events`, outbox backoff, `next_attempt_at`, quyền RPC, reset flag).
6. `supabase/full_schema_singapore.sql`: Đồng bộ nguyên văn toàn bộ nội dung sửa đổi của 044 sau marker chuẩn.
7. `supabase/tests/run-mission-flow-hardening.mjs`: Bộ kiểm thử nâng cấp từ schema baseline 043 với dữ liệu trùng, kiểm tra R03, R04, R05 (chống starvation 50 job), R06.
8. `.tmp/mission-recheck/database-probes.mjs`: Đảo toàn bộ 4 probe database sang assert hành vi đúng.
9. `.tmp/mission-recheck/database-results.json`: Ghi nhận kết quả thực thi probe database thực tế.

---

## 4. KẾT QUẢ KIỂM THỬ THẬT TẾ

### 4.1. Bộ kiểm thử Android (Unit Tests & Probes)
- **Lệnh thực thi:**
  `.\gradlew.bat -I .tmp/mission-recheck/recheck.init.gradle :app:testDebugUnitTest --console=plain`
- **Kết quả:** `BUILD SUCCESSFUL in 9s`
- **Chi tiết kết quả (File XML `TEST-com.ntt.crosstest.data.MissionRecheckProbeTest.xml`):**
  - Số lượng test: **66 tests completed, 0 failures, 0 errors, 0 skipped (100% PASS)**.
  - `ownerScreenMustConstructOnImmediateMainWithoutNullPointer`: **PASS** (Khắc phục hoàn toàn crash R02).
  - `createMissionScreenMustConstructWithoutNullPointer`: **PASS** (Khắc phục hoàn toàn crash R01).
  - `listStartedBeforeJoinMustNotUndoSuccessfulJoin`: **PASS** (Khắc phục hoàn toàn lỗi race condition R07).

### 4.2. Bộ kiểm thử Database Probes (`database-probes.mjs`)
- **Lệnh thực thi:** `node .tmp/mission-recheck/database-probes.mjs`
- **Kết quả:** Toàn bộ 4 probe assert hành vi đúng đều **PASS 100%**:
  ```json
  [
    {
      "id": "R03",
      "name": "Android authenticated role preserves the auto-accept fallback",
      "reproduced": true,
      "evidence": { "allowed": true, "error": null }
    },
    {
      "id": "R04",
      "name": "Migration reconciles duplicate missions with proper audit, notifications, and valid status",
      "reproduced": true,
      "evidence": {
        "preservedMission": { "id": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", "status": "open", "credits": 10000, "participant_status": "joined" },
        "retiredStatus": "expired",
        "notificationsAdded": 1,
        "auditCount": 1
      }
    },
    {
      "id": "R05",
      "name": "Ineligible oldest job does not starve newer payable retries",
      "reproduced": true,
      "evidence": {
        "batch1_first": 0,
        "batch1_second": 1,
        "jobsAfterRetry": [
          { "status": "failed", "attempt_count": 2, "last_error": "MISSION_PAUSED_BY_CREATOR" },
          { "status": "completed", "attempt_count": 1, "last_error": null }
        ]
      }
    },
    {
      "id": "R06",
      "name": "Auto-accept cleanly resets report notification suppression",
      "reproduced": true,
      "evidence": { "suppression": "0" }
    }
  ]
  ```

### 4.3. Kiểm thử Nâng cấp Schema Baseline 043 & Hardening (`npm test`)
- **Lệnh thực thi:** `cd supabase/tests; npm test`
- **Kết quả:**
  ```text
  > test
  > node run-daily-task-ratio.mjs && node run-backend-cleanup-hardening.mjs && node run-mission-flow-hardening.mjs

  PASS: existing full schema through its current pre-042 content loads
  PASS: migration 042 upgrades the existing schema
  PASS: mirror equals migration 043 byte for byte
  PASS: mirror equals migration 044 byte for byte
  === TẤT CẢ 5 NHÓM KIỂM THỬ HARDENING 043 ĐỀU PASS 100% ===
  --- KHỞI TẠO PGLITE ĐỂ KIỂM THỬ REGRESSION 044 (M01-M09 & R01-R07) ---
  PASS: Nạp thành công schema baseline 043 (trước 044)
  PASS: Áp dụng thành công migration 044_mission_flow_hardening.sql trên nền 043 với dữ liệu trùng
  PASS: R04 - Hòa giải dữ liệu trùng có audit, bảo toàn số dư và giữ mission có tester
  PASS: Áp dụng lại migration 044 an toàn (idempotency)
  --- Kiểm tra M02 ---
  PASS: M02 - Pause mission chiếm giữ slot, chặn tạo trùng mission trên cùng campaign
  --- Kiểm tra M06 ---
  PASS: M06 - Tự động đồng bộ mission hết hạn dưới khóa khi tạo mới
  --- Kiểm tra M03 ---
  PASS: M03 - Báo cáo trước join không được tính thưởng; báo cáo sau join được thưởng đúng 1 lần
  --- Kiểm tra M05 & R05 ---
  PASS: M05 - Settlement failure được lưu vào outbox và retry bền vững, idempotent
  --- Kiểm tra R05: Anti-starvation với 50 job tạm dừng ---
  PASS: R05 - Cơ chế backoff ngăn 50 job tạm dừng làm nghẽn batch retry
  --- Kiểm tra R03: Quyền RPC auto_accept_expired_reports ---
  PASS: R03 - Role authenticated gọi được auto_accept_expired_reports
  --- Kiểm tra R06: Reset suppress notification flag ---
  PASS: R06 - Cờ suppress_report_notify được reset về 0 sau khi chạy
  --- Kiểm tra M07 ---
  PASS: M07 - Sắp xếp trước khi Limit 100 bảo toàn nhiệm vụ mới nhất
  --- Kiểm tra get_my_active_mission_campaign_ids ---
  PASS: get_my_active_mission_campaign_ids trả đúng danh sách campaign có mission
  --- Kiểm tra bảo toàn 043 review point 6 ---
  PASS: Bảo toàn sửa đổi 043 cho pause/resume expired
  === TẤT CẢ CÁC REGRESSION TESTS BACKEND 044 ĐỀU PASS 100% ===
  ```

---

## 5. PHẦN CÒN THIẾU VÀ LƯU Ý KHI TRIỂN KHAI

1. **Chính sách M04:**
   - Tiếp tục giữ nguyên quy tắc hiện hành: Báo cáo được duyệt sau khi nhiệm vụ đã hết hạn (`ends_at <= now()`) sẽ không nhận thưởng mission credits. Hạn chế này cần được Product Owner ra quyết định nghiệp vụ chính thức trước khi bổ sung bất kỳ logic grace period / refund nào.
2. **Triển khai Production:**
   - Migration `044_mission_flow_hardening.sql` là file local chưa deploy và chưa commit vào git. Khi tiến hành release, chỉ cần áp dụng migration 044 lên Supabase Singapore Dashboard; đoạn preflight sẽ tự động hòa giải mọi mission trùng lặp trong lịch sử mà không gây lỗi khóa Unique Index.
3. **Môi trường Client:**
   - Các unit test và probe headless bằng Robolectric / MockEngine đã chạy và PASS 100%. Kiểm thử thao tác cảm ứng người dùng trên thiết bị vật lý / Android Virtual Device thực tế sẽ được thực hiện trong đợt QA tổng thể của ứng dụng.
