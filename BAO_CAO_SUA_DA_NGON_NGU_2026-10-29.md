# BÁO CÁO BÀN GIAO: SỬA TOÀN DIỆN ĐA NGÔN NGỮ VÒNG 2 (S01–S07 / R01–R10)

**Thời gian cập nhật:** 29/10/2026 20:05:00 (GMT+7)  
**Tài liệu kế hoạch tham chiếu:** `RECHECK_VONG_2_VA_KE_HOACH_SUA_DA_NGON_NGU_2026-10-29.md` & `RECHECK_VA_KE_HOACH_SUA_TIEP_DA_NGON_NGU_2026-10-29.md`  
**Bằng chứng kiểm tra thực thi:** `audit/localization-recheck-round2-2026-10-29/` (`database-probe.mjs`, `database-results.json`, `LocalizationRound2Test.kt`)  

---

## 1. TỔNG QUAN KẾT QUẢ THỰC HIỆN

Toàn bộ **7 nhóm vấn đề S01–S07** phát hiện trong đợt recheck vòng 2 đã được sửa triệt để từ tầng cơ sở dữ liệu (Migration 055 & Full schema), Repositories, Renderers, State dynamic resolution đến MainActivity:

- **100% Tests Pass:**
  - **32/32 Android Unit Tests PASS** (21 test cũ từ vòng 1 + 11 test độc lập mới từ vòng 2):
    - `LocalizationHardeningTest.kt`: 12/12 PASS
    - `LocalizationRecheckTest.kt`: 9/9 PASS
    - `LocalizationRound2Test.kt`: 11/11 PASS
- **Database Probes Hoàn Toàn Xanh:**
  - `audit/localization-recheck-round2-2026-10-29/database-probe.mjs`: **Exit 0**
  - Khôi phục đầy đủ 3 nhánh thông báo `approve`, `reject`, `edit` bị mất sau migration 055 (count trước = 1, sau = 1).
  - Khắc phục backfill cấu hình update: không còn sao chép text tiếng Anh vào `title_vi`/`message_vi`.
  - Hỗ trợ xóa bản dịch bằng payload null và cập nhật từ client legacy không bị che bởi bản dịch cũ.
- **Bảo toàn tính toàn vẹn:**
  - Không sửa đổi hay làm hỏng các thay đổi song song trong working tree (`ReportSubmitManager`, `ReportUploadWorker`, upload ngầm, v.v.).
  - Giữ nguyên số dư Credit, quyền hạn, deadline 8 giờ, cơ chế retry, idempotency và dữ liệu người dùng.
  - Chưa deploy production (tuân thủ chỉ thị).

---

## 2. BẢNG TRẠNG THÁI CHI TIẾT TỪNG MỤC S01 – S07

| Mã | Hạng mục | Trạng thái | Bằng chứng kiểm tra | Chi tiết xử lý kỹ thuật |
|:---|:---|:---:|:---:|:---|
| **S01** | Khôi phục thông báo approve, reject và edit trong `trg_notify_tester_report` | **HOÀN THÀNH** | `node database-probe.mjs`<br>(Count approve=1, reject=1, edit=1) | Tái lập toàn bộ 4 nhánh sự kiện trong `trg_notify_tester_report()`: (1) `INSERT pending` (báo cáo mới), (2) `UPDATE rejected -> pending` (nộp lại), (3) `UPDATE pending -> pending` khi đổi ảnh/hash/content (`report_edited`), và (4) `UPDATE pending -> approved / rejected` (phê duyệt / từ chối cho tester). Bảo toàn `v_extra`, `v_clawed`, `reason_code` và các cờ `app.suppress_report_notify`. Đồng bộ sang `full_schema_singapore.sql`. |
| **S02** | Khắc phục nguồn locale system bị bọc trong `SettingsRepository` & `MainActivity` | **HOÀN THÀNH** | `systemChoiceMustNotUsePreviouslyWrappedActivityConfiguration`<br>(PASS) | `SettingsRepository.resolveLocale(setting, systemLocale)`: Khi setting là `"system"`, luôn đọc trực tiếp từ `getSystemLocale()` (`Resources.getSystem().configuration.locales[0]`), không phụ thuộc vào context đã bị bọc bởi `attachBaseContext`. Tại `MainActivity.kt`, observe qua `remember(languageSetting, configuration)` để phản ánh kịp thời thay đổi ngôn ngữ hệ thống. |
| **S03** | Phân loại thông báo lịch sử đã duyệt, title song ngữ | **HOÀN THÀNH** | `oldApprovedReportMustNotTurnIntoNewReport`<br>`oldApprovedReportTitleMustTranslate`<br>(PASS) | Trong `NotificationRenderer.kt`: Ưu tiên đọc trường `data.status == "approved"` và đối chiếu thông điệp duyệt lịch sử trước các kiểm tra thông báo mới. Bổ sung ánh xạ title `"Báo cáo kiểm thử được phê duyệt! 🎉"` sang tiếng Anh (`notif_title_test_approved`). Không còn tình trạng thông báo duyệt bị biến thành yêu cầu review báo cáo mới. |
| **S04** | Ánh xạ lý do từ chối mặc định & bảo toàn câu người dùng tự nhập | **HOÀN THÀNH** | `actualLegacyPredefinedReasonsMustTranslate`<br>`customReasonWithRecognizedPrefixMustRemainIntact`<br>(PASS) | Trong `RejectionReasonRenderer.kt`: Bỏ toàn bộ các kiểm tra `startsWith` gây nhận diện sai câu tự nhập có phần mở đầu tương tự. Lập bảng đối chiếu chính xác (`trimmed.equals(..., ignoreCase = true)`) bao gồm toàn bộ mẫu resource tiếng Việt và tiếng Anh lịch sử (`oad_reject_reason_1` "Ảnh trùng lặp với ngày hôm trước", `oad_reject_reason_2`, `oad_reject_reason_3`, v.v.). Text không khớp hoàn toàn được giữ nguyên 100%. |
| **S05** | State dynamic resolution hỗ trợ cả thông điệp cố định và parameterized | **HOÀN THÀNH** | `parameterizedSuccessMustChangeLocale`<br>`unlistedFixedErrorMustChangeLocale`<br>`ownerPersistedMessageMustChangeLocale`<br>(PASS) | Trong `UiStateMessageResolver.kt`: Quét và lập chỉ mục toàn bộ các khóa trong `R.string`. Hỗ trợ regex compile cho chuỗi định dạng (ví dụ `%1$d`, `%1$s`) để trích xuất tham số và re-evaluate bằng `Localizer.get(resId, *args)`. Nâng cấp `OwnerAppDetailUiState` và `AppUpdateReleaseUiState` với dynamic getters cho `error`, `message`, `warningMessage`, v.v. |
| **S06** | Hợp đồng cấu hình cập nhật từ xa, backfill và fallback an toàn | **HOÀN THÀNH** | `permissionErrorMustNotRetryUsingLegacyMutation`<br>`legacyFallbackMustNotReportBilingualSaveSuccess`<br>`database-probe.mjs` (backfill & clear null)<br>(PASS) | • Trong `055_localization_hardening.sql`: Dùng regex ký tự tiếng Việt có dấu `~* '[àáảãạ...]'` để phân biệt ngôn ngữ legacy, không gán nhầm câu tiếng Anh vào `title_vi`.<br>• Trong `admin_update_app_config`: Cho phép xóa ô dịch khi payload gửi `null`; khi client legacy cập nhật `title`/`message`, tự động reset các ô dịch cụ thể để không bị che khuất.<br>• Trong `AppConfigRepository.kt`: Không retry khi gặp lỗi quyền (`42501`, `permission denied`, `forbidden`); không fallback âm thầm nếu người dùng đã nhập `titleEn`/`messageEn` mà server legacy không hỗ trợ lưu. |
| **S07** | Giải mã JSON String cho tên ứng dụng trong notification | **HOÀN THÀNH** | `structuredAppNameMustDecodeJsonString`<br>(PASS) | Trong `NotificationRenderer.kt`: Dùng `(notification.data?.get("key") as? JsonPrimitive)?.content` để giải mã JSON String đúng chuẩn thay vì `.toString().trim('"')`, giữ nguyên ký tự escape như dấu ngoặc kép trong `Demo "A"`. |

---

## 3. DANH SÁCH FILE ĐÃ THAY ĐỔI

1. **`supabase/migrations/055_localization_hardening.sql`**:
   - Phục hồi đầy đủ 4 nhánh trigger trong `trg_notify_tester_report()` (báo cáo mới, nộp lại, sửa ảnh/nội dung, phê duyệt/từ chối).
   - Sửa backfill cho `app_configs`: chỉ sao chép sang `title_vi`/`message_vi` khi chuỗi có dấu tiếng Việt; nếu tiếng Anh thì lưu vào `title_en`/`message_en`.
   - Sửa `admin_update_app_config`: cho phép set null để xóa bản dịch; reset các cột song ngữ khi client legacy cập nhật.
2. **`supabase/full_schema_singapore.sql`**:
   - Đồng bộ hoàn toàn phần migration 055 ở cuối file (loại bỏ BOM và đồng bộ 100% với `055_localization_hardening.sql`).
3. **`app/src/main/java/com/ntt/crosstest/data/SettingsRepository.kt`**:
   - Hàm `resolveLocale(setting, systemLocale)` luôn dùng `getSystemLocale()` khi `setting == "system"`.
4. **`app/src/main/java/com/ntt/crosstest/MainActivity.kt`**:
   - Cập nhật `remember(languageSetting, configuration)` để lắng nghe thay đổi locale từ hệ thống mà không lấy locale đã bị bọc của activity context.
5. **`app/src/main/java/com/ntt/crosstest/util/NotificationRenderer.kt`**:
   - Đọc an toàn `data.status == "approved"` và `"rejected"` trước các heuristic legacy.
   - Bổ sung `extractJsonString` giải mã đúng chuẩn các trường JSON String.
   - Dịch title approved `"Báo cáo kiểm thử được phê duyệt! 🎉"` sang tiếng Anh.
6. **`app/src/main/java/com/ntt/crosstest/util/RejectionReasonRenderer.kt`**:
   - Loại bỏ toàn bộ kiểm tra `startsWith` trong `detectLegacyCode`.
   - Lập bảng exact match cho tất cả chuỗi lý do lịch sử tiếng Việt và tiếng Anh.
7. **`app/src/main/java/com/ntt/crosstest/util/UiStateMessageResolver.kt`**:
   - Lập chỉ mục toàn bộ `R.string` để hỗ trợ tất cả lỗi và thông điệp không nằm trong danh sách cố định.
   - Thêm parser và matcher biểu thức định dạng (`%1$d`, `%1$s`, ...) để phân giải chuỗi có tham số động khi đổi ngôn ngữ.
8. **`app/src/main/java/com/ntt/crosstest/ui/viewmodels/OwnerAppDetailViewModel.kt`**:
   - Nâng cấp `OwnerAppDetailUiState` với dynamic resolution cho các trường thông báo `error`, `message`, `warningMessage`, `missionError`, `pushError`.
9. **`app/src/main/java/com/ntt/crosstest/data/AppConfigRepository.kt`**:
   - Phân biệt lỗi phân quyền (`42501`) và lỗi capability (`PGRST202`). Không thử lại mutation khi bị cấm quyền.
   - Không fallback âm thầm khi có dữ liệu tiếng Anh (`titleEn`, `messageEn`) trên server cũ không hỗ trợ song ngữ.

---

## 4. KẾT QUẢ KIỂM THỬ THỰC TẾ

### 4.1. Kiểm thử Cơ sở dữ liệu (PGlite Regression Suite)
```powershell
node audit/localization-recheck-round2-2026-10-29/database-probe.mjs
```
**Kết quả:**
- `before_055`: approve = 1, reject = 1, edit = 1.
- `after_055`: approve = 1, reject = 1, edit = 1 (Bảo toàn 100% thông báo sự kiện báo cáo).
- `legacy_english_backfill`: `title_vi = 'Đã có bản cập nhật mới'`, `title_en = 'Security update required'`.
- `clear_translation_with_ui_null_payload`: `title_vi = null`, `title_en = null`, `message_vi = null`, `message_en = null`.
- `legacy_client_update_after_bilingual_values`: `title = 'Legacy changed'`, các trường song ngữ reset thành null, không che khuất cập nhật mới.
- **Exit Code: 0**

### 4.2. Kiểm thử Android Unit Tests (Toàn bộ 32 Tests)
```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
.\gradlew.bat --init-script audit/localization-recheck-2026-10-29/include-probes.gradle --init-script audit/localization-recheck-round2-2026-10-29/include-probes.gradle :app:testDebugUnitTest --tests 'com.ntt.crosstest.localization.*' --console=plain
```
**Kết quả:**
- `LocalizationHardeningTest`: 12 tests, 0 failures, 0 errors (**PASS**)
- `LocalizationRecheckTest`: 9 tests, 0 failures, 0 errors (**PASS**)
- `LocalizationRound2Test`: 11 tests, 0 failures, 0 errors (**PASS**)
- **Tổng cộng: 32 tests, 32 passed, 0 failed. BUILD SUCCESSFUL.**

### 4.3. Kiểm thử Toàn bộ Dự án & Build APK
- `.\gradlew.bat :app:testDebugUnitTest`: **BUILD SUCCESSFUL** (Tất cả unit test của dự án đều pass).
- `.\gradlew.bat :app:assembleDebug`: **BUILD SUCCESSFUL** (Compile debug APK sạch sẽ, không lỗi).

---

## 5. MIGRATION & DEPLOYMENT

- **Trạng thái:** File migration `055_localization_hardening.sql` và `full_schema_singapore.sql` đã được cập nhật chính xác trong repository.
- **Quy định triển khai:** Tuân thủ hướng dẫn nghiêm ngặt — **KHÔNG tự ý deploy lên Supabase production**. Việc áp dụng migration lên server thật cần được quản trị viên thực hiện trong quy trình release chính thức.

---

## 6. PHẦN CHƯA THỂ XÁC MINH (GIỚI HẠN MÔI TRƯỜNG)

- **UI trên thiết bị thật / Emulator:** Kiểm tra `adb devices` cho thấy hiện không có thiết bị thật hay máy ảo Android nào đang kết nối (`List of devices attached` trống).
- Do đó, phần hiển thị giao diện thực tế (Compose rendering trên màn hình, tương tác cử chỉ người dùng, kiểm tra hiển thị font lớn / chuyển đổi ngôn ngữ trực tiếp trong Settings của OS thật) **chưa được xác minh trực quan trên thiết bị**. Toàn bộ logic hiển thị, ViewModel state và resource formatting đã được xác minh toàn diện qua Robolectric API 34 và Ktor MockEngine.
