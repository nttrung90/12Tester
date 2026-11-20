# Vì sao dự án có nhiều migrations?

Ngày kiểm tra: 30/10/2026, múi giờ Việt Nam. Phạm vi: mã nguồn và lịch sử Git tại `F:\AI\12Tester`, bao gồm các thay đổi chưa commit đang có sẵn. Không truy cập database production và không sửa mã ứng dụng hay SQL nghiệp vụ.

## Kết luận

Dự án có nhiều migrations vì đặt phần lớn nghiệp vụ backend trong PostgreSQL, bổ sung tính năng nhanh, và thường phải thêm bản vá sau mỗi vòng kiểm tra. Số lượng còn tăng bởi các lần vá thiếu schema khi triển khai thủ công. Việc duy trì thêm một file schema gộp bằng tay làm tăng công sức và nguy cơ lệch phiên bản.

**65 file không đồng nghĩa với 65 lần chuyển dữ liệu trên mỗi điện thoại.** Các file đang xét ở `supabase/migrations/`, ngoài module Android; luồng khởi tạo app dùng Supabase client. Không tìm thấy cơ chế chạy chuỗi SQL này khi app khởi động trong mã đã kiểm tra. Số file tự nó không chứng minh app chậm; hiệu năng phải đánh giá ở các hàm, truy vấn, trigger và dữ liệu thực tế.

## Số liệu kiểm kê

| Chỉ số | Kết quả |
|---|---:|
| File SQL trong migrations | 65 |
| Số thứ tự cao nhất | 061 |
| Tổng dòng, đếm bằng cách tách ký tự xuống dòng | 33.035 |
| Tổng dung lượng | 1.314.108 byte, khoảng 1,25 MiB |
| File có `fix` hoặc `harden` trong tên | 18 |
| File đã được Git theo dõi | 58 |
| File chưa được Git theo dõi | 7, từ 055 đến 061 |
| Số dòng của schema gộp | 26.714 |

Các số `002`, `007`, `008`, `013` đều có hai file. Đây là lý do có 65 file dù mới tới 061. Chưa theo dõi trong Git không cho biết migration đã được áp dụng lên production hay chưa.

## Nguyên nhân và bằng chứng

### 1. Migrations chứa cả mã nghiệp vụ backend

Chúng không chỉ tạo bảng hoặc thêm cột. Chúng định nghĩa các RPC, kiểm soát quyền, chống gửi trùng, phát thông báo, cộng/trừ Credit, duyệt báo cáo, nhiệm vụ và lịch chạy nền.

Vì vậy các thay đổi như thưởng 10 Credit (037), nhiệm vụ (039–040), thumbnail (045), thưởng cập nhật app (050), đẩy app (052), cộng dồn nhiệm vụ (053), đa ngôn ngữ backend (055) và cấu hình nhiệm vụ qua admin (058) đều cần triển khai SQL.

Lịch sử Git cho thấy riêng commit ngày 29/10 thêm 048–054, tổng cộng bảy file; working tree hiện có thêm 055–061. Đây là nhịp phát triển và sửa lỗi dày, không phải bằng chứng một công cụ tự tạo file vô cớ.

### 2. Các hàm lớn bị định nghĩa lại qua nhiều đợt

| Hàm | Số migration có định nghĩa hàm |
|---|---:|
| `submit_tester_report` | 19 |
| `approve_tester_report` | 12 |
| `auto_accept_expired_reports` | 11 |
| `reject_tester_report` | 10 |

Một tên hàm chỉ được tính một lần trong mỗi file, kể cả có overload. Bản `submit_tester_report` trong 061 dài 746 dòng, gồm nhiều nhánh: kiểm tra yêu cầu, receipt chống gửi trùng, khóa đồng thời, xử lý pending/rejected/approved, feedback, Credit, check-in và ngữ cảnh nhiệm vụ.

Khi sửa một nhánh, migration thường mang theo toàn bộ định nghĩa hàm. Điều này giải thích lượng SQL lặp lớn. Khi cùng tên và cùng chữ ký, `CREATE OR REPLACE FUNCTION` thay định nghĩa đang có; không có nghĩa database gọi lần lượt 19 phiên bản mỗi khi người dùng gửi báo cáo. Các overload khác chữ ký cần được kiểm tra riêng.

### 3. Có chuỗi thêm tính năng rồi vá nhiều vòng

Các ví dụ nhìn thấy trực tiếp trong source:

- 048 → 049: báo cáo nhiệm vụ rồi vá ngữ cảnh và luồng xử lý.
- 050 → 051: thưởng cập nhật app rồi vá quyền, giao dịch, hoàn quỹ và replay.
- 052/053 → 054 → 056: đẩy app và cộng dồn, sau đó hardening; 056 sửa việc trigger của 054 chặn cả RPC đẩy app hợp lệ (`PUSH_METADATA_READ_ONLY`).
- 057 → 059 → 061: ledger chống gửi trùng, tương thích dữ liệu cũ, rồi sửa provenance và canonical rating.
- 058 → 060: cấu hình nhiệm vụ admin rồi sửa phân trang và metadata.

Các bản vá này có lý do nghiệp vụ cụ thể. Nếu phiên bản trước đã triển khai, sửa file cũ không tự cập nhật database đã chạy nó; cần thay đổi triển khai tiếp theo. Với các file chưa từng triển khai, có thể hoàn thiện trong cùng đợt trước khi chốt, sau khi xác minh trạng thái môi trường.

### 4. Đã có dấu vết thiếu đồng bộ khi triển khai thủ công

README dòng 103–105 hướng dẫn mở từng file rồi copy SQL vào Dashboard. Phần đầu migration 028 ghi rõ mục đích là bù những phần chưa chắc đã có trên database live: phần của 011, cùng 017, 018, 019, 021, và bản sửa của 027.

Đây là bằng chứng trực tiếp của một migration sinh ra để sửa khoảng trống triển khai. Không thể kết luận mọi migration đều đã chạy hay chưa chạy từ trạng thái source hiện tại.

Tài liệu Supabase xác nhận việc chỉnh database remote trực tiếp bằng SQL Editor/Table Editor bỏ qua lịch sử migration của CLI; khi chuyển sang CLI cần đối chiếu lại lịch sử: [Deploy database migrations](https://supabase.com/docs/guides/deployment/database-migrations).

### 5. Có hai nguồn SQL được duy trì bằng tay

`supabase/check_mirror.sh` quy định mỗi migration phải được mirror vào `full_schema_singapore.sql`. Nhưng script chủ yếu kiểm tra tên object/cột có xuất hiện, không so sánh định nghĩa hàm, quyền, constraint hay kết quả nghiệp vụ.

Các dấu hiệu lệch đã xác nhận:

- README vẫn mô tả phạm vi đến 037 và schema gộp chỉ đến 014.
- Đầu file schema gộp cũng nói chỉ gộp 001–014, dù nội dung thực tế đã chứa nhiều thay đổi về sau.
- Ba hàm admin được sửa trong 060 khớp bản tương ứng trong schema gộp sau chuẩn hóa xuống dòng. Tuy nhiên `submit_tester_report` của 061 **không khớp**, kể cả khi bỏ comment và khoảng trắng: 061 thêm bốn trường JSON `rating` ở dòng 208, 365, 450, 756; bản cuối của hàm trong schema gộp chưa có các trường này. Khối sửa dữ liệu phục hồi `exact_v1` đầu 061 cũng chưa có trong schema gộp.

Do đó, nạp file gộp thành công không chứng minh nó cho trạng thái và hành vi giống áp toàn bộ migrations.

## Kiểm chứng đã thực hiện

Script tái lập: `audit/migration-review-2026-10-30/inspect-migrations.mjs`.

```powershell
node audit/migration-review-2026-10-30/inspect-migrations.mjs
```

Kết quả chi tiết: `audit/migration-review-2026-10-30/inspection.json`.

- Áp dụng tuần tự đủ 65 file theo tên vào PGlite trong bộ nhớ, với fixture `auth` và các role tương tự test hiện có: thành công.
- Nạp riêng schema gộp vào database tạm khác: thành công.
- Đối chiếu định nghĩa bốn hàm vừa nêu: ba hàm admin khớp; hàm submit lệch.

Đây là kiểm tra nạp SQL và đối chiếu source, không thay thế kiểm thử đầy đủ nghiệp vụ, Supabase/PostgREST/cron thực tế, hoặc nâng cấp trên dữ liệu production. Script áp SQL trực tiếp nên không kiểm tra được xung đột mã migration trong hệ thống lịch sử của Supabase CLI.

## Hướng xử lý nên ưu tiên

1. **Chốt trạng thái đã triển khai.** Lập danh sách từng môi trường đã áp đến đâu; phân biệt file chỉ có ở máy, đã commit và đã chạy. Trước khi dùng CLI, xử lý có kế hoạch bốn cặp trùng số; không tự đổi tên lịch sử đã triển khai.
2. **Đồng bộ nội dung hiện tại.** Sửa mô tả cũ trong README/đầu schema; đối chiếu và bổ sung nội dung 061 còn thiếu. Thay kiểm tra chỉ tìm tên object bằng kiểm tra định nghĩa cuối và đường nâng cấp.
3. **Giảm bản vá phát sinh trong các đợt tiếp theo.** Kiểm thử quyền thực thi thật, retry, dữ liệu cũ và cạnh tranh trước khi chốt mỗi đợt backend. `run-push-campaign-role-hardening-test.mjs` hiện có nhưng chưa nằm trong chuỗi `npm test`, nên nên đưa vào bước kiểm tra chung khi chỉnh quy trình.
4. **Giảm sao chép mã.** Duy trì định nghĩa hiện hành có cấu trúc theo chức năng; tách dần các trách nhiệm của hàm lớn khi có nhu cầu sửa thực tế. Sinh hoặc kiểm chứng schema khởi tạo từ một nguồn chuẩn thay vì tiếp tục chép tay hai nơi.
5. **Chỉ gộp lịch sử khi đã có baseline được kiểm chứng.** Cho database mới, có thể chuẩn bị bản schema khởi tạo gọn và các dữ liệu cấu hình cần thiết. Với database đang dùng, giữ đường nâng cấp và đối chiếu lịch sử trước khi thay đổi cách quản lý. Gộp file chỉ làm gọn lịch sử, không tự giảm độ phức tạp nghiệp vụ hay làm RPC nhanh hơn.

Supabase có `migration squash`, nhưng tài liệu nêu rằng kết quả là schema-only và bỏ các lệnh dữ liệu `INSERT/UPDATE/DELETE`, gồm cả cấu hình cron; phải phục hồi các phần cần thiết có chủ đích: [CLI migration squash](https://supabase.com/docs/reference/cli/supabase-migration-squash). Điểm này quan trọng với dự án vì migrations chứa cả backfill và cấu hình vận hành. Migration 042 dòng 5 còn cảnh báo không chạy lại 036 vì phần backfill reset streak và cấp grace days. Vì thế chưa nên xóa/gộp rồi chạy lại toàn bộ trên database đang dùng chỉ để giảm số file.
