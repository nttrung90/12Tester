# Báo cáo bàn giao Dashboard Warning Consistency (Core Dashboard Handover)

**Chặng thực hiện**: Fix confirmed warning revocation exclusion on Home Dashboard (`get_my_dashboard`)  
**Model**: `google/antigravity-gemini-3.8-flash` (`--variant high`)  
**Workspace**: `F:/AI/12Tester`  
**Ngày thực hiện**: 01/11/2026  
**Trạng thái**: Hoàn tất 100%, probe độc lập và test hồi quy đã xác minh thành công.

---

## 1. Vấn đề đã xử lý

- **Hiện tượng**: Báo cáo kiểm thử độc lập từ Codex (`codex-core-review-round1.md` và `independent-core-probe-results.json`) chỉ ra rằng sau khi thu hồi cảnh báo (`admin_revoke_warning`), `get_campaign_detail` đã hiển thị 0 cảnh báo nhưng Home dashboard (`get_my_dashboard`) vẫn trả về `weekly_warnings_count: 1` và `unread_warnings.length: 1`.
- **Nguyên nhân**: Hàm `get_my_dashboard` từ migration `062_consolidated_baseline.sql` chưa có bộ lọc loại trừ các cảnh báo đã bị thu hồi trong bảng append-only `public.user_warning_revocations`.
- **Giải pháp thực hiện**:
  1. Sử dụng script sinh mã build-time `supabase/tools/append-dashboard-to-070.mjs` để trích xuất nguyên bản định nghĩa `get_my_dashboard` từ migration 062.
  2. Bổ sung điều kiện loại trừ chính xác cho hai vị trí truy vấn `user_warnings` (`v_unread` và `v_weekly`):
     ```sql
     and not exists (
         select 1 from public.user_warning_revocations r
         where r.warning_id = w.id
     )
     ```
  3. Gắn định nghĩa `CREATE OR REPLACE FUNCTION public.get_my_dashboard` được review vào cuối migration `supabase/migrations/070_admin_management.sql` (ngay trước lệnh `COMMIT;`), kèm phân quyền `REVOKE ALL` từ client không xác thực và `GRANT EXECUTE` cho `authenticated`, `service_role`.
  4. Bảo toàn nguyên vẹn 100% cấu trúc 15 trường DTO gốc (`profile`, `my_campaigns`, `joined_campaigns`, `leaderboard`, `today_checkin_count`, `pending_reports_count`, `unread_warnings`, `weekly_warnings_count`, `credit_logs`, `topup_packages`, `notifications`, `owner_stats`, `slots`, `quest_summary`, `daily_task`) và logic nghiệp vụ đăng nhập hàng ngày.

---

## 2. Kết quả kiểm thử & xác minh độc lập

### 2.1 Kết quả chạy `independent-core-probe.mjs`
Chạy trực tiếp file probe độc lập của Codex:
```json
{
  "name": "warning revocation consistent on dashboard and detail",
  "beforeDashboardKeys": [
    "slots", "profile", "daily_task", "credit_logs", "leaderboard",
    "owner_stats", "my_campaigns", "notifications", "quest_summary",
    "topup_packages", "unread_warnings", "joined_campaigns",
    "today_checkin_count", "pending_reports_count", "weekly_warnings_count"
  ],
  "beforeDashboardWarnings": 1,
  "detailWarnings": 0,
  "dashboardWarnings": 0,
  "dashboardUnread": 0
}
```
-> Cả `get_campaign_detail` và `get_my_dashboard` đều trả về chính xác 0 cảnh báo sau khi thu hồi.

### 2.2 Kết quả chạy các bộ test hệ thống
- `npm run test:admin-management`: **PASS 100%** (bao gồm test hồi quy mới xác minh 15 keys DTO và số lượng cảnh báo trước/sau thu hồi trên `get_my_dashboard`).
- `npm run test:admin-audit`: **PASS 100%** (không hồi quy bất kỳ guard phân quyền nào của admin audit).

---

## 3. Tệp tin đã chỉnh sửa / tạo mới trong lượt này

1. `supabase/migrations/070_admin_management.sql`: Thêm override cho `public.get_my_dashboard(boolean)`.
2. `supabase/tools/append-dashboard-to-070.mjs`: Script trích xuất và nối định nghĩa hàm build-time an toàn.
3. `supabase/tests/run-admin-management-test.mjs`: Thêm assertion hồi quy cho dashboard warnings & DTO keys.
4. `audit/admin-expansion-2026-11-01/core-dashboard-handover.md`: Báo cáo bàn giao lượt này.
5. `core-dashboard-handover.md`: Bản sao tại thư mục gốc workspace.

*Lưu ý*: Các tệp Android, 071, 072 hoàn toàn không bị thay đổi. Các mục review tiếp theo (idempotency, slots array, legacy audit và role/lock coordination) sẽ được thực hiện tuần tự trong các lượt tiếp theo.
