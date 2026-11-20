# Lịch sử trước baseline 062 — không dùng để triển khai

65 migration 001–061 đã được chuyển nguyên trạng vào đây. `full_schema_singapore.sql` là bản gộp cũ, được giữ nguyên để tái lập các test lịch sử; nội dung và hướng dẫn đầu file có thể không phản ánh trạng thái cuối 061.

File triển khai hiện hành: `../../migrations/062_consolidated_baseline.sql`. Các migration mới bắt đầu từ 063 và đặt trong `supabase/migrations/`.

Không chạy lại các file trong thư mục này trên database đang dùng. Đặc biệt, các đoạn backfill cũ có thể reset streak, cấp grace days hoặc thay đổi dữ liệu cấu hình. Kho lưu trữ không phải danh sách migration cần chạy sau 062.

Hướng dẫn: `../../operations/HUONG_DAN_BASELINE_062.md`.
