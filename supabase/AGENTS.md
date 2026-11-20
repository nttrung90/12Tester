# Supabase — chỉ dẫn định vị

- Quy tắc chung: `../AGENTS.md`; bản đồ tính năng: `../docs/AI_NAVIGATION.md`.
- `migrations/` là chuỗi triển khai hiện tại. `migrations_archive/` là lịch sử, vẫn cần cho một số bài test; không xóa, chuyển hoặc bỏ qua file khi bootstrap.
- `node tools/ai-sql-index.mjs find ten_ham` chạy từ gốc dự án kiểm tra nguồn và hiển thị từng chữ ký, file đầu tiên và file thay đổi định nghĩa cuối. `lastDefinitionChange` không bao phủ các thay đổi ACL, trigger, DML hoặc dependency không đổi thân hàm.
- Chỉ mục dùng PGlite cô lập và hash nguồn, không phản ánh database production. Hints dòng là các ứng viên cùng tên trong file, có thể gồm nhiều overload; đọc đúng chữ ký.
- Khi sửa SQL, tìm thêm tên bảng, trigger, GRANT/REVOKE trong migrations hiện hành và caller Android/backend. Mở archive khi cần lịch sử hoặc test trước/sau, không áp dụng nó lên server thật.
- Để cập nhật chỉ mục: `node tools/ai-sql-index.mjs build`. Cần dependency hiện có ở `supabase/tests/node_modules`; không tự tải gói hoặc sửa migration để làm công cụ chạy.
