# Bản đồ mã cho AI

Mục tiêu: tìm đúng mã hiện hành, giảm đọc lặp lịch sử. Giữ nguyên migrations và hành vi ứng dụng. Không có thay đổi database trong việc tổ chức tài liệu này.

## Chọn điểm bắt đầu

| Tác vụ | Mở/tìm trước | Mở thêm khi cần |
|---|---|---|
| Giao diện Android | `app/src/main/java/com/ntt/crosstest/ui/screens/`, `ui/components/` | `ui/viewmodels/`, `app/src/main/res/values*/` |
| Nhiệm vụ ngày | `ui/viewmodels/HomeViewModel.kt`, `ui/components/DailyTaskCard.kt`, `data/CampaignRepository.kt` | RPC `get_daily_task_status`, `refresh_daily_completion`; `data/DailyTaskActionGate.kt` |
| Báo cáo test, gửi nền | `data/ReportSubmitManager.kt`, `data/ReportSubmitPolicy.kt`, `data/PendingReportStore.kt` | `data/CampaignRepository.kt`, `work/`; RPC `submit_tester_report` và receipts |
| Vòng đời app, đăng/xóa/dừng/tiếp tục | `data/CampaignRepository.kt`, `ui/viewmodels/OwnerAppDetailViewModel.kt`, `ui/viewmodels/CreateCampaignViewModel.kt` | SQL theo tên RPC; gate nhiệm vụ ngày, trigger bảo vệ tài chính |
| Thưởng thêm tester | `data/CampaignTesterBonusStore.kt`, `data/Models.kt` | SQL chứa `tester_bonus`, migrations và tests cùng chủ đề |
| Cấu hình, phiên bản, flags | `data/AppConfigRepository.kt`, `app/build.gradle.kts` | SQL chứa `app_configs`; `supabase/operations/egress_sync_v2_rollout.sql` |
| Kết nối, cache, đồng bộ | `data/SupabaseHolder.kt`, `data/SessionCacheManager.kt`, `data/EgressSyncProtocol.kt` | `data/EgressOptimization.kt`, `util/`; RPC sync V2 |
| Quản trị, chính sách, thông báo | `data/AdminManagementRepository.kt`, `data/BusinessPolicyRepository.kt`, `data/AdminNotificationCampaignRepository.kt` | `ui/viewmodels/Admin*`, SQL theo tên RPC và test admin |
| Media/Firebase | `backend/media-gateway/`, `app/src/main/java/com/ntt/crosstest/util/FirebaseStorageHelper.kt`, `storage.rules` | `backend/storage-cleanup/`, RPC cấp quyền media |
| Worker thông báo | `backend/notification-worker/` | SQL queue/RPC liên quan và test worker |
| Hồi quy Android | `app/src/test/` | Chạy lớp test liên quan trước, mở rộng theo phạm vi thay đổi |
| Hồi quy SQL | `supabase/tests/package.json`, `supabase/tests/run-*-test.mjs` theo tính năng | Một số bộ nghiệm thu 090 nằm trong `audit/daily-task-action-gate-2026-11-12/`; chỉ mở đúng file cần dùng |

Các đường dẫn `data/`, `ui/`, `work/`, `util/` trong bảng là tương đối với `app/src/main/java/com/ntt/crosstest/`.

## Tìm nhanh trên Windows và các máy có Node

Chạy từ gốc dự án; script tự xác định gốc nên cũng có thể gọi bằng đường dẫn tuyệt đối.

```powershell
node tools/ai-search.mjs --scope android "DailyTaskCard"
node tools/ai-search.mjs --scope sql "get_daily_task_status"
node tools/ai-search.mjs --scope backend "verify_media_access_grant"
node tools/ai-search.mjs --scope tests "carryover" --files --limit 30
node tools/ai-search.mjs --scope code --list --limit 30
```

| Scope | Phạm vi |
|---|---|
| `code` (mặc định) | Android main source, backend, migrations hiện hành và cấu hình build chính |
| `android` | Android main source/resources và cấu hình Gradle chính |
| `backend` | Các service trong `backend/` |
| `sql` | `supabase/migrations/`, `supabase/operations/` |
| `tests` | Android unit/instrumentation tests và `supabase/tests/` |
| `docs` | Bản đồ này và chỉ dẫn AGENTS/GEMINI; không đọc toàn bộ báo cáo cũ |
| `history` (chọn tường minh) | SQL archive, audit/runs và báo cáo/kế hoạch Markdown/TXT ở gốc |

Mặc định tìm không phân biệt hoa/thường, literal, tối đa 60 kết quả; `--case-sensitive` để phân biệt. `--files` chỉ in tên file có khớp; `--list` liệt kê file trong scope mà không đọc nội dung, có thể thêm chuỗi để lọc đường dẫn. `--limit` từ 1 đến 500. Kết quả dài được rút gọn; script luôn cho biết khi có thể còn kết quả. Không tìm thấy có exit code 1; đối số lỗi là 2.

Script không thay `.gitignore`, cài đặt IDE hoặc global ignore. Các thư mục lịch sử vẫn đọc được bằng đường dẫn trực tiếp hoặc scope `history`. Scope hẹp chỉ là điểm bắt đầu; mở rộng khi chưa đủ dữ kiện. File cấu hình local/khóa, dependency, build và output sinh tự động được loại khỏi tìm kiếm này.

## Định vị SQL hiện hành theo chữ ký

```powershell
node tools/ai-sql-index.mjs find get_daily_task_status
node tools/ai-sql-index.mjs find submit_tester_report
node tools/ai-sql-index.mjs check
# Chỉ chạy khi thiếu/cũ hoặc sau khi migrations đổi:
node tools/ai-sql-index.mjs build
```

Chỉ mục `docs/ai/sql-index.json` được sinh bằng nạp lần lượt migrations hiện hành vào PGlite trong bộ nhớ. Sau từng file, công cụ so định nghĩa hàm trong catalog theo schema và chữ ký; không suy ra bản cuối chỉ từ số file hoặc tên hàm. Vì thế thay đổi `search_path` bằng ALTER FUNCTION cũng được tính. Không có kết nối production hoặc SQL triển khai được xuất từ chỉ mục.

Lệnh `find` kiểm tra danh sách file và SHA-256 trước khi trả kết quả; nguồn thay đổi thì từ chối dùng chỉ mục cũ. Kết quả chỉ trả các chữ ký khớp để tránh đưa toàn bộ lịch sử vào ngữ cảnh. `lineHints` là những dòng CREATE/ALTER cùng tên trong file cuối, cần đọc đúng overload. `lastDefinitionChange` không khẳng định đó là lần sửa quyền/trigger/dữ liệu cuối cùng.

Quy trình sửa SQL: tra chỉ mục → đọc định nghĩa thật → tìm caller, grants/revokes, trigger và thay đổi tiếp nối → sửa bằng migration mới khi thích hợp → chạy test liên quan → cập nhật chỉ mục. Không coi source hoặc chỉ mục là bằng chứng đã triển khai lên máy chủ.

## Phạm vi kiểm chứng

Đây là thay đổi hướng dẫn và công cụ định vị. Không bảo đảm một tỷ lệ tăng tốc cố định hoặc mọi IDE/AI tự đọc hướng dẫn; hiệu quả đến từ việc công cụ/agent dùng đúng phạm vi. `AGENTS.md` và `GEMINI.md` là các điểm vào; có thể dẫn AI khác đọc bản đồ này trong prompt.

Kiểm tra công cụ bằng `node --test tools/ai-navigation.test.mjs`. Bộ thử xác minh phạm vi tìm, khả năng đọc lịch sử tường minh, tìm literal/giới hạn kết quả, chữ ký RPC và từ chối chỉ mục cũ khi SQL thay đổi/thêm/bớt. Các thử nghiệm sửa file chỉ chạy trên bản sao trong thư mục tạm, không sửa migrations gốc.
