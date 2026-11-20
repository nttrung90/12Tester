# Báo Cáo Kết Quả Subtask Backend: Cờ Vận Hành Egress Optimization V2

**Ngày thực hiện:** 2026-11-10  
**Phạm vi:** Bounded backend-only subtask cho tối ưu hóa egress (Supabase / PostgreSQL migrations, PGlite behavioral tests, rollout operations).  
**Lưu ý phạm vi:** Báo cáo này **chỉ xác nhận hoàn thành subtask backend cụ thể này**, không tuyên bố toàn bộ dự án egress đã hoàn thành hay đã được xác thực trên môi trường production/remote. Toàn bộ kiểm thử được thực thi cục bộ qua PGlite in-memory, không kết nối cơ sở dữ liệu từ xa.

---

## 1. Danh sách tệp can thiệp

1. **`supabase/migrations/085_egress_optimization_sync_v2.sql`** (Chỉnh sửa)
   - Thêm 4 cột cờ vận hành vào `public.app_configs` sử dụng `ADD COLUMN IF NOT EXISTS` với ràng buộc `NOT NULL DEFAULT false`.
   - Tuyệt đối không chứa lệnh `UPDATE` giá trị cờ khi migration được áp dụng lại (idempotent & value-preserving).
   - Chuẩn hóa các hàm tích hợp trong `_sync_block`: sử dụng định danh tường minh `pg_catalog.encode`, `pg_catalog.sha256`, `pg_catalog.convert_to`, `pg_catalog.jsonb_typeof`, `pg_catalog.jsonb_build_object` cùng `SET search_path TO 'pg_catalog', 'public'` nhằm ngăn chặn shadowing schema công khai.
   - Siết chặt quyền truy cập helper `_sync_block`: thu hồi quyền từ `PUBLIC, anon`, chỉ cấp cho `authenticated, service_role`; hàm được gọi an toàn bên trong các hàm wrapper `SECURITY DEFINER`.

2. **`supabase/tests/run-egress-flags-v2-test.mjs`** (Tạo mới)
   - Bộ kiểm thử hành vi PGlite độc lập sử dụng `createDb` và `read` từ `baseline-lib.mjs`.
   - Bao phủ 6 trường hợp kiểm thử nghiêm ngặt:
     1. Cả 4 cờ đều mặc định `false` cho bản ghi `android_client`.
     2. Bật 1 cờ, áp dụng lại migration 085, xác minh giá trị `true` được bảo toàn nguyên vẹn.
     3. Từng cờ có thể bật/tắt độc lập không gây ảnh hưởng chéo.
     4. Tắt toàn bộ cờ (rollback) đưa cả 4 cờ về `false`.
     5. Chữ ký, định nghĩa và quyền EXECUTE của các hàm legacy (`get_home_feed`, `get_my_dashboard`, `get_campaign_detail`) hoàn toàn không bị thay đổi khi áp dụng lại migration.
     6. Quyền đọc của role `anon` trên `public.app_configs` tuân thủ đúng chính sách bảo mật/RLS hiện hữu (cho phép đọc thông tin cấu hình, chặn quyền ghi/sửa).

3. **`supabase/operations/egress_sync_v2_rollout.sql`** (Tạo mới)
   - Tạo mẫu kịch bản vận hành chi tiết, phân tách rõ ràng từng transaction độc lập để bật từng endpoint theo từng giai đoạn (Phase 1 -> 4).
   - Cung cấp transaction rollback khẩn cấp đưa tất cả 4 cờ về `false`.
   - Có truy vấn SELECT kiểm tra trạng thái trước và sau mỗi bước.
   - Tài liệu hóa rõ cơ chế lan truyền cấu hình và các cảnh báo vận hành. Tệp này **không được tự động thực thi**.

4. **`audit/egress-safe-2026-11-10/BACKEND_FLAGS_RESULT.md`** (Tệp hiện tại)
   - Ghi nhận chi tiết kết quả kiểm thử, thay đổi kỹ thuật, cơ chế hoạt động và các giới hạn vận hành.

---

## 2. Chi tiết 4 Cờ Vận Hành trên `public.app_configs`

Khớp chính xác với các Serial Name trong Kotlin `AppConfig`:
- `egress_sync_home_feed_v2_enabled` (`boolean NOT NULL DEFAULT false`)
- `egress_sync_dashboard_v2_enabled` (`boolean NOT NULL DEFAULT false`)
- `egress_sync_campaign_detail_v2_enabled` (`boolean NOT NULL DEFAULT false`)
- `egress_candidates_v2_enabled` (`boolean NOT NULL DEFAULT false`)

### Tính tương thích ngược (Backward Compatibility):
- Ứng dụng Android đọc bảng `public.app_configs` qua lệnh PostgREST table select (`/rest/v1/app_configs?id=eq.android_client`).
- Việc bổ sung các cột mới kèm giá trị mặc định `false` là thay đổi mang tính cộng dồn (additive schema change), hoàn toàn tương thích ngược với các phiên bản client cũ lẫn mới.
- Client chưa bật cờ hoặc chạy phiên bản cũ sẽ tiếp tục gọi các RPC legacy mà không gặp bất kỳ xung đột nào.

---

## 3. Kết Quả Kiểm Thử Cục Bộ (PGlite Tests)

### Lệnh 1: `node supabase/tests/run-egress-flags-v2-test.mjs`
```text
=== LOADING ALL MIGRATIONS IN ORDER ===
Loaded 24 migrations successfully.

--- TEST 1: Default Flag Values ---
PASS: All four flags default false for android_client
--- TEST 2: Reapply Migration Idempotency & Value Preservation ---
PASS: True flag value preserved after migration 085 reapplication
--- TEST 3: Flag Independence ---
PASS: Individual flags toggle independently without cross-interference
--- TEST 4: Disable All Rollback ---
PASS: Rollback resets all 4 flags to false
--- TEST 5: Legacy RPC & Function Metadata Stability ---
PASS: Legacy functions and grants unchanged; _sync_block protected
--- TEST 6: Anon Read & RLS Policies on app_configs ---
PASS: Anon read succeeds and respects security policies

======================================================
ALL BACKEND FLAGS V2 TESTS PASSED SUCCESSFULLY!
======================================================
```
*Kết quả:* **Thành công 100% (Exit code 0).**

### Lệnh 2: `node supabase/tests/run-egress-sync-v2-test.mjs`
```text
=== LOADING ALL MIGRATIONS IN ORDER (INCLUDING 085) ===
MIGRATIONS LOADED SUCCESSFULLY (24 migrations).
=== BASE FIXTURES SEEDED ===
--- TEST 1: Home Feed Sync V2 ---
PASS: Home Feed Sync V2
--- TEST 2: Dashboard Sync V2 ---
PASS: Dashboard Sync V2
--- TEST 3: Campaign Detail Sync V2 ---
PASS: Campaign Detail Sync V2
--- TEST 4: Next Campaign Candidates V2 ---
Candidates count: 1
PASS: Next Campaign Candidates V2

======================================================
ALL EGRESS SYNC V2 DATABASE TESTS PASSED WITH COMPLETE PARITY!
======================================================
```
*Kết quả:* **Thành công 100% (Exit code 0).**

---

## 4. Cơ chế Phân Phối Cấu hình và Giới Hạn Vận Hành

> [!IMPORTANT]
> **Cơ chế lan truyền đến Client:**
> Cấu hình cờ tính năng không được đẩy tức thì qua WebSocket hay Push Notification. Thay vào đó, ứng dụng Android nhận cấu hình thông qua chu kỳ nạp của `AppConfigRepository` (khi khởi động ứng dụng, nạp định kỳ, hoặc khi người dùng làm mới/thực hiện kiểm tra thủ công).

> [!WARNING]
> **Giới hạn Rollback:**
> Khi thực thi kịch bản rollback trong `egress_sync_v2_rollout.sql`, giá trị cờ trên cơ sở dữ liệu sẽ chuyển về `false` ngay lập tức, nhưng thiết bị client chỉ áp dụng thay đổi ở lần nạp cấu hình kế tiếp hoặc sau khi khởi động lại ứng dụng. Không khẳng định đây là cơ chế rollback tức thời trên thiết bị người dùng.
