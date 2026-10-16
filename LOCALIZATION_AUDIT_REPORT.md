# Báo cáo kiểm tra luồng ngôn ngữ — 12 Cross Testers

> Ngày kiểm tra: 2026-10-16
> Phạm vi: toàn bộ client Android (`app/src/main`), resource (`app/src/main/res`), backend Supabase (`supabase/`)
> Kết luận chính: Máy đặt tiếng Anh nhưng nhiều chỗ vẫn tiếng Việt là do **~597 chuỗi tiếng Việt hardcode trong 30 file Kotlin** (không đi qua `strings.xml`), cộng thêm **nội dung tiếng Việt từ server** hiển thị trực tiếp.

---

## 1. Kết luận điều tra (tóm tắt)

| # | Nguyên nhân | Quy mô | Ảnh hưởng |
|---|---|---|---|
| 1 | Chuỗi tiếng Việt hardcode trong Kotlin | ~597 chuỗi / 30 file | Mọi màn hình, luôn tiếng Việt bất kể locale |
| 2 | Message/nội dung từ Supabase (RPC, trigger, credit_logs, app_config) | 8 RPC + trigger + data | Snackbar, notification, lịch sử Credit, dialog update |
| 3 | Notification dùng Application context | 2 worker | Notification theo ngôn ngữ hệ thống, không theo cài đặt trong app |
| 4 | `AuthScreen` phát hiện thành công bằng so khớp chuỗi tiếng Việt | 1 chỗ | Vỡ logic khi message được dịch sang tiếng Anh |
| 5 | Resource dead/lẫn ngôn ngữ | 7 key | `warning_*` vẫn tiếng Việt trong cả `values` + `values-en` (hiện unused) |

**Kết luận:** Cơ chế locale của app (DataStore + `attachBaseContext` + `LocalConfiguration`) **hoạt động đúng**. Tất cả chuỗi dùng `stringResource` đều hiển thị đúng tiếng Anh trên máy EN. Hiện tượng "nhiều chỗ vẫn tiếng Việt" đến 100% từ chuỗi hardcode + dữ liệu server.

---

## 2. Cơ chế ngôn ngữ hiện tại (đã kiểm tra, hoạt động đúng)

### 2.1. Lưu & resolve ngôn ngữ — `data/SettingsRepository.kt`

```
app_language (DataStore) ∈ { "system", "vi", "en" }, mặc định "system"
- "vi" → Locale("vi")
- "en" → Locale("en")
- "system" → máy là tiếng Việt ? vi : en   (chính sách 2 ngôn ngữ)
```

- `setAppLanguage` ghi song song SharedPreferences (`testcheo_settings_sync`) để đọc sync lúc khởi động (`getSavedLanguageSync`, L78).
- Ưu điểm: đọc sync nhanh, tránh nháy sai ngôn ngữ khi mở app.

### 2.2. Áp locale — `MainActivity.kt`

- `attachBaseContext` (L50–L59): tạo `createConfigurationContext` với locale đã resolve → Activity context đúng ngôn ngữ.
- `setContent` (L68–L105):
  - `collectAsStateWithLifecycle` theo dõi `appLanguage`; tính `currentLocale` (L73–L84).
  - `Locale.setDefault(currentLocale)` (L86–L88) — đổi ngôn ngữ runtime không cần restart Activity.
  - `CompositionLocalProvider(LocalConfiguration + LocalContext)` cung cấp config/context localized cho toàn bộ Compose tree → `stringResource` luôn đúng.

### 2.3. Resource — `app/src/main/res`

| File | Số key | Nội dung |
|---|---|---|
| `values/strings.xml` | 308 | EN default — **trừ 4 key VN còn sót** (mục 5) |
| `values-en/strings.xml` | 308 | EN — **trừ 2 key VN còn sót** (`warning_*`) |
| `values-vi/strings.xml` | 308 | VI (283 key dịch VI hoàn chỉnh) |

- Cả 3 file **khớp 100% tên key** (đã diff) — không có key thiếu.

### 2.4. Notification (chưa đúng) — `work/`

- `NotificationHelper` (L21–L57, L64–L91) dùng `context.getString(...)` với context nhận từ `ReminderWorker`/`UpdateCheckWorker` = Application context.
- Application context **không được bọc locale override** của app → notification luôn theo ngôn ngữ hệ thống.
- Hệ quả: máy EN + cài đặt app VI → notification EN (ngược lại với UI); và ngược lại.

---

## 3. Nguyên nhân #1 — Chuỗi tiếng Việt hardcode (chi tiết đầy đủ)

Phương pháp quét: diacritics Việt (`[àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđĐ]`) trên toàn bộ 52 file `.kt`; loại comment và chuỗi non-UI.
Đã xác nhận: **0** chuỗi viết bằng `\uXXXX`, **0** chuỗi trong triple-quote, **0** tiếng Việt không dấu.

Tổng: **604 dòng chứa literal tiếng Việt**, trong đó **~597 chuỗi hiển thị cho người dùng**, 4 exception (có thể lộ qua UI lỗi), 3 non-UI (log/DB/parse); 59 dòng comment thuần (không tính).

### 3.1. Bảng tổng hợp theo file

| File | Số chuỗi UI | Ghi chú |
|---|---|---|
| `ui/screens/OwnerAppDetailScreen.kt` | ~141 | Quản lý app, duyệt báo cáo, slot, boa, so sánh ảnh |
| `ui/screens/CampaignDetailScreen.kt` | ~136 | Hướng dẫn cài, lịch sử test, form nộp báo cáo |
| `ui/components/ImageViewerComponents.kt` | ~33 | Viewer, so sánh/chớp ảnh, hash tương đồng |
| `ui/screens/AccountLockedScreen.kt` | ~32 | Khóa tài khoản + template email admin |
| `ui/screens/CreateCampaignScreen.kt` | ~25 | Slot, mục tiêu test, hướng dẫn tester |
| `ui/viewmodels/AuthViewModel.kt` | ~25 | Bao gồm toàn bộ `friendlyError` (L205–L231) |
| `ui/viewmodels/CampaignDetailViewModel.kt` | ~19 | |
| `ui/viewmodels/CreateCampaignViewModel.kt` | ~17 | |
| `ui/components/NotificationBottomSheet.kt` | ~16 | Filter, empty state, time-ago |
| `ui/screens/AdminUsersScreen.kt` | ~15 | |
| `ui/screens/HomeScreen.kt` | ~15 | Banner cảnh báo, dialog chỉ tiêu, cache |
| `ui/screens/ProfileScreen.kt` | ~13 | Slot, password, language |
| `ui/screens/QuestsScreen.kt` | ~13 | |
| `ui/viewmodels/OwnerAppDetailViewModel.kt` | ~13 | |
| `ui/components/CampaignCard.kt` | ~11 | |
| `ui/viewmodels/AdminUsersViewModel.kt` | ~10 | |
| `ui/viewmodels/ProfileViewModel.kt` | ~10 | |
| `ui/components/MyAppCard.kt` | ~9 | |
| `util/TopupConfig.kt` | ~8 | Feature flag đang tắt |
| `ui/screens/GoogleSignIn.kt` | ~7 | Toàn bộ là lỗi đăng nhập |
| `ui/components/DailyTaskCard.kt` | ~6 | |
| `ui/components/QuestSection.kt` | ~6 | |
| `ui/components/CreditTopupBottomSheet.kt` | ~5 | Feature flag đang tắt |
| `util/ImageHashHelper.kt` | ~4 | Nhãn mức tương đồng |
| `ui/viewmodels/HomeViewModel.kt` | ~4 | |
| `data/CampaignRepository.kt` | ~4 | 2 exception + 2 credit-log note |
| `data/SupabaseHolder.kt` | ~2 | Exception hiển thị qua MissingConfigScreen |
| `util/FirebaseStorageHelper.kt` | ~1 | Exception |
| **Tổng** | **~597** | |

### 3.2. Chi tiết theo file (line — chuỗi)

#### `ui/screens/OwnerAppDetailScreen.kt` (~141)
- L182 `"CẢNH BÁO DUYỆT BÁO CÁO ⚠️"` · L205 `"Tôi đã hiểu"` · L216 `"Quản lý App"` · L229 `"Chỉnh sửa"` · L232 `"Liên kết"` · L235 `"Làm mới"` · L261 `"Không tìm thấy thông tin ứng dụng."` · L323 `"Đóng"` · L355 `"App đã lên production"`/`"Đã hoàn thành kiểm thử"` · L362, L364 mô tả chuyển mục hoàn thành
- L393 `"App đang tạm dừng"` · L400–L404 lý do tạm dừng · L417 `"Bạn"` · L436–L447 thống kê người cài/báo cáo
- L458–L464 nút tiếp tục/tạm dừng nhận tester · L476–L490 production/xóa app
- L511–L516 tự test · L540/L562/L772 `"Không thể mở liên kết."` · L571 mở app · L586–L610 dialog pause/resume · L619–L661 dialog production/xóa · L720–L748 dialog gia hạn slot · L757–L782 app chưa cài · L836–L872 thông tin slot · L900–L915 stats · L940–L959 mục tiêu Closed Testing · L1080–L1116 boa cho tester · L1137–L1199 dialog chỉnh sửa app · L1215–L1275 dialog liên kết · L1288–L1378 danh sách tester · L1444–L1475 dialog duyệt báo cáo · L1501–L1534 tab trạng thái · L1559–L1589 hướng dẫn duyệt/empty state · L1729–L1750 trạng thái report · L1778–L1819 so sánh ảnh · L1837–L1963 chi tiết report + duyệt/từ chối · L1978–L2058 dialog lý do từ chối

#### `ui/screens/CampaignDetailScreen.kt` (~136)
- L209–L244 banner cảnh báo + acknowledge · L267–L364 dialog nộp thành công/test tiếp · L375–L393 app chưa cài · L404–L439 hướng dẫn cài đặt · L450–L486 troubleshoot không tải được app · L496–L601 lịch sử kiểm thử · L630–L683 dialog gỡ app admin · L707–L734 header + refresh · L790–L835 app của chính mình · L885–L922 stats · L954–L1043 bước cài đặt · L1090–L1097 trạng thái finalized/paused · L1201–L1254 nút test + tiến độ · L1279–L1343 trạng thái hôm nay · L1356–L1367 nút nộp · L1418–L1496 thông tin cài đặt + ghi chú owner · L1522–L1559 khu vực admin · L1619 `"Không thể mở liên kết này trên thiết bị."` · L1659 cảnh báo tương đồng ảnh · L1697–L1738 dialog nộp báo cáo · L1748–L2041 form: ảnh, đánh giá 1–5 sao, nhận xét, nút gửi

#### `ui/components/ImageViewerComponents.kt` (~33)
- L51/L135/L184/L193/L209 viewer cơ bản · L268–L338 dialog so sánh + duyệt/từ chối · L379–L429 phân tích hash + cảnh báo · L451–L457 chế độ so sánh · L505–L555 ảnh hôm nay/ngày trước · L573–L600 so sánh đè · L623–L692 chớp đổi A/B · L713–L745 slider + hướng dẫn

#### `ui/screens/AccountLockedScreen.kt` (~32)
- L95–L190 trạng thái, dialog mở khóa, kết quả · L202 `"Đăng xuất"` · L254–L300 header + thông tin tài khoản · L332–L387 2 lựa chọn mở khóa · L415–L455 liên hệ admin + template email (`"[12 Tester] Yêu cầu mở khóa tài khoản - $userEmail"`...) · L479 đăng xuất

#### `ui/viewmodels/AuthViewModel.kt` (~25)
- L59, L77, L92–L135, L155–L196 (login/register/reset flow) · L205–L231 `friendlyError`: map lỗi server (EN code) → message VN; fallback `"Đã có lỗi xảy ra."` và dump `e.message` ≤200 ký tự

#### 3.3. File sạch (không có tiếng Việt hardcode — 21 file)

`work/ReminderWorker.kt`, `work/UpdateCheckWorker.kt`, `work/NotificationHelper.kt`, `TestCheoApp.kt`, `MainActivity.kt`, `util/NetworkMonitor.kt`, `util/InstalledAppCache.kt`, `data/SettingsRepository.kt`, `data/SessionCacheManager.kt`, `data/AuthRepository.kt`, `data/AppConfigRepository.kt`, `ui/navigation/NavGraph.kt`, `ui/theme/*` (3 file), `ui/components/ProgressSection.kt`, `ui/components/CreditTopupBadge.kt`, `ui/components/AppIcon.kt`, `ui/screens/MissingConfigScreen.kt`, `ui/screens/PostAppGuideScreen.kt`, `ui/screens/UpdateScreens.kt`.
(`data/Models.kt` chỉ có comment tiếng Việt, không có UI text.)

---

## 4. Nguyên nhân #2 — Nội dung tiếng Việt từ Supabase

### 4.1. RPC trả message tiếng Việt hiển thị trực tiếp (8 RPC)

| RPC | Message VN | Hiển thị tại |
|---|---|---|
| `claim_quest_reward` | 6 message (`"Bạn chưa hoàn thành mốc..."`, `"Chúc mừng! Bạn đã nhận thành công +%s Credit..."`...) | `HomeViewModel.kt:436/439` (snackbar quest) |
| `process_topup_order` | `"Nạp thành công +%s Credit..."`, `"Giao dịch này đã được cộng điểm trước đó."` + `PACKAGE_NOT_FOUND` VN | `HomeViewModel.kt:405` |
| `approve_tester_report` | message VN nhưng **bị Kotlin bỏ qua** (result không có field `message`) | — |
| `self_unlock_account` | `RAISE 'INSUFFICIENT_CREDITS: Cần tối thiểu 100 Credit...'` | `AccountLockedScreen.kt:95/152` |
| `admin_adjust_credits` | `PERMISSION_DENIED` VN | `AdminUsersViewModel` |
| `admin_set_user_lock` | 2 RAISE VN | `AdminUsersViewModel` |
| `admin_delete_campaign` | `PERMISSION_DENIED` VN | `CampaignDetailViewModel` |
| `handle_campaign_target_cost` | `INSUFFICIENT_CREDITS_FOR_TARGET` VN | `CreateCampaignViewModel.kt:478` (map theo code) |

### 4.2. Dữ liệu do DB trigger tạo (hiển thị trong NotificationBottomSheet)

- Migration 009/013/014/016/017 chèn notification VN: `"Báo cáo kiểm thử mới 📋"`, `"Tài khoản đã bị tạm khóa 🔒"`, `"Biến động số dư: +%s Credit 🪙"`...
- `credit_logs.note` VN (`"Thưởng test app..."`, `"Đã nộp kết quả test (tạm tính...)"`...) — hiển thị lịch sử Credit.
- Client tự tạo note VN: `CampaignRepository.kt:938/965`.

### 4.3. `app_config` mặc định tiếng Việt (migration 018)

- `title = "Đã có bản cập nhật mới"`, `message = "Phiên bản mới với nhiều tính năng..."` → hiển thị trong `OptionalUpdateDialog`/`ForceUpdateScreen` qua `config.title ?: stringResource(...)`.

---

## 5. Nguyên nhân #3 — Lỗi luồng phụ

| # | Vị trí | Vấn đề | Hệ quả |
|---|---|---|---|
| 1 | `NotificationHelper.kt` (qua `ReminderWorker`, `UpdateCheckWorker`) | Dùng Application context, không bọc locale app | Notification sai ngôn ngữ so với UI khi cài đặt in-app khác hệ thống |
| 2 | `AuthScreen.kt:116` | `contains("Đăng ký thành công", ...)` để phát hiện message | Vỡ logic khi `AuthViewModel` được dịch sang EN |
| 3 | `PlayStoreHelper.kt:135` | Chỉ strip `" - Ứng dụng trên Google Play"` | Tên app parse từ Play Store EN còn dính `" - Apps on Google Play"` |
| 4 | `values/strings.xml` (default) | 4 key giá trị VN: `checkin_today`, `checked_in`, `app_not_installed_*`, `download_app_now` | Hiện **unused** (dead) — không gây lỗi hiển thị, nhưng sai nếu tái sử dụng |
| 5 | `values/` + `values-en/` | `warning_tester_msg`, `warning_owner_msg` vẫn VN | Hiện **unused**; cùng nội dung đang bị hardcode trong `HomeScreen`/`OwnerAppDetailViewModel` |
| 6 | `ProfileScreen.kt:1123` | Hardcode `"Tiếng Việt"` dù đã có `R.string.language_vietnamese` | Không sai hiển thị (tên ngôn ngữ), nhưng lệch pattern |
| 7 | `data/Models.kt:225/227` | Timezone `Asia/Ho_Chi_Minh` cố định | Không phải lỗi ngôn ngữ nhưng lệch locale (chỉ ảnh hưởng cách tính "hôm nay" ngoài VN) |

---

## 6. Kiểm tra nhanh để tái xác nhận (reproduce)

```powershell
# 1. Đếm chuỗi VN còn hardcode trong Kotlin
Get-ChildItem -Recurse "app\src\main\java" -Filter *.kt |
  Select-String -Pattern '[àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđĐ]' |
  Where-Object { $_.Line -notmatch '^\s*(//|\*|/\*)' } | Measure-Object

# 2. So khớp key giữa 3 file strings.xml (kỳ vọng: 308/308/308, không lệch)
# 3. Grep nơi hiển thị message server
Select-String -Path "app\src\main\java\com\ntt\crosstest\ui\viewmodels\HomeViewModel.kt" -Pattern 'res\.message'
```

## 7. Ghi chú cuối

- Việc khắc phục được lập kế hoạch riêng tại `LOCALIZATION_PLAN.md` (Phase 0–3).
- Báo cáo này là ảnh chụp trạng thái code ngày 2026-10-16 (commit `1382714`); line number có thể xê dịch sau khi sửa.
