# Đổi tỷ lệ test bằng Supabase

Áp dụng cho project `com.ntt.crosstest`, sau khi cài migration 042. Không cần sửa hay phát hành APK/AAB khi đổi chỉ tiêu. Migration giữ mức hiện tại **3 app test / 1 app đang hoạt động / ngày**; chưa tự chọn tỷ lệ mới thay bạn.

## Cài đặt một lần

1. Mở đúng project trên Supabase Dashboard → SQL Editor.
2. Chạy `supabase/operations/daily_task_ratio_preflight.sql`. Lưu kết quả định nghĩa function/quyền để phục hồi. Hai dòng kiểm tra phải là `BASELINE_036_OK` hoặc `ALREADY_042_OK`. Nếu `STOP_*`, xem định nghĩa live và xử lý khác biệt trước; không bỏ kiểm tra drift để chạy ép migration.
3. Chạy **toàn bộ** `supabase/migrations/042_configurable_daily_task_ratio.sql` trong một lần, bằng database owner (thường `postgres`). File có transaction; gặp lỗi thì dừng và rollback, không chạy từng đoạn để lách lỗi. Nếu SQL Editor giữ transaction lỗi, chạy `rollback;` trước thao tác tiếp theo.
4. Kiểm tra tỷ lệ hôm nay:

```sql
select * from public.get_daily_task_policy(
    (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date
);
```

Lần cài đầu kết quả là 3. Lần chạy lại giữ nguyên chính sách đang áp dụng, lịch tương lai và audit; không tự trả về 3 nếu trước đó đã đổi tỷ lệ.

5. Dùng APK hiện có và tài khoản thử kiểm tra Trang chủ sau làm mới. Đối chiếu `get_my_daily_task_status()` bằng phiên authenticated của chính tài khoản đó; SQL Editor không có `auth.uid()` nên không gọi RPC cá nhân để giả làm người dùng thật. Không dùng SQL test fixture trên production.

Chỉ cần chạy migration **042** khi backend đã có các migration nền hiện hành. Không chạy lại 036: nó có backfill reset streak và miễn phạt. Không dùng toàn bộ schema gộp để nâng cấp database production.

## Mỗi lần đổi tỷ lệ

Ví dụ đổi sang **2 app test / 1 app đang hoạt động**, áp dụng từ ngày mai theo giờ Việt Nam:

```sql
select public.schedule_daily_task_policy(
    p_tests_per_active_app => 2, -- sửa số này, trong khoảng 1 đến 30
    p_effective_from => (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date + 1,
    p_operator_note => 'Áp dụng tỷ lệ 2:1 từ ngày mai'
);
```

Tỷ lệ là số nguyên. Có thể thay ngày mai bằng ngày tương lai cụ thể, ví dụ `date '2026-11-01'` khi ngày đó chưa tới. Không sửa lịch đã có hiệu lực, không áp dụng hồi tố hoặc giữa ngày. Thời điểm bắt đầu là 00:00 ngày đã chọn theo `Asia/Ho_Chi_Minh`.

Nếu lịch ngày đó chưa áp dụng, gọi lại cùng ngày để điều chỉnh tỷ lệ; hệ thống giữ audit trước/sau. Sau mốc bắt đầu, muốn đổi tiếp thì tạo lịch cho ngày tương lai.

Chỉ tiêu thực tế = **min(tỷ lệ × số app của chủ thuộc diện tính nhiệm vụ, số app khác khả dụng)**. Quy tắc app đang hoạt động, cửa sổ 30 ngày, app mới tính từ hôm sau và cách đếm report không đổi. Đây là quota test hằng ngày, không phải phí hay điều kiện mở khóa nút đăng app.

## Xem lịch và lịch sử

```sql
select id, effective_from, tests_per_active_app, operator_note, updated_at
from public.daily_task_policies order by effective_from;

select policy_id, operation, operator_note, old_row, new_row,
       session_role, invoked_role, changed_at
from public.daily_task_policy_changes order by id desc limit 50;
```

Row `-infinity` là mức nền 3 dùng cho các ngày trước lịch đầu tiên. Không sửa/xóa row đó. Role từ SQL Editor có thể chỉ là `postgres`; ghi người thực hiện/mục đích trong `operator_note` nếu cần đối chiếu cá nhân.

## Hủy một lịch chưa áp dụng

```sql
select public.cancel_daily_task_policy(
    p_effective_from => (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date + 1,
    p_operator_note => 'Hủy lịch ngày mai, tiếp tục mức đang áp dụng'
);
```

Lịch được hủy trong một transaction, giữ lý do hủy trong audit. Nếu không có lịch đúng ngày đó, hàm báo `DAILY_TASK_POLICY_NOT_FOUND`. Hủy/sửa lịch đã có hiệu lực bị từ chối.

## Kiểm tra khi chuyển ngày

- Cron lúc 00:05 chấm ngày vừa kết thúc, dùng tỷ lệ của ngày đó. Ví dụ mức 2 bắt đầu 01/11 thì lượt chấm ngày 31/10 vẫn dùng mức cũ.
- Các dòng đã chốt trong `user_daily_tasks` giữ nguyên, có `policy_id` và `tests_per_active_app` cho lần chốt từ 042 trở đi. Row cũ giữ NULL ở hai cột mới, không backfill Credit.
- Client nhận chỉ tiêu mới khi tải trạng thái mới thành công. Dùng thao tác làm mới Trang chủ; cache hoặc màn hình đang mở có thể còn số cũ.
- Source Android đã đổi hướng dẫn sang “hoàn thành chỉ tiêu hôm nay”, bỏ tỷ lệ 3 và các ví dụ cố định ở cả ba bộ resource. Cần build/phát hành và cập nhật app một lần để nhận nội dung này. APK cũ chưa cập nhật vẫn ghi “3 app”; các lần tăng/giảm tỷ lệ sau chỉ thao tác Supabase, thẻ tiến độ và lời nhắc tiếp tục lấy chỉ tiêu từ server.

## Hoàn tác

Nếu lịch chưa áp dụng, sửa/hủy bằng hàm ở trên. Nếu đã áp dụng, lên lịch đưa tỷ lệ về mức cũ từ ngày tương lai. Không sửa/xóa chính sách đã sử dụng hay lịch sử phạt.

Nếu phải hoàn tác migration kỹ thuật, phục hồi định nghĩa hai function và quyền từ bản lưu preflight trong một transaction sau khi đã thử ở staging. Có thể để các bảng/cột mới không còn được dùng nhằm giữ audit; không drop cascade hoặc chạy lại 036. Khôi phục function không tự hoàn trả Credit đã trừ. Không tính lại lịch sử để sửa số dư tự động.

## Kiểm thử cục bộ

Trong `supabase/tests`, chạy `npm ci --ignore-scripts` rồi `npm test`. Runner dùng PostgreSQL WASM (PGlite) trong bộ nhớ, nạp schema thật và 042; không kết nối Supabase và không cần secret. Fixture test được rollback. Runner đối chiếu baseline trước/sau, test quota/phạt/quyền/RPC và migration chạy lại.

Kiểm tra đồng thời nhiều connection, cron thật và APK/device trên Supabase staging là các bước riêng; kết quả cục bộ không thay thế những bước đó.
