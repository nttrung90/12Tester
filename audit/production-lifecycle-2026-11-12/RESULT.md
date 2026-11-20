# Sửa lỗi xác nhận app đã lên production — 12/11/2026

## Nguyên nhân đã tái hiện

Android gửi PATCH `campaigns` với `status = 'production'`, `paused_reason = null`.
Migration 087 thêm BEFORE trigger `trg_campaigns_bonus_round_lifecycle`.
PostgreSQL chạy các BEFORE trigger theo tên, khiến trigger này thay đổi
`tester_bonus_status`/`tester_bonus_remaining_slots` trước khi
`trg_campaigns_guard_financial_snapshots` kiểm tra dữ liệu.
Guard từ chối với `PERMISSION_DENIED: TESTER_BONUS_SUMMARY_IMMUTABLE`.
Lỗi xảy ra ngay cả với app chưa từng có đợt thưởng, vì trigger cũ luôn gán
`tester_bonus_status = 'closing'`.

## Bản sửa

- Thêm migration `supabase/migrations/089_fix_production_bonus_lifecycle.sql`.
- Đổi tên trigger để kiểm tra dữ liệu từ client trước, rồi mới chạy xử lý đóng đợt thưởng.
- Chỉ thay đổi tóm tắt thưởng khi thực sự chuyển một đợt active sang closing.
- Giữ nguyên guard tài chính, RLS và quy định app đang bị Admin tạm dừng.
- Không sửa mã Android, phiên bản ứng dụng hoặc tạo APK.

Tab Đã hoàn thành đã lọc đúng `production` và `completed`, cả đường truy vấn
trực tiếp, `get_home_feed` và `get_home_feed_sync_v2`.
`CampaignRepository.updateCampaignStatus` đã gọi `invalidateCampaign`, xóa
cache các danh sách và dashboard sau khi cập nhật thành công.

## Kiểm thử

- `node supabase/tests/run-production-lifecycle-test.mjs`: đạt; tái hiện lỗi trước
  bản sửa với role authenticated thật, rồi xác nhận chuyển production thành công,
  completed_at, tab Đã hoàn thành, token đồng bộ thay đổi, gọi lại an toàn,
  app tạm dừng thủ công/tự động/hết slot, quyền sở hữu, Admin pause, chống sửa
  dữ liệu thưởng, số dư không đổi và hoàn thành tự động.
- `node supabase/tests/run-campaign-tester-bonus-test.mjs`: đạt 17 nhóm kiểm thử.
- `node supabase/tests/run-owner-tester-bonus-removal-test.mjs`: đạt 8 nhóm kiểm thử.
- Một kiểm thử cache Android được thử chạy nhưng Gradle wrapper bị chặn quyền
  ghi tại `C:\Users\nguye\.gradle\wrapper\dists`; chưa chạy được kiểm thử Android.

Các kiểm thử SQL dùng PostgreSQL PGlite cục bộ và nạp chuỗi migration 062–089.
Không kết nối hoặc thay đổi cơ sở dữ liệu production.

## Áp dụng

Chạy nội dung migration 089 trong SQL Editor của đúng dự án Supabase đã áp dụng
migration 087. Migration có transaction và có thể chạy lại an toàn. Không cần
phát hành APK mới cho bản sửa này.

Sau khi áp dụng, đăng nhập bằng chủ app, mở Quản lý app, xác nhận App đã lên
production rồi quay lại Trang chủ → Đã hoàn thành. Kiểm tra cả app không có
đợt thưởng và app đang có đợt thưởng. App do Admin tạm dừng vẫn bị chặn theo
quy định hiện tại.
