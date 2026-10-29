# Báo cáo bàn giao tính năng: Cộng dồn app test còn thiếu sang ngày hôm sau

- **Dự án**: 12 Tester (`F:\AI\12Tester`)
- **Ngày thực hiện**: 29/10/2026
- **Trạng thái**: **Đã hoàn thành và kiểm thử PASS 100% tại môi trường local**. Chưa áp dụng lên Supabase live/production (tuân thủ nguyên tắc an toàn).

---

## 1. Migration đã chọn và cấu trúc dữ liệu

Do tính năng "Đẩy app" đang được triển khai song song đã sử dụng migration `052_campaign_push_priority.sql`, tính năng Cộng dồn được cấp số migration tiếp theo là **`053`**:

### File migration: `supabase/migrations/053_daily_task_carryover.sql`
1. **Mở rộng bảng `public.user_daily_tasks`**:
   - `carryover_version`: int (đánh dấu phiên bản công thức 1; NULL cho dữ liệu cũ).
   - `base_required_tests`: int (chỉ tiêu cơ bản $B = \text{tỷ lệ} \times K$).
   - `carry_in_tests`: int (số app còn thiếu chuyển vào từ ngày trước $C$).
   - `carry_out_tests`: int (số app còn thiếu chuyển sang ngày sau sau khi chốt).
   - `carry_source_date`: date (ngày liền trước làm căn cứ nguồn).
   - `carryover_effective_from`: date (mốc ngày bắt đầu áp dụng chính sách).
2. **Bảng chính sách `public.daily_task_carryover_policies`**:
   - Quản lý ngày hiệu lực `effective_from`, cờ kích hoạt `is_enabled` và ghi chú audit.
   - Hàm helper `get_daily_task_carryover_policy(p_date)` và hàm cấu hình `set_daily_task_carryover_policy(...)` (bảo vệ bằng RLS và quyền `service_role`).
3. **Cập nhật RPC & Helper**:
   - `get_daily_task_status(p_user_id, p_date)`: Tính toán $B, C, A, D, R, T, Q, \text{carry\_out}$ theo đúng công thức nghiệp vụ, phân biệt rõ trạng thái `carryover_ready` (hôm qua đã chốt hay chưa).
   - `_apply_daily_inactivity_penalty(p_eval)`: Chốt ngày nguyên tử dưới khóa `pg_advisory_xact_lock` và `FOR UPDATE of p`, phạt Credit theo tỷ lệ thiếu trên số dư thực tế, ghi log, notification, và ghi sổ phần thiếu mà không ghi đè số dư của giao dịch khác.
   - `apply_daily_inactivity_penalty()`: Điều phối tự động phát hiện khoảng trống ngày chưa chốt để chốt tuần tự từ ngày thiếu sớm nhất đến ngày hôm qua.
   - `resume_campaign(p_campaign_id)`: Đồng bộ thứ tự khóa `campaigns` trước `profiles`, chống deadlock với các giao dịch mua đẩy hoặc trừ Credit.
4. **Đồng bộ schema gộp**: Đã append đầy đủ vào `supabase/full_schema_singapore.sql`.

---

## 2. Danh sách file đã chỉnh sửa & tạo mới

### Backend (Database & Tests)
1. `supabase/migrations/053_daily_task_carryover.sql` *(Tạo mới)*: Toàn bộ schema, trigger, hàm helper, finalizer và điều phối chốt ngày bù.
2. `supabase/full_schema_singapore.sql` *(Cập nhật)*: Append nội dung migration 053 vào cuối file, bảo toàn 100% nội dung migration 052 (Đẩy app) và các tính năng trước đó.
3. `supabase/tests/run-daily-task-carryover.mjs` *(Tạo mới)*: Suite kiểm thử 10 ca toàn diện trên PGlite database nhúng.
4. `supabase/tests/package.json` *(Cập nhật)*: Thêm kịch bản test `test:carryover`.

### Client Android
1. `app/src/main/java/com/ntt/crosstest/data/Models.kt` *(Cập nhật)*:
   - Thêm các trường vào `DailyTaskStatus`: `baseRequiredTests`, `carryInTests`, `carryoverRemainingTests`, `carryoverReady`, `carryoverEffectiveFrom`.
   - Bổ sung helper properties: `isPendingCarryover`, `hasCarryIn`, `hasRemainingCarryover`, cập nhật `isWaived` (chỉ waived khi `isOwner && carryoverReady && effectiveRequiredTests <= 0`).
2. `app/src/main/java/com/ntt/crosstest/ui/components/DailyTaskCard.kt` *(Cập nhật)*:
   - Xử lý badge và thông điệp trạng thái "Đang cập nhật chỉ tiêu" khi `isPendingCarryover` (chưa kết luận hoàn thành, giữ `met = false`).
   - Hiển thị giải thích chi tiết khi có phần thiếu chuyển sang (`hasCarryIn`), và giải thích bảo lưu phần thiếu khi nguồn app khả dụng thiếu hụt.
3. `app/src/main/java/com/ntt/crosstest/work/ReminderWorker.kt` *(Cập nhật)*:
   - Thêm điều kiện `daily.carryoverReady` trước khi gửi notification nhắc nhở, tránh nhắc sai chỉ tiêu trong khoảng chờ chốt.
4. `app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt` *(Cập nhật)*:
   - Trong `resumeCampaign()`, tự động gọi `invalidateProfile()` và `invalidateDashboardCache()` sau khi resume thành công để đồng bộ streak/nhiệm vụ tức thì.
5. `app/src/main/java/com/ntt/crosstest/ui/viewmodels/HomeViewModel.kt` *(Cập nhật)*:
   - Bổ sung scheduler tại mốc 00:05:05 VN (sau khi cron server chốt ngày) để tự động làm mới trạng thái `carryoverReady = true` mà người dùng không cần reload thủ công.
6. `app/src/main/res/values/strings.xml`, `values-vi/strings.xml`, `values-en/strings.xml` *(Cập nhật)*:
   - Thêm chuỗi giao diện giải thích cộng dồn cho cả 3 file tài nguyên.
   - Cập nhật hộp thoại giải thích chính sách phạt vắng mặt (`daily_penalty_dialog_content`) làm rõ cơ chế chuyển tiếp phần thiếu.
7. `app/src/test/java/com/ntt/crosstest/data/DailyTaskCarryoverUnitTest.kt` *(Tạo mới)*:
   - Unit test kiểm tra tương thích ngược JSON cũ, parse JSON mới, trạng thái pending và logic waived/member.

---

## 3. Kết quả kiểm thử thực tế

### 3.1. Kiểm thử Backend Database (`node supabase/tests/run-daily-task-carryover.mjs`)
Tất cả 10 nhóm kiểm thử đều **PASS 100%**:
- **PASS 1**: Nạp thành công baseline full schema và các migration 045 -> 053 (xác minh idempotent khi nạp lại).
- **PASS 2**: Ngày đầu hiệu lực: $C = 0$; cần 6, làm 4 $\rightarrow$ $\text{carry\_out} = 2$, phạt Credit và tăng streak.
- **PASS 3**: Ngày 2 nhận $B=6, C=2 \rightarrow$ phải test 8; làm 5 $\rightarrow$ $\text{carry\_out} = 3$, streak tăng lên 2.
- **PASS 4**: Ngày 3 nhận $B=6, C=3 \rightarrow$ phải test 9; làm đủ 9/9 $\rightarrow$ $\text{carry\_out} = 0$, streak reset về 0, không phạt.
- **PASS 5**: Vượt chỉ tiêu (làm 10/6) không làm $\text{carry\_out}$ bị âm và không chuyển tín dụng sang ngày sau.
- **PASS 6**: Ma trận thiếu nguồn app (mục 3.2):
  - $B=6, C=2, A=5, D=5 \rightarrow T=5, \text{carry\_out}=0$, đạt chỉ tiêu.
  - $B=6, C=8, A=5, D=5 \rightarrow T=5$, trả 5 trong 8 phần cũ, giữ lại $\text{carry\_out}=3$.
  - $B=6, C=8, A=0, D=0 \rightarrow T=0$, miễn kiểm thử hôm nay do $A=0$, giữ $\text{carry\_out}=8$, tuyệt đối **không phạt và không tăng streak**.
- **PASS 7**: Thành viên không đăng app (member) chỉ cần đăng nhập là hoàn thành (`met = true`); nếu có phần thiếu cũ từ lúc còn là owner thì phần thiếu được tạm giữ và giảm khi có test tự nguyện, không bị ép trả hết mới được xem là hoàn thành.
- **PASS 8**: Khoảng 00:00–00:05 khi ngày hôm trước chưa chốt: owner trả `carryover_ready = false`, `met = false`; các lượt test sau 00:00 vẫn được ghi nhận cho ngày mới; sau khi cron chốt hôm qua chuyển ngay sang `carryover_ready = true`.
- **PASS 9**: Idempotency & chống chạy trùng: retry finalizer không nhân đôi phạt hay nhân đôi phần thiếu.
- **PASS 10**: Tương thích Đẩy app: Mua đẩy trừ 100 Credit, không thay đổi chỉ tiêu test, không reset streak và không làm sai lệch số dư.

### 3.2. Kiểm thử Android Client (`./gradlew.bat testDebugUnitTest`)
- **Kết quả**: `BUILD SUCCESSFUL in 31s` (31 tasks, 0 lỗi).
- Bao gồm toàn bộ regression tests hiện có và suite mới `DailyTaskCarryoverUnitTest`.

---

## 4. Bảo đảm tương thích với tính năng "Đẩy app"

| Tiêu chí tương thích | Đã thực hiện và kiểm chứng |
|---|---|
| **Không xóa nghĩa vụ** | Mua đẩy chỉ đổi thứ hạng và trừ 100 Credit; không giảm $B, C, D, T$, không reset streak và không đánh dấu `met = true`. |
| **Thứ tự khóa tránh deadlock** | Cả `push_campaign` và `resume_campaign` đều tuân thủ thứ tự khóa: `campaigns` trước, sau đó đến `profiles`. `_apply_daily_inactivity_penalty` chỉ khóa `profiles`, không giữ vòng khóa `campaigns`. |
| **Số dư an toàn dưới khóa** | Cả mua đẩy và chấm phạt đều kiểm tra và trừ Credit dưới khóa `FOR UPDATE` của profile, không ghi đè số dư của nhau dù chạy đồng thời. |
| **Độc lập hai mốc 00:00 và 00:05** | Ưu tiên đẩy app tự động hết hạn tại 00:00 theo ngày Việt Nam; nhiệm vụ ngày chuyển sang trạng thái chờ (`carryover_ready = false`) và được chốt tại 00:05 bởi cron. Hai trạng thái hoàn toàn độc lập. |
| **Phân biệt dailyTarget** | Không can thiệp vào `campaign.dailyTarget` (chỉ tiêu lượt test campaign nhận được) hay `isTargetReached`. |

---

## 5. Tích hợp và tương thích với tính năng "Đẩy app"

1. **Trạng thái tích hợp**:
   - Tính năng "Đẩy app" (migration 052) và runner `run-campaign-push-test.mjs` đã hoàn tất và PASS 19/19 checks.
   - Bộ test runner liên hoàn `npm test --prefix supabase/tests` đã chạy thành công **10/10 test suites** trên cùng một schema gộp `full_schema_singapore.sql`.
   - Android Unit Tests (`./gradlew.bat testDebugUnitTest`) đã chạy thành công toàn bộ trong 24s.
2. **Kiểm thử Concurrency đa kết nối**:
   - Khi đưa lên môi trường staging PostgreSQL có connection pool thực tế, thực hiện kiểm thử concurrency đồng thời giữa `push_campaign` (trừ 100 Credit) và `_apply_daily_inactivity_penalty` (chốt ngày/phạt) để tái kiểm chứng khóa profile không race condition hay deadlock.

---

## 6. Hướng dẫn triển khai lên Staging / Production

> [!IMPORTANT]
> Toàn bộ công việc hiện tại chỉ nằm trong source code và môi trường kiểm thử local. Tuyệt đối **chưa** tự ý áp migration hoặc chạy finalizer trên Supabase Production.

Khi có kế hoạch phát hành lên môi trường thật:
1. **Bước 1**: Áp dụng migration `053_daily_task_carryover.sql` lên Supabase (sau migration `052_campaign_push_priority.sql`).
2. **Bước 2**: Xác nhận bảng `daily_task_carryover_policies` đã có bản ghi hiệu lực với ngày bắt đầu trong tương lai (ví dụ ngày mai).
3. **Bước 3**: Kiểm tra pg_cron đã lên lịch hàm `apply_daily_inactivity_penalty()` lúc 00:05 VN hàng ngày.
4. **Bước 4**: Phát hành bản cập nhật Android APK có hỗ trợ các trường carryover mới.
