# BÁO CÁO BÀN GIAO: BÁO CÁO NHIỆM VỤ VÀ ẨN NHIỆM VỤ HẾT HẠN
**Thời điểm cập nhật:** 2026-10-29  
**Kế hoạch thực hiện:** `KE_HOACH_BAO_CAO_NHIEM_VU_VA_AN_NHIEM_VU_HET_HAN_2026-10-29.md`  
**Kế hoạch recheck & sửa tiếp:** `RECHECK_VA_KE_HOACH_SUA_TIEP_BAO_CAO_NHIEM_VU_2026-10-29.md`

---

## 1. Tổng quan công việc đã thực hiện

Đã xử lý triệt để toàn bộ 6 nhóm lỗi (R01 đến R06) được phát hiện trong đợt kiểm tra lại ngày 2026-10-29, bảo đảm tính toàn vẹn của nghiệp vụ báo cáo nhiệm vụ và ẩn nhiệm vụ hết hạn:

1. **R01 (P1) — Đồng bộ contract đọc báo cáo:** Bổ sung đầy đủ metadata nhiệm vụ (`submission_context`, `mission_id`, `mission_title`, `mission_reward_credits`, `mission_ends_at`) và `thumbnail_url` vào RPC `get_campaign_detail`, đồng nhất hoàn toàn giữa submit receipt, API chi tiết app, API chủ app duyệt báo cáo (`get_campaign_reports_for_owner`) và query bảng trực tiếp. Lịch sử tester luôn hiển thị huy hiệu "Báo cáo nhiệm vụ" và tên nhiệm vụ chính xác.
2. **R02 (P1) — Bảo toàn ý định gửi nhiệm vụ:** Tách ý định gửi (`intendedMissionId`, `isMissionReport`) khỏi trạng thái tải ngữ cảnh. Nếu đang tải, tải lỗi (HTTP 500), hoặc nhiệm vụ hết hạn/không tồn tại, app chặn gửi với lỗi thân thiện tương ứng; tuyệt đối không tự ý bỏ `missionId` để âm thầm hạ cấp xuống báo cáo test thường.
3. **R03 (P2) — Tách bổ sung nhận xét cho báo cáo approved khỏi nộp nhiệm vụ mới:** Cho phép tester sửa nhận xét cho báo cáo đã duyệt (`approved`) mà không bắt `participant.status` phải là `joined` (kể cả khi đã `completed`), giữ nguyên ảnh/rating/context/danh tính nhiệm vụ đã chốt và không phát sinh thưởng thêm. Đồng thời, báo cáo test thường đã duyệt không bị auto-resolve nhầm sang nhiệm vụ mới khi sửa nhận xét.
4. **R04 (P2) — Kiểm tra hạn và trạng thái theo nhiệm vụ đã lưu khi sửa báo cáo pending:** Với báo cáo đã mang danh tính nhiệm vụ, mọi request sửa minh chứng/nội dung (kể cả client cũ không truyền `p_mission_id`) đều kiểm tra trạng thái và thời hạn của nhiệm vụ gốc đã lưu. Nếu đã hết hạn, server trả lỗi `MISSION_EXPIRED` và giữ nguyên toàn bộ minh chứng cũ. Phân biệt rõ request mới với replay receipt idempotency (replay vẫn trả kết quả đã commit).
5. **R05 (P2) — Kiểm tra token/sequence sau các bước suspend trong ViewModel:** Đặt guard `refreshSequence`, session generation và effective `userId` ngay trước khi publish state ở cả fast-path RPC gộp và fallback trong `CampaignDetailViewModel`. Tránh tình trạng response cũ từ bước tải ngữ cảnh nhiệm vụ ghi đè refresh mới; truyền tiếp `CancellationException` để hủy coroutine sạch sẽ.
6. **R06 (P2) — Chuẩn hóa parser mirror và đăng ký toàn bộ test suites vào `npm test`:** Sửa logic cắt đoạn trong `supabase/tests/run-daily-task-ratio.mjs` bằng cách tự động tìm ranh giới marker kế tiếp thay vì giả định migration 044 là đoạn cuối; đăng ký toàn bộ 7 test runner (migrations 042 đến 049) vào `package.json`.

---

## 2. Chi tiết các file và migration liên quan

### 2.1. Backend (Database & Migrations)
- **`supabase/migrations/048_mission_report_and_expiration.sql`**:
  - Bổ sung 7 cột metadata vào bảng `public.tester_reports`.
  - Trigger bảo vệ metadata `trig_protect_tester_reports_mission_metadata`.
  - Lọc deadline `ends_at > now()` trước `ORDER BY` và `LIMIT 100` trong `get_open_missions()`, trả `server_now`.
  - Trả thưởng nguyên tử, một lần trong `_settle_mission_reward`.
- **`supabase/migrations/049_mission_report_hardening.sql`** (Migration mới kế tiếp):
  - Override `get_campaign_detail(p_campaign_id)`: Trả `submission_context`, `mission_id`, `mission_title`, `mission_reward_credits`, `mission_ends_at`, `thumbnail_url` trong mảng `reports` của chi tiết app (R01).
  - Override `submit_tester_report(...)`:
    - Xử lý replay idempotency trước tiên theo `client_submit_id`.
    - Tách nhánh cập nhật comment báo cáo approved: cho phép sửa comment khi participant `completed`, giữ nguyên `submission_context`, không regrant thưởng, không auto-resolve sang mission khác (R03 Case A & B).
    - Ràng buộc deadline và trạng thái nhiệm vụ đã lưu trên báo cáo pending khi sửa minh chứng, kể cả khi request không truyền `p_mission_id` (R04).
    - Kiểm tra `is_valid_report_thumbnail_url` đúng signature 4 tham số.
- **`supabase/full_schema_singapore.sql`**:
  - Tích hợp đầy đủ các migration từ 041 đến 049, đảm bảo bootstrap trên database trắng khớp 100% với nâng cấp từng bước.

### 2.2. Android Frontend & Data Layer
- **`CampaignDetailViewModel.kt`**:
  - Thêm `intendedMissionId: String?` và `missionContextError: String?` vào `CampaignDetailUiState` (R02).
  - Khởi tạo UI state giữ `intendedMissionId = initialMissionId` và `isMissionReport = initialMissionId != null`.
  - Trong `resolveMissionContext()`: truyền tiếp `CancellationException`, giữ lại ý định gửi nhiệm vụ khi gặp lỗi mạng (HTTP 500) hoặc khi nhiệm vụ hết hạn.
  - Trong `startSubmit()`: kiểm tra `hasMissionIntent`; chặn gửi và báo lỗi thích hợp khi lỗi kết nối, quá hạn hoặc chưa tham gia; luôn gửi kèm `missionId` đã chốt nếu có ý định nhiệm vụ (R02, R03).
  - Trong `refresh()`: kiểm tra guard sequence/generation/uid ngay sau khi `resolveMissionContext()` hoàn tất trước khi gọi `applyDetail` hoặc cập nhật `_uiState` (R05).
- **`CampaignDetailScreen.kt`**:
  - Đồng bộ hiển thị "Báo cáo nhiệm vụ" trong lịch sử tester dựa trên `isMissionReport` từ dữ liệu báo cáo thực tế trả về từ `get_campaign_detail`.
- **`HomeViewModel.kt` & `HomeScreen.kt`**:
  - Timer căn theo deadline nhiệm vụ `scheduleNextMissionDeadlineCheck()` và resume handler `pruneExpiredMissions()`.
- **`ReportSubmitManager.kt`, `PendingReportStore.kt`, `ReportUploadWorker.kt`**:
  - Giữ nguyên vẹn danh tính nhiệm vụ (`missionId`, `missionTitle`, `isMissionReport`) qua WorkManager, disk persistence, retry và process restart.
- **Tài nguyên chuỗi đa ngôn ngữ (`strings.xml`, `values-vi/strings.xml`, `values-en/strings.xml`)**:
  - Bổ sung và đồng bộ chuỗi thông báo, nhãn bộ lọc và lỗi hết hạn nhiệm vụ.

### 2.3. Test Suites & Runners
- **`supabase/tests/run-daily-task-ratio.mjs`**: Sửa logic parser mirror tìm marker động (R06).
- **`supabase/tests/package.json`**: Đăng ký cả 7 runner vào lệnh `npm test` chuẩn (R06).
- **`supabase/tests/run-mission-report-and-expiration-test.mjs`**: Bổ sung 4 test case backend chính thức (PASS 15: R01, PASS 16: R03 Case A, PASS 17: R04, PASS 18: R03 Case B).
- **`app/src/test/java/com/ntt/crosstest/data/MissionReportRecheckProbeTest.kt`**: Đăng ký bộ probe thành test suite chính thức trong `app/src/test`.

---

## 3. Kiểm thử thực tế đã thực hiện và kết quả

Tất cả các kiểm thử dưới đây đã thực sự được chạy trên môi trường kiểm tra cục bộ:

| STT | Lệnh kiểm thử | Phạm vi | Kết quả thực tế |
|:---:|:---|:---|:---:|
| 1 | `node .tmp/mission-report-recheck/database-probes.mjs` | 4 probe tái hiện lỗi ban đầu (R01, R03 Case A, R04, R03 Case B) | **PASS (4/4)** |
| 2 | `npm --prefix supabase/tests test` | Toàn bộ 7 runner backend SQL migrations từ 042 đến 049: `run-daily-task-ratio.mjs`, `run-backend-cleanup-hardening.mjs`, `run-mission-flow-hardening.mjs`, `run-thumbnail-migration.mjs`, `run-media-gateway-test.mjs`, `run-home-stats-cycle-test.mjs`, `run-mission-report-and-expiration-test.mjs` | **PASS (7/7 suites, 100%)** |
| 3 | `node supabase/tests/run-mission-report-and-expiration-test.mjs` | Toàn bộ 18 test cases backend về báo cáo nhiệm vụ, ẩn hết hạn và hardening 049 | **PASS (18/18)** |
| 4 | `gradlew.bat testDebugUnitTest` | Toàn bộ 209 unit tests của dự án Android (bao gồm `MissionReportRecheckProbeTest` và `MissionReportAndExpirationTest`) | **PASS (209/209 tests, 0 failures, 0 errors)** |
| 5 | `gradlew.bat assembleDebug` | Biên dịch toàn bộ mã nguồn Android Kotlin/Java, desugar và đóng gói APK Debug | **BUILD SUCCESSFUL** |

---

## 4. Những phần chưa xác minh hoặc cần lưu ý

1. **Không deploy lên production:** Theo quy định an toàn, không tự động deploy migrations lên Supabase production và không sửa dữ liệu production trong lượt này. Các file migration `048_mission_report_and_expiration.sql` và `049_mission_report_hardening.sql` đã sẵn sàng để quản trị viên triển khai tuần tự.
2. **Kiểm tra trực quan trên thiết bị thật (Physical device / Emulator):** Tại thời điểm kiểm thử, không có thiết bị thật hoặc emulator gắn vào adb (`adb devices` rỗng). Toàn bộ logic giao diện, luồng điều hướng, StateFlow, timer pruning và background WorkManager đã được kiểm chứng tự động qua 209 Robolectric unit tests. Việc kiểm tra trải nghiệm thực tế (màu sắc giao diện, cảm giác khi thẻ tự ẩn) sẽ thực hiện trong bước nghiệm thu người dùng (UAT).
3. **Bảo toàn thay đổi chưa commit:** Các thay đổi uncommitted trước đó liên quan đến tối ưu độ trễ nộp báo cáo, thumbnail, receipt và idempotency (`FirebaseStorageHelper.kt`, `ReportUploadWorker.kt`, `CampaignRepository.kt`, `PendingReportStore.kt`) đều được bảo toàn 100%, không bị xung đột.

---

## 5. Thứ tự triển khai chuẩn

Để đảm bảo không gián đoạn dịch vụ và tương thích ngược:

1. **Bước 1 (Backend Supabase):**
   - Chạy migration `048_mission_report_and_expiration.sql` (bổ sung cột, trigger, hàm hết hạn và settlement).
   - Chạy migration `049_mission_report_hardening.sql` (đồng bộ RPC `get_campaign_detail` và hardening `submit_tester_report`).
2. **Bước 2 (Android Release):**
   - Phát hành bản cập nhật ứng dụng Android. Client sẽ bắt đầu truyền `missionId`, hiển thị nhãn "Báo cáo nhiệm vụ" trong lịch sử/màn duyệt và tự động kích hoạt timer ẩn nhiệm vụ hết hạn.
