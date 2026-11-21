# Hướng dẫn deploy Edge Function `play-production-detector` (0-VPS)

Edge Function `play-production-detector` tự động kiểm tra trang Google Play công khai cho các chiến dịch đang `status = 'open'` và ghi nhận kết quả qua RPC `system_record_play_store_check` (yêu cầu 2 lần phát hiện Production liên tiếp cách nhau ≥ 120 phút mới bật dấu `v`).

---

## Bước 1: Chạy Migration 102
Trong **Supabase Dashboard → SQL Editor**, chạy toàn bộ nội dung file [102_play_production_detection.sql](../../migrations/102_play_production_detection.sql).

---

## Bước 2: Khai báo Secret trên Supabase
Trong **Supabase Dashboard → Edge Functions → Secrets** (hoặc `npx supabase secrets set`), thêm:

| Tên Secret | Giá trị |
|---|---|
| `PLAY_DETECTOR_CRON_SECRET` | Chuỗi bí mật ngẫu nhiên bạn tự đặt (ví dụ `12tester_play_cron_9f8e7d6c5b4a`) |

*(Không cần thêm `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `SUPABASE_SERVICE_ROLE_KEY` vì Supabase tự cấp sẵn cho Edge Function).*

---

## Bước 3: Deploy Edge Function (chọn 1 trong 2 cách)

### Cách A — Làm trực tiếp trên Web Supabase Dashboard (không cần cài CLI)
1. Vào **Supabase Dashboard → Edge Functions → Deploy a new function → Via Editor**.
2. Đặt tên function là **`play-production-detector`**.
3. Copy toàn bộ code trong file [index.ts](./index.ts) dán vào trình soạn thảo.
4. **QUAN TRỌNG**: Ở phần cài đặt function, **TẮT** mục **Enforce JWT Verification / Verify JWT** (vì cron gửi header `X-Cron-Secret`, còn chế độ kiểm tra đơn lẻ từ app tự xác thực Bearer JWT qua `/auth/v1/user`).
5. Bấm **Deploy**.

### Cách B — Dùng lệnh CLI từ thư mục gốc dự án
```powershell
npx supabase login
npx supabase link --project-ref <MA_PROJECT_REF_CUA_BAN>
npx supabase functions deploy play-production-detector --no-verify-jwt
```

---

## Bước 4: Thiết lập lịch quét tự động mỗi 3 giờ (miễn phí)

Có thể dùng **GitHub Actions** (miễn phí), **Cloudflare Workers Cron**, hoặc **cron-job.org** gọi:
- **URL**: `https://<MA_PROJECT_REF>.supabase.co/functions/v1/play-production-detector`
- **Method**: `POST`
- **Headers**:
  - `Content-Type: application/json`
  - `X-Cron-Secret: <PLAY_DETECTOR_CRON_SECRET>`
- **Body**: `{"limit": 25}`

### Ví dụ GitHub Actions (`.github/workflows/play-production-detector.yml`)
```yaml
name: Play Production Detector
on:
  schedule:
    - cron: "0 */3 * * *"
  workflow_dispatch:

jobs:
  scan:
    runs-on: ubuntu-latest
    steps:
      - name: Trigger Edge Function
        run: |
          curl -fsS -X POST "${{ secrets.SUPABASE_URL }}/functions/v1/play-production-detector" \
            -H "Content-Type: application/json" \
            -H "X-Cron-Secret: ${{ secrets.PLAY_DETECTOR_CRON_SECRET }}" \
            -d '{"limit": 25}'
```

### Kiểm thử thủ công ngay sau khi deploy
```powershell
$URL = "https://<MA_PROJECT_REF>.supabase.co/functions/v1/play-production-detector"
$SECRET = "<PLAY_DETECTOR_CRON_SECRET>"

Invoke-RestMethod -Method Post -Uri $URL `
  -Headers @{ "X-Cron-Secret" = $SECRET } `
  -ContentType "application/json" `
  -Body '{"limit": 5}'
```
