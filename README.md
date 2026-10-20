# 12 Tester

App cộng đồng **test chéo** giúp các developer Android cùng nhau vượt qua yêu cầu
**closed testing 14 ngày / 20 tester** của Google Play trước khi phát hành production.

**Cách hoạt động:** developer đăng chiến dịch test (tên app, package, link track testing),
các developer khác tham gia test chéo lẫn nhau. Mỗi ngày mỗi người test app rồi bấm
**"Đã test hôm nay"** (mỗi người 1 lần/ngày — chống trùng ngay ở database), có thể để lại
đánh giá sao + góp ý. App tự tính tiến độ: đủ 14 ngày và đủ 20 tester thì sẵn sàng nộp Play duyệt.

> ⚠️ **Lưu ý quan trọng:** project này được viết trên máy không có Android SDK nên
> **CHƯA được compile**. Bạn build lần đầu trên máy Windows của mình bằng Android Studio.
> Mọi version thư viện đã được tra cứu và pin (xem mục "Phiên bản thư viện" bên dưới),
> code chỉ dùng API ổn định, nhưng nếu build báo lỗi version nào thì cứ paste lỗi cho
> Muse/Codex sửa — thường chỉ là lệch version nhỏ.

---

## 1. Tính năng (bản MVP)

- **Đăng nhập Google** qua Supabase Auth + Credential Manager (đăng nhập ngay trong app,
  không mở trình duyệt, app không lưu mật khẩu)
- **Tạo / đóng chiến dịch test** (chỉ chủ chiến dịch): tên app, package name, link Play,
  link testing track, mô tả, ngày bắt đầu
- **Khám phá**: danh sách chiến dịch đang mở + tìm kiếm theo tên app
- **Tham gia / rời** chiến dịch
- **Check-in mỗi ngày**: mỗi người 1 lần/ngày, chống check-in trùng bằng ràng buộc
  `UNIQUE(campaign_id, user_id, checkin_date)` ngay ở database
- **Đánh giá sao (1–5) + góp ý** cho từng chiến dịch (mỗi người 1 đánh giá, sửa được)
- **Màn hình tiến độ**: số tester (mục tiêu 20), số ngày cao nhất của tester (mục tiêu 14),
  danh sách tester xếp theo số ngày đã test
- **Nhắc nhở hằng ngày lúc 20:00** nếu hôm nay chưa check-in (WorkManager + notification)
- **Hệ thống Credit & Nhiệm vụ ngày**:
  - Mặc định tặng **100 Credit** khi tạo tài khoản (hiển thị cạnh tài khoản ở tab "Hồ sơ").
  - Thanh nhiệm vụ ở tab "Khám phá": Mỗi ngày cần test ít nhất 1 app.
  - Thưởng **+2 Credit** cho mỗi app test thành công (giới hạn trần thưởng tối đa 30 app/ngày = max 60 Credit/ngày).
  - Phạt vắng mặt lũy tiến nếu không làm nhiệm vụ: ngày 1 trừ 1%, ngày 2 trừ 2%, ngày 3 trừ 5%, từ ngày 4+ trừ 10%/ngày.
  - Điều kiện đăng app: Người dùng phải có **trên 10 Credit** mới được tạo chiến dịch.
- **Hồ sơ cá nhân**: xem số dư Credit, xem lịch sử biến động điểm, chiến dịch tôi tạo, chiến dịch tôi đã tham gia, bật/tắt nhắc giờ, đăng xuất
- **Song ngữ Việt / Anh** (tiếng Việt mặc định)

## 2. Công nghệ

- **App:** Kotlin + Jetpack Compose (Material 3), minSdk 26 (Android 8+), targetSdk/compileSdk 36
- **Backend:** Supabase — Auth (đăng nhập Google bằng Google ID token) + PostgreSQL với
  Row Level Security (quyền đọc/ghi kiểm soát ngay ở DB, mỗi người chỉ ghi được dữ liệu của mình)
- **Thư viện chính:** supabase-kt 3.8.0 (auth-kt, postgrest-kt), Credential Manager +
  Play Services Auth, Navigation Compose, WorkManager (nhắc giờ), DataStore (lưu cài đặt)
- Không dùng DI framework — ViewModel tạo thủ công, đơn giản

## 3. Cấu trúc project

```
cross-test/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/libs.versions.toml        # mọi version thư viện pin ở đây
├── app/
│   ├── build.gradle.kts             # đọc secret từ local.properties
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/ntt/crosstest/
│       │   ├── MainActivity.kt      # setContent + kiểm tra session
│       │   ├── TestCheoApp.kt       # Application: init Supabase + lên lịch nhắc giờ
│       │   ├── data/
│       │   │   ├── SupabaseHolder.kt      # object giữ SupabaseClient duy nhất
│       │   │   ├── Models.kt              # Profile, Campaign, CampaignMember, Checkin, Feedback
│       │   │   ├── AuthRepository.kt      # đăng nhập Google, đăng xuất, upsert profile
│       │   │   ├── CampaignRepository.kt  # mọi truy vấn campaign/member/checkin/feedback
│       │   │   └── SettingsRepository.kt  # DataStore: bật/tắt nhắc giờ
│       │   ├── ui/
│       │   │   ├── theme/                 # Color.kt, Theme.kt, Type.kt (Material 3)
│       │   │   ├── navigation/NavGraph.kt # routes: auth, home, campaign/{id}, create
│       │   │   ├── screens/               # Auth, Home (3 tab), CampaignDetail, Create, Profile
│       │   │   ├── components/            # CampaignCard, ProgressSection
│       │   │   └── viewmodels/            # mỗi màn hình một ViewModel (StateFlow)
│       │   └── work/
│       │       ├── ReminderWorker.kt       # WorkManager: kiểm tra check-in, bắn notification
│       │       └── NotificationHelper.kt   # tạo notification channel
│       └── res/                     # strings VI (mặc định) + EN, theme, icon
├── supabase/migrations/001_init.sql # toàn bộ schema + RLS + trigger tạo profile
└── README.md
```

## 4. Setup từng bước

### Bước A — Tạo project trên Supabase (miễn phí)

1. Vào https://supabase.com → **Start your project** → đăng nhập → **New project**.
2. Đặt tên (ví dụ `testcheo`), đặt database password, chọn region gần nhất (Singapore).
3. Đợi project khởi tạo xong (~2 phút). Vào **Project Settings → API**, copy:
   - **Project URL** → `SUPABASE_URL`
   - **anon public key** → `SUPABASE_ANON_KEY`

### Bước B — Chạy file SQL tạo database

1. Trong Supabase Dashboard, mở **SQL Editor** → **New query**.
2. Chạy lần lượt **tất cả** file trong `supabase/migrations/` theo thứ tự tăng dần
   (`001_init.sql` → `033_media_integrity.sql`). Mỗi file: mở ra, copy toàn bộ,
   paste vào một query mới rồi bấm **Run**.
3. Kiểm tra: vào **Table Editor** phải thấy các bảng chính `profiles`, `campaigns`,
   `campaign_members`, `checkins`, `feedbacks`, `credit_logs`, `tester_reports`,
   `notifications`, `user_warnings`, `report_review_events`.

> **Lưu ý quan trọng:**
> - `supabase/full_schema_singapore.sql` chỉ gộp schema tới migration 014, **không** bao gồm
>   các migration mới. Nếu dùng file này cho project mới, vẫn phải chạy tiếp các migration
>   từ `015` → `033` theo đúng thứ tự.
> - **Migration 030, 031, 032 và 033 là bắt buộc**: 030 chống lỗi hệ thống Credit (thu hồi Credit tạm,
>   Tip, cron auto-duyệt), 031 chống gian lận luồng duyệt báo cáo (bắt buộc ảnh mới sau khi bị
>   từ chối, khóa ảnh/rating sau khi duyệt, bộ đếm cảnh báo theo ngày Việt Nam), 032 bổ sung
>   quyền Admin tạm dừng/tiếp tục nhận tester cho app của người khác (không đổi chủ sở hữu),
>   033 chống nộp trùng khi retry (`client_submit_id`), bắt buộc `image_hash`/URL ảnh hợp lệ
>   và chặn client ghi thẳng bảng `tester_reports`.
> - Nếu chưa bật được `pg_cron` cho `auto_accept_expired_reports`, không sao: từ migration 031
>   hệ thống tự auto-duyệt khi mở chi tiết app hoặc tải Trang chủ.

File SQL làm những gì:
- Tạo 6 bảng + index + ràng buộc `UNIQUE(campaign_id, user_id, checkin_date)` chống check-in trùng.
- Bật **Row Level Security** cho từng bảng + policies: chiến dịch `open` thì ai đăng nhập
  cũng xem được; chỉ chủ có `credits > 10` mới tạo được chiến dịch; chỉ chủ mới sửa/đóng chiến dịch của mình; mỗi người chỉ ghi
  check-in/feedback/tham gia của chính mình.
- Trigger `handle_new_user()`: tự tạo dòng `profiles` và thưởng khởi tạo **100 Credit** (ghi log vào `credit_logs`).
- Trigger `handle_checkin_reward()`: thưởng **+2 Credit** cho mỗi app test (tối đa 30 app/ngày = max 60 Credit/ngày) và reset chuỗi vắng mặt.
- Hàm `apply_daily_inactivity_penalty()`: tự động trừ phạt Credit theo chuỗi ngày không làm nhiệm vụ (1%, 2%, 5%, 10%).

### Bước B2 — Deploy Firebase Storage rules

App upload ảnh screenshot report + icon app lên Firebase Storage bucket
`cross-testers.firebasestorage.app` (cấu hình trong `app/google-services.json`).
Repo có sẵn `firebase.json` + `storage.rules`:

1. Cài Firebase CLI: `npm install -g firebase-tools`.
2. `firebase login`.
3. `firebase use --add` → chọn project Firebase `cross-testers` (hoặc project đang dùng
   trong `google-services.json`).
4. `firebase deploy --only storage`.

Rules cho phép **read** công khai nhưng chỉ **create/update** object ảnh đúng
path/size/content-type và **không cho delete** — để client không thể xoá ảnh đang
được report tham chiếu.

### Bước C — Bật đăng nhập Google (quan trọng, làm cẩn thận)

App đăng nhập bằng **Google ID token** nên cần một **OAuth client loại Web application**
trên Google Cloud, rồi điền vào Supabase.

1. Vào https://console.cloud.google.com → tạo (hoặc chọn) một project.
2. **APIs & Services → OAuth consent screen**:
   - User type: **External** → Create.
   - Điền App name (TestChéo), User support email, Developer contact → Save.
   - Scopes: không cần thêm gì → Save. Test users: không cần (app public thì bỏ qua).
3. **APIs & Services → Credentials → Create Credentials → OAuth client ID**:
   - Application type: **Web application**, đặt tên ví dụ `TestCheo Web`.
   - **Authorized JavaScript origins**: có thể để trống.
   - **Authorized redirect URIs**: thêm `https://<project-ref>.supabase.co/auth/v1/callback`
     (lấy `<project-ref>` từ SUPABASE_URL của bạn, ví dụ `https://abcd1234.supabase.co/auth/v1/callback`).
     > Redirect URI này Supabase dùng cho các flow OAuth khác; với ID-token flow của app
     > thì không bắt buộc, nhưng cứ thêm cho chắc.
   - Create → copy **Client ID** và **Client secret**.
4. Quay lại Supabase Dashboard → **Authentication → Sign In / Providers** → bật **Google**:
   - Dán **Client ID** vào ô Client ID, **Client secret** vào ô Client Secret → Save.
   - **Chính Client ID này** sẽ dùng làm `GOOGLE_WEB_CLIENT_ID` ở bước D.

### Bước D — Mở project bằng Android Studio, điền secret

1. Trên máy Windows, mở **Android Studio** → Open → chọn thư mục `cross-test`.
   Đợi Gradle sync xong (lần đầu tải dependency hơi lâu).
2. Tạo file **`local.properties`** ở **thư mục gốc project** (cùng cấp với `settings.gradle.kts`),
   nội dung:
   ```properties
   SUPABASE_URL=https://<project-ref>.supabase.co
   SUPABASE_ANON_KEY=<anon public key ở bước A>
   GOOGLE_WEB_CLIENT_ID=<Web OAuth Client ID ở bước C>
   ```
   File này đã có trong `.gitignore` — không bao giờ commit.
   > Nếu chưa điền: project vẫn sync/build được, nhưng khi chạy app sẽ hiện màn hình
   > "Chưa cấu hình Supabase" thay vì crash.

### Bước E — Thêm SHA-1 vào OAuth client (bắt buộc, nếu không đăng nhập Google sẽ lỗi)

Google Play Services chỉ cấp ID token cho app có chữ ký (SHA-1) đã đăng ký **trong đúng
Google Cloud project đang sở hữu client Web** (client dùng làm `GOOGLE_WEB_CLIENT_ID`).
Đăng ký nhầm project khác là nguyên nhân phổ biến nhất khiến đăng nhập fail dù đã thêm SHA-1.

1. Lấy SHA-1 tương ứng với **bản build đang cài trên máy**:
   - **Debug** (chạy từ Android Studio): tab **Gradle** → `app → Tasks → android → signingReport` →
     copy dòng **SHA1** của variant `debug`.
     (hoặc `keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android -keypass android`)
   - **Bản cài từ Google Play**: Play Console → chọn app → **Test và phát hành → App integrity** →
     copy **SHA-1** ở card **"App signing key certificate"** (KHÔNG phải "Upload key certificate").
   - **APK release tự ký**: SHA-1 của file keystore release (`keytool -list -v -keystore <file>.jks`).
2. Kiểm tra `GOOGLE_WEB_CLIENT_ID` trong `local.properties` thuộc project nào (số đầu của
   Client ID = project number) → vào **đúng project đó** → **APIs & Services → Credentials** →
   Create Credentials → OAuth client ID → **Android**:
   - Package name: `com.ntt.crosstest`
   - SHA-1: dán giá trị vừa lấy (mỗi client Android chỉ nhận 1 SHA-1 → cần debug + Play thì tạo 2 client)
3. Đợi vài phút rồi đăng nhập lại — **không cần build lại app**.

> Dấu hiệu SHA-1 chưa đăng ký đúng: bảng chọn tài khoản Google hiện ra, chọn xong thì app
> báo **"Bạn đã hủy đăng nhập"** (Credential Manager trả về cancellation dù bạn không hủy —
> xem log tag `GoogleSignIn`/`AuthViewModel` để biết chi tiết).

### Bước F — Build & chạy, checklist kiểm thử

1. Cắm máy Android (hoặc emulator) → bấm **Run** trong Android Studio.
2. Lần đầu mở app sẽ hỏi quyền **thông báo** → cho phép (để nhận nhắc check-in).
3. Checklist:
   - [ ] Bấm **Đăng nhập bằng Google** → hiện bảng chọn tài khoản Google → đăng nhập thành công, vào tab Khám phá.
   - [ ] Bấm **+** → tạo chiến dịch (nhập tên app + package, link có thể để trống) → quay lại thấy chiến dịch trong danh sách.
   - [ ] Mở chiến dịch → bấm **Tham gia** → nút **Đã test hôm nay** hiện ra.
   - [ ] Bấm **Đã test hôm nay** → báo thành công → nút chuyển thành **Đã check-in hôm nay ✓** (bị disable).
   - [ ] Thử bấm lại (nếu nút còn bấm được): phải báo "Hôm nay bạn đã check-in rồi" — **không** được tạo 2 dòng check-in (kiểm tra trong Supabase Table Editor bảng `checkins`).
   - [ ] Gửi đánh giá 5 sao + góp ý → thấy hiện trong danh sách feedback.
   - [ ] Thanh tiến độ: Tester 1/20, Ngày cao nhất 1/14.
   - [ ] Tab **Của tôi**: thấy chiến dịch mình tạo + chiến dịch đã tham gia.
   - [ ] Tab **Hồ sơ**: thấy email, tắt/bật nhắc giờ, **Đăng xuất** → về màn hình đăng nhập.
   - [ ] **Nhắc giờ**: để qua 20:00 (hoặc test bằng cách đổi giờ máy), nếu hôm nay chưa check-in chiến dịch đã tham gia thì phải nhận notification.
   - [ ] Đăng nhập bằng tài khoản Google **khác** trên máy khác → tham gia cùng chiến dịch → chủ chiến dịch thấy 2 tester trong danh sách.

## 5. Phiên bản thư viện (đã tra cứu ngày 2026-10-13)

| Thư viện | Version | Nguồn tra cứu |
|---|---|---|
| AGP / Gradle / Kotlin | 8.13.2 / 8.13 / 2.4.20 | Google Maven metadata, kotlinlang.org (Kotlin 2.4.20 là stable mới nhất; supabase-kt 3.8.0 build bằng Kotlin 2.4 nên project phải ≥ 2.4) |
| supabase-kt (bom → auth-kt, postgrest-kt) | 3.8.0 | GitHub supabase-community/supabase-kt releases (stable 08/2026; tọa độ `io.github.jan-tennert.supabase`, module auth-kt/postgrest-kt; có BOM) |
| Ktor OkHttp engine | 3.6.0 | supabase-kt 3.8.0 build trên Ktor 3.6.0 |
| Compose BOM | 2026.09.00 | Google Maven metadata (compose-bom) |
| activity-compose | 1.13.0 | Google Maven metadata |
| androidx.credentials + credentials-play-services-auth | 1.6.0 | developer.android.com release notes (1.6.0 stable 04/2026; 1.7.x vẫn là alpha) |
| googleid (GetGoogleIdOption) | 1.1.0 | Google Maven |
| navigation-compose | 2.10.1 | Google Maven metadata (2.x stable; Navigation 3 vẫn RC) |
| lifecycle (viewmodel, runtime-compose) | 2.11.0 | Google Maven metadata |
| workmanager-ktx | 2.11.2 | Google Maven metadata |
| datastore-preferences | 1.2.1 | Google Maven metadata (1.3.0 vẫn alpha) |
| core-ktx | 1.19.0 | Google Maven metadata |
| kotlinx-coroutines-android | 1.11.0 | Maven Central metadata |
| kotlinx-serialization-json | 1.11.0 | Maven Central metadata |

minSdk 26 / targetSdk 36 / compileSdk 36 / Java 17 theo yêu cầu.

## 6. Những điểm cần biết thêm

- **Giờ nhắc 20:00 không chính xác tuyệt đối**: WorkManager đảm bảo chạy *ít nhất mỗi ngày*,
  nhưng trên một số máy (Doze/battery optimization) notification có thể đến trễ vài chục phút.
  Muốn chính xác giờ thì phải dùng `AlarmManager.setExactAndAllowWhileIdle` + xin quyền
  `SCHEDULE_EXACT_ALARM` — để dành bản sau nếu cần.
- **Chưa có màn hình sửa chiến dịch**: hiện chỉ có tạo + đóng. Muốn sửa thì nói một câu,
  thêm nhanh (dùng chung form Create).
- **Chưa realtime**: tiến độ cập nhật khi mở lại màn hình / kéo refresh. Muốn realtime thì
  dùng supabase-realtime — để dành bản sau.
- Khi build bản **release** để đăng Play: tạo keystore riêng (upload key). Nhưng máy người
  dùng nhận bản do **Play App Signing** ký → phải thêm SHA-1 ở Play Console
  (**App integrity → "App signing key certificate"**) vào OAuth client Android trong đúng
  project của client Web (bước E). `GOOGLE_WEB_CLIENT_ID` giữ nguyên, không đổi.

## 7. Đổi tên app / package

Mặc định: tên **TestChéo**, package **com.ntt.crosstest**. Muốn đổi thì sửa:
`app/build.gradle.kts` (namespace, applicationId), `AndroidManifest.xml` không cần
(package lấy từ namespace), tên hiển thị trong `res/values*/strings.xml` (`app_name`),
và nhớ tạo lại OAuth client Android với package mới (bước E).
