# Kết quả triển khai cấu hình tỷ lệ test trên Supabase

Ngày: 27/10/2026. Phạm vi: backend và tài liệu/kiểm thử liên quan.

**Đã hoàn tất mã nguồn và kiểm thử PostgreSQL cục bộ. Chưa áp dụng lên Supabase live.**

## Thay đổi đã thực hiện

- Thêm migration `supabase/migrations/042_configurable_daily_task_ratio.sql`, trong transaction. Giữ tỷ lệ 3 khi cài lần đầu.
- Lịch tỷ lệ nguyên 1–30 theo ngày Việt Nam; tạo/sửa/hủy lịch tương lai bằng hàm dành cho database owner trong SQL Editor. Tài khoản admin trong app và `service_role` không được chỉnh tỷ lệ.
- Giữ nguyên chữ ký, JSON và đường gọi RPC của Android. Helper nhiệm vụ lấy tỷ lệ theo ngày được tính, thay cho số 3 cố định.
- Chấm phạt ghi `policy_id` và `tests_per_active_app` cùng snapshot hiện có; không backfill hay ghi đè lịch sử đã chốt.
- Audit tạo/sửa/hủy lịch; bảo vệ chính sách đã có hiệu lực, baseline và audit khỏi sửa/xóa/truncate thông thường. Kiểm tra ngày bằng wall clock ở lúc thao tác và trigger deferred ở cuối transaction để chặn transaction kéo dài qua nửa đêm rồi ghi chính sách vừa có hiệu lực.
- Migration đối chiếu fingerprint thân hai hàm được thay thế, chấp nhận bản 036 hoặc bản 042. Nếu live có chỉnh sửa riêng, dừng toàn bộ transaction với `DAILY_TASK_FUNCTION_DRIFT` để rà soát trước.
- Đồng bộ nguyên nội dung 042 vào cuối `supabase/full_schema_singapore.sql`. Không sửa migration 036 đã phát hành.
- Có SQL preflight chỉ đọc và hướng dẫn thao tác tại `supabase/operations/`.
- Thêm runner PostgreSQL WASM (PGlite 0.5.12) được khóa phiên bản trong `supabase/tests/package-lock.json`. Dependency chỉ phục vụ test; không thêm vào Android.

Ở đợt triển khai backend ban đầu, không sửa source/resource/build config Android, không build hay phát hành APK. Phần bổ sung hướng dẫn Android theo yêu cầu tiếp theo được ghi riêng ở cuối báo cáo. Thay đổi chưa commit sẵn có trong `KE_HOACH_SUA_LOI_MANG_YEU_BAO_CAO.md` không thuộc gói này và không được chỉnh trong quá trình triển khai tỷ lệ.

## Kiểm thử đã chạy

Lệnh từ thư mục dự án:

```powershell
npm test --prefix supabase/tests
```

Kết quả cuối: **PASS — 2.016 kiểm tra SQL và 8 kiểm tra của runner**.

Các kiểm tra SQL chạy thân hàm thật trên PostgreSQL trong bộ nhớ, có fixture riêng và rollback. `auth.uid()` và các role Supabase được dựng cục bộ; không dùng token thật và không kết nối database production. Fixture lịch sử bỏ qua trigger không liên quan khi dựng dữ liệu; các đường RPC, finalizer, check-in refresh và pause/resume được gọi với trigger bình thường trong các ca tương ứng.

| Kiểm tra | Kết quả |
| --- | --- |
| Nạp nội dung schema gộp hiện có trước 042 | PASS |
| Nâng cấp schema bằng migration 042 | PASS |
| Tỷ lệ mặc định 3 trước/sau: trạng thái nhiệm vụ, kết quả phạt, số dư, credit log | PASS, đối chiếu dữ liệu thực từ hai phiên fixture |
| Preflight nhận đúng fingerprint bản 036 và bản đã cài 042 | PASS |
| Các tỷ lệ 1/2/3/4/30 × K=0/1/2/6 × A=0/1/3/6/24/180 × mức hoàn thành thiếu/đủ/vượt | PASS |
| Chọn lịch theo ngày: hôm qua/hôm nay vẫn 3, mốc tương lai là 2, mốc tiếp sau là 4 | PASS |
| Session timezone khác Việt Nam; date NULL/infinity; tỷ lệ NULL/0/âm/31; ghi chú trống | PASS |
| Chặn hồi tố, sửa/xóa baseline, đổi identity, sửa/xóa/truncate audit và truncate lịch | PASS |
| Constraint trigger kiểm tra cuối transaction được cấu hình deferred; chạy flush constraint trên lịch hợp lệ | PASS |
| Sửa/hủy lịch tương lai, unique theo ngày và lưu lý do hủy | PASS |
| Report pending/approved, rejected, own app, report cũ duyệt lại, check-in/report trùng | PASS |
| RPC cá nhân và dashboard trên role authenticated cho cùng trạng thái; không cần API client mới | PASS |
| Role anon/authenticated/service_role không được sửa tỷ lệ; JWT admin trong app cũng bị chặn | PASS |
| App mới tính từ ngày mai, cửa sổ 30 ngày, paused owner_inactive/admin_manual | PASS |
| Finalizer thực sự sử dụng tỷ lệ 2 và ghi đúng chính sách; chấm ngày cũ dùng tỷ lệ 3 | PASS |
| Chạy finalizer lặp giữ nguyên row finalized và số dư | PASS |
| Phạt theo các tier 1%/2%/5%/10%, tỷ lệ thiếu 1/2, số dư 0/1/100, làm tròn và hoàn thành đủ | PASS |
| Miễn Admin/Moderator và ngày đăng ký; auto-pause, chặn resume khi thiếu, reset streak khi đủ, resume khi hoàn thành | PASS |
| Chạy lại 042 giữ nguyên lịch, audit, hồ sơ/số dư, credit log và snapshot đã chốt | PASS |
| Thiếu baseline thì báo lỗi, không âm thầm dùng hệ số khác | PASS |
| Cài fresh từ schema gộp có cùng định nghĩa và ACL của các hàm liên quan như upgrade | PASS |
| Hàm live bị custom thì migration từ chối trước khi sửa schema, không có thay đổi dở dang | PASS |

Kiểm tra mirror so sánh toàn bộ block 042 với migration, không chỉ tên object. `git diff --check` được chạy để kiểm tra whitespace. Source Android và migration 036 không có thay đổi trong gói này.

## Chưa xác minh trên môi trường thật

- Chưa đọc định nghĩa/quyền/lịch cron trên project Supabase live `com.ntt.crosstest`.
- Chưa chạy migration 042 hoặc tạo lịch tỷ lệ nào trên live. Mức thực tế trên server chưa được thay đổi bởi phiên này.
- Chưa kiểm thử qua PostgREST gateway/Supabase Auth thật, worker Android, cache/UI, APK/thiết bị hoặc thông báo hệ điều hành.
- Chưa kiểm thử hai connection PostgreSQL chạy đồng thời hoặc cron thật. PGlite một session không chứng minh khả năng tranh chấp khóa nhiều connection.
- Chọn tỷ lệ theo ngày và constraint deferred được kiểm tra cục bộ; chưa đo giao dịch thực tế đi xuyên đúng mốc 00:00/00:05 trên server. Không đổi đồng hồ máy để giả lập.

Người dùng đã cho biết Dashboard nằm ở Firefox. Khi kiểm tra lại, công cụ báo Computer Use đã bị dừng bằng phím Escape và không cho tiếp tục trong lượt này. Vì vậy chưa thể đọc/xác nhận project rồi triển khai qua Dashboard. Trước đó, lần thử Edge cũng dừng do công cụ không xác định chắc URL trên Windows. Đây là giới hạn truy cập UI của phiên làm việc, không phải lỗi SQL hay lỗi xác thực Supabase đã được xác nhận.

## Bước triển khai còn lại

1. Trong đúng project Supabase, chạy `supabase/operations/daily_task_ratio_preflight.sql`, lưu định nghĩa và quyền hiện có. Chỉ triển khai khi kết quả không có `STOP_*` hoặc các khác biệt đã được rà soát.
2. Chạy toàn bộ migration 042 bằng database owner. Nếu có lỗi, rollback và xử lý nguyên nhân; không chạy lẻ từng đoạn.
3. Kiểm tra policy hiện tại và APK đang dùng. Kiểm thử staging/thiết bị và cron/concurrency nêu trên trước khi kết luận nghiệm thu live đầy đủ.
4. Từ đó, mỗi lần đổi tỷ lệ gọi `schedule_daily_task_policy` với số mới và ngày từ hôm sau, như trong hướng dẫn vận hành. Chưa chọn mức khác 3 khi người dùng chưa đưa mức mới cụ thể.

Giới hạn của APK cũ: hộp giải thích vẫn ghi cố định “3 app” tới khi người dùng cài bản cập nhật resource Android bên dưới. Chỉ tiêu thực tế, tiến độ và dữ liệu cho thông báo nhận từ server có thể đổi mà không sửa app.

## Bổ sung: hướng dẫn Android không cố định tỷ lệ

Theo yêu cầu tiếp theo của người dùng, đã sửa `daily_quest_desc`, `daily_task_goal`, `daily_penalty_dialog_content` ở cả `values`, `values-en` và `values-vi`:

- Bỏ số 3 cố định và ví dụ 1→3, 2→6, 6→18 khỏi lời hướng dẫn nhiệm vụ.
- Hướng dẫn hoàn thành số app khác nhau theo chỉ tiêu trên thẻ nhiệm vụ hôm nay; giải thích chỉ tiêu phụ thuộc app đang hoạt động, chính sách hiện hành và nguồn app khác khả dụng.
- Thẻ nhiệm vụ và lời nhắc vẫn lấy chỉ tiêu thực từ server; không thêm RPC hoặc thay công thức ở Android.

Xác minh: cả ba file biên dịch thành công bằng AAPT2 từ Android Build Tools 36.0.0; XML hợp lệ; mỗi file chỉ thay đúng ba chuỗi dự kiến, không thêm/xóa resource key; không còn nội dung quota “test 3 app” hay ví dụ cố định trong các resource. `git diff --check` đạt. Không chạy lại bộ test SQL vì không thay backend ở phần bổ sung này.

Chưa build APK/AAB đầy đủ hoặc kiểm tra hộp thoại trên thiết bị. Cần build và phân phối một bản cập nhật app để người dùng nhận hướng dẫn mới. Sau đó, mỗi lần nâng hoặc giảm tỷ lệ chỉ cấu hình Supabase; không cần đổi hướng dẫn và build lại theo từng tỷ lệ.
