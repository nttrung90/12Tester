# BÁO CÁO BÀN GIAO: TÍNH NĂNG THƯỞNG CẬP NHẬT PHIÊN BẢN APP

- **Mã bàn giao**: `APP-UPDATE-20261029-01a18562`
- **Ngày thực hiện**: 2026-10-29
- **Dự án**: `F:\AI\12Tester`
- **Kế hoạch đối chiếu**: `F:\AI\12Tester\KE_HOACH_THUONG_CAP_NHAT_PHIEN_BAN_2026-10-29.md`
- **Tình trạng commit/deploy**: Tuân thủ tuyệt đối quy định an toàn — KHÔNG commit/push git, KHÔNG deploy Supabase/Firebase, KHÔNG tác động dữ liệu production; bảo toàn 100% thay đổi chưa commit từ các lượt làm việc trước.

---

## 1. TỔNG QUAN TÍNH NĂNG & QUY TẮC NGHIỆP VỤ ĐÃ CHỐT

1. **Quỹ giữ trước (Escrow)**:
   - Mỗi phiên bản cập nhật được công bố giữ trước đúng **120 Credit** từ ví của chủ app (log loại `app_update_reserve`, số lượng `-120`).
   - Mức thưởng cố định: **6 Credit / người**, tối đa **20 người** cập nhật thành công (`max_claims = 20`).
   - **Tuyệt đối không trừ lại 6 Credit từ ví chủ app khi chi từ quỹ giữ**: Khoản thưởng +6 Credit cho tester (`app_update_reward`) được trích trực tiếp từ quỹ `remaining_credits` của đợt cập nhật, ví của chủ app không bị trừ thêm lần nào nữa.
2. **Bất biến kế toán xuyên suốt mọi vòng đời**:
   $$\text{reserved\_credits} = \text{remaining\_credits} + (\text{paid\_count} \times 6) + \text{refunded\_credits} = 120$$
3. **Giới hạn nhận thưởng**:
   - 1 lần duy nhất cho mỗi tài khoản tester trên mỗi phiên bản (`release_id`).
   - Tester phải là thành viên campaign trước thời điểm công bố đợt cập nhật (`joined_at <= release.created_at`).
   - Chủ app không thể tự nhận thưởng bản cập nhật của chính app mình (`owner_cannot_claim`).
4. **Vòng đời & Trạng thái**:
   - Khi đủ 20 người nhận thưởng: đợt chuyển sang trạng thái `exhausted`. Khối cập nhật phiên bản mới phía tester tự động ẩn hoàn toàn (thông qua `get_campaign_detail`).
   - Khi chủ app công bố phiên bản tiếp theo: mở một đợt mới. Nếu đợt cũ còn tiền chưa dùng, hệ thống hoàn trả số dư còn lại (`refunded_credits = remaining_credits`) về ví chủ (`app_update_refund`) và giữ 120 Credit cho đợt mới trong cùng một transaction nguyên tử. Nếu `ví khả dụng + hoàn trả < 120`, transaction tự động rollback toàn bộ.
   - Chủ app có thể chủ động đóng đợt cập nhật (`close_app_update_release`): hoàn trả ngay số Credit chưa dùng về ví chủ.
   - Khi campaign hoàn thành (`status = 'closed'` hoặc `'production'`) hoặc campaign bị xóa: trigger DB tự động tất toán và hoàn trả phần Credit chưa dùng về ví chủ app.
5. **Nêu đúng giới hạn xác minh client**:
   - Việc xác minh phiên bản đã cài trên máy tester được thực hiện an toàn trên client thông qua `PackageManagerCompat` / `PackageManager.getPackageInfo`.
   - **Hệ thống không tuyên bố và không giả định rằng server hay Google Play đã chứng thực bản cài app bên ngoài**. Giới hạn này được hiển thị rõ ràng trên UI và ghi nhận chi tiết trong hợp đồng kỹ thuật.

---

## 2. CHI TIẾT CÁC THAY ĐỔI ĐÃ TRIỂN KHAI

### 2.1. Backend Supabase & Cơ sở dữ liệu

- **File migration mới**: `supabase/migrations/050_app_update_reward.sql` (Lấy số thứ tự tiếp theo thực tế vì 048 và 049 đã được sử dụng).
- **Đồng bộ toàn diện**: `supabase/full_schema_singapore.sql` (bổ sung toàn bộ bảng, RLS, hàm RPC, trigger của migration 050).
- **Các bảng cơ sở dữ liệu mới**:
  1. `public.app_update_releases`: Quản lý từng đợt cập nhật (`version_name`, `target_version_code`, `release_notes`, `reserved_credits`, `reward_per_claim`, `max_claims`, `paid_count`, `remaining_credits`, `refunded_credits`, `status`: `active`, `exhausted`, `superseded`, `closed`).
  2. `public.app_update_controls`: Đảm bảo tối đa 1 release active cho mỗi campaign (`status in ('active', 'exhausted')`), lưu watermark `highest_version_code` chống công bố lùi bản dựng.
  3. `public.app_update_claims`: Lưu trữ receipt nhận thưởng của tester kèm `installed_version_code`, `installed_version_name`, ràng buộc `UNIQUE(release_id, user_id)`.
  4. `public.app_update_operations`: Chống lặp và chống replay idempotent dựa trên `client_operation_id`.
  5. `public.app_update_financial_events`: Lưu vết kiểm toán tài chính cho mọi hoạt động giữ quỹ, trả thưởng, hoàn quỹ.
- **Bảo mật RLS**: Bật Row Level Security trên cả 5 bảng mới; tester/chủ app chỉ được SELECT dữ liệu thuộc phạm vi cho phép; nghiêm cấm INSERT/UPDATE/DELETE trực tiếp từ client.
- **Các RPC Functions**:
  1. `publish_app_update(p_campaign_id, p_version_name, p_version_code, p_release_notes, p_client_operation_id)`: Xác thực quyền chủ app, kiểm tra watermark bản dựng, hoàn đợt cũ nếu có, trừ 120 Credit ví chủ, khởi tạo đợt mới. Idempotent qua operation ID.
  2. `claim_app_update_reward(p_release_id, p_installed_version_code, p_installed_version_name, p_client_operation_id)`: Xác thực tester, kiểm tra điều kiện bản cài, khóa dòng release (`FOR UPDATE`), kiểm tra suất (< 20), cộng 6 Credit cho tester từ quỹ giữ (KHÔNG trừ ví chủ), đánh dấu `exhausted` khi đủ 20. Idempotent qua operation ID.
  3. `close_app_update_release(p_release_id)`: Chủ app chủ động kết thúc đợt và nhận lại số Credit chưa dùng.
  4. `get_campaign_update_history(p_campaign_id)`: Trả lịch sử toàn bộ các đợt phát hành cho chủ app theo dõi.
  5. `_settle_campaign_app_update(p_campaign_id)`: Helper nội bộ hoàn trả quỹ cho các đợt active khi campaign đóng/xóa.
- **Triggers**:
  - `trg_campaigns_app_update_settle_update`: Tự động hoàn quỹ khi status của campaign chuyển sang `closed` hoặc `production`.
  - `trg_campaigns_app_update_settle_delete`: Tự động hoàn quỹ trước khi campaign bị xóa khỏi hệ thống.
- **Cập nhật `get_campaign_detail`**:
  - Trả về object `app_update` chứa thông tin đợt phát hành mới nhất.
  - Đối với tester: chỉ hiển thị khi release đang `active` và `paid_count < max_claims` (còn suất); khi release chuyển sang `exhausted` hoặc `closed`/`superseded`, `app_update` được ẩn (`null`) đối với tester.
  - Đối với chủ app: luôn trả về đầy đủ để theo dõi trạng thái và tiến độ.

---

### 2.2. Ứng dụng Android (Client)

1. **Bộ đọc phiên bản cài đặt**:
   - `app/src/main/java/com/ntt/crosstest/util/InstalledAppVersionReader.kt`: Đọc an toàn thông qua `PackageInfoCompat.getLongVersionCode` trên `Dispatchers.IO`, hỗ trợ đầy đủ từ Android API 26 đến API 34+, trả kết quả theo Sealed Class: `Installed`, `NotInstalled`, `Unverifiable`.
2. **Data Models & Network Contract**:
   - `app/src/main/java/com/ntt/crosstest/data/Models.kt`: Bổ sung `AppUpdateRelease`, `AppUpdateClaimReceipt`, `PublishAppUpdateParams`, `ClaimAppUpdateParams`, `CloseAppUpdateReleaseParams`, và trường `app_update` vào `CampaignDetailResult`.
3. **Repository**:
   - `app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt`: Bổ sung các phương thức `publishAppUpdate`, `claimAppUpdateReward`, `closeAppUpdateRelease`, `getCampaignUpdateHistory`.
4. **Màn hình & ViewModel cho Chủ app (Owner)**:
   - `app/src/main/java/com/ntt/crosstest/ui/screens/OwnerAppDetailScreen.kt`: Bổ sung mục thao tác `OwnerActionItem` **“Đã cập nhật phiên bản mới”**, phụ đề **“Trả cho mỗi người cập nhật 6 Credit”**, đặt ngay dưới mục “Người đang cài”, hiển thị badge tiến độ dạng `x/20` khi có đợt active.
   - `app/src/main/java/com/ntt/crosstest/ui/screens/AppUpdateReleaseScreen.kt`: Màn hình riêng biệt để chủ app:
     - Nhập số phiên bản (`versionName`, vd: `1.2.0`), mã bản dựng (`versionCode`, vd: `15`), tính năng mới (`releaseNotes`).
     - Hiển thị bảng tính toán ngân sách trực quan: Số dư khả dụng hiện tại, Số dư hoàn trả từ bản cũ (nếu có), Tổng ngân sách hiệu dụng, Chi phí giữ trước (120 Credit), Dự kiến còn lại sau áp dụng.
     - Kiểm tra điều kiện tiên quyết (mã bản dựng phải lớn hơn bản trước, đủ ngân sách 120 Credit).
     - Nút **“Áp dụng”** thực hiện công bố và trừ quỹ giữ.
     - Cho phép chủ app chủ động **“Kết thúc sớm đợt cập nhật”** để hoàn Credit chưa dùng về ví.
     - Hiển thị danh sách lịch sử các đợt phát hành trước đó.
   - `app/src/main/java/com/ntt/crosstest/ui/viewmodels/AppUpdateReleaseViewModel.kt`: Quản lý state, tính toán ngân sách, gọi API publish/close.
   - `app/src/main/java/com/ntt/crosstest/ui/navigation/NavGraph.kt`: Đăng ký route `Routes.APP_UPDATE_RELEASE`.
5. **Màn hình & ViewModel cho Người kiểm thử (Tester)**:
   - `app/src/main/java/com/ntt/crosstest/ui/screens/CampaignDetailScreen.kt`:
     - Khối cập nhật phiên bản mới đặt **ngay phía trên nút “Mở app kiểm thử”**.
     - Thể hiện rõ tên phiên bản yêu cầu, tính năng mới do chủ app nhập, và nhãn **“+6 Credit”**.
     - Nút bấm **“Cập nhật trên Play Store”**: mở đúng listing Play Store của ứng dụng (`market://details?id=...` kèm fallback URL web).
     - Khi tester quay lại màn hình (`Lifecycle.Event.ON_RESUME`): tự động đọc lại phiên bản app qua `InstalledAppVersionReader`.
     - Nếu đã đạt phiên bản yêu cầu: tự động đổi giao diện thành **“Đã cập nhật”** kèm biểu tượng tích xanh và nút nhận thưởng **“Nhận +6 Credit”**.
     - Nhận thưởng thành công hiển thị thông báo chúc mừng và cập nhật số dư Credit tức thì.
   - `app/src/main/java/com/ntt/crosstest/ui/viewmodels/CampaignDetailViewModel.kt`: Bổ sung state `appUpdate`, `installedVersionCode`, `isCheckingInstalledVersion`, `isClaimingUpdateReward`, `isAppUpdated`, `showAppUpdateBlock`, cùng hàm `checkInstalledAppVersion` và `claimAppUpdateReward`.
6. **Đa ngôn ngữ (Localization)**:
   - Cập nhật đầy đủ chuỗi ký tự trong `strings.xml`, `values-vi/strings.xml`, `values-en/strings.xml`.

---

## 3. KẾT QUẢ KIỂM THỬ THỰC TẾ

### 3.1. Kiểm thử Backend Node.js / PostgreSQL (PGlite)

- **Lệnh thực thi**: `npm --prefix supabase/tests test`
- **Kết quả tổng quát**: **8/8 BỘ TEST PASS 100%**, mã thoát `0`.
- **Chi tiết bộ test migration 050 (`run-app-update-reward-test.mjs`)**:
  - `PASS 1`: Nạp thành công full schema và migration 045 -> 050 (xác minh idempotent).
  - `PASS 2`: Chặn công bố khi ví chủ < 120 Credit (`INSUFFICIENT_CREDITS`).
  - `PASS 3`: Chặn non-owner gọi publish_app_update (`NOT_ACTUAL_OWNER`).
  - `PASS 4`: Công bố v1.0.0 thành công, trừ 120 Credit ví chủ, khởi tạo quỹ 120.
  - `PASS 5`: Idempotency publish: Replay cùng operation_id không trừ tiền lần hai.
  - `PASS 6`: Chặn công bố versionCode không lớn hơn watermark (`VERSION_CODE_NOT_GREATER`).
  - `PASS 7`: Chủ app bị chặn nhận thưởng của chính app mình (`OWNER_CANNOT_CLAIM`).
  - `PASS 8`: Tester cài bản cũ (< targetVersionCode) bị từ chối (`VERSION_NOT_SATISFIED`).
  - `PASS 9`: Tester 1 nhận +6 Credit thành công; quỹ giữ giảm còn 114, ví chủ KHÔNG trừ lại.
  - `PASS 10`: Tester 1 gọi lại claim: Idempotent trả receipt cũ, không nhân đôi thưởng.
  - `PASS 11`: Tester tham gia sau thời điểm công bố bị từ chối (`MEMBER_JOINED_AFTER_RELEASE`).
  - `PASS 12`: Đủ 20 người nhận thưởng: `paid_count = 20`, `remaining = 0`, `status = 'exhausted'`.
  - `PASS 13`: Người thứ 21 claim bị từ chối (`RELEASE_EXHAUSTED`).
  - `PASS 14`: `get_campaign_detail` ẩn khối cập nhật cho tester khi đủ 20 suất, chủ vẫn thấy.
  - `PASS 15`: Thay đợt nguyên tử: Hoàn 78 Credit đợt cũ và giữ 120 đợt mới trong cùng transaction.
  - `PASS 16`: Claim vào release đã superseded bị từ chối (`RELEASE_REPLACED`).
  - `PASS 17`: Chủ kết thúc sớm đợt, hoàn trả toàn bộ 120 Credit chưa dùng (`close_app_update_release`).
  - `PASS 18`: Trigger hoàn tiền an toàn khi campaign completed hoặc deleted.
  - `PASS 19`: `get_campaign_update_history` trả đầy đủ lịch sử các đợt cập nhật cho chủ app.
  - `PASS 20`: Bất biến kế toán: `reserved_total (120) = remaining + (paid_count * 6) + refunded` khớp 100% trên mọi release.

### 3.2. Kiểm thử đơn vị Android (Unit Tests)

- **Lệnh thực thi**: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest`
- **Kết quả**: `BUILD SUCCESSFUL in 15s`, mã thoát `0`.
- **Chi tiết bộ test `AppUpdateRewardTest.kt`** (10 test cases, 0 failure, 0 error):
  1. `reader_returns_not_installed_when_package_missing`: PASS
  2. `reader_returns_unverifiable_on_blank_package`: PASS
  3. `reader_returns_installed_version_code_and_name`: PASS
  4. `owner_ui_state_calculates_budget_correctly_without_old_release`: PASS
  5. `owner_ui_state_calculates_budget_correctly_with_refund_from_old_release`: PASS
  6. `owner_can_publish_requires_version_code_greater_than_current`: PASS
  7. `owner_can_publish_validates_blank_fields_and_lengths`: PASS
  8. `tester_is_app_updated_checks_greater_or_equal`: PASS
  9. `tester_show_app_update_block_rules`: PASS (bao gồm quy tắc ẩn khi đủ 20 người nhận thưởng)
  10. `accounting_invariant_holds_across_all_lifecycle_states`: PASS (bất biến 120 = remaining + 6*paid + refunded)

### 3.3. Đóng gói bản dựng Android (Assemble Debug)

- **Lệnh thực thi**: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat assembleDebug`
- **Kết quả**: `BUILD SUCCESSFUL in 12s`, mã thoát `0`.

---

## 4. BẢNG TỔNG HỢP DANH MỤC TỆP THAY ĐỔI & TẠO MỚI

| Phân hệ | Loại thao tác | Đường dẫn tệp |
|---|---|---|
| **Database** | Tạo mới | `supabase/migrations/050_app_update_reward.sql` |
| **Database** | Chỉnh sửa | `supabase/full_schema_singapore.sql` |
| **Backend Test** | Tạo mới | `supabase/tests/run-app-update-reward-test.mjs` |
| **Backend Test** | Chỉnh sửa | `supabase/tests/package.json` |
| **Android Data** | Chỉnh sửa | `app/src/main/java/com/ntt/crosstest/data/Models.kt` |
| **Android Data** | Chỉnh sửa | `app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt` |
| **Android Util** | Tạo mới | `app/src/main/java/com/ntt/crosstest/util/InstalledAppVersionReader.kt` |
| **Android UI** | Tạo mới | `app/src/main/java/com/ntt/crosstest/ui/screens/AppUpdateReleaseScreen.kt` |
| **Android UI** | Tạo mới | `app/src/main/java/com/ntt/crosstest/ui/viewmodels/AppUpdateReleaseViewModel.kt` |
| **Android UI** | Chỉnh sửa | `app/src/main/java/com/ntt/crosstest/ui/screens/OwnerAppDetailScreen.kt` |
| **Android UI** | Chỉnh sửa | `app/src/main/java/com/ntt/crosstest/ui/viewmodels/OwnerAppDetailViewModel.kt` |
| **Android UI** | Chỉnh sửa | `app/src/main/java/com/ntt/crosstest/ui/screens/CampaignDetailScreen.kt` |
| **Android UI** | Chỉnh sửa | `app/src/main/java/com/ntt/crosstest/ui/viewmodels/CampaignDetailViewModel.kt` |
| **Android Nav** | Chỉnh sửa | `app/src/main/java/com/ntt/crosstest/ui/navigation/NavGraph.kt` |
| **Android Res** | Chỉnh sửa | `app/src/main/res/values/strings.xml` |
| **Android Res** | Chỉnh sửa | `app/src/main/res/values-vi/strings.xml` |
| **Android Res** | Chỉnh sửa | `app/src/main/res/values-en/strings.xml` |
| **Android Test** | Tạo mới | `app/src/test/java/com/ntt/crosstest/data/AppUpdateRewardTest.kt` |
| **Báo cáo** | Tạo mới | `BAO_CAO_BAN_GIAO_THUONG_CAP_NHAT_PHIEN_BAN_2026-10-29.md` |

---

## 5. HẠN CHẾ & CÁC ĐIỂM CHƯA KIỂM CHỨNG TRỰC TIẾP

1. **Kiểm tra tranh suất bằng nhiều connection PostgreSQL vật lý**:
   - Môi trường phát triển cục bộ hiện tại không có dịch vụ PostgreSQL độc lập đang chạy ở nền (background daemon). Toàn bộ logic giao dịch nguyên tử, khóa hàng `FOR UPDATE`, giới hạn 20 suất và tính toán tài chính đã được kiểm thử đầy đủ thông qua PGlite in-memory trong Node.js.
   - Chưa kiểm chứng trực tiếp bằng kịch bản stress-test 100 connection đồng thời trên cụm PostgreSQL vật lý đa luồng.
2. **Kiểm thử trên thiết bị thực tế kèm Google Play Store**:
   - Quá trình chuyển hướng sang Google Play Store (`market://details?id=...`) và đọc bản cài `PackageManager` đã được kiểm thử qua Robolectric unit test và biên dịch thành công APK.
   - Chưa tiến hành cài đặt ứng dụng thực tế trên thiết bị vật lý kết nối với Google Play Console đang có bản dựng cập nhật thật để trải nghiệm luồng end-to-end trên store.
3. **Môi trường Production**:
   - Tuyệt đối chưa deploy lên Supabase Production, chưa apply migration lên database thật, và chưa commit/push lên Git remote theo đúng yêu cầu đề bài.
