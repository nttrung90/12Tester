# Cài Dual Storage khi 091 bị lỗi quyền Storage

Ngày cập nhật: 17/11/2026.

## Vai trò các file

| File | Vai trò |
|---|---|
| 091 | Cài nền tảng Dual Storage, bảng theo dõi upload, quota và RPC |
| 092 | Sửa tính đúng đắn của 091, khôi phục nghiệp vụ báo cáo và tăng độ chặt của kiểm tra media/cleanup |
| 093 | Sửa tiếp preflight upload, giữ quota cho write đến muộn, trạng thái đối soát và lịch sử hoàn tất từng path |
| 094 | Sửa nhiệm vụ ngày/carryover; không sửa lỗi tạo bảng trong schema Storage |

092/093 cần các đối tượng do 091 tạo ra, nên chạy chúng trước không khắc phục được lỗi 42501 tại dòng tạo storage.buckets.

Supabase hosted quản lý schema `storage` và hạn chế tạo bảng trong đó. File 091 gốc chứa đoạn bootstrap dùng được trong môi trường kiểm thử trống nhưng không phù hợp để tạo lại các bảng Storage trên hosted. `IF NOT EXISTS` không khắc phục được quyền CREATE bị thiếu; kiểm thử hạn chế quyền đã tái hiện đúng lỗi này.

## Cách chạy cho trường hợp 091 chưa cài thành công

Điều kiện: các migration ứng dụng đến 090 đã được áp dụng; 091 chưa cài, 092/093 chưa chạy. Nếu bảng Dual Storage đã tồn tại hoặc đã có một lần cài dở, bundle sẽ dừng để tránh replay lịch sử trên dữ liệu đang dùng.

1. Trong **Storage** của Supabase Dashboard, tạo hoặc chỉnh cấu hình bucket:

   | Thuộc tính | Giá trị |
   |---|---|
   | Tên / ID | `report-screenshots` |
   | Public | Tắt, bucket private |
   | Giới hạn mỗi file | 2 MB; bundle chấp nhận 2.000.000 đến 2.097.152 byte |
   | MIME được phép | `image/jpeg`, `image/png`, `image/webp` |

   Dùng chức năng cấu hình bucket hiện có nếu đã có bucket; không cần xóa bucket hay file đang lưu.

2. Mở **SQL Editor**, dùng role migration `postgres`, mở toàn bộ file:

   `supabase/operations/091_093_dual_storage_hosted_install.sql`

   Chạy toàn bộ file một lần. File đã chứa **091 → 092 → 093**, với một transaction bao ngoài. Không chạy lại ba file gốc sau khi bundle thành công.

3. Sau khi bundle thành công, chạy riêng:

   `supabase/migrations/094_fix_daily_task_completion_and_carryover.sql`

Bundle không tạo/đổi cấu trúc bảng Storage, đổi chủ sở hữu hoặc ghi/xóa metadata trong các bảng Storage. Nó giữ việc tạo RLS policy được Supabase hỗ trợ và triển khai các bảng/hàm ứng dụng trong `public`. Preflight kiểm tra bucket, RLS, các bảng nền tảng và xung đột tên policy trước khi thay đổi ứng dụng. Nếu thiếu điều kiện, đọc thông báo và xử lý điều kiện đó thay vì bỏ qua kiểm tra.

Không cần cấp quyền CREATE trên schema Storage. Bundle không tự cập nhật `supabase_migrations.schema_migrations`; đây là đường cài thủ công cho SQL Editor, không thay đổi lịch sử migration của CLI.

Sau khi cài, metric quota được đánh dấu chưa hợp lệ và định tuyến upload mới sang Google cho đến khi cấu hình org/trusted host và lần đối soát thực tế hợp lệ hoàn tất. Đây là cơ chế fail-safe của 093, không phải bằng chứng Supabase upload đã được smoke test.

## Kiểm chứng và tái tạo

- Giữ nguyên checksum nguồn 091, 092, 093.
- 10/10 kiểm tra PGlite đạt: tái hiện 42501 với CREATE bị thu hồi, từ chối thiếu/sai bucket, RLS tắt, xung đột policy; rollback toàn bộ nếu lỗi cuối quá trình; cài thành công mà vẫn không có CREATE Storage; bảo toàn metadata/quyền sở hữu/policy không liên quan; từ chối replay; so khớp 262 định nghĩa hàm cuối; áp dụng 094 tiếp theo thành công.
- Mô phỏng này không thay thế chạy thật trên Supabase hosted và không kiểm chứng Storage API hoặc scheduler thực tế.

Tái tạo file và chạy kiểm tra:

```powershell
node supabase/tools/build-dual-storage-hosted-install.mjs
node supabase/tests/run-dual-storage-hosted-install-test.mjs
```

Bằng chứng: `audit/dual-storage-hosted-install-2026-11-17/results.json`.

## Nguồn kỹ thuật chính thức

- [Supabase: hạn chế thao tác trên Auth/Storage/Realtime](https://supabase.com/changelog/34270-restricting-access-on-auth-storage-and-realtime-schemas-on-april-21-2025): hạn chế tạo bảng, vẫn cho quản lý RLS policy trên các bảng được liệt kê.
- [Supabase: tạo bucket](https://supabase.com/docs/guides/storage/buckets/creating-buckets).
- [Supabase: thiết kế Storage schema](https://supabase.com/docs/guides/storage/schema/design).
