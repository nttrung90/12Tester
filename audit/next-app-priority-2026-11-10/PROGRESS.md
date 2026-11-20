# Tiến độ Triển khai Kế hoạch Ưu tiên Gợi ý App Chưa Đủ Chỉ Tiêu

- Ngày: 10/11/2026
- Trạng thái: Hoàn tất sửa mã và kiểm tra cục bộ ngày 11/11/2026; chưa triển khai máy chủ thật.

## 1. Mục tiêu
Triển khai kế hoạch ưu tiên gợi ý app chưa đủ chỉ tiêu test trong hộp thoại sau nộp báo cáo (`KE_HOACH_UU_TIEN_GOI_Y_APP_CHUA_DU_CHI_TIEU_2026-11-10.md`):
- Phân 4 nhóm nghiêm ngặt:
  (1) Chưa đủ target + đã cài
  (2) Chưa đủ target + chưa cài
  (3) Đã đủ/vượt target + đã cài
  (4) Đã đủ/vượt target + chưa cài
- Chọn ngẫu nhiên trong nhóm không rỗng đầu tiên.
- Ưu tiên tài khoản test lần đầu (installed_count thấp nhất) chỉ áp dụng bên trong nhóm đã chọn.
- Migration mới (086) bổ sung `today_tested_count` và `stats_date` vào `get_next_campaign_candidates_v2`.
- Android Model / Repository / ViewModel / UI hỗ trợ fallback, batch-fill stats khi thiếu dữ liệu, prefetch staleness check (phiên, ngày VN, mutation) và phân biệt rỗng vs lỗi tải.

## 2. Nhật ký công việc & Mốc hoàn thành

- [x] Đọc và nắm toàn bộ yêu cầu kế hoạch, ràng buộc kiểm tra, không sửa checkpoint/hashes/SESSION_SETTINGS.
- [x] Khởi tạo `PROGRESS.md`.
- [x] Soạn thảo và kiểm tra migration `086_next_campaign_candidate_daily_target_priority.sql`.
- [x] Cập nhật SQL test runner (`supabase/tests/run-egress-sync-v2-test.mjs`) & các test database liên quan (đã chạy và PASS 100%).
- [x] Cập nhật Android Model (`Models.kt`) và Repository (`CampaignRepository.kt`, DTOs).
- [x] Tách bộ chọn thuần túy (`NextCampaignSelector.kt`) và viết unit test toàn diện (4 nhóm, biên 12/15/20, tài khoản mới, loại trừ, fallback).
- [x] Cập nhật `CampaignDetailViewModel.kt`, `CampaignDetailScreen.kt`, strings (default/en/vi).
- [x] Sửa telemetry measurement đếm RPC `get_campaigns_stats` bên trong network attempt của `getCampaignsStatsMap`.
- [x] Sửa sequence owner invalidation trong ViewModel: phân biệt stale sequence (0 writes) vs sequence hiện tại bị invalidate scope/day/mutation (chuyển sang retryable failed state, không báo nhầm all-done).
- [x] Bổ sung phương thức `validateAndConsumeNextCampaign()` kiểm tra độ tươi mới lúc nhấn "Test ngay".
- [x] Củng cố race test `firstSubmissionSnapshot_preservedAcrossSubmitCompletion` với test seam kiểm tra lựa chọn nộp lần đầu và hành vi lựa chọn bình thường lần tiếp theo.
- [x] Bổ sung các test kịch bản batch repair trong `EgressFinalClientReviewTest.kt` (has_tested_today loại trừ nhưng giữ full candidate, missing/stale stats error, blank/null id error trước khi gọi stats, tạm dừng stats deferred và thay đổi session/day/mutation).
- [x] Cập nhật `EgressFinalClientReviewTest.kt` và fixtures.
- [x] Chạy kiểm thử: PGlite SQL test, Unit test, `assembleDebug --offline`, `testDebugUnitTest`.
- [x] Hoàn tất `IMPLEMENTATION_REPORT.md` tại `audit/next-app-priority-2026-11-10/IMPLEMENTATION_REPORT.md`.

## 3. Nghiệm thu cuối

- Android: 644 tests / 82 classes; 0 failures, 0 errors, 0 skipped; assembleDebug thành công.
- 40 kiểm thử trực tiếp liên quan: Selector 15, ViewModel 13, Repository 12; đều qua trong lượt chạy đầy đủ.
- SQL: runner mới có migration 086 và kiểm tra nộp báo cáo/nhận thưởng ở app đủ chỉ tiêu đã qua; các runner hồi quy riêng cũng qua.
- Báo cáo: IMPLEMENTATION_REPORT.md. Log: runs/reviewer-full-android-final.log và runs/reviewer-final-sql-2026-11-11.log.
- Bản đối chiếu 313 tệp trước tác vụ được kiểm tra nguyên vẹn. Danh sách 13 tệp mã/kiểm thử thay đổi: final-changed-files.json.

