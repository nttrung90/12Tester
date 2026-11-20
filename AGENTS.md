# 12Tester — hướng dẫn đọc và sửa mã

## Bắt đầu gọn

- Đọc `docs/AI_NAVIGATION.md` để chọn đúng thư mục theo tác vụ. Không đọc toàn bộ báo cáo/kế hoạch hoặc toàn bộ migrations để bắt đầu một lỗi nhỏ.
- Tìm trong phạm vi hẹp trước. Có `rg` thì dùng đường dẫn cụ thể; nếu máy thiếu `rg`, dùng `node tools/ai-search.mjs --scope android "từ khóa"` (hoặc `backend`, `sql`, `tests`, `docs`). Tìm literal mặc định, không cần escape regex.
- `audit/`, `runs/`, báo cáo/kế hoạch ở gốc và `supabase/migrations_archive/` là nguồn lịch sử: chỉ mở có mục tiêu khi cần tái hiện, nguồn bằng chứng hoặc test có tham chiếu. Không quét các cây này trong lượt tìm mã sản phẩm mặc định.
- Mở rộng phạm vi nếu chưa tìm đủ; không coi kết quả tìm bị giới hạn hoặc phạm vi hẹp là bằng chứng đối tượng không tồn tại.

## SQL: xác định đúng phiên bản

- Giữ nguyên cấu trúc migrations hiện có. Chưa thực hiện kế hoạch gộp/di chuyển migrations chỉ để tối ưu AI.
- Trước khi sửa RPC, dùng `node tools/ai-sql-index.mjs find ten_ham` để xem chữ ký và migration thay đổi định nghĩa cuối. Lệnh kiểm tra checksum nguồn; nếu cũ/thiếu, chạy `node tools/ai-sql-index.mjs build` rồi tìm lại.
- Chỉ mục là công cụ định vị, không phải SQL triển khai và không chứng minh production đã áp dụng. Đọc SQL thật và các thay đổi tiếp theo về quyền, trigger, bảng, dữ liệu.
- Không lấy định nghĩa trong baseline hoặc archive làm bản hiện hành khi migration phía sau đã thay thế. Phân biệt overload theo chữ ký; giữ wrapper còn được client/service sử dụng.
- Không sửa file đã triển khai để thay backend đang chạy. Khi cần thay backend, tạo migration tiếp nối theo quy trình dự án, giữ nguyên lịch sử và kiểm thử đường nâng cấp.
- Không chạy database reset, replay archive hoặc backfill cũ trên database đang dùng để giải quyết vấn đề tìm mã.

## Thay đổi và kiểm chứng

- Kiểm tra thay đổi có sẵn trong phần sẽ sửa; không reset/clean hoặc ghi đè việc người dùng đang làm.
- Chạy test phù hợp với phần thay đổi; `supabase/tests/package.json` không bao phủ mọi test SQL mới. Test PostgreSQL nhiều kết nối thiếu `TEST_DATABASE_URL` là chưa chạy, không phải PASS.
- Thay đổi tài liệu/công cụ định vị không cần build Android hoặc kết nối production. Không tự tăng phiên bản, build APK/AAB hoặc deploy cho tác vụ tối ưu cách AI đọc mã.
- Chỉ mục sinh từ source phải được tạo lại sau thay đổi migrations. Không sửa tay file `docs/ai/sql-index.json`.
