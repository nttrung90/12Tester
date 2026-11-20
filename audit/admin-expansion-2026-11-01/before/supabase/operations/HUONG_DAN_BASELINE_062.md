# Chạy migration nền 062 và các migration tiếp theo

## File cần chạy

`supabase/migrations/062_consolidated_baseline.sql` là nguồn SQL triển khai baseline duy nhất.

File giữ một định nghĩa cuối cho mỗi chữ ký hàm, thay cho việc chép nối 65 migration. Bao gồm đủ schema, hàm, RLS, policies, triggers, indexes và constraints từ 001–061, có cả Media Gateway 046 và bản sửa receipt/rating 061.

Trường hợp database được chuẩn bị và kiểm tra: **đã chạy tất cả tới 061, còn thiếu 046**. File cũng dùng được nếu đã chạy cả 046, hoặc trên project Supabase mới chưa có các bảng ứng dụng. Schema cũ/thiếu các phần khác sẽ bị chặn ở bước kiểm tra, toàn bộ giao dịch rollback.

## Chạy trên database hiện có

1. Trong đúng project Supabase, mở **SQL Editor → New query**, dùng quyền **postgres/database owner**.
2. Mở `062_consolidated_baseline.sql`, copy **toàn bộ file**, dán và chạy toàn bộ query. File đã có `BEGIN`/`COMMIT`; không chọn chạy riêng một đoạn ở giữa.
3. Kết quả cuối phải có một dòng `version = 062`, `existing_database = true`.

Không cần chạy riêng 046 trước hoặc sau file này. Không chạy lại 001–061 hoặc schema gộp cũ.

Trong workspace hiện có thêm bản vá mới `063_report_crypto_search_path.sql`: **chạy tiếp 063 sau 062**. Bản vá cho phép hàm nộp báo cáo tìm `pgcrypto.digest()` cả khi extension nằm trong schema `extensions` trên Supabase. Đây là migration mới sau baseline, không thuộc 65 file lịch sử được gộp. Các thay đổi sau bản vá này bắt đầu từ 064.

062 không chạy lại các backfill/reset lịch sử. Các hàng hiện có trong bảng ứng dụng, tài khoản auth, số dư Credit, report, receipt, streak, nhiệm vụ đã chốt, cấu hình giá, lịch chính sách và audit được giữ nguyên. Sequences không bị reset hoặc tăng thêm. Cron và thiết lập secret/URL đang dùng cũng được giữ nguyên. Với report cũ còn thiếu cột của 046, bổ sung `media_expired` có mặc định `false`.

Các hàm được cập nhật về định nghĩa cuối từ source 061. Quyền hiện có trên các bảng/cột và các hàm ngoài Media Gateway được giữ; các hàm Media Gateway nhận đúng phần grant/revoke của 046.

Nếu gặp `BASELINE_062_SCHEMA_NOT_READY`, `BASELINE_062_CONSTRAINT_DRIFT` hoặc lỗi khác, giao dịch không được đánh dấu thành công. Cần đọc đối tượng được nêu trong lỗi và đối chiếu schema; không bỏ các bước kiểm tra trong file.

## Media Gateway 046

062 bổ sung bốn hàm `get_media_gateway_secret`, `get_report_media_access`, `verify_media_access_grant`, `verify_media_access_grant_internal` và cột `media_expired`.

File không chứa secret mặc định và không tự deploy backend gateway. Các thiết lập `app.settings.media_gateway_secret` và `app.settings.media_gateway_base_url` vẫn phải được cấu hình theo service gateway khi dùng chức năng này. Nếu chưa cấu hình, RPC cấp URL media sẽ báo thiếu cấu hình; không tự chuyển sang secret hardcode. Việc khởi tạo schema 062 không yêu cầu gọi RPC này.

## Chạy lại 062

062 lưu dấu mốc trong `crosstest_migrations.baselines`, một schema nội bộ không cấp quyền cho API roles. Khi đã có dấu mốc đúng source checksum, file bỏ qua toàn bộ payload. Vì thế chạy lại 062 sau khi đã chạy 063 sẽ không phục hồi định nghĩa hàm cũ hoặc ghi đè thay đổi mới.

Không xóa dấu mốc để ép chạy lại. Sau khi đã triển khai 062, mọi sửa đổi phải nằm trong migration tiếp theo.

## Migration tiếp theo

Tạo `supabase/migrations/063_ten_thay_doi.sql`, rồi `064_...sql`, theo số tăng dần. Mỗi file chỉ chứa thay đổi mới, không chép lại toàn bộ baseline và không sửa 062 đã triển khai.

Khung cho một migration chạy bằng SQL Editor:

```sql
begin;

do $$
begin
    if not exists (
        select 1 from crosstest_migrations.baselines where version = '062'
    ) then
        raise exception 'APPLY_BASELINE_062_FIRST';
    end if;
end;
$$;

-- Các thay đổi riêng của 063 ở đây.

notify pgrst, 'reload schema';
commit;
```

Quy trình SQL Editor hiện tại dùng dấu mốc nội bộ trên. Dấu mốc này không thay thế hay tự sửa bảng history của Supabase CLI nếu sau này chuyển sang quản lý bằng CLI.

## Database mới

Trên project Supabase mới, chỉ cần chạy 062 rồi các file từ 063 trở đi. Kết quả 062 có `existing_database = false`.

Database mới nhận schema cuối và dữ liệu cấu hình mặc định. Nếu `pg_cron` đã được bật, 062 tạo năm lịch mặc định tương ứng lịch sử cũ. Nếu chưa bật, không tự cài pg_cron; có thể cấu hình scheduler riêng sau. Database hiện có giữ lịch cron đang dùng.

## Kho lưu trữ và kiểm tra

`supabase/migrations_archive/pre_baseline_062/` chứa nguyên 65 migration 001–061 và `full_schema_singapore.sql` cũ. Chúng chỉ dùng để tra cứu, tái lập lịch sử và chạy test nâng cấp. Các test hồi quy cũ đã được đổi đường dẫn tới kho này; test mới đối chiếu trực tiếp baseline với trạng thái cuối của toàn bộ lịch sử.

```powershell
cd F:\AI\12Tester\supabase\tests
npm run test:baseline
npm test
```

Kết quả baseline: `audit/migration-baseline-062-2026-10-30/test-results.json`. Manifest và checksum: `audit/migration-baseline-062-2026-10-30/manifest.json`.

`supabase/tools/build-baseline.mjs` là công cụ tái lập trong giai đoạn chuẩn bị 062, không phải bước tạo migration hằng ngày. Không dùng nó để thay nội dung 062 sau khi 062 đã chạy trên môi trường dùng chung.
