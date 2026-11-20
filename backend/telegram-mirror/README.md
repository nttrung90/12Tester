# @ntt/telegram-mirror

Cầu nối đồng bộ tin nhắn nhóm cộng đồng Telegram vào Supabase PostgreSQL (`public.telegram_mirror_chat` & `public.telegram_mirror_messages`) để hiển thị ở chế độ **Chỉ xem (Read-Only Preview)** cho người dùng 12Tester (bao gồm cả Guest chưa đăng nhập).

## 1. Tạo Bot Telegram & Tắt Group Privacy Mode

1. Mở Telegram và chat với **@BotFather**:
   - Gõ `/newbot`, đặt tên hiển thị (ví dụ: `12Tester Community Mirror`) và username kết thúc bằng `bot` (ví dụ: `twelvetester_mirror_bot`).
   - Lưu lại `TELEGRAM_BOT_TOKEN`.
2. **Tắt Group Privacy Mode** (bắt buộc để Bot đọc được tin nhắn thường trong nhóm):
   - Trong `@BotFather`, gõ `/setprivacy`.
   - Chọn bot vừa tạo → chọn **`Disable`**.
3. Thêm bot vào nhóm cộng đồng (`https://t.me/+KcJBT02zr1ZiOGNl`).
   - Để lấy `TELEGRAM_ALLOWED_CHAT_ID`: gửi 1 tin nhắn trong nhóm, gọi `https://api.telegram.org/bot<TOKEN>/getUpdates` và lấy `message.chat.id` (dạng số âm `-100xxxxxxxxxx`).
   - Khuyến nghị: không cần cấp quyền gửi tin cho bot nếu chỉ mirror; bot chỉ đọc tin nhắn và kiểm tra quyền Admin qua `getChatMember` khi có lệnh `/hide_preview`.

## 2. Cài đặt dependencies

```bash
cd backend/telegram-mirror
npm ci
```

## 3. Biến môi trường

| Biến | Bắt buộc | Mô tả |
|---|---|---|
| `DATABASE_URL` (hoặc `SUPABASE_DB_URL`) | Có | Connection string PostgreSQL (`service_role` / postgres user có quyền ghi `telegram_mirror_*`). |
| `DATABASE_SSL` | Tùy chọn | Mặc định bật TLS xác thực chứng chỉ (`rejectUnauthorized: true`). Chỉ đặt `'false'` khi chạy DB local không TLS. |
| `TELEGRAM_BOT_TOKEN` | Có (polling), khuyến nghị (webhook) | Token từ `@BotFather` để gọi `getUpdates`, `getFile` (tải thumbnail ảnh nhỏ `photo[0]`), và `getChatMember`. |
| `TELEGRAM_ALLOWED_CHAT_ID` | **Bắt buộc** | ID số của nhóm cộng đồng (vd `-1001234567890`). Mọi update từ chat khác bị bỏ qua tuyệt đối. |
| `TELEGRAM_CHAT_KEY` | Tùy chọn | Khóa nhóm trong DB, mặc định `'community_main'`. |
| `TELEGRAM_WEBHOOK_SECRET` | Bắt buộc ở `--mode=webhook` | Chuỗi bí mật (>= 16 ký tự) khớp `secret_token` đã đăng ký với Telegram `setWebhook`. |
| `PORT` / `HOST` | Tùy chọn (webhook) | Mặc định `0.0.0.0:8080`. |
| `PURGE_KEEP_LATEST` | Tùy chọn | Số tin nhắn giữ lại khi tự động dọn dẹp (mặc định `500`). |
| `PURGE_EVERY` | Tùy chọn | Chạy `purge_old_telegram_mirror_messages` sau mỗi N tin nhắn (mặc định `100`). |

## 4. Chế độ 1: Long-Polling (`--mode=polling`)

Phù hợp chạy daemon nền không cần mở port HTTPS public (giống `backend/notification-worker/`).

### Chạy trực tiếp / test 1 nhịp
```bash
DATABASE_URL="postgres://postgres:password@db.example.supabase.co:5432/postgres" \
TELEGRAM_BOT_TOKEN="123456:ABC..." \
TELEGRAM_ALLOWED_CHAT_ID="-1001234567890" \
node mirror-worker.mjs --mode=polling --once
```

### PM2
```bash
pm2 start mirror-worker.mjs --name "12tester-telegram-mirror" -- --mode=polling --poll-timeout=30
```

### systemd (`/etc/systemd/system/12tester-telegram-mirror.service`)
```ini
[Unit]
Description=12Tester Telegram Group Mirror Worker (Polling)
After=network.target

[Service]
Type=simple
User=crosstest
WorkingDirectory=/opt/crosstest/backend/telegram-mirror
EnvironmentFile=/etc/crosstest/telegram-mirror.env
ExecStart=/usr/bin/node mirror-worker.mjs --mode=polling --poll-timeout=30
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

## 5. Chế độ 2: HTTP Webhook (`--mode=webhook`)

Phù hợp triển khai sau Nginx/Caddy HTTPS reverse proxy, Cloud Run, hoặc nhận forward từ Supabase Edge Function.

### Khởi chạy server
```bash
DATABASE_URL="postgres://postgres:password@db.example.supabase.co:5432/postgres" \
TELEGRAM_BOT_TOKEN="123456:ABC..." \
TELEGRAM_ALLOWED_CHAT_ID="-1001234567890" \
TELEGRAM_WEBHOOK_SECRET="your-random-secret-at-least-32-chars" \
node mirror-worker.mjs --mode=webhook --port=8080
```

- Endpoint nhận webhook: `POST /telegram/webhook` (bắt buộc header `X-Telegram-Bot-Api-Secret-Token`, kiểm tra bằng `crypto.timingSafeEqual`).
- Endpoint kiểm tra sức khỏe: `GET /healthz`.

### Đăng ký Webhook với Telegram Bot API
```bash
curl -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/setWebhook" \
  -H "Content-Type: application/json" \
  -d '{
    "url": "https://mirror.example.com/telegram/webhook",
    "secret_token": "'"${TELEGRAM_WEBHOOK_SECRET}"'",
    "allowed_updates": ["message", "edited_message"]
  }'
```

*(Lưu ý: Nếu chuyển từ `--mode=polling` sang `--mode=webhook`, cần gọi `setWebhook`; nếu quay lại `--mode=polling`, cần gọi `deleteWebhook` trước).*

## 6. Nhập lịch sử tin nhắn cũ từ Telegram Desktop (`result.json`)

1. Trên Telegram Desktop, mở nhóm cộng đồng → `...` → **Export chat history** → chọn định dạng **JSON** (tắt tải media nặng để export nhanh).
2. Chạy dry-run kiểm tra trước:
   ```bash
   TELEGRAM_ALLOWED_CHAT_ID="-1001234567890" \
   node import-desktop-export.mjs --file=./ChatExport/result.json --limit=500 --dry-run
   ```
3. Nhập vào Supabase PostgreSQL:
   ```bash
   DATABASE_URL="postgres://postgres:password@db.example.supabase.co:5432/postgres" \
   TELEGRAM_ALLOWED_CHAT_ID="-1001234567890" \
   node import-desktop-export.mjs --file=./ChatExport/result.json --limit=500
   ```

## 7. Ẩn tin nhắn khỏi bản xem trước (`/hide_preview`)

Admin hoặc Creator của nhóm Telegram có thể **reply trực tiếp** vào bất kỳ tin nhắn nào trong nhóm và gửi `/hide_preview`:
- Worker kiểm tra quyền qua `getChatMember` (`creator` / `administrator`) hoặc `sender_chat` ẩn danh của chính nhóm.
- Nếu hợp lệ, tin nhắn đích được đánh dấu `is_deleted = true` (và xóa banner ghim nếu đang ghim tin đó), đồng thời bản thân lệnh `/hide_preview` không bị lưu lên feed.
- Client Android khi delta poll (`p_since_edited_at`) sẽ nhận bản ghi `is_deleted = true` và gỡ tin nhắn khỏi màn hình Chỉ xem.

## 8. Chạy Unit Tests

```bash
node --test mirror-worker.test.mjs
```
