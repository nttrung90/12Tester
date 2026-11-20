# Báo cáo Khoảng cách Backend (Android Backend Gaps — Admin Core Chặng A/B)
Ngày cập nhật: 01/11/2026
Tác giả: Core Android (`ses_e694e3214ffeA65KCtmBe1HUpC`)

---

## 1. Trạng thái hết hạn ảnh trong Hàng đợi Báo cáo (`admin_get_tester_reports_queue`)
- **Tình trạng hiện tại**: Backend 070 (A5) đã hoàn thiện việc chiếu trực tiếp cột `tr.media_expired` (`BOOLEAN`) vào kết quả của `public.admin_get_tester_reports_queue`.
- **Phía Android**: DTO `AdminTesterReportQueueItem` đã nhận trường `@SerialName("media_expired") val mediaExpired: Boolean? = null` và ánh xạ đầy đủ sang `TesterReport.mediaExpired` cũng như `ScreenshotDescriptor.mediaExpired`. Toàn bộ luồng hiển thị thumbnail và ảnh phóng to toàn màn hình đều đi qua `ScreenshotRequestFactory` và `FullscreenImageViewer` có ngữ cảnh bảo vệ và hết hạn chính xác. Không có khoảng cách cần sửa đổi thêm ở phần này.

## 2. Các trường mở rộng từ Migration 071 trong Hàng đợi Báo cáo
- **Trường dữ liệu**: Migration 071 đã mở rộng hàng đợi với:
  - `review_tester_bonus` (`INTEGER`)
  - `review_owner_bonus` (`INTEGER`)
  - `business_policy_id` (`BIGINT`)
  - `provisional_granted_credits` (`INTEGER`)
  - `provisional_clawed` (`INTEGER`)
  - `bonus_awarded` (`INTEGER`)
- **Phía Android**: DTO `AdminTesterReportQueueItem` đã tích hợp đầy đủ các trường tùy chọn này. Giao diện duyệt báo cáo mới hiển thị huy hiệu thưởng duyệt cấu hình thực tế (`item.effectiveTesterBonus`, fallback 2) và giải thích rõ ràng việc duyệt của admin không nhận thưởng chủ ứng dụng. Hộp thoại duyệt đã loại bỏ ô nhập điểm thưởng tùy ý, gọi RPC với tham số tương thích `bonus = 0` theo đúng quy ước backend.

## 3. Các điểm đã được Backend bổ sung & Client tích hợp hoàn tất
- **Mảng Slot thực tế**: `public.admin_get_member_overview` trả về mảng `slots` (tối đa 6 slot) kèm thông tin `slot_number`, `is_active`, `unlocked_at`, `expires_at`, `current_campaign`. Đã hiển thị đầy đủ trên thẻ tổng quan thành viên.
- **RPC Chi tiết Slot**: `public.admin_get_member_slots` đã sẵn sàng trong `AdminManagementRepository`.
- **Phân trang Carryover sâu**: `public.admin_get_carryover_policy_history` đã được tích hợp vào `AdminManagementRepository` và `CarryoverPolicyViewModel`, hiển thị đầy đủ nhật ký thay đổi và các sự kiện hủy lịch (`is_cancelled: true`).
- **Idempotency Replay trước CAS**: Toàn bộ mutation quản trị (carryover schedule/cancel, warning revoke, credit compensation, retry settlement jobs, role change) đều hỗ trợ replay an toàn trước CAS revision check.
