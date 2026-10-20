# Kế hoạch đa ngôn ngữ (EN/VI) — 12 Cross Testers

> Ngày lập kế hoạch: 2026-10-16
> Trạng thái: ĐÃ TRIỂN KHAI XONG CLIENT (Phase 0–2 + xác minh Phase 3) — còn test thủ công trên máy
> Phạm vi: full client (Phase 0–2) + giữ nguyên nội dung tiếng Việt từ Supabase (Phase 3)

## 1. Bối cảnh & vấn đề

Máy đặt ngôn ngữ tiếng Anh nhưng app vẫn hiển thị tiếng Việt ở nhiều chỗ.

### Cơ chế hiện tại (hoạt động đúng)

- `SettingsRepository` lưu `app_language` (`system`/`vi`/`en`), mặc định `system` → máy EN resolve `en`.
- `MainActivity.attachBaseContext` + `CompositionLocalProvider(LocalConfiguration/LocalContext)` bọc locale đúng → **chuỗi dùng `stringResource` hiển thị đúng tiếng Anh**.
- 3 file strings.xml đủ 308 key: `values/` = EN (default), `values-en/` = EN, `values-vi/` = VI.

### Nguyên nhân gốc

1. **~597 chuỗi tiếng Việt hardcode trong Kotlin (30 file)** — bỏ qua hoàn toàn strings.xml, luôn hiển thị tiếng Việt bất kể locale.
2. **Nội dung từ Supabase luôn tiếng Việt** — 8 RPC trả message VN, trigger DB tạo notification VN, `credit_logs.note`, default `app_config.title/message` (migration 018). Hiển thị qua `res.message`/`e.message`.
3. **Lỗi luồng phụ:**
   - `NotificationHelper` chạy bằng Application context → theo ngôn ngữ hệ thống, không theo cài đặt trong app.
   - `AuthScreen.kt:116` phát hiện thành công bằng `contains("Đăng ký thành công")` — vỡ khi dịch sang EN.
   - `PlayStoreHelper.kt:135` chỉ strip hậu tố tiếng Việt của tiêu đề Play Store.
   - `warning_tester_msg`/`warning_owner_msg` vẫn tiếng Việt trong `values` + `values-en` (hiện unused).
   - 5 key dead trong default `values/`: `checkin_today`, `checked_in`, `app_not_installed_title`, `app_not_installed_msg`, `download_app_now` (giá trị VN; `values-en` đã EN).

## 2. Quyết định đã chốt

- [x] Phạm vi: **Full 3 phase** (toàn bộ client + xử lý server-side).
- [x] ViewModel: **localized context trong VM** (helper `Localizer`), không refactor UiText.
- [x] Nội dung server (RPC, trigger notification, credit_logs): **giữ nguyên tiếng Việt**, không sửa SQL.

## 3. Quy ước triển khai

### 3.1. Helper locale (thêm vào `data/SettingsRepository.kt`)

```kotlin
companion object {
    /** Context đã bọc locale theo cài đặt trong app (dùng cho VM/notification). */
    fun wrap(context: Context): Context {
        val locale = resolveLocale(getSavedLanguageSync(context))
        val config = Configuration(context.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return context.createConfigurationContext(config)
    }
}

/** Tiện ích cho ViewModel: lấy chuỗi đúng ngôn ngữ hiện tại tại thời điểm gọi. */
class Localizer(private val appContext: Context) {
    fun get(@StringRes id: Int, vararg args: Any): String =
        if (args.isEmpty()) SettingsRepository.wrap(appContext).getString(id)
        else SettingsRepository.wrap(appContext).getString(id, *args)
}
```

- VM nhận `Localizer` qua factory (`Localizer(app.applicationContext)`), KHÔNG giữ Activity context.
- `wrap()` gọi mỗi lần `get()` → không bị stale sau khi người dùng đổi ngôn ngữ trong app.

### 3.2. Chuỗi resource

- Key mới: snake_case, tiền tố màn hình/feature: `auth_*`, `home_*`, `campaign_*`, `quest_*`, `profile_*`, `slot_*`, `owner_*`, `admin_*`, `notif_*`, `img_*`, `daily_*`, `topup_*`.
- Thêm đồng thời vào cả 3 file: `values/strings.xml` (EN), `values-en/strings.xml` (EN), `values-vi/strings.xml` (VI).
- Chuỗi format nhiều tham số: dùng positional `%1$s`, `%2$d` và giữ nguyên thứ tự ở cả 3 ngôn ngữ.
- Chuỗi ghép động (VD: `"$a/$b hôm nay · $c người đã cài"`) → tách thành template resource có placeholder.
- URL/email/giá trị non-translatable: `translatable="false"`.
- Trước khi thêm key mới: kiểm tra key đã tồn tại trong 308 key hiện có để tái sử dụng (VD: `error_insufficient_credits_for_slot`, `slot_unlock_success`, `account_locked_*`...).

### 3.3. Pattern thay thế

- Composable: `Text(stringResource(R.string.key))` hoặc `Text(stringResource(R.string.key, arg1, arg2))`.
- ViewModel: `_uiState.update { it.copy(error = localizer.get(R.string.key, arg)) }`.
- Không dịch: comment code, giá trị so khớp DB (`"tạm tính"` trong `CampaignRepository.kt:405`), error code server (`"INSUFFICIENT_CREDITS"`...).

## 4. Phase 0 — Hạ tầng & lỗi luồng

- [x] Thêm `SettingsRepository.wrap(context)` + class `Localizer` (mục 3.1).
- [x] `MainActivity.attachBaseContext`: refactor dùng `SettingsRepository.wrap(newBase)` (giảm trùng lặp).
- [x] Tiêm `Localizer` vào các ViewModel chưa có context:
  - `AuthViewModel` (sửa factory trong `AuthScreen.kt:83`)
  - `CampaignDetailViewModel` (factory `CampaignDetailScreen.kt:137`)
  - `CreateCampaignViewModel` (factory `CreateCampaignScreen.kt:110`)
  - `AdminUsersViewModel` (factory `AdminUsersScreen.kt:94`)
  - `OwnerAppDetailViewModel` (factory `OwnerAppDetailScreen.kt:127`)
  - `HomeViewModel`/`ProfileViewModel` đã có sẵn `applicationContext`/`app` — dùng lại.
- [x] `AuthScreen.kt:116`: thay `contains("Đăng ký thành công")` bằng cờ `isSignUpSuccess: Boolean` trong `AuthUiState` (set trong `AuthViewModel.signUp`, reset khi đổi tab/nhập liệu).
- [x] `NotificationHelper.showCheckinReminder/showUpdateAvailable`: bọc context qua `SettingsRepository.wrap(context)` trước khi `getString`.
- [x] `PlayStoreHelper.kt:135`: strip cả `" - Apps on Google Play"` và `" - Ứng dụng trên Google Play"`.
- [x] Resource:
  - [x] Điền EN cho `warning_tester_msg`, `warning_owner_msg` trong `values/` + `values-en/` (viết lại; `values-vi` giữ VN).
  - [x] Sửa 5 key dead trong default `values/` sang EN: `checkin_today`, `checked_in`, `app_not_installed_title`, `app_not_installed_msg`, `download_app_now` (giữ để tái sử dụng ở Phase 1).

## 5. Phase 1 — Auth + Home + Campaign + Quests + Profile + components (~250 chuỗi)

### Components

- [x] `ui/components/CampaignCard.kt` — 11 chuỗi (L108–L197: trạng thái, `Chủ app`, `Đã cài`, tiến độ...)
- [x] `ui/components/DailyTaskCard.kt` — 6 chuỗi (L231–L424: `Khách`, `Vắng $n ngày`, yêu cầu, quy định phạt)
- [x] `ui/components/MyAppCard.kt` — 9 chuỗi (L118–L258: slot, trạng thái, `chờ duyệt`...)
- [x] `ui/components/NotificationBottomSheet.kt` — 16 chuỗi (L72–L447: filter tabs, empty state, time-ago `Vừa xong/$n phút trước`...)
- [x] `ui/components/QuestSection.kt` — 6 chuỗi (L140–L475: tiến độ ngày/tuần, claimable)

### Screens

- [x] `ui/screens/HomeScreen.kt` — 15 chuỗi (L178–L1010: banner cảnh báo, dialog đủ chỉ tiêu, cache, notification title, empty state)
- [x] `ui/screens/CampaignDetailScreen.kt` — ~136 chuỗi (file lớn nhất; L209–L2041: hướng dẫn cài, lịch sử test, form nộp báo cáo, dialog admin, trạng thái campaign). Tái sử dụng key `app_not_installed_*`, `checkin_today`, `warning_*` đã chuẩn bị ở Phase 0.
- [x] `ui/screens/QuestsScreen.kt` — 13 chuỗi (L148–L659)
- [x] `ui/screens/ProfileScreen.kt` — 13 chuỗi (L221–L1349; L1123 hardcode `"Tiếng Việt"` — xem lại có cần thiết vì đã có `R.string.language_vietnamese`)
- [x] `ui/screens/GoogleSignIn.kt` — 7 chuỗi (L51–L117)

### ViewModels

- [x] `ui/viewmodels/AuthViewModel.kt` — ~25 chuỗi (bao gồm toàn bộ `friendlyError` L205–L231)
- [x] `ui/viewmodels/CampaignDetailViewModel.kt` — ~19 chuỗi (L108–L385)
- [x] `ui/viewmodels/HomeViewModel.kt` — 4 chuỗi (L172, L405, L436, L439)
- [x] `ui/viewmodels/ProfileViewModel.kt` — 10 chuỗi (L124–L268)

## 6. Phase 2 — Owner/Admin + tiện ích (~350 chuỗi)

### Screens

- [x] `ui/screens/OwnerAppDetailScreen.kt` — ~141 chuỗi (L182–L2058: quản lý app, duyệt báo cáo, slot, boa, so sánh ảnh, dialog từ chối...) — dùng tiền tố khóa `oad_*`
- [x] `ui/screens/AccountLockedScreen.kt` — 32 chuỗi (L95–L479; template email giải trình L429–L436 giữ VN vì gửi cho admin)
- [x] `ui/screens/AdminUsersScreen.kt` — 15 chuỗi (L209–L741)
- [x] `ui/screens/CreateCampaignScreen.kt` — 25 chuỗi (L194–L1088)

### Components

- [x] `ui/components/ImageViewerComponents.kt` — ~33 chuỗi (L51–L745: viewer, so sánh ảnh, chớp đổi A/B, hash tương đồng) — dùng tiền tố khóa `img_*`
- [x] `ui/components/CreditTopupBottomSheet.kt` — 5 chuỗi (L271–L399; feature flag `IS_TOPUP_FEATURE_ENABLED = false` nhưng vẫn làm; ký hiệu tiền tệ `"đ"` giữ nguyên như `"$"`)

### ViewModels

- [x] `ui/viewmodels/CreateCampaignViewModel.kt` — ~17 chuỗi (L141–L481)
- [x] `ui/viewmodels/OwnerAppDetailViewModel.kt` — ~13 chuỗi (L121–L277)
- [x] `ui/viewmodels/AdminUsersViewModel.kt` — 10 chuỗi (L134–L262)

### Utils / data

- [x] `util/ImageHashHelper.kt` — 4 chuỗi (L177–L180: mức tương đồng) — `compare()` nay nhận thêm `context`
- [x] `util/TopupConfig.kt` — 8 chuỗi (L27–L76: tên gói) — **BỎ QUA có chủ đích**: tên gói phản chiếu dữ liệu bảng `topup_packages` trên server (chính sách VN) + feature flag đang tắt
- [x] `data/SupabaseHolder.kt` — 2 exception (L24, L46; hiển thị qua MissingConfigScreen) — đổi sang **tiếng Anh chẩn đoán dev**; MissingConfigScreen đã localize đầy đủ nên không lộ ra người dùng
- [x] `util/FirebaseStorageHelper.kt` — 1 exception (L95)
- [x] `data/CampaignRepository.kt` — 2 exception (L102, L286) + 2 credit-log note client tự tạo (L938, L965) → tạo qua `Localizer` để đúng ngôn ngữ lúc phát sinh (lưu ý: dữ liệu đã ghi vào DB sẽ không đổi khi user đổi ngôn ngữ sau này). Constructor nay nhận `localizer`; 2 nơi khởi tạo cập nhật (`TestCheoApp.kt:66`, `ReminderWorker.kt:33`)

## 7. Phase 3 — Server-side

- [x] Quyết định: **giữ nguyên tiếng Việt** — không sửa SQL migration.
- [x] Kiểm tra không làm vỡ luồng khi server trả VN:
  - [x] `friendlyError` (AuthViewModel) tiếp tục map theo error code EN nhúng trong message (`INSUFFICIENT_CREDITS:`, `PERMISSION_DENIED:`...).
  - [x] `HomeViewModel` L405/L436/L439 dùng `res.message` nguyên trạng, fallback đã localize.
  - [x] `AccountLockedScreen` L95/L152 hiển thị `e.localizedMessage` nguyên trạng.
- [x] `app_config.title/message` (migration 018 default VN): giữ fallback `config.title ?: stringResource(...)` — admin có thể tự nhập EN trong DB nếu muốn.

## 8. Kiểm tra & xác minh

Sau mỗi file/nhóm file:

```powershell
.\gradlew.bat :app:assembleDebug
```

Cuối mỗi phase:

```powershell
.\gradlew.bat :app:lintDebug
```

Grep hồi quy (chỉ còn comment + check DB + SQL là hợp lệ):

```powershell
Get-ChildItem -Recurse "app\src\main\java" -Filter *.kt |
  Select-String -Pattern '[àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđĐ]' |
  Where-Object { $_.Line -notmatch '^\s*(//|\*|/\*)' }
```

Kết quả hợp lệ còn lại (sau sweep cuối):
- Comment code (toàn bộ file) — không dịch.
- `CampaignRepository.kt:409` — `"tạm tính"` (giá trị so khớp DB, phải giữ).
- `CampaignRepository.kt` L123/L141/L159/L170–172/L183/L187/L396/L860 — comment.
- `PlayStoreHelper.kt:135` — `" - Ứng dụng trên Google Play"` (hậu tố tiêu đề do Google Play sinh theo ngôn ngữ listing).
- `AccountLockedScreen.kt:429–436` — template email giải trình gửi admin (chủ đích giữ VN).
- `CampaignDetailScreen.kt:658` — `"Gỡ bỏ bởi Admin"` (giá trị lý do xóa lưu DB).
- `CreditTopupBottomSheet.kt` — ký hiệu tiền tệ `"đ"` (tương tự `"$"`).
- `util/TopupConfig.kt` — tên gói phản chiếu DB `topup_packages` (xem Ghi chú triển khai).
- SQL trong `supabase/`.

Test thủ công (không có test tự động trong repo — chỉ `app/src/main`):

- [ ] Máy để EN (system): walk Auth → Home 3 tab → CampaignDetail (mở app, nộp báo cáo, so sánh ảnh) → Quests → Profile (slot) → notification.
- [ ] Đổi in-app sang VI: kiểm tra lại UI + notification (phải theo ngôn ngữ app, không theo hệ thống).
- [ ] Đổi lại EN: kiểm tra không còn sót chuỗi VN.
- [ ] Guest mode + owner mode + admin mode (nếu có tài khoản).

## 9. Thứ tự thực hiện đề xuất trong phiên làm việc

1. Phase 0 toàn bộ → build.
2. Phase 1 components → build → screens nhỏ (Quests, Profile, GoogleSignIn, Home) → build → CampaignDetailScreen → build.
3. Phase 1 ViewModels → build.
4. Phase 2 theo thứ tự: ImageViewerComponents → OwnerAppDetailScreen → AccountLockedScreen → CreateCampaignScreen → AdminUsersScreen → ViewModels → utils → build.
5. Rà soát grep hồi quy + test thủ công 2 ngôn ngữ.

## 10. Rủi ro

- Khối lượng ~600 chuỗi / ~30 file → chia nhỏ theo file, build sau mỗi nhóm để tránh vỡ diện rộng.
- Chuỗi format động phải giữ đúng positional args giữa 3 file strings.xml.
- `OwnerAppDetailScreen.kt` (~2000 dòng) và `CampaignDetailScreen.kt` (~2000 dòng) là 2 file rủi ro cao nhất — làm sau cùng trong phase, cẩn thận từng khối UI.
- Dữ liệu đã lưu DB (credit_logs.note, notification) luôn theo ngôn ngữ lúc tạo, đổi ngôn ngữ sau không cập nhật lại — chấp nhận.

## 11. Ghi chú triển khai (khác biệt so với kế hoạch)

- **Số key resource cuối cùng: 836 key** ở cả 3 file `strings.xml` (trước kế hoạch: 308). Đã kiểm tra parity + không trùng key bằng script.
- **TopupConfig.kt (bỏ qua)**: tên gói + badge phản chiếu nội dung bảng `topup_packages` trên server (chính sách nội dung VN); khi bật lại feature topup nên lấy `title` từ server thay vì dịch cứng trong code. Feature flag đang tắt (`IS_TOPUP_FEATURE_ENABLED = false`).
- **Ký hiệu tiền tệ `"đ"`** trong `CreditTopupBottomSheet.kt` giữ nguyên (analog với `"$"`, không phải câu dịch).
- **SupabaseHolder.kt**: 2 exception đổi sang **tiếng Anh** dạng chẩn đoán dev (không dùng Localizer vì đây là lỗi cấu hình build; `MissingConfigScreen` phía người dùng đã localize đầy đủ).
- **AccountLockedScreen.kt L429–436**: template email gửi admin giữ VN có chủ đích (người nhận là admin VN).
- **ImageHashHelper.compare()** đổi chữ ký thành `compare(hash1, hash2, context)` — 3 call site đã cập nhật (`ImageViewerComponents`, `OwnerAppDetailScreen`, `CampaignDetailScreen`).
- **CampaignRepository** constructor nhận `Localizer`; cập nhật 2 nơi khởi tạo `TestCheoApp.kt` và `ReminderWorker.kt`.
- **Sự cố đã xử lý**: trùng key `error_password_too_short` khi nối block XML (đã xóa duplicate ở L600 cả 3 file); lỗi smart-cast `campaign.testingDaysRemaining` trong `OwnerAppDetailScreen` (sửa bằng `?: 0`).
- Xác minh tự động đã chạy: `:app:compileDebugKotlin` EXIT 0 sau từng nhóm; sweep VN toàn bộ `app/src/main/java` chỉ còn comment + danh sách hợp lệ ở mục 8.
- `:app:lintDebug` đã chạy (EXIT 0; project đặt `abortOnError = false`):
  - Đã xử lý triệt để 20× `StringFormatInvalid` ở `daily_penalty_dialog_content` bằng thuộc tính `formatted="false"` ở cả 3 file `strings.xml`.
  - 17× `LocalContextGetResourceValueCall` tại các điểm `context.getString(...)` trong lambda non-composable (AccountLockedScreen ×6, OwnerAppDetailScreen ×6, ProfileScreen ×2, HomeScreen ×1, CampaignDetailScreen ×2) — chấp nhận vì `LocalContext.current` trong app chính là context đã bọc locale và Activity được recreate khi đổi ngôn ngữ trong app (không stale).
- Còn lại: **test thủ công 2 ngôn ngữ trên máy** (mục 8).
