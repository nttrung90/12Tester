# Báo cáo bàn giao cuối cùng: Hoàn tất 7 ca Provenance độc lập & Quy trình đối soát

Ngày cập nhật: **30/10/2026**.  
Dự án: `F:\AI\12Tester`.  
Tài liệu thẩm định độc lập: `audit/push-carryover-review-2026-10-30/opencode-last-two-fixes.txt`.

---

## 1. Kết quả kiểm thử thực tế cuối cùng

| Bộ kiểm thử | Lệnh thực thi chính xác | Kết quả thực tế | Chi tiết |
|---|---|---|---|
| **Final Provenance Suite** | `node audit/push-carryover-review-2026-10-30/final-provenance.mjs` | **PASS 7/7 tests** (Exit 0) | Toàn bộ 7 trường hợp độc lập trong `final-provenance-results.json` đều passed |
| **Provenance Probes** | `node audit/push-carryover-review-2026-10-30/provenance.mjs` | **PASS 3/3 tests** (Exit 0) | Chặn hồi tố trạng thái, bảo toàn D khi xoá campaign, chặn dời ngày policy |
| **Baseline 062+ Proven Regression Suite** | `node supabase/tests/run-push-and-carryover-baseline062-regression-test.mjs` | **PASS 6/6 test groups** | Hoạt động cho user vắng mặt trên ngày phủ kín, bảo lưu nợ=8, D append-only, role journal |
| **Independent Android Review Test** | `.\gradlew.bat -I audit/push-carryover-review-2026-10-30/review.init.gradle :app:testDebugUnitTest --tests com.ntt.crosstest.data.PushGenerationReviewTest --rerun --offline --console=plain` | **BUILD SUCCESSFUL (PASS)** | Chặn callback rò rỉ sau chuỗi đổi tài khoản A->B->A thay đổi generation |
| **Push & Carryover Android Unit Tests** | `.\gradlew.bat :app:testDebugUnitTest --tests "com.ntt.crosstest.data.PushAndCarryoverHardeningTest" --tests "com.ntt.crosstest.data.CampaignPushUnitTest" --tests "com.ntt.crosstest.data.DailyTaskCarryoverUnitTest" --rerun --offline --console=plain` | **PASS 100%** (14 + 3 + 4 tests) | Đối soát chỉ đọc không trừ tiền, guard generation chặt chẽ |
| **Android Assemble Debug Build** | `.\gradlew.bat :app:assembleDebug --offline --console=plain` | **BUILD SUCCESSFUL** | Biên dịch thành công 100%, 0 compile error |

---

## 2. Chi tiết xử lý 2 điểm nghẽn cuối cùng

### 1. Bảo toàn số lượt test D trong ngày hiện tại khi campaign bị xoá cascade & phản ánh đúng xoá trực tiếp
- **Nguyên nhân lỗi trước đó:** Đối với ngày hôm nay (`p_date = v_today`), hàm `get_daily_task_status` chỉ truy vấn live `checkins` và `tester_reports`. Khi campaign bị xoá, quan hệ `ON DELETE CASCADE` xoá sạch các hàng này, khiến `completed_tests` bị sụt giảm từ 1 về 0.
- **Giải pháp dứt điểm:**
  - Thêm trigger `AFTER DELETE` trên `public.checkins` và `public.tester_reports`.
  - Phân biệt giữa **xoá trực tiếp (explicit removal)** và **xoá cascade do xoá campaign**:
    - Khi một checkin hoặc report bị xoá mà campaign cha vẫn tồn tại trong `public.campaigns`: Đây là hành động thu hồi/xoá trực tiếp -> trigger ghi một event `status = 'deleted', is_qualifying = false` với clock timestamp thực tế.
    - Khi campaign cha không còn tồn tại trong `public.campaigns`: Đây là xoá cascade do xoá app -> trigger **không ghi đè huỷ bỏ**, bảo toàn nguyên vẹn tính hợp lệ đã được ghi nhận trước đó.
  - Khi tính `D` cho cả ngày hôm nay lẫn ngày quá khứ, hàm `get_daily_task_status` lấy bản ghi sự kiện mới nhất theo từng `(source_type, source_id)` rồi đếm distinct `campaign_id` đạt chuẩn (`is_qualifying = true` và `campaign_owner_id <> user_id`).
  - Đạt tiêu chuẩn:
    - Xoá trực tiếp cùng ngày làm giảm D về 0 ngay lập tức (Test 4 PASS).
    - Có nhiều report (1 pending + 1 rejected) trên cùng 1 campaign thì sau khi xoá campaign vẫn giữ đúng D = 1 (Test 5 PASS).

### 2. Sửa lỗi Not-Null của Audit và Kiểm tra hợp lệ đầu vào trong `reconcile_legacy_daily_task_checkpoint`
- **Nguyên nhân lỗi trước đó:** Lệnh ghi audit chèn `new_is_enabled = NULL`, vi phạm ràng buộc `NOT NULL` của bảng `daily_task_carryover_policy_audit`.
- **Giải pháp dứt điểm:**
  - Chèn giá trị `new_is_enabled = true` hợp lệ vào `daily_task_carryover_policy_audit`.
  - Validate nghiêm ngặt các tham số đầu vào:
    - `p_user_id` không được null.
    - `p_task_date` không được null và phải là ngày quá khứ (`< v_today`).
    - `p_mode` phải thuộc `('owner', 'member', 'exempt')`.
    - `p_active_app_count` không được null và phải $\ge 0$.
    - `p_carry_in` không được null và phải $\ge 0$ (tuyệt đối không âm thầm gán nợ null về 0).
    - `p_carry_out` không được null và phải $\ge 0$.
    - `p_operator_note` bắt buộc phải có nội dung không rỗng.
  - Mệnh đề `ON CONFLICT (user_id, task_date)` cập nhật đầy đủ toàn bộ các trường checkpoint, version, và provenance, bảo đảm tính bất biến của các dòng đã finalized và không làm thay đổi số dư Credit của tài khoản.
  - Test 6 và Test 7 đều PASS 100%.

### 3. Tích hợp chuỗi chạy kiểm thử npm test
- Đã thêm `node run-push-and-carryover-baseline062-regression-test.mjs` vào chuỗi script `"test"` chính trong `supabase/tests/package.json` để tự động chạy kiểm chứng schema cuối 066.

---

## 3. Danh sách tệp đã cập nhật

1. `supabase/migrations/066_push_and_carryover_hardening.sql`: Cập nhật logic D thời gian thực với trigger xoá, sửa hàm `reconcile_legacy_daily_task_checkpoint` với audit hợp lệ và validation chặt chẽ.
2. `supabase/tests/package.json`: Tích hợp test runner mới vào `npm test`.
3. `app/src/main/java/com/ntt/crosstest/ui/viewmodels/OwnerAppDetailViewModel.kt`: Tách biệt đối soát chỉ đọc và mua mới, guard session generation.
4. `app/src/test/java/com/ntt/crosstest/data/PushAndCarryoverHardeningTest.kt`: 14 bài kiểm tra Android hardening đều pass.
5. `audit/push-carryover-review-2026-10-30/final-provenance-results.json`: 7/7 test passed.
6. `audit/push-carryover-review-2026-10-30/provenance-results.json`: 3/3 test passed.
7. `audit/push-carryover-review-2026-10-30/probe-results.json`: Đã cập nhật.

---
*Bàn giao hoàn tất toàn diện. Tất cả các kịch bản kiểm thử độc lập đã xanh 100%.*
