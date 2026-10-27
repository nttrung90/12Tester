# BÁO CÁO SỬA TOÀN DIỆN LUỒNG NHIỆM VỤ → NHIỆM VỤ APP (M01 - M09)

*Ngày thực hiện: 27/10/2026*  
*Người thực hiện: Antigravity (Pair Programming)*  
*Mục tiêu: Triển khai toàn bộ giải pháp kỹ thuật theo kế hoạch F:\AI\12Tester\KE_HOACH_SUA_LUONG_NHIEM_VU_APP_CHO_GEMINI_2026-10-27.md, giải quyết dứt điểm các vấn đề M01, M02, M03, M05, M06, M07, M08, M09; bảo toàn chính sách nghiệp vụ M04 và bảo toàn toàn bộ mã nguồn đang có của các công việc trước.*

---

## 1. TỔNG QUAN KẾT QUẢ THỰC HIỆN

| Vấn đề | Tên vấn đề | Trạng thái | Giải pháp chính |
| :--- | :--- | :--- | :--- |
| **M01** | Tester đã tham gia mission nhưng mission bị đầy (`isFull`) thì nút đổi thành "Nhiệm vụ đã đầy" thay vì cho phép "Đi test" | **ĐÃ SỬA** | Đảo thứ tự `when` trong `MissionCard`: kiểm tra `mission.hasJoined` trước `mission.isFull`. Bổ sung click vào Header Row để mở app/campaign. |
| **M02** | Chủ app tạo trùng nhiều mission active cho 1 app (kể cả khi mission cũ đang `paused_by_creator`), hoặc bị sót app đang có mission | **ĐÃ SỬA** | Thêm Partial Unique Index `idx_missions_campaign_active_unique` trên DB; khóa `campaigns FOR UPDATE` và sync expired trước khi check; cung cấp RPC `get_my_active_mission_campaign_ids()` để Android lọc chính xác không phụ thuộc giới hạn 100 discovery. |
| **M03** | Tester gửi báo cáo trước khi bấm tham gia mission vẫn được nhận thưởng mission khi chủ app duyệt | **ĐÃ SỬA** | Cập nhật `_settle_mission_reward`: kiểm tra chặt chẽ `v_report.created_at >= mp.joined_at` và `v_report.status = 'approved'`. Nếu báo cáo nộp trước lúc join, không thưởng mission credits và không đổi status participant sang completed. |
| **M04** | Báo cáo nộp trước hạn mission nhưng được duyệt sau khi mission hết hạn (`ends_at <= now()`) bị mất thưởng | **GIỮ CHÍNH SÁCH** | Bảo toàn chính sách hiện tại (`m.ends_at > now()`) do chưa có quyết định nghiệp vụ; mô tả chi tiết hạn chế, không tự ý sửa quy tắc kinh doanh hoặc trả bù lịch sử. |
| **M05** | Lỗi kỹ thuật khi ghi `credit_logs` hoặc trừ/cộng credit làm thất lạc khoản thưởng mission vĩnh viễn (thiếu outbox/retry) | **ĐÃ SỬA** | Tạo bảng durable outbox `public.mission_settlement_jobs`; tách logic settlement vào subtransaction; nếu ghi log/credit lỗi thì ghi nhận job `status = 'failed'`, không fail giao dịch duyệt report; bổ sung RPC `retry_failed_mission_settlements()` và tích hợp auto-retry vào cron. |
| **M06** | Tạo mission bị chặn sai do mission cũ đã hết hạn `ends_at` nhưng `status` trên DB vẫn còn là `open` | **ĐÃ SỬA** | Trong `create_mission`, thực hiện đồng bộ `sync_expired_missions(campaign_id)` ngay dưới khóa `campaign FOR UPDATE` trước khi kiểm tra conflict. |
| **M07** | `get_open_missions()` áp dụng `LIMIT 100` trước khi `ORDER BY` làm nhiệm vụ mới tạo bị rơi khỏi danh sách | **ĐÃ SỬA** | Sửa `get_open_missions()`: thực hiện sắp xếp ưu tiên (nhiệm vụ của tôi / nhiệm vụ đã tham gia, trạng thái hoạt động, `created_at desc`, `id desc`) trong subquery/CTE trước khi áp dụng `LIMIT 100`. |
| **M08** | Màn hình danh sách nhiệm vụ, tạo nhiệm vụ và chi tiết app chủ app không phân biệt lỗi mạng với trạng thái rỗng / hết app khả dụng | **ĐÃ SỬA** | Android UI phân tách rõ rệt: State Error (hiển thị thông báo lỗi mạng + nút Thử lại) tách biệt với State Empty (chưa có nhiệm vụ); trên Create Mission phân tách "Tất cả ứng dụng đang mở đều đã có nhiệm vụ đang chạy" với "Bạn chưa có ứng dụng nào". |
| **M09** | Bất đồng bộ response cũ ghi đè UI mới (race condition) và rò rỉ cache khi chuyển tài khoản | **ĐÃ SỬA** | Thêm sequence guard (`AtomicLong`), token generation và userId guard trong `HomeViewModel`, `MissionCreateViewModel`, `OwnerAppDetailViewModel`; phân lập cache discovery theo `userId` trong `CampaignRepository`; re-throw `CancellationException`. |

---

## 2. CHI TIẾT THAY ĐỔI THEO TỪNG VẤN ĐỀ

### 2.1. M01 — Sửa logic nút tương tác MissionCard (Android)
- **File sửa:** `app/src/main/java/com/ntt/crosstest/ui/screens/QuestsScreen.kt`
- **Chi tiết:**
  - Trong `MissionCard`, nhánh kiểm tra điều kiện hiển thị nút hành động được sắp xếp lại:
    ```kotlin
    when {
        mission.hasJoined -> {
            // Hiển thị nút "Đi test" (primary button)
            Button(onClick = onOpenApp, ...) { Text(stringResource(R.string.mission_action_test)) }
        }
        mission.isFull -> {
            // Hiển thị nhãn "Nhiệm vụ đã đầy" (disabled button)
            Button(onClick = {}, enabled = false, ...) { Text(stringResource(R.string.mission_status_full)) }
        }
        ...
    }
    ```
  - Click vào dòng tiêu đề / icon ứng dụng (`Row` chứa AppIcon và App Info) được gán callback `Modifier.clickable { onOpenApp() }` giúp tester dễ dàng mở trang thông tin app / mở link cài đặt ngay cả khi card ở trạng thái khác.

### 2.2. M02 — Chặn tạo trùng mission và hỗ trợ lọc app bận (Backend & Android)
- **Files sửa:**
  - `supabase/migrations/044_mission_flow_hardening.sql`
  - `supabase/full_schema_singapore.sql`
  - `app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt`
  - `app/src/main/java/com/ntt/crosstest/ui/viewmodels/MissionCreateViewModel.kt`
- **Chi tiết:**
  - **Database Index:** Tạo Partial Unique Index trên Postgres:
    ```sql
    create unique index if not exists idx_missions_campaign_active_unique
        on public.missions (campaign_id)
        where status in ('open', 'full', 'paused_by_creator');
    ```
    Chỉ số này đảm bảo mức DB engine không bao giờ tồn tại hai nhiệm vụ cùng active (dù là `open`, `full` hay `paused_by_creator`) trên cùng một campaign.
  - **Hàm `create_mission` & `resume_mission`:**
    - Khóa dòng campaign `for update`.
    - Đồng bộ nhiệm vụ hết hạn của campaign trước khi kiểm tra.
    - Kiểm tra nếu tồn tại mission active khác thì trả mã lỗi `MISSION_ALREADY_EXISTS`.
  - **RPC `get_my_active_mission_campaign_ids()`:**
    - Trả về danh sách `campaign_id` của chủ app hiện có mission active (`open`, `full`, `paused_by_creator`), không bị giới hạn bởi LIMIT 100 của danh sách discovery.
  - **Android Client:** `MissionCreateViewModel` gọi `repository.getMyActiveMissionCampaignIds()` để lọc bỏ các app đang có nhiệm vụ đang chạy, phân biệt chính xác app nào khả dụng để tạo nhiệm vụ.

### 2.3. M03 — Chặn lấy thưởng nhiệm vụ bằng báo cáo nộp trước khi tham gia (Backend)
- **Files sửa:**
  - `supabase/migrations/044_mission_flow_hardening.sql`
  - `supabase/full_schema_singapore.sql`
- **Chi tiết:**
  - Cập nhật hàm `_settle_mission_reward(p_report_id uuid)`:
    ```sql
    select mp.id, mp.mission_id, mp.status, mp.joined_at, m.reward_credits, m.creator_id
    into v_match
    from public.mission_participants mp
    join public.missions m on m.id = mp.mission_id
    where mp.user_id = v_report.user_id
      and m.campaign_id = v_report.campaign_id
      and mp.status = 'joined'
      and m.status in ('open', 'full')
      and m.ends_at > now()
      and v_report.created_at >= mp.joined_at
    for update of mp;
    ```
  - Nếu `v_report.created_at < mp.joined_at`: Không tìm thấy bản ghi thỏa mãn, hàm bỏ qua việc thưởng mission, giữ nguyên trạng thái participant là `joined` cho đến khi có báo cáo hợp lệ nộp sau thời điểm tham gia.
  - Đảm bảo idempotent: Bản ghi `mission_settlement_jobs` lưu `report_id` duy nhất (`unique(report_id)`).

### 2.4. M04 — Bảo toàn chính sách nghiệp vụ hết hạn và mô tả hạn chế
- **Trạng thái:** **GIỮ NGUYÊN CHÍNH SÁCH HIỆN TẠI THEO YÊU CẦU**
- **Chi tiết nghiệp vụ:**
  - Điều kiện trao thưởng trong `_settle_mission_reward` tiếp tục duy trì: `m.ends_at > now()`.
  - **Hạn chế kỹ thuật & nghiệp vụ tồn tại:** Nếu tester tham gia mission hợp lệ, nộp báo cáo đúng hạn trước `ends_at`, nhưng chủ app hoặc cron `auto_accept_expired_reports` thực hiện duyệt báo cáo sau khi mission đã bước qua `ends_at`, tester sẽ chỉ nhận được thưởng test cơ bản / tip của campaign mà không nhận được phần thưởng mission.
  - **Lý do giữ nguyên:** Quyết định này đòi hỏi thống nhất từ Product Owner liên quan đến:
    1. Tiền ký quỹ của chủ app: Số credit còn lại của mission hết hạn sẽ được hoàn lại (refund) vào ví chủ app khi mission hết hạn. Nếu cho phép trả thưởng sau khi đã hết hạn, hệ thống có thể bị thâm hụt ngân sách nếu số dư ký quỹ đã được hoàn trả.
    2. Không tự ý sửa đổi quy tắc phí, tip, hoặc truy thu/trả bù cho các nhiệm vụ trong quá khứ khi chưa có chỉ đạo nghiệp vụ chính thức.

### 2.5. M05 — Outbox bền vững và cơ chế Retry cho Mission Settlement (Backend)
- **Files sửa:**
  - `supabase/migrations/044_mission_flow_hardening.sql`
  - `supabase/full_schema_singapore.sql`
- **Chi tiết:**
  - **Bảng `public.mission_settlement_jobs`:**
    ```sql
    create table if not exists public.mission_settlement_jobs (
        id              uuid primary key default gen_random_uuid(),
        report_id       uuid not null references public.tester_reports(id) on delete cascade,
        mission_id      uuid not null references public.missions(id) on delete cascade,
        participant_id  uuid not null references public.mission_participants(id) on delete cascade,
        tester_id       uuid not null references public.profiles(id) on delete cascade,
        creator_id      uuid not null references public.profiles(id) on delete cascade,
        reward_credits  int not null check (reward_credits > 0),
        status          text not null default 'pending' check (status in ('pending', 'completed', 'skipped', 'failed')),
        retry_count     int not null default 0,
        last_error      text,
        created_at      timestamptz not null default now(),
        updated_at      timestamptz not null default now(),
        constraint uq_mission_settlement_report unique (report_id)
    );
    ```
  - **Subtransaction trong settlement:** Khi `_settle_mission_reward` thực hiện trừ/cộng credit và ghi `credit_logs`, nếu phát sinh lỗi kỹ thuật (ví dụ timeout, trigger audit tạm lỗi), subtransaction sẽ bắt exception:
    - Cập nhật job: `status = 'failed'`, ghi nhận `last_error`.
    - Trả về JSON thành công một phần cho approval (`approval_success: true, settlement_status: 'failed'`), **không rollback việc duyệt báo cáo của tester**.
  - **RPC Retry:**
    - Tạo `retry_failed_mission_settlements(p_limit int default 50)`: Quét các job có status `failed` và `retry_count < 5`, khóa dòng `for update skip locked` và thực hiện lại giao dịch cộng/trừ điểm an toàn.
    - Tích hợp `perform public.retry_failed_mission_settlements(20)` vào bên trong `auto_accept_expired_reports()`, giúp hệ thống tự động khắc phục các khoản thưởng bị nghẽn trong mỗi chu kỳ quét định kỳ.

### 2.6. M06 — Tự động đồng bộ nhiệm vụ hết hạn trước khi kiểm tra tạo mới (Backend)
- **Files sửa:**
  - `supabase/migrations/044_mission_flow_hardening.sql`
  - `supabase/full_schema_singapore.sql`
- **Chi tiết:**
  - Trong `create_mission`:
    ```sql
    select * into v_campaign from public.campaigns where id = p_campaign_id for update;
    ...
    -- Đồng bộ nhiệm vụ hết hạn của campaign này dưới khóa hàng
    perform public.sync_expired_missions(p_campaign_id);
    ```
  - Khi chủ app tạo nhiệm vụ mới, hàm sẽ tự động giải phóng slot nếu nhiệm vụ trước đó đã quá hạn (`ends_at <= now()`), chuyển trạng thái nhiệm vụ cũ sang `expired`, tránh tình trạng chủ app bị chặn oan dù nhiệm vụ cũ đã hết hạn.

### 2.7. M07 — Sắp xếp ưu tiên trước khi Limit 100 trong discovery (Backend)
- **Files sửa:**
  - `supabase/migrations/044_mission_flow_hardening.sql`
  - `supabase/full_schema_singapore.sql`
- **Chi tiết:**
  - Trong `get_open_missions()`:
    ```sql
    with ordered_missions as (
        select m.*, ...
        from public.missions m
        join public.campaigns c on c.id = m.campaign_id
        where m.status in ('open', 'full')
          and m.ends_at > now()
        order by
            case when m.creator_id = v_caller_id then 0
                 when exists (select 1 from public.mission_participants mp where mp.mission_id = m.id and mp.user_id = v_caller_id) then 1
                 else 2 end asc,
            case when m.status = 'open' then 0 else 1 end asc,
            m.created_at desc,
            m.id desc
        limit 100
    )
    ...
    ```
  - Đảm bảo nhiệm vụ vừa tạo (mới nhất) luôn xuất hiện trong top danh sách, không bị thuật toán phân trang/giới hạn DB đẩy ra ngoài khi số lượng bản ghi vượt quá 100.

### 2.8. M08 — Phân tách tường minh Error State và Empty State trên Android
- **Files sửa:**
  - `app/src/main/res/values/strings.xml`, `values-en/strings.xml`, `values-vi/strings.xml`
  - `app/src/main/java/com/ntt/crosstest/ui/viewmodels/HomeViewModel.kt`
  - `app/src/main/java/com/ntt/crosstest/ui/screens/QuestsScreen.kt`
  - `app/src/main/java/com/ntt/crosstest/ui/screens/HomeScreen.kt`
  - `app/src/main/java/com/ntt/crosstest/ui/viewmodels/MissionCreateViewModel.kt`
  - `app/src/main/java/com/ntt/crosstest/ui/screens/MissionCreateScreen.kt`
  - `app/src/main/java/com/ntt/crosstest/ui/viewmodels/OwnerAppDetailViewModel.kt`
  - `app/src/main/java/com/ntt/crosstest/ui/screens/OwnerAppDetailScreen.kt`
- **Chi tiết:**
  - **QuestsScreen / HomeViewModel:**
    - `HomeUiState` bổ sung `missionsError: String? = null`.
    - Khi tải danh sách nhiệm vụ gặp lỗi mạng/kết nối và danh sách đang rỗng, UI hiển thị khối thông báo lỗi chuyên biệt kèm nút "Thử lại", thay vì hiển thị giao diện trống rỗng (empty state) gây nhầm lẫn là không có nhiệm vụ.
  - **MissionCreateScreen / MissionCreateViewModel:**
    - `MissionCreateUiState` bổ sung `initialLoadError: String? = null` và cờ `allAppsBusy: Boolean = false`.
    - Phân tách 3 trường hợp:
      1. Lỗi mạng: Hiển thị thẻ báo lỗi kèm nút "Thử lại".
      2. `allAppsBusy = true`: Hiển thị thông báo "Tất cả ứng dụng đang mở của bạn đều đã có nhiệm vụ đang hoạt động" kèm nút đóng/quay lại.
      3. `apps.isEmpty()`: Hiển thị thông báo chưa có ứng dụng nào kèm nút "Thêm ứng dụng mới".
  - **OwnerAppDetailScreen / OwnerAppDetailViewModel:**
    - `OwnerAppDetailUiState` bổ sung `isMissionLoading: Boolean` và `missionError: String? = null`.
    - Khi tải mission của campaign bị lỗi, mục nhiệm vụ hiển thị card lỗi kèm nút Retry ngay tại chỗ.

### 2.9. M09 — Chống Race Condition và rò rỉ Cache đa tài khoản (Android)
- **Files sửa:**
  - `app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt`
  - `app/src/main/java/com/ntt/crosstest/ui/viewmodels/HomeViewModel.kt`
  - `app/src/main/java/com/ntt/crosstest/ui/viewmodels/MissionCreateViewModel.kt`
  - `app/src/main/java/com/ntt/crosstest/ui/viewmodels/OwnerAppDetailViewModel.kt`
- **Chi tiết:**
  - **User-Scoped Cache:** Bộ đệm `cachedOpenMissions` trong `CampaignRepository` được gắn kèm `userId`. Khi chuyển tài khoản hoặc đăng xuất, cache cũ bị vô hiệu hóa lập tức, ngăn ngừa rò rỉ dữ liệu hoặc hiển thị sai trạng thái `hasJoined` của người dùng trước.
  - **Sequence Guard & Generation Check:**
    - Trong `HomeViewModel`: Thêm `missionRefreshSequence = AtomicLong(0)`. Mọi response trả về từ coroutine bất đồng bộ đều phải đối chiếu xem sequence và generation còn khớp với yêu cầu gần nhất hay không. Nếu response đến trễ sau một request mới hơn, response cũ sẽ bị hủy bỏ (discarded).
    - Trong `HomeViewModel.joinMission`: Chống click đúp bằng cờ `joiningMissionId`. Khi server trả mã `ALREADY_JOINED`, cập nhật ngay cờ `hasJoined = true` cục bộ và đồng thời kích hoạt authoritative refresh để đồng bộ dữ liệu chuẩn.
    - Trong `MissionCreateViewModel` và `OwnerAppDetailViewModel`: Sử dụng sequence token tương tự để bảo vệ quá trình nạp dữ liệu.
  - **Coroutine Cancellation:** Tất cả các khối try-catch trong các ViewModel đều kiểm tra và re-throw `CancellationException` để coroutine lifecycle của Compose hoạt động chính xác.

---

## 3. DANH SÁCH FILE THAY ĐỔI VÀ TẠO MỚI

### 3.1. Database & Migrations
1. `supabase/migrations/044_mission_flow_hardening.sql` *(Tạo mới)*: Chứa toàn bộ định nghĩa bảng `mission_settlement_jobs`, chỉ số unique, các trigger, hàm `create_mission`, `resume_mission`, `_settle_mission_reward`, `retry_failed_mission_settlements`, `auto_accept_expired_reports`, `get_open_missions`, `get_my_active_mission_campaign_ids`.
2. `supabase/full_schema_singapore.sql` *(Cập nhật)*: Đồng bộ nguyên văn toàn bộ nội dung của migration 044 vào cuối schema chính, phân tách bằng marker chuẩn.

### 3.2. Kiểm thử Backend (PGlite / Node.js)
1. `supabase/tests/run-mission-flow-hardening.mjs` *(Tạo mới)*: Bộ kiểm thử hồi quy toàn diện cho 044 (upgrade path, idempotency, M02, M03, M05, M06, M07, get_my_active_mission_campaign_ids, bảo toàn review point 6 của 043).
2. `supabase/tests/run-daily-task-ratio.mjs` *(Cập nhật)*: Cập nhật marker split để xác minh tính khớp từng byte của cả migration 043 và 044 với `full_schema_singapore.sql`.
3. `supabase/tests/run-backend-cleanup-hardening.mjs` *(Cập nhật)*: Điều chỉnh kịch bản test 3 để không vi phạm ràng buộc M02 (sử dụng 2 campaign riêng biệt khi kiểm tra sync expired).
4. `.tmp/mission-audit/repro.mjs` *(Cập nhật)*: Chuyển các probe kiểm tra lỗi cũ thành các assertion kiểm tra hành vi đúng của M01-M07.
5. `.tmp/mission-audit/results.json` *(Cập nhật)*: Ghi nhận kết quả chạy probe thực tế sau khi đã sửa.

### 3.3. Android Source Code
1. `app/src/main/res/values/strings.xml` *(Cập nhật)*: Bổ sung chuỗi song ngữ cho error/busy state.
2. `app/src/main/res/values-en/strings.xml` *(Cập nhật)*: Bổ sung bản dịch tiếng Anh.
3. `app/src/main/res/values-vi/strings.xml` *(Cập nhật)*: Bổ sung bản dịch tiếng Việt.
4. `app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt` *(Cập nhật)*: User-scoped cache cho open missions và hàm `getMyActiveMissionCampaignIds()`.
5. `app/src/main/java/com/ntt/crosstest/ui/screens/QuestsScreen.kt` *(Cập nhật)*: Sửa thứ tự hiển thị nút hành động, thêm click vào header row, thêm UI error state và retry.
6. `app/src/main/java/com/ntt/crosstest/ui/screens/HomeScreen.kt` *(Cập nhật)*: Nối `missionsError` và `onRetryMissions` từ ViewModel xuống QuestsScreen.
7. `app/src/main/java/com/ntt/crosstest/ui/viewmodels/HomeViewModel.kt` *(Cập nhật)*: Sequence token guard, xử lý `missionsError`, chống race condition và click đúp khi join.
8. `app/src/main/java/com/ntt/crosstest/ui/screens/MissionCreateScreen.kt` *(Cập nhật)*: Tách biệt error state, `allAppsBusy` state và empty state.
9. `app/src/main/java/com/ntt/crosstest/ui/viewmodels/MissionCreateViewModel.kt` *(Cập nhật)*: Sequence guard, tải danh sách app bận qua RPC mới, quản lý error state.
10. `app/src/main/java/com/ntt/crosstest/ui/screens/OwnerAppDetailScreen.kt` *(Cập nhật)*: Hiển thị error action item kèm retry khi tải mission thất bại.
11. `app/src/main/java/com/ntt/crosstest/ui/viewmodels/OwnerAppDetailViewModel.kt` *(Cập nhật)*: Sequence token guard, quản lý `missionError` và callback retry.
12. `app/src/test/java/com/ntt/crosstest/data/MissionRegressionTest.kt` *(Tạo mới)*: Kiểm thử unit test cho M01, M08 và M09.

---

## 4. KẾT QUẢ KIỂM THỬ THẬT TẾ

### 4.1. Bộ kiểm thử Backend (PGlite / Node.js)
Tất cả các bài kiểm tra được chạy trên môi trường CSDL PGlite mô phỏng sát thực tế Postgres:
- **Lệnh thực thi:** `cd supabase/tests; npm test`
- **Kết quả:**
  ```text
  > test
  > node run-daily-task-ratio.mjs && node run-backend-cleanup-hardening.mjs && node run-mission-flow-hardening.mjs

  PASS: existing full schema through its current pre-042 content loads
  PASS: migration 042 upgrades the existing schema
  PASS: default 3:1 status, penalties, balances and logs match pre-042
  PASS: read-only preflight recognizes baseline and installed definitions
  PASS: 2016 SQL assertions (matrix, dates, audit, penalties, RPCs, roles)
  PASS: rerunning 042 preserves baseline, future schedules and audit
  PASS: missing baseline fails closed
  PASS: fresh full-schema install matches upgraded definitions and ACLs
  PASS: customized live function is refused without partial migration
  PASS: 8 runner checks plus SQL assertions
  --- KHỞI TẠO PGLITE ĐỂ KIỂM THỬ HARDENING 043 ---
  PASS: Nạp thành công full_schema_singapore.sql (bao gồm 041, 042, 043, 044)
  PASS: Review Point 1 - Dry run an toàn, chỉ scan/log, không đổi dữ liệu nghiệp vụ
  PASS: Purge thật tự động đồng bộ mission hết hạn và xóa sạch campaign
  PASS: Review Point 2 - sync_expired_missions() hoạt động chính xác độc lập UI
  PASS: Review Point 3 - Cơ chế chống starvation & backoff bảo đảm campaign tốt không bị chặn
  PASS: Review Point 6 - pause/resume cập nhật status expired không rollback, giữ contract auth
  === TẤT CẢ 5 NHÓM KIỂM THỬ HARDENING 043 ĐỀU PASS 100% ===
  --- KHỞI TẠO PGLITE ĐỂ KIỂM THỬ REGRESSION 044 (M01-M09) ---
  PASS: Nạp thành công full_schema_singapore.sql gốc
  PASS: Áp dụng thành công migration 044_mission_flow_hardening.sql (upgrade path)
  PASS: Áp dụng lại migration 044 an toàn (idempotency)
  --- Kiểm tra M02 ---
  PASS: M02 - Pause mission chiếm giữ slot, chặn tạo trùng mission trên cùng campaign
  --- Kiểm tra M06 ---
  PASS: M06 - Tự động đồng bộ mission hết hạn dưới khóa khi tạo mới
  --- Kiểm tra M03 ---
  PASS: M03 - Báo cáo trước join không được tính thưởng; báo cáo sau join được thưởng đúng 1 lần
  --- Kiểm tra M05 ---
  PASS: M05 - Settlement failure được lưu vào outbox và retry bền vững, idempotent
  --- Kiểm tra M07 ---
  PASS: M07 - Sắp xếp trước khi Limit 100 bảo toàn nhiệm vụ mới nhất
  --- Kiểm tra get_my_active_mission_campaign_ids ---
  PASS: get_my_active_mission_campaign_ids trả đúng danh sách campaign có mission
  --- Kiểm tra bảo toàn 043 review point 6 ---
  PASS: Bảo toàn sửa đổi 043 cho pause/resume expired
  === TẤT CẢ CÁC REGRESSION TESTS BACKEND 044 ĐỀU PASS 100% ===
  ```

### 4.2. Bộ kiểm thử Probe Audit (`repro.mjs`)
- **Lệnh thực thi:** `node .tmp/mission-audit/repro.mjs`
- **Kết quả:**
  ```json
  {
    "schema": "full_schema_singapore.sql through 044",
    "results": [
      { "id": "M02", "name": "Paused mission blocks another mission on the same campaign", "passed": true },
      { "id": "M03", "name": "A report submitted before joining does NOT earn mission credits", "passed": true },
      { "id": "M04", "name": "Report submitted in time loses mission reward when auto-approved after ends_at (policy preserved)", "passed": true },
      { "id": "M05", "name": "Settlement failure is preserved in outbox and retried successfully", "passed": true },
      { "id": "M06", "name": "Expired open status does not block creation even before list read", "passed": true },
      { "id": "M07", "name": "Ordered limit includes the newest mission when there are 101 records", "passed": true },
      { "id": "M01", "name": "MissionCard checks joined before full", "passed": true }
    ]
  }
  ```

### 4.3. Kiểm thử Android Unit Test & Build
- **Unit Test:**
  - Lệnh: `.\gradlew.bat :app:testDebugUnitTest`
  - Kết quả: `BUILD SUCCESSFUL in 10s` (31 tasks up-to-date, tất cả test case trong `MissionRegressionTest.kt` và `CacheRegressionTest.kt` PASS 100%).
- **Assemble Debug Build:**
  - Lệnh: `.\gradlew.bat :app:assembleDebug`
  - Kết quả: `BUILD SUCCESSFUL in 18s` (tất cả file Kotlin/Java biên dịch thành công ra APK debug).
- **Android Lint Check:**
  - Lệnh: `.\gradlew.bat :app:lintDebug`
  - Kết quả: `BUILD SUCCESSFUL in 1m 6s` (báo cáo ghi nhận tại `app/build/reports/lint-results-debug.html`, không có lỗi lint block build).

### 4.4. Phân biệt các hạng mục kiểm thử ĐÃ CHẠY và CHƯA CHẠY
- **Đã chạy và xác minh 100% thành công:**
  1. Kiểm tra tính toàn vẹn cú pháp và schema Postgres (PGlite).
  2. Nâng cấp migration 044 trên nền 043 và kiểm tra tính tái lập (idempotency).
  3. Kiểm tra logic nghiệp vụ SQL của M02, M03, M05, M06, M07.
  4. Kiểm tra mã nguồn Android: unit test logic `when` branch, UI states, token guards và cache eviction.
  5. Biên dịch toàn bộ ứng dụng Android (`:app:assembleDebug`).
- **Chưa chạy (do đặc thù môi trường):**
  1. **UI End-to-End trên thiết bị thật / Giả lập Android:** Chưa chạy tương tác chạm/vuốt màn hình thực tế do môi trường dòng lệnh không gắn sẵn thiết bị/AVD trực tiếp.
  2. **Deploy Supabase / Firebase:** Chưa đẩy code hay migration lên production theo đúng chỉ đạo an toàn.

---

## 5. PHẦN CÒN THIẾU VÀ KHUYẾN NGHỊ TIẾP THEO

1. **Vấn đề M04 (Chính sách duyệt báo cáo sau khi mission kết thúc):**
   - Hiện tại hệ thống vẫn giữ quy tắc: nếu duyệt sau `ends_at`, tester không nhận credit mission.
   - *Khuyến nghị:* Product Owner cần đưa ra quy định rõ: Có cho phép gia hạn thời gian ân hạn (grace period ví dụ 24h - 48h sau `ends_at`) cho các báo cáo đã nộp trước deadline hay không? Nếu cho phép, logic hoàn trả credit thừa cho chủ app phải được dời lại sau khi hết thời gian ân hạn.
2. **Kế hoạch triển khai Migration 044 lên Production:**
   - Khi có quyết định release, migration `044_mission_flow_hardening.sql` cần được chạy trên Supabase Dashboard của môi trường Staging/Production.
   - Do có tạo Partial Unique Index `idx_missions_campaign_active_unique`, migration đã chứa sẵn đoạn preflight cleanup tự động đồng bộ và dọn dẹp các nhiệm vụ hết hạn đang có trên DB để tránh lỗi `duplicate key value violates unique constraint`.
3. **Bảo toàn Workspace:**
   - Toàn bộ các file tài liệu và code của các công việc dọn rác backend (`043_backend_cleanup_hardening.sql`, các file kế hoạch/báo cáo tương ứng) đều được giữ nguyên vẹn không bị ghi đè.
   - Không thực hiện commit/push git hoặc deploy ngoại vi.
