# Báo Cáo Bàn Giao: Sửa Bộ Đếm Báo Cáo Của Tester (Owner Report Count) — 2026-11-01

## 1. Tóm tắt vấn đề và nguyên nhân gốc rễ (Root Cause)
- **Vấn đề**: Trên màn hình chủ app (`OwnerAppDetailScreen`), số lượng báo cáo chờ duyệt (`pendingReportsCount`) hiển thị sai (thường bằng 0), và tổng số báo cáo hiển thị theo `reports_count` (vốn là số lượng đánh giá/feedback của tester chứ không phải số lượng báo cáo lỗi/ảnh chụp tester).
- **Nguyên nhân gốc rễ**:
  1. Trong baseline `062_consolidated_baseline.sql`, hàm RPC `get_campaign_detail` không trả về `pending_reports_count`.
  2. DTO `CampaignDetailResult` trong Android client mặc định `pendingReportsCount = 0` khi thiếu trường trong JSON.
  3. `OwnerAppDetailViewModel.applyDetail` gán `totalReportsCount = detail.reportsCount`, trong khi `reportsCount` trong database đếm từ bảng `feedbacks` và được màn hình tester sử dụng cho UI đánh giá sao.
  4. Mảng `reports` trả về trong RPC bị giới hạn `limit 200` và chỉ hiển thị báo cáo của chính tester khi người gọi là tester thường, không thể dùng độ dài mảng để làm bộ đếm toàn thể.

---

## 2. Các thay đổi đã thực hiện (Changed Files)

### A. Database Migration
- **File mới**: `supabase/migrations/068_owner_tester_reports_count.sql`
  - Thay thế `public.get_campaign_detail(p_campaign_id uuid)` với logic bổ sung tối thiểu, bảo toàn toàn bộ schema, quyền truy cập (`authenticated`, `service_role`), và các trường hiện có.
  - Tính toán hai trường mới sau khi đã kích hoạt `public.auto_accept_expired_reports()`:
    ```sql
    select count(*),
           count(*) filter (where status = 'pending')
    into v_tester_reports_count, v_pending_reports_count
    from public.tester_reports
    where campaign_id = p_campaign_id
      and (v_can_manage or user_id = v_uid);
    ```
  - Trả về `pending_reports_count` và `tester_reports_count` trong JSON kết quả.
  - Đếm đầy đủ vượt qua giới hạn `limit 200` của mảng `reports`.
  - Giữ nguyên `reports_count` (đếm `feedbacks`) và `avg_rating` cho UI đánh giá.
  - Bảo đảm phân quyền: Owner, Admin, Moderator xem toàn bộ báo cáo của campaign; Tester thường chỉ thấy và đếm báo cáo của chính mình (không rò rỉ dữ liệu tester khác).

### B. Android Model & DTO
- **File**: `app/src/main/java/com/ntt/crosstest/data/Models.kt`
  - Cập nhật `CampaignDetailResult`:
    ```kotlin
    @SerialName("reports_count") val reportsCount: Int = 0,
    /** Giá trị server aggregate là tối hậu; khi backend legacy thiếu khóa, suy ra từ mảng reports (tối đa 200). */
    @SerialName("pending_reports_count") val pendingReportsCount: Int = reports.count { it.status.equals("pending", ignoreCase = true) },
    /** Giá trị server aggregate là tối hậu; khi backend legacy thiếu khóa, suy ra từ reports.size (tối đa 200). */
    @SerialName("tester_reports_count") val testerReportsCount: Int = reports.size,
    @SerialName("avg_rating") val avgRating: Float? = null,
    ```
  - Tính tương thích ngược 2 chiều:
    - Khi backend cũ thiếu trường, fallback tự suy ra từ mảng `reports` trả về thay vì mặc định 0.
    - Khi backend mới trả về giá trị (kể cả số 0), giá trị từ server luôn có tính tối hậu (authoritative).
    - Bảo toàn tương thích chữ ký hàm và constructor theo tên.

### C. Android ViewModel
- **File**: `app/src/main/java/com/ntt/crosstest/ui/viewmodels/OwnerAppDetailViewModel.kt`
  - Sửa `applyDetail`:
    ```kotlin
    val stats = CampaignOwnerStats(
        installedTestersCount = detail.testers.size,
        testedTodayCount = if (isSameDay) detail.todayTestedCount else 0,
        totalReportsCount = detail.testerReportsCount, // Trước đây là detail.reportsCount
        pendingReportsCount = detail.pendingReportsCount,
        averageRating = detail.avgRating,
    )
    ```

### D. Kiểm thử & Regression Suites
- **File**: `supabase/tests/run-owner-tester-reports-count-test.mjs`
  - Runner kiểm thử PGlite độc lập, tự động nạp tất cả migrations hiện hành từ 062 đến 068.
  - Tái hiện lỗi thiếu trường trước khi áp dụng migration 068.
  - Kiểm thử đầy đủ 9 trường hợp: fresh zero, owner scope, admin/moderator scope, non-owner isolation, cross-campaign isolation, all approved/rejected, auto-accept quá 8h, feedback separation, và đếm vượt ngưỡng 200 reports (>200 pending).
- **File**: `app/src/test/java/com/ntt/crosstest/data/OwnerReportCountBehavioralTest.kt`
  - Bộ kiểm thử Robolectric & MockEngine 9 kịch bản:
    1. Legacy JSON fallback đếm pending và total từ mảng reports.
    2. Legacy JSON với reports rỗng về 0.
    3. Server aggregate 0 rõ ràng thắng mảng chứa pending reports.
    4. Server aggregate > 200 reports độc lập với mảng giới hạn 200.
    5. ViewModel fast-path nhận đúng `testerReportsCount` và `pendingReportsCount`.
    6. ViewModel fast-path fallback an toàn với backend cũ.
    7. ViewModel authoritative counts vượt giới hạn 200.
    8. Luồng duyệt báo cáo (`approveReport`) cập nhật optimistic và kích hoạt refresh từ server, xác nhận state server đến nơi.
    9. Luồng từ chối báo cáo (`rejectReport`) giữ vững số pending 0 và tester total không bị thay bằng feedback count.

---

## 3. Kết quả kiểm thử thực tế (Exact Test Results)

### 1. Backend PGlite Test (`run-owner-tester-reports-count-test.mjs`)
- Lệnh: `node run-owner-tester-reports-count-test.mjs` (trong `supabase/tests`)
- Kết quả: **PASS 100% (9/9 checks)**
  - `PASS: Successfully reproduced before-fix state (pending_reports_count and tester_reports_count missing)`
  - `Applying 068_owner_tester_reports_count.sql...`
  - `PASS: Fresh campaign with no reports returns 0 for all report counts`
  - `PASS: Owner sees total and pending reports across all testers`
  - `PASS: Admin and Moderator see campaign totals matching owner`
  - `PASS: Regular tester only sees their own report totals and reports list`
  - `PASS: Reports on Camp B do not leak into Camp A counts`
  - `PASS: When all reports are approved or rejected, pending_reports_count is 0`
  - `PASS: Pending reports older than 8 hours are auto-accepted and not counted as pending`
  - `PASS: Feedbacks update reports_count and avg_rating without affecting tester_reports_count`
  - `PASS: Authoritative count counts >200 reports accurately while reports array caps at 200`
  - `ALL OWNER TESTER REPORT COUNT DATABASE TESTS PASSED`

### 2. Android Behavioral Tests (`OwnerReportCountBehavioralTest`)
- Lệnh: `.\gradlew.bat --no-daemon --offline --console=plain :app:testDebugUnitTest --tests "com.ntt.crosstest.data.OwnerReportCountBehavioralTest"`
- Kết quả: **BUILD SUCCESSFUL (9/9 tests passed, 0 failures, 0 errors)**
  - `legacyJsonWithoutCountKeysDerivesCountsFromReportsArray`: PASSED
  - `legacyJsonWithEmptyReportsDefaultsToZero`: PASSED
  - `viewModelFastPathLegacyFallbackDerivesFromReportsList`: PASSED
  - `newJsonWithExplicitZeroCountsStaysAuthoritativeZero`: PASSED
  - `viewModelAuthoritativeCountsBeyond200ArrayLimit`: PASSED
  - `newJsonAuthoritativeBeyond200ArrayLimit`: PASSED
  - `reportReviewTriggersRefreshAndOptimisticUpdate`: PASSED
  - `reportRejectTriggersRefreshAndPreservesZeroPendingAndTesterTotal`: PASSED
  - `viewModelFastPathUsesNewTesterReportsCountAndPendingReportsCount`: PASSED

### 3. Existing Cache Regressions (`CacheRegressionTest` & `IndependentCachePublicationAuditTest`)
- Lệnh: `.\gradlew.bat --no-daemon --offline --console=plain :app:testDebugUnitTest --tests "com.ntt.crosstest.data.CacheRegressionTest" --tests "com.ntt.crosstest.data.IndependentCachePublicationAuditTest"`
- Kết quả: **BUILD SUCCESSFUL**
  - `CacheRegressionTest`: 36 tests passed, 0 failures, 0 errors.
  - `IndependentCachePublicationAuditTest`: 5 tests passed, 0 failures, 0 errors.

### 4. Build & Compilation Verification
- Lệnh: `.\gradlew.bat --no-daemon --offline --console=plain :app:assembleDebug`
- Kết quả: **BUILD SUCCESSFUL in 22s** (0 compile errors).

---

## 4. Giới hạn và lưu ý kiểm chứng (Limitations)

1. **Giới hạn mảng Legacy (200-cap)**:
   - Khi client mới kết nối với server backend chưa triển khai migration 068, client sẽ tự suy ra số lượng từ mảng `reports`. Do RPC trên backend cũ áp đặt `limit 200`, nếu campaign có hơn 200 báo cáo thì bộ đếm fallback tối đa chỉ là 200. Khi backend đã áp dụng migration 068, server aggregate sẽ trả về số lượng chính xác tuyệt đối vượt qua 200.
2. **Auto-accept 8 giờ**:
   - Các báo cáo ở trạng thái `pending` quá 8 giờ sẽ tự động được chấp nhận (`approved`) khi `get_campaign_detail` được gọi hoặc qua pg_cron (`auto_accept_expired_reports`). Khi đó chúng không còn được tính vào `pending_reports_count`.
3. **Môi trường thực thi**:
   - Tất cả kiểm thử backend được thực thi trên môi trường PGlite cục bộ độc lập và mock engines Android. Chưa có bất kỳ thao tác nào áp dụng migration trực tiếp lên Supabase staging/production hay đọc secrets môi trường.

---

## 5. Hướng dẫn triển khai Production (Production Rollout Instructions)

1. **Database Staging / Production**:
   - Đảm bảo migration `067` đã được áp dụng.
   - Chạy migration `068_owner_tester_reports_count.sql` bằng công cụ Supabase CLI hoặc Query Editor của dự án.
   - Kiểm tra RPC sau khi deploy:
     ```sql
     select public.get_campaign_detail('<campaign_id_here>');
     ```
     Xác nhận trong kết quả JSON có xuất hiện hai khóa `pending_reports_count` và `tester_reports_count`.
2. **Android Client Release**:
   - Build bản phát hành mới (app bundle hoặc APK) chứa DTO và ViewModel đã cập nhật.
   - Do client có cơ chế fallback tự suy ra số lượng từ mảng `reports`, bản Android mới có thể phát hành trước hoặc sau migration backend mà không gây crash hoặc gián đoạn dịch vụ.
