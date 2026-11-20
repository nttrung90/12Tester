# BÁO CÁO BÀN GIAO: SỬA LỖI TÍNH NĂNG "ĐẨY APP" (RPC PUSH_CAMPAIGN)

**Ngày thực hiện:** 29/10/2026  
**Dự án:** 12Tester (`F:\AI\12Tester`)  
**Căn cứ kế hoạch:** `KE_HOACH_SUA_LOI_DAY_APP_RPC_2026-10-29.md`  
**Trạng thái:** Hoàn thành triển khai code, migration 056, bộ test hồi quy SQL và unit test/build Android. Giữ nguyên các thay đổi chưa commit của các nhiệm vụ khác; không tự ý commit, không chạy migration production, không phát hành APK.

---

## 1. Nguyên nhân gốc đã xác nhận

1. **R01 — Trigger nhầm role của request với quyền thực thi RPC (`P0001` / `PUSH_METADATA_READ_ONLY`):**
   - Trong `supabase/migrations/054_push_and_carryover_hardening.sql`, trigger `trg_fn_protect_campaign_push_metadata` kiểm tra:
     ```sql
     v_active_role := coalesce(nullif(current_setting('role', true), ''), current_user);
     if v_active_role in ('authenticated', 'anon', 'public') ...
     ```
   - Khi request đến qua PostgREST với JWT của người dùng, PostgREST thiết lập GUC `role = 'authenticated'`.
   - RPC `push_campaign` là hàm `SECURITY DEFINER` (chạy với quyền của owner `postgres`), nhưng setting `role` vẫn giữ giá trị `'authenticated'`. Trigger `SECURITY INVOKER` khi chạy đã đọc `current_setting('role')`, từ đó chặn nhầm toàn bộ giao dịch mua hợp lệ của người dùng qua RPC dù cờ `app.allow_push_metadata_write` đã được bật.
   - Kết quả: Lỗi `PUSH_METADATA_READ_ONLY` / `P0001`, giao dịch rollback toàn bộ (không trừ tiền, không ghi nhận ưu tiên).

2. **R02 — Thiếu ca kiểm thử authenticated gọi RPC hợp lệ:**
   - Bộ test cũ chỉ kiểm tra gọi RPC bằng tài khoản quản trị local (`postgres`) và chỉ đổi role sang `authenticated` khi kiểm tra ghi metadata trực tiếp thất bại, bỏ sót luồng gọi RPC hợp lệ dưới role `authenticated`.

3. **R03 — Rủi ro hiển thị/log thông tin kỹ thuật thô:**
   - Dù mã nguồn hiện tại đã có cơ chế fallback generic trong `friendlyError`, việc log exception trực tiếp ở `AuthViewModel.kt` có nguy cơ ghi lại URL, Header Authorization hoặc Bearer Token vào logcat.
   - Chưa map mã lỗi `PUSH_METADATA_READ_ONLY` thành thông báo thân thiện riêng cho tính năng Đẩy app.

4. **R04 — Điều kiện nút trên giao diện chặn xác minh giao dịch pending khi số dư dưới 100 Credit:**
   - ViewModel `OwnerAppDetailViewModel` đã hỗ trợ replay idempotent cho request pending bỏ qua số dư, nhưng tại `OwnerAppDetailScreen.kt:807`, nút bấm trong dialog có điều kiện `enabled = !uiState.isPushLoading && isSufficient` (`isSufficient = ownerCredits >= 100`). Khi số dư dưới 100, nút bấm bị vô hiệu hóa khiến người dùng không thể xác minh giao dịch cũ bị timeout.
   - Đồng thời, nếu request trước đó bị server từ chối chắc chắn (business/trigger rejection, rollback hoàn toàn), mã cũ không dọn dẹp `pendingRequestId`, dẫn đến nguy cơ hiểu nhầm thành giao dịch pending cần xác minh và có thể tự động mua lại khi backend được sửa.

---

## 2. Giải pháp kỹ thuật đã triển khai

### 2.1. Backend & Database Migration (`056_push_campaign_role_hardening.sql`)
- **Số migration:** `056` (tiếp nối migration `055_localization_hardening.sql` còn trống).
- **Sửa trigger bảo vệ metadata (`trg_fn_protect_campaign_push_metadata`):**
  - Giữ nguyên trigger là `SECURITY INVOKER`.
  - Phân biệt bằng quyền thực thi hiệu lực **`current_user`** thay vì `current_setting('role')`:
    - Nếu thao tác gọi trực tiếp từ client qua PostgREST (bảng `campaigns`): `current_user in ('authenticated', 'anon', 'public')` -> **CHẶN NGAY LẬP TỨC** với lỗi `PUSH_METADATA_READ_ONLY`.
    - Nếu client tự đặt GUC `set_config('app.allow_push_metadata_write', 'true', true)` rồi ghi trực tiếp: `current_user` vẫn là `'authenticated'` -> **VẪN BỊ CHẶN NGAY**.
    - Nếu gọi qua RPC `push_campaign` (`SECURITY DEFINER` do `postgres` sở hữu): `current_user` chuyển thành `'postgres'` (không thuộc danh sách cấm) và cờ `app.allow_push_metadata_write` được bật trong transaction-local -> **HỢP LỆ VÀ THÀNH CÔNG**.
- **Đảm bảo tính nguyên tử & an toàn:**
  - Giữ nguyên toàn bộ logic kiểm tra: `auth.uid()`, khóa `campaigns` và `profiles` FOR UPDATE, khóa advisory `campaign_push_order_lock`, kiểm tra chủ app, trạng thái tài khoản không khóa, chiến dịch mở, thời hạn 30 ngày, thời hạn slot.
  - Trừ đúng 100 Credit, ghi log biến động số dư, cập nhật metadata campaign và ghi receipt trong cùng một transaction PostgreSQL duy nhất. Bất kỳ lỗi nào xảy ra đều rollback 100%.
  - Cập nhật và đồng bộ toàn diện vào `supabase/full_schema_singapore.sql`.

### 2.2. Android Frontend & Giao diện
- **Sửa điều kiện nút bấm xác nhận (`OwnerAppDetailScreen.kt`):**
  - Bổ sung kiểm tra `hasPendingPush`:
    ```kotlin
    val hasPendingPush = remember(campId, uiState.ownerProfile?.id, uiState.isPushLoading) {
        val uid = uiState.ownerProfile?.id
        if (uid != null && campId != null) {
            CampaignPushStore.peekPendingRequestId(context, uid, campId) != null
        } else false
    }
    ```
  - Nút bấm dialog có điều kiện: `enabled = !uiState.isPushLoading && (isSufficient || hasPendingPush)`.
  - Khi có giao dịch pending cần xác minh: nút bấm luôn mở kể cả khi số dư < 100 Credit; nhãn nút chuyển thành "Kiểm tra lại" / "Verify Again"; tiêu đề dialog chuyển thành "Xác minh yêu cầu đẩy app".
  - Khi mua mới: bắt buộc số dư >= 100 Credit (`isSufficient`). Nếu không đủ 100 Credit, nút bị khóa và hiển thị cảnh báo thiếu tiền.
- **Dọn dẹp request khi server từ chối chắc chắn (`OwnerAppDetailViewModel.kt`):**
  - Thêm phương thức nhận diện lỗi từ chối dứt điểm từ server `isServerRejection(e)` (`PUSH_METADATA_READ_ONLY`, `INSUFFICIENT_CREDITS`, `CAMPAIGN_NOT_OPEN`, `NOT_OWNER`, `ACCOUNT_LOCKED`, v.v.).
  - Khi server đã từ chối chắc chắn (đã rollback dứt điểm trên server), gọi ngay `CampaignPushStore.clearPendingRequestId(...)` để dọn dẹp request ID; tuyệt đối không tự động mua lại khi backend được sửa.
  - Chỉ giữ lại `pendingRequestId` khi gặp lỗi timeout / mạng chập chờn (chưa rõ kết quả commit trên server).
- **Bảo mật log và an toàn chuỗi thông báo:**
  - Thêm hàm `sanitizeErrorMessage(raw)` loại bỏ triệt để Bearer tokens, Authorization headers, apikeys, passwords và URL trước khi log.
  - Map mã lỗi `PUSH_METADATA_READ_ONLY` thành thông báo an toàn, bản địa hóa: *"Hiện chưa thể đẩy app. Vui lòng thử lại sau. (Chưa trừ Credit)"* (`oad_push_retry_later_unbilled`).
  - Toàn bộ exception không xác định đi qua fallback generic an toàn, không hiển thị raw exception / URL / header / SQLSTATE lên màn hình.

---

## 3. Danh sách các file thay đổi & file mới

| File | Trạng thái | Nội dung chính |
|---|---|---|
| `supabase/migrations/056_push_campaign_role_hardening.sql` | Tạo mới | Migration sửa trigger `trg_fn_protect_campaign_push_metadata` kiểm tra `current_user`, giữ INVOKER, cập nhật quyền RPC. |
| `supabase/full_schema_singapore.sql` | Sửa đổi | Tích hợp nội dung migration 056 vào cuối schema Singapore. |
| `supabase/tests/run-push-campaign-role-hardening-test.mjs` | Tạo mới | Test harness PGlite kiểm tra tái hiện lỗi cũ và kiểm chứng 11 ca kiểm thử sau khi sửa. |
| `app/src/main/res/values/strings.xml` | Sửa đổi | Bổ sung string tài nguyên tiếng Anh mặc định cho xác minh pending và lỗi trigger. |
| `app/src/main/res/values-vi/strings.xml` | Sửa đổi | Bổ sung string tài nguyên tiếng Việt cho xác minh pending và lỗi trigger unbilled. |
| `app/src/main/res/values-en/strings.xml` | Sửa đổi | Bổ sung string tài nguyên tiếng Anh cho xác minh pending và lỗi trigger unbilled. |
| `app/src/main/java/com/ntt/crosstest/ui/viewmodels/AuthViewModel.kt` | Sửa đổi | Bổ sung hàm `sanitizeErrorMessage`, lọc sạch token/header trước khi log fallback. |
| `app/src/main/java/com/ntt/crosstest/ui/viewmodels/OwnerAppDetailViewModel.kt` | Sửa đổi | Thêm `isServerRejection`, xóa pending ID khi server từ chối chắc chắn, map `PUSH_METADATA_READ_ONLY`. |
| `app/src/main/java/com/ntt/crosstest/ui/screens/OwnerAppDetailScreen.kt` | Sửa đổi | Sửa điều kiện enable và nhãn nút dialog cho phép xác minh pending khi số dư < 100; giữ nguyên chặn mua mới khi thiếu tiền. |
| `app/src/test/java/com/ntt/crosstest/data/PushAndCarryoverHardeningTest.kt` | Sửa đổi | Bổ sung 4 unit test bao phủ: server rejection clear pending, timeout preserve pending, sanitize log, button conditions. |

---

## 4. Kết quả kiểm thử thực tế

### 4.1. Bằng chứng tái hiện lỗi cũ (Audit Probe)
- Lệnh: `node audit/push-rpc-role-2026-10-29/probe.mjs`
- Kết quả: **PASS (100% giữ nguyên bằng chứng cũ)**
  - Context `postgres`: Gọi RPC thành công (1000 -> 900 Credit).
  - Context `SET LOCAL ROLE authenticated`: Tái hiện chính xác mã lỗi `P0001` / `PUSH_METADATA_READ_ONLY`, rollback tiền về 1000, 0 receipt, 0 log.

### 4.2. Backend Test: Kiểm chứng toàn diện Migration 056
- Lệnh: `node supabase/tests/run-push-campaign-role-hardening-test.mjs`
- Kết quả: **11/11 TEST CASES PASS 100%**:
  1. `Tái hiện`: Authenticated role trên baseline 054 bị lỗi `PUSH_METADATA_READ_ONLY` -> **PASS (Đã tái hiện)**
  2. `Test 1`: Authenticated role + đúng 100 Credit mua hợp lệ thành công (100 -> 0 Credit, có receipt, có log -100, cập nhật push_order) -> **PASS**
  3. `Test 2`: Authenticated role + 483 Credit mua thành công còn 383 Credit (kịch bản người dùng thật trong ảnh) -> **PASS**
  4. `Test 3`: Authenticated ghi trực tiếp metadata qua `UPDATE` bị chặn `PUSH_METADATA_READ_ONLY` -> **PASS**
  5. `Test 4`: Authenticated tự đặt GUC `app.allow_push_metadata_write = true` rồi `UPDATE` vẫn bị chặn -> **PASS**
  6. `Test 5`: Authenticated khởi tạo metadata qua `INSERT` bị chặn `PUSH_METADATA_READ_ONLY` -> **PASS**
  7. `Test 6`: Thiếu tiền (99 Credit) bị từ chối `INSUFFICIENT_CREDITS`, không trừ tiền, không receipt/log -> **PASS**
  8. `Test 7`: Lỗi nghiệp vụ xảy ra (campaign đã đóng) thì rollback toàn bộ tiền và log -> **PASS**
  9. `Test 8`: Idempotent replay cùng `client_request_id` trả `replayed=true`, không trừ lần 2, không tăng push_order -> **PASS**
  10. `Test 9`: Thứ tự ưu tiên (App A đẩy -> App B đẩy -> App A đẩy lại): B1 > A1, A2 > B1, mỗi lượt mới trừ đúng 100 Credit -> **PASS**
  11. `Test 10`: Non-owner, Guest (role anon), Locked user bị từ chối đúng nguyên nhân -> **PASS**
  12. `Test 11`: `push_business_date` đúng ngày Việt Nam và `push_expires_at` hết hạn lúc 00:00 ngày hôm sau giờ Việt Nam -> **PASS**

- Lệnh hồi quy cũ: `node supabase/tests/run-push-and-carryover-hardening-test.mjs`
- Kết quả: **10/10 REGRESSION TESTS PASS 100%**.

### 4.3. Android Unit Tests & Gradle Build
- Lệnh: `.\gradlew.bat testDebugUnitTest --tests "com.ntt.crosstest.data.PushAndCarryoverHardeningTest"`
  - Kết quả: **BUILD SUCCESSFUL** (Tất cả test cases về pending replay, server rejection cleanup, timeout persistence, log sanitizer, dialog button condition đều PASS).
- Lệnh: `.\gradlew.bat :app:testDebugUnitTest`
  - Kết quả: **BUILD SUCCESSFUL in 17s** (Toàn bộ test suite Android PASS 100%).
- Lệnh: `.\gradlew.bat :app:assembleDebug`
  - Kết quả: **BUILD SUCCESSFUL in 3s** (Biên dịch thành công bản build debug, không có lỗi biên dịch).

---

## 5. Thứ tự triển khai lên môi trường thực tế

1. **Bước 1 - Áp dụng migration hẹp lên database Supabase Singapore:**
   - Chạy tệp: `supabase/migrations/056_push_campaign_role_hardening.sql`.
   - Lệnh tự động reload schema cache PostgREST: `notify pgrst, 'reload schema';`.
   - Xác minh: Gọi thử RPC `push_campaign` từ một tài khoản test thường mang JWT `authenticated`.
2. **Bước 2 - Giám sát giao dịch và đối soát số dư:**
   - Theo dõi log PostgREST và Supabase Database: kiểm tra mỗi giao dịch trừ 100 Credit tương ứng chính xác với 1 bản ghi `campaign_push_receipts` và 1 bản ghi `credit_logs` có `action = 'campaign_push'`.
   - Tuyệt đối không tự ý cộng trừ credit dựa trên ảnh mà không có đối soát log/receipt.
3. **Bước 3 - Phát hành bản cập nhật ứng dụng Android:**
   - Đóng gói APK/AAB phiên bản mới chứa các cải tiến hiển thị an toàn, dọn dẹp lỗi từ chối và hỗ trợ xác minh pending khi số dư < 100.
   - Thử nghiệm trên thiết bị thật: mở hộp thoại xác nhận, kiểm tra nút bấm khi đủ tiền và thiếu tiền.

---

## 6. Giới hạn môi trường còn lại cần lưu ý

1. **Chưa kiểm thử trực tiếp trên PostgREST HTTP Staging / Supabase Cloud:**
   - Các bài test backend được kiểm chứng độc lập trên môi trường PostgreSQL local (PGlite) với đầy đủ context `SET LOCAL ROLE authenticated` và JWT claim. Cần kiểm tra smoke test trên môi trường staging Supabase thực tế qua HTTP endpoint trước khi mở cho người dùng đại trà.
2. **Kiểm thử tranh chấp đồng thời (Concurrency) trên daemon PostgreSQL đa kết nối:**
   - Môi trường Windows phát triển hiện tại không có service PostgreSQL daemon nội bộ hay Docker container hoạt động. Do đó, kiểm thử cạnh tranh đã được xác nhận bằng khóa hàng (`FOR UPDATE`) và khóa transaction advisory (`pg_advisory_xact_lock`), nhưng chưa chạy test bắn đồng thời 50 request song song trên nhiều connection pool thật. Cần kiểm chứng concurrency trên Staging có PostgreSQL đa kết nối.
3. **Kiểm thử trên thiết bị vật lý:**
   - Đã biên dịch thành công `:app:assembleDebug` và vượt qua toàn bộ unit test trên Robolectric SDK 34. Chưa cài đặt trực tiếp file APK debug lên máy Android của người dùng để thao tác thủ công.
