# TIẾN ĐỘ THỰC HIỆN: ĐỢT THƯỞNG THÊM CHO TESTER (CAMPAIGN TESTER BONUS ROUNDS)

> **Kế hoạch gốc:** `KE_HOACH_THUONG_THEM_CHO_TESTER_2026-11-11.md`  
> **Cập nhật ngày:** 11/11/2026  

---

## 1. Mục tiêu Dự án
Triển khai tính năng cho phép chủ ứng dụng mở các đợt thưởng thêm credit cho tester để khuyến khích nộp báo cáo chất lượng cao:
- Chủ app ký quỹ 100% ngân sách (`reward_credits * max_recipients`), không mất phí nền tảng.
- Trao thưởng theo thứ tự duyệt báo cáo ("first eligible approvals/award transactions").
- Mỗi tester duy nhất nhận tối đa 1 phần thưởng trong 1 đợt thưởng.
- Không reset lúc nửa đêm (00:00).
- Bảo toàn tuyệt đối ngân sách (`reserved = remaining + spent + refunded`).
- Sau khi hết suất, tester vẫn nộp báo cáo và nhận thưởng cơ bản bình thường.

---

## 2. Trạng thái các Milestone

### ✅ MILESTONE 1: BACKEND VÀ HỢP ĐỒNG API (HOÀN THÀNH)
- [x] **Phân tích Khóa:** Viết `LOCK_ORDER.md` phân tích và quy định thứ tự khóa 6 cấp đơn hướng (`Advisory -> tester_reports -> campaigns -> rounds -> profiles -> ledgers`) phá vỡ hoàn toàn chu kỳ deadlock.
- [x] **Migration 087:** Soạn thảo `supabase/migrations/087_campaign_tester_bonus_rounds.sql`:
  - 3 bảng mới: `campaign_tester_bonus_rounds`, `campaign_tester_bonus_awards` (có `owner_id`), `campaign_tester_bonus_events` (có `owner_id`).
  - Cột mới: `tester_bonus_round_id`, `tester_bonus_awarded` trên `tester_reports`; summary fields trên `campaigns`.
  - Triggers & RLS: Chặn sửa trực tiếp từ client, bảo vệ tính bất biến của snapshot tài chính và chặn xóa trực tiếp ứng dụng khi còn nghĩa vụ thưởng.
  - RPCs: `create_campaign_tester_bonus`, `reconcile_campaign_tester_bonus`, `get_campaign_tester_bonus`, `close_campaign_tester_bonus`.
  - Tích hợp vòng đời: `_submit_tester_report_core` (bảo toàn tiền điều kiện ngày, context mission, attachments), `_impl_approve_tester_report`, `auto_accept_expired_reports`, `_impl_admin_delete_campaign`, `_campaign_json`.
- [x] **Kiểm thử Hành vi & RLS PGlite:** `supabase/tests/run-campaign-tester-bonus-test.mjs` với 12 test toàn diện: **PASS 12/12**.
- [x] **Kiểm thử Bảo mật Quyền riêng tư:** `audit/tester-bonus-2026-11-11/review-bonus-privacy.mjs`: **PASS**. Không rò rỉ sổ cái tài chính cho tester dù có default grants.
- [x] **Kiểm thử Hồi quy Toàn diện:**
  - `run-mission-reward-modes-test.mjs`: **PASS 100% (15/15 kịch bản)**.
  - `run-carryover-fix-acceptance-test.mjs`: **PASS 100%**.
  - `run-egress-sync-v2-test.mjs`: **PASS 100%** (xác minh token invalidation khi thay đổi slot).
  - `run-app-mission-window-test.mjs`: **PASS 100%**.
  - `run-owner-auto-pause-test.mjs`: **PASS 100%**.
  - `run-business-policies-test.mjs`: **PASS 100%**.
  - `run-campaign-posting-policies-test.mjs`: **PASS 100%**.
- [x] **Thiết kế Kiểm thử Concurrency Đa kết nối:**
  - `supabase/tests/campaign-tester-bonus-concurrency-fixture.mjs` (Fixture & cleanup).
  - `supabase/tests/run-campaign-tester-bonus-concurrency-fixture-test.mjs`: **PASS**.
  - `supabase/tests/run-campaign-tester-bonus-concurrency-test.mjs`: Driver multi-connection cho native PostgreSQL, sẵn sàng cho Parent supervisor nghiệm thu.
- [x] **Tài liệu bàn giao:**
  - `API_CONTRACT.md`: Đặc tả DTOs Kotlin, mã lỗi, durability request ID, chiến lược fallback khớp 100% chữ ký SQL thực tế.
  - `BACKEND_RESULT.md`: Tổng hợp kết quả thực thi và kế hoạch rollout/rollback.

---

### ⏳ MILESTONE 2: ANDROID APP INTEGRATION (CHỜ PARENT REVIEW & NGHIỆM THU TEST BACKEND)
- [ ] Chờ Parent supervisor nghiệm thu kết quả Milestone 1 và chạy kiểm thử concurrency trên PostgreSQL cục bộ độc lập.
- [ ] Nhận bàn giao và phân bổ nhiệm vụ Android Milestone.
- [ ] Cập nhật Android Models & Repository DTOs theo `API_CONTRACT.md`.
- [ ] Cập nhật UI/UX:
  - Thẻ chiến dịch trên Trang chủ & Danh sách: hiển thị huy hiệu thưởng thêm (bonus badge).
  - Chi tiết chiến dịch: hiển thị số suất còn lại, mức thưởng thêm và trạng thái đã nhận / chưa nhận của tester.
  - Màn hình chi tiết chủ app: nút và hộp thoại "Tạo đợt thưởng tester" (với số credit, số người, tính toán ngân sách, idempotency UUID).
- [ ] Viết Unit Tests & Regression Tests cho Android.
