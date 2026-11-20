# Hướng dẫn chạy Bot Mirror trên Supabase Edge Functions (0 cần VPS)

Thư mục này chứa Edge Function `telegram-mirror-webhook` để Telegram bắn trực tiếp tin nhắn mới của nhóm về Supabase (lưu vào bảng `101_telegram_group_mirror.sql`) mà **không cần thuê VPS hay bật máy tính**.

---

## Bước 1: Chạy Migration 101 (nếu chưa chạy)
Trong **Supabase Dashboard → SQL Editor**, chạy nội dung file [101_telegram_group_mirror.sql](../../migrations/101_telegram_group_mirror.sql).

---

## Bước 2: Lấy `TELEGRAM_ALLOWED_CHAT_ID` của nhóm Telegram
1. Đảm bảo bạn đã thêm Bot vào nhóm và đã tắt Privacy Mode trong `@BotFather` (`/setprivacy` → `Disable`).
2. Gửi 1 tin nhắn bất kỳ trong nhóm Telegram.
3. Mở trình duyệt truy cập (thay `<BOT_TOKEN>` bằng token từ `@BotFather`):
   ```
   https://api.telegram.org/bot<BOT_TOKEN>/getUpdates
   ```
4. Tìm phần `"chat":{"id":-100xxxxxxxxxx,...}` — con số âm `-100...` đó chính là `TELEGRAM_ALLOWED_CHAT_ID`.

---

## Bước 3: Khai báo 3 Secrets trên Supabase
Trong **Supabase Dashboard → Edge Functions → Secrets** (hoặc dùng CLI `npx supabase secrets set`), thêm 3 biến sau *(không cần thêm `SUPABASE_URL` hay `SUPABASE_SERVICE_ROLE_KEY` vì Supabase tự cấp sẵn cho Edge Function)*:

| Tên Secret | Giá trị |
|---|---|
| `TELEGRAM_BOT_TOKEN` | Token do `@BotFather` cấp (dạng `123456789:AAH...`) |
| `TELEGRAM_ALLOWED_CHAT_ID` | ID số của nhóm (dạng `-100xxxxxxxxxx`) |
| `TELEGRAM_WEBHOOK_SECRET` | Một chuỗi bí mật ngẫu nhiên bạn tự đặt (ví dụ `12tester_wh_9f8e7d6c5b4a3210`, không dùng ký tự đặc biệt lạ) |

---

## Bước 4: Deploy Edge Function (chọn 1 trong 2 cách)

### Cách A — Làm trực tiếp trên Web Supabase Dashboard (không cần cài CLI)
1. Vào **Supabase Dashboard → Edge Functions → Deploy a new function → Via Editor**.
2. Đặt tên function là **`telegram-mirror-webhook`**.
3. Copy toàn bộ code trong file [index.ts](./index.ts) dán vào trình soạn thảo.
4. **QUAN TRỌNG**: Ở phần cài đặt của function, **TẮT** mục **Enforce JWT Verification / Verify JWT** (vì Telegram gửi header `X-Telegram-Bot-Api-Secret-Token`, không gửi JWT của Supabase; code trong `index.ts` đã tự xác thực token bí mật đó rồi).
5. Bấm **Deploy**.

### Cách B — Dùng lệnh CLI từ thư mục gốc dự án
```powershell
npx supabase login
npx supabase link --project-ref <MA_PROJECT_REF_CUA_BAN>
npx supabase functions deploy telegram-mirror-webhook --no-verify-jwt
```

---

## Bước 5: Gắn Webhook để Telegram gửi tin nhắn về Supabase
Thay `<MA_PROJECT_REF>`, `<BOT_TOKEN>`, và `<TELEGRAM_WEBHOOK_SECRET>` rồi chạy lệnh (hoặc mở URL tương ứng):

```powershell
$BOT_TOKEN = "123456789:AAH..."
$WEBHOOK_URL = "https://<MA_PROJECT_REF>.supabase.co/functions/v1/telegram-mirror-webhook"
$SECRET = "12tester_wh_9f8e7d6c5b4a3210"

Invoke-RestMethod -Method Post -Uri "https://api.telegram.org/bot$BOT_TOKEN/setWebhook" -ContentType "application/json" -Body (@{
  url = $WEBHOOK_URL
  secret_token = $SECRET
  allowed_updates = @("message", "edited_message")
} | ConvertTo-Json)
```

Kiểm tra kết quả trả về `"ok":true,"result":true` là xong! Từ lúc này, mỗi khi nhóm Telegram có tin nhắn mới hoặc tin đã sửa, Telegram sẽ tự đẩy về Supabase 24/7 và người dùng chưa đăng nhập trên app 12Tester sẽ xem được ngay.
