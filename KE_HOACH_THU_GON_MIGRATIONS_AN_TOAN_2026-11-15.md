# Nghiên cứu và kế hoạch thu gọn migrations an toàn

Ngày: 15/11/2026. Dự án: 12Tester. Phạm vi đợt này: khảo sát, thử nghiệm cô lập và lập kế hoạch; chưa chuyển migration, chưa tạo baseline thay thế, chưa sửa database thật.

## 1. Kết luận và phương án đề xuất

**Có thể chuyển các migration cũ sang kho lưu trữ, nhưng phải có một baseline mới chứa đầy đủ trạng thái cuối trước khi bỏ chúng khỏi đường triển khai. Không được chỉ giữ vài file gần đây rồi bỏ qua các file nền.**

Phiên bản Android người dùng đang cài không xác định được máy chủ đã chạy những SQL nào. Migration trong dự án này là thay đổi PostgreSQL/Supabase trên máy chủ; không thấy cơ chế nạp chuỗi SQL này khi Android khởi động trong phần source đã khảo sát. Thu gọn file không tự làm RPC nhanh hơn hay sửa lỗi nhiệm vụ ngày.

Đề xuất tạo **baseline mới gộp trạng thái cuối 062–090**, với tên dự kiến `091_consolidated_baseline.sql`. Sau nghiệm thu, chuyển nguyên 29 file 062–090 sang `supabase/migrations_archive/pre_baseline_091/`. Giữ nguyên kho `pre_baseline_062/` đã có. Mọi thay đổi mới sau đó dùng 092 trở đi.

Tên 091 là dự kiến theo source khảo sát đang kết thúc ở 090. Nếu có migration mới phát sinh trong lúc thực hiện, phải chốt lại nguồn và cấp số mới không xung đột. Chỉ chọn mốc 090 khi đã xác minh nó là trạng thái chuẩn để triển khai, không dựa vào việc file có sẵn trên máy.

Không giữ 085–090 như các file thực thi độc lập đứng trước baseline 091: chúng phụ thuộc schema cũ và có thể chạy trước khi baseline được tạo. Chức năng của chúng được giữ trong định nghĩa cuối của baseline mới; bản gốc được giữ nguyên để tra cứu và thử nghiệm.

## 2. Kết quả khảo sát có thể kiểm chứng

| Hạng mục | Kết quả |
|---|---|
| Thư mục triển khai hiện tại | `supabase/migrations/` |
| Migration hiện hành | 29 file, từ 062 đến 090; tổng 2.254.835 byte |
| Kho cũ | `supabase/migrations_archive/pre_baseline_062/`: 65 migration 001–061 và schema gộp cũ |
| Baseline đang có | `062_consolidated_baseline.sql`, có nhánh database mới/đang dùng và dấu mốc kèm checksum |
| Cách triển khai được tài liệu dự án mô tả | Supabase SQL Editor, sau đó chạy các file tiếp nối theo thứ tự |
| Lịch sử nội bộ của baseline | `crosstest_migrations.baselines`; khác lịch sử Supabase CLI |
| Công cụ hiện có | `supabase/tools/build-baseline.mjs`, `baseline-lib.mjs`, các bài thử PGlite |
| Tham chiếu cần phân loại | 129 dòng trong 50 file của tests/tools/operations; gồm cả tham chiếu lịch sử hợp lệ, không phải 129 lỗi |
| Trạng thái Git | Nhiều thay đổi có sẵn; 062–090 và kho archive hiện chưa được Git theo dõi trong checkout khảo sát |
| Xác minh production | Chưa thực hiện; chưa biết migration nào thực sự đã được áp dụng |

Không phát hiện `supabase/config.toml` trong cây Supabase và không tìm thấy lệnh `supabase`, `docker`, `psql` trên PATH của phiên khảo sát. Điều này chỉ phản ánh phiên hiện tại, không chứng minh các công cụ hoặc một quy trình triển khai khác không tồn tại ở nơi khác.

### Thử nghiệm đã chạy trong đợt nghiên cứu

Lệnh tái lập:

```powershell
node audit/migration-retention-plan-2026-11-15/inspect.mjs
```

| Kiểm tra | Kết quả |
|---|---|
| Nạp đủ 29 file 062–090 lên PGlite rỗng, có fixture auth/roles | Thành công |
| Catalog sau khi nạp | 65 bảng public, 249 hàm public không thuộc extension, 49 trigger theo bộ thu thập hiện có, 56 policy public |
| Chạy lại 062 sau 090 trên database tạm đã có marker | Catalog và hàng dữ liệu giữ nguyên |
| Chỉ nạp 085–090, bỏ baseline và các phần trước đó | Thất bại dự kiến: `42P01`, thiếu `public.app_configs` |
| So hash các file migration trước/sau khảo sát | Không thay đổi |

Bằng chứng: `audit/migration-retention-plan-2026-11-15/inspection.json`. Script không đọc thông tin kết nối, không kết nối dịch vụ ngoài, chỉ tạo database PGlite trong bộ nhớ.

Các kết quả này chứng minh sự phụ thuộc và khả năng nạp source hiện tại; **chưa chứng minh baseline mới an toàn**, vì baseline mới chưa được tạo. Không phải kiểm thử Supabase/PostgREST/cron hoặc PostgreSQL nhiều kết nối thực tế.

## 3. Những rủi ro riêng của dự án

| Rủi ro | Bằng chứng/điểm cần giữ | Cách xử lý bắt buộc |
|---|---|---|
| Mất schema nền | 085 cần `app_configs`; các bản sau tiếp tục dùng bảng/hàm cũ | Baseline mới đầy đủ, không chỉ chép những file gần nhất |
| Ghi lại dữ liệu lịch sử | 080 có UPDATE vào `daily_test_events_journal`; lịch sử trước 062 có các backfill/reset | Không phát lại thao tác sửa dữ liệu cũ trên database đang dùng |
| Mất hoặc ghi đè cấu hình | 071 khởi tạo chính sách; 083 cập nhật thông báo/phiên bản; 085 thêm flags; 088 khởi tạo chính sách quota | Tách seed cho database mới khỏi dữ liệu đang vận hành; không xuất cấu hình production làm mặc định |
| Sai thứ tự trigger | 089 sửa tên/thứ tự trigger để tránh guard tài chính từ chối chuyển production | So tên, định nghĩa, thời điểm, sự kiện và thứ tự thực thi trigger; không chỉ so thân hàm |
| Mất bảo vệ nghiệp vụ mới | 090 có action gate, tombstone receipt, quyền và trigger bảo vệ thao tác chủ app | Nghiệm thu với role thật, không thử riêng superuser |
| Mất quyền hoặc mở rộng quyền | RPC có SECURITY DEFINER, search_path, grants/revokes và nhiều chữ ký | So đúng chữ ký, owner, cấu hình hàm, ACL/RLS/default privileges |
| Bỏ sót dữ liệu ngoài schema dump | Seed, cron và cấu hình vận hành không chỉ là DDL | Kiểm kê riêng; giữ lịch hiện có, chỉ thêm mặc định cho database mới khi đã duyệt |
| Sai lịch sử CLI | Marker 062 không phải `supabase_migrations.schema_migrations` | Chọn rõ đường SQL Editor hoặc CLI; đối chiếu lịch sử trước mọi thao tác sửa metadata |
| Hỏng kiểm thử khi chuyển file | Nhiều test đọc cứng 062/063/064/089; công cụ tạo SQL ghi vào đường dẫn cũ | Tách loader lịch sử và loader hiện hành; kiểm tra cả hai |
| Mất việc chưa commit | Nhiều migration và archive chưa được theo dõi trong Git | Checkpoint đúng các file liên quan, giữ mọi thay đổi có sẵn; không reset/clean toàn dự án |

Supabase nêu rằng `migration squash` tạo kết quả tương đương schema-only, bỏ các lệnh thao tác dữ liệu; cron, storage buckets và vault secrets cũng cần xử lý riêng. Vì vậy không dùng kết quả squash làm bản triển khai hoàn chỉnh khi chưa bổ sung và nghiệm thu các phần này.

## 4. Cấu trúc đích sau khi đạt điều kiện nghiệm thu

| Đường dẫn dự kiến | Vai trò |
|---|---|
| `supabase/migrations/091_consolidated_baseline.sql` | Nguồn triển khai nền mới; trạng thái chuẩn kết thúc ở 090 |
| `supabase/migrations/092_*.sql` và tiếp theo | Chỉ thay đổi phát sinh sau baseline mới |
| `supabase/migrations_archive/pre_baseline_091/062_*.sql` đến `090_*.sql` | Giữ nguyên byte; lịch sử và đường nâng cấp có kiểm soát |
| `supabase/migrations_archive/pre_baseline_062/` | Giữ nguyên kho 001–061 hiện có |
| `supabase/operations/HUONG_DAN_BASELINE_091.md` | Hướng dẫn tách database mới, đang đủ mốc, còn thiếu và CLI |
| Manifest baseline mới | Danh sách file nguồn, SHA-256, mốc nguồn, định dạng catalog, phiên bản công cụ, seed và khác biệt được duyệt |

Đích ban đầu là từ **29 file triển khai xuống 1 baseline**, sau đó chỉ thêm migration mới. Không tạo một thư mục con chứa SQL cũ bên trong `migrations/` để trông chờ công cụ tự bỏ qua. Archive nằm ngoài đường triển khai và không bị loader hiện hành quét đệ quy.

## 5. Kế hoạch thực hiện theo các cổng kiểm tra

### Giai đoạn A — Chốt nguồn và trạng thái từng môi trường

1. Dừng thay đổi vào tập migration được gộp trong khoảng tạo/kiểm chứng baseline. Lưu manifest/checksum và checkpoint các file liên quan, gồm cả file chưa được Git theo dõi. Không đưa credentials vào checkpoint.
2. Xác định các môi trường còn sử dụng: production, staging, database thử, dự án/nhánh khác nếu có. Thu thập chỉ đọc thông tin phiên bản PostgreSQL, extensions, marker baseline và lịch sử CLI nếu bảng đó tồn tại.
3. Xuất catalog thực tế: bảng/cột/default/identity, constraints và trạng thái validate, indexes, views/materialized views, enum/type/domain, hàm theo chữ ký, owner, volatility/SECURITY DEFINER/search_path, triggers, RLS/force RLS, grants/default privileges, sequences, cron, publication và đối tượng dự án trong auth/storage hoặc schema khác nếu có.
4. Đối chiếu catalog và hậu điều kiện dữ liệu của từng migration với source. File đã commit, số build Android, marker 062 hoặc dòng lịch sử CLI đơn lẻ đều chưa đủ xác nhận 063–090 đã áp dụng đầy đủ.
5. Phân loại từng môi trường: mới; đúng mốc 090; thiếu một số migration đã xác định; hoặc có drift/chưa rõ. Mọi môi trường chưa rõ tiếp tục giữ đường nâng cấp cũ và chưa chuyển sang baseline mới.

**Đầu ra:** bảng trạng thái theo môi trường; danh sách thay đổi/dữ liệu còn thiếu; quyết định mốc gộp. Chưa có đầu ra này thì không thực hiện chuyển đổi production.

### Giai đoạn B — Xây dựng baseline mới, không sửa 062

1. Trên database thử rỗng có nền Supabase tương ứng, nạp lần lượt bản sao bất biến 062–090. Xuất trạng thái cuối làm nguồn chuẩn. Không lấy database production chứa dữ liệu người dùng làm nguồn sinh seed.
2. Tạo công cụ mới hoặc chế độ mới để sinh baseline 091. Không chạy công cụ cũ ghi đè `062_consolidated_baseline.sql` và không sửa các migration đã triển khai.
3. Mỗi hàm giữ một định nghĩa cuối theo **schema + chữ ký**; giữ overload/wrapper còn được client và service gọi. Không xóa API cũ chỉ vì cho rằng tất cả người dùng đã nâng cấp.
4. Không tái sử dụng nguyên bộ xuất hiện tại mà mặc định nó bao phủ mọi đối tượng: `baseline-lib.mjs` chủ yếu khảo sát public và một tập loại đối tượng. Bổ sung kiểm tra những mục ở giai đoạn A, nhất là owner, default privileges, view/type, publication và tùy chỉnh auth/storage.
5. Phân loại DML thành: seed database mới; thao tác nâng cấp một lần; thao tác vận hành; dữ liệu người dùng. Chỉ seed được duyệt đi vào nhánh tạo mới. Không gộp backfill bằng cách chép nối SQL lịch sử.
6. Quy định rõ mặc định `app_configs` cho database mới. Không mặc định lấy phiên bản cũ do 083 khởi tạo làm phiên bản phát hành mới; không cập nhật phiên bản/thông báo/flags của database đang dùng trong việc thu gọn.

**Đầu ra:** baseline ứng viên, manifest và bộ catalog/seed kỳ vọng; chưa chuyển file nguồn sang archive.

### Giai đoạn C — Thiết kế cách chạy an toàn

| Trạng thái database | Hành vi baseline mới |
|---|---|
| Chưa có bảng ứng dụng, có nền auth/roles phù hợp | Tạo đầy đủ schema cuối; seed cấu hình đã duyệt; thiết lập quyền và lịch mặc định theo điều kiện đã kiểm chứng |
| Đã có đầy đủ trạng thái chuẩn 090 | Kiểm tra tương thích; chỉ ghi dấu mốc baseline mới. Không phát lại DDL/DML nghiệp vụ hoặc ghi đè cấu hình |
| Đã có marker 091 đúng checksum | Bỏ qua payload ngay; không phục hồi schema 090 lên database đã có thay đổi 092+ |
| Marker cùng số nhưng khác checksum | Dừng và yêu cầu migration mới, không tự sửa marker |
| Thiếu migration, schema dở dang hoặc drift chưa duyệt | Dừng trước thay đổi; cung cấp danh sách khác biệt. Không tự đánh dấu đã hoàn tất |

Chi tiết triển khai:

- Dùng transaction, khóa giao dịch dành cho baseline, thời gian chờ khóa hữu hạn và kiểm tra owner; ghi marker sau khi hoàn tất đúng nhánh. Thử lỗi giữa chừng phải rollback cả marker và phần thay đổi trong giao dịch.
- Giữ marker/checksum 062 và các marker tương thích mà migration/harness tương lai còn dùng; không tạo marker giả để bỏ qua precondition của SQL cũ.
- Đối với database đang đúng 090: đối chiếu trước/sau phải giữ nguyên hàng dữ liệu, số dư, escrow, logs, receipts, ledger/journal, streak, lịch chính sách, timestamps nghiệp vụ, sequence values và lịch cron. Marker mới là thay đổi quản lý được phép.
- Kiểm tra catalog trên dữ liệu đang hoạt động phải tránh kết luận drift từ đối tượng tạm hoặc thay đổi dữ liệu bình thường. Dữ liệu người dùng không dùng làm checksum schema. Khác biệt môi trường/đối tượng tùy chỉnh phải được phân loại rõ, không tự xóa.
- Nếu thiếu SQL: tạo kế hoạch nâng cấp riêng bằng chuỗi gốc bất biến trên bản sao thử, đánh giá tác động backfill và quyền rồi mới áp dụng đúng phần còn thiếu. Archive vẫn phục vụ phục hồi/nâng cấp có kiểm soát, không phải đường triển khai mặc định.

### Giai đoạn D — Giải quyết lịch sử Supabase theo đúng cách triển khai

**Nếu tiếp tục dùng SQL Editor như tài liệu hiện tại:** dùng marker nội bộ đã kiểm chứng; không giả định SQL Editor tự ghi lịch sử CLI. Không tự sửa bảng history CLI trong thao tác thu gọn. Chặn việc sau đó vô tình chạy `db push` khi history chưa được chuẩn hóa.

**Nếu dùng/chuyển sang Supabase CLI:** xác định và pin phiên bản CLI thật; kiểm tra cú pháp bằng `--help` của phiên bản đó, không sao chép cờ từ phiên bản khác. Lưu đầy đủ history cũ và lập bảng ánh xạ từng version sang baseline mới. Trên môi trường thử, đối chiếu schema và dữ liệu trước khi sửa metadata; chỉ dùng `migration repair` để phản ánh trạng thái SQL đã thực sự có, không dùng thay việc chạy migration còn thiếu. Sau chuẩn hóa phải chạy `migration list` và `db push --dry-run`: không còn file cũ bị đề nghị phát lại hoặc lịch sử không khớp.

Không chạy `migration squash` nhắm remote trong giai đoạn nghiên cứu. Tài liệu CLI hiện tại mô tả squash với remote có thể thay cả lịch sử remote. Không đánh dấu toàn bộ migrations là applied, xóa cả history hoặc dùng tùy chọn chạy mọi file để vượt lỗi đồng bộ.

Các thao tác history và đổi source phải thuộc một lần chuyển đổi có kiểm soát: tạm dừng các pipeline/người triển khai khác, lưu trạng thái trước và không phát hành giữa chừng. Nếu chọn SQL Editor, phải nói rõ CLI chưa được hỗ trợ cho tới khi bước đồng bộ riêng hoàn tất.

### Giai đoạn E — Sửa đường dẫn và bộ kiểm thử

1. Loader triển khai/current chỉ đọc `migrations/` theo thứ tự rõ ràng. Loader legacy chỉ đọc manifest của kho lịch sử khi test yêu cầu. Không tự fallback từ thiếu file hiện hành sang file archive.
2. Các test chứng minh lỗi trước/sau (ví dụ lỗi trigger được sửa ở 089) tiếp tục dùng bản gốc trong archive và giữ assertions. Các test nghiệp vụ hiện hành khởi tạo bằng baseline mới.
3. Bài thử baseline 062 cũ chuyển tham chiếu tới kho `pre_baseline_091/` và vẫn được giữ. Công cụ sinh SQL lịch sử chuyển sang chế độ tham khảo/tái lập có đường xuất an toàn, không ghi vào thư mục triển khai mặc định.
4. Cập nhật README, `supabase/check_mirror.sh`, hướng dẫn vận hành, câu lệnh bootstrap, fixture và script CI/deploy nếu phát hiện thêm. Không sửa tất cả báo cáo lịch sử thành tuyên bố mới; chỉ đánh dấu tài liệu đã thay thế khi cần.
5. Rà soát lại ngoài phạm vi 50 file đã quét: scripts audit được dùng cho nghiệm thu, pipeline, backend và mọi glob/path chứa migrations. Tách rõ script lịch sử và script đang hoạt động.

### Giai đoạn F — Nghiệm thu trước khi archive

| Nhóm bắt buộc | Điều kiện đạt |
|---|---|
| Database mới | Baseline mới nạp độc lập thành công; catalog và seed tương đương nguồn chuẩn 062–090 sau chuẩn hóa khác biệt vật lý/thời gian được duyệt |
| Database đang đủ 090 | Chạy baseline chỉ thêm metadata; dữ liệu, ACL và catalog nghiệp vụ giữ nguyên |
| Dữ liệu sống đa dạng | Reports pending/approved/rejected, receipts cũ/mới, mission escrow, bonus rounds active/closing/closed, nợ/checkpoint/journal, chính sách tương lai và sequence đã tăng đều được giữ |
| Database thiếu/drift | Bị chặn rõ ràng và nguyên tử, không ghi marker; có đường nâng cấp riêng đã thử |
| Chạy lại và thay đổi tương lai | Chạy 091 nhiều lần không lặp seed; sau một migration tiếp nối thử nghiệm, chạy lại 091 không ghi đè thay đổi đó |
| Quyền/API | Các role anon, authenticated member/owner/admin/locked user, service_role và authenticator qua PostgREST cho kết quả đúng; không lộ hàm nội bộ |
| Nghiệp vụ | Nhiệm vụ ngày, report daily/mission, idempotency, Credit/escrow/hoàn tiền, bonus, đăng/xóa/dừng/tiếp tục/production, thông báo admin và sync V2 giữ hành vi |
| PostgreSQL/Supabase thật | Xác minh pgcrypto trong extensions, cron, RLS/ACL, publication, schema cache và gọi RPC thực tế trên staging |
| Đồng thời | Thử nhiều kết nối trên database thử: tranh chấp tài chính, replay báo cáo, thao tác vòng đời và chạy baseline đồng thời; không coi PGlite tuần tự hoặc SKIPPED là đạt |
| CLI nếu được chọn | History sau ánh xạ khớp source, dry-run không yêu cầu chạy lại SQL cũ |
| Kho lưu trữ | 29 file chuyển đi có SHA-256 khớp; 65 file kho cũ giữ nguyên; không có loader triển khai quét archive |

Bộ chạy hiện có cần dùng làm nền: `npm test` trong `supabase/tests`, và gọi bổ sung những bài chưa thuộc chuỗi mặc định như campaign posting policies, mission reward modes/finalized campaigns, tester bonus/removal, immediate quota, production lifecycle, egress V2/flags, cùng bộ nghiệm thu 090 trong `audit/daily-task-action-gate-2026-11-12/`. Phải rà lại script mới nhất để chỉ định chính xác test bổ sung; không coi `npm test` hiện tại là bao phủ toàn bộ 085–090.

Các runner dùng `TEST_DATABASE_URL` ghi fixture: chỉ trỏ database thử chuyên dụng. Thiếu URL thì báo chưa chạy; không tự dùng URL production và không tính exit 0 do skip là PASS.

### Giai đoạn G — Chuyển đổi và hoàn tất

1. Backup database và history trước mọi thay đổi máy chủ; thử phục hồi backup vào môi trường riêng. Xác định mốc source/hash đã nghiệm thu và cách theo dõi lỗi sau chuyển đổi.
2. Nghiệm thu staging bằng đúng artifact sẽ áp dụng. Đối với production đủ mốc, dùng nhánh kiểm tra/đăng ký baseline đã thử; nếu thiếu mốc thì xử lý nâng cấp riêng trước.
3. Chỉ sau khi các môi trường đang phục vụ đã có đường đi rõ ràng mới chuyển nguyên 062–090 sang archive, đưa baseline mới vào `migrations/` và cập nhật runner/tài liệu trong cùng thay đổi source. Với CLI, phối hợp bước history như giai đoạn D.
4. Chạy lại kiểm tra từ checkout sạch, đảm bảo toàn bộ archive/migration/manifest/test cần thiết đã có trong Git. Không chỉ dựa vào các file chưa theo dõi trên máy hiện tại.
5. Theo dõi một chu kỳ chốt ngày theo giờ Việt Nam trên staging và đối chiếu vận hành sau chuyển đổi: lỗi RPC/quyền, trạng thái history/carryover, Credit bất thường, report trùng, bonus escrow, cron và bộ đếm nhiệm vụ. Không tự đổi met/ready hoặc xóa nợ để làm kết quả có vẻ đạt.

Không cần tăng phiên bản Android hay build APK chỉ để tổ chức lại migration. Nếu kiểm thử phát hiện lỗi nghiệp vụ, xử lý bằng thay đổi riêng có kiểm chứng, không nhân tiện sửa baseline đã chốt.

## 6. Phương án quay lại

- Trước thay đổi máy chủ: khôi phục bố cục source và runner từ checkpoint; không đụng dữ liệu người dùng.
- Nếu giao dịch baseline thất bại: xác minh rollback và không có marker mới; giữ đường triển khai cũ, sửa ứng viên trước khi thử lại.
- Nếu chỉ đăng ký baseline trên database đủ 090: quay lại source không yêu cầu chạy lại SQL nghiệp vụ. Nếu đã đổi history CLI thì khôi phục **đúng metadata đã lưu** trong cửa sổ triển khai được khóa, rồi dry-run lại; không đoán lại history từ tên file.
- Nếu đã có migration nghiệp vụ sau baseline: không hạ database bằng cách phát lại 062–090. Dùng bản vá tiến có kiểm chứng; khôi phục backup chỉ theo phương án riêng đánh giá dữ liệu phát sinh sau backup.
- Không có `db reset`, DROP/TRUNCATE bảng nghiệp vụ, reset sequence hoặc phát lại backfill trong quy trình dọn file.

## 7. Điều kiện dừng và kết quả bàn giao

Dừng chuyển đổi nếu chưa xác minh được trạng thái production, còn drift chưa giải thích, thiếu seed/cron/ACL, khác kết quả tài chính/nhiệm vụ, test quan trọng fail/skip, history CLI không khớp hoặc chưa bảo toàn các file chưa được Git theo dõi. Khi dừng, giữ nguyên 29 migration hiện tại để hệ thống tiếp tục có đường triển khai cũ.

Bàn giao khi thực hiện xong phải gồm: baseline cuối; kho nguyên bản và manifest hash; bảng môi trường/mốc áp dụng; kết quả so catalog/dữ liệu; log test có phân biệt chạy thật và skip; hướng dẫn database mới/cũ/thiếu mốc; kế hoạch history; xác nhận phục hồi và báo cáo giới hạn còn lại.

**Trạng thái hiện tại:** đã hoàn tất nghiên cứu source và các probe cô lập nêu trên. Chưa thực hiện các giai đoạn chuyển đổi. Chưa đủ bằng chứng để bỏ ngay các file 062–090 khỏi đường triển khai production.

## 8. Tài liệu tham chiếu

Source nội bộ đã đọc: `supabase/operations/HUONG_DAN_BASELINE_062.md`, `supabase/tools/baseline-lib.mjs`, `supabase/tools/build-baseline.mjs`, `supabase/tests/package.json`, `supabase/tests/run-consolidated-baseline-test.mjs`, các migration được nêu trong bảng rủi ro và tài liệu vận hành 080/085. Mọi số liệu hiện tại được ghi trong `audit/migration-retention-plan-2026-11-15/inspection.json`.

Tài liệu Supabase chính thức kiểm tra ngày 15/11/2026:

- [Deploy database migrations](https://supabase.com/docs/guides/deployment/database-migrations): phân biệt source Git và lịch sử `supabase_migrations.schema_migrations`; đối chiếu trước khi repair.
- [CLI — migration squash](https://supabase.com/docs/reference/cli/supabase-migration-squash): giới hạn schema-only/DML và tác động lên lịch sử khi chọn remote.
- [CLI — migration repair](https://supabase.com/docs/reference/cli/supabase-migration-repair): sửa ghi nhận applied/reverted; không thay việc áp SQL còn thiếu.

Cờ CLI phải được xác minh lại với phiên bản thật trước khi triển khai. Đợt nghiên cứu không thực thi các lệnh sửa history hoặc squash.
