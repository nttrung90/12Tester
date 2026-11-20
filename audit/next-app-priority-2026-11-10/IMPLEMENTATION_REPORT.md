# Kết quả sửa thứ tự gợi ý app tiếp theo

- Hoàn tất kiểm tra cục bộ: 11/11/2026, giờ Việt Nam.
- Công cụ triển khai: Antigravity CLI, mô hình `gemini-3.8-flash-high`, mức suy luận `high`.
- Phạm vi: thay đổi thứ tự gợi ý sau nộp báo cáo; giữ app đủ chỉ tiêu vẫn nhận test và thưởng.
- Trạng thái: mã nguồn đã sửa, toàn bộ kiểm thử Android và dựng debug thành công. Chưa áp dụng migration lên máy chủ thật, chưa phát hành ứng dụng hoặc kiểm tra trên thiết bị thật.

## 1. Hành vi cuối cùng

Ứng viên phải đang mở, không phải app hiện tại hoặc app của tester, chưa được tester test hôm nay và không có báo cáo test thường đang chờ gửi cục bộ.

| Ưu tiên | Chỉ tiêu trong ngày | Cài trên máy |
|---|---|---|
| 1 | Chưa đủ | Đã cài |
| 2 | Chưa đủ | Chưa cài |
| 3 | Đã đủ hoặc vượt | Đã cài |
| 4 | Đã đủ hoặc vượt | Chưa cài |

Chọn ngẫu nhiên trong nhóm đầu tiên còn ứng viên. Tiêu chí ít người tham gia/cài dành cho tài khoản test lần đầu chỉ áp dụng trong nhóm đã chọn.

Ví dụ: A đã cài, đủ 12/12; B chưa cài, mới 2/12 thì B được chọn trước. Khi không còn ứng viên thiếu chỉ tiêu, A vẫn được gợi ý và nhận test bình thường.

Chỉ tiêu dùng số tester khác nhau có check-in trong riêng ngày Việt Nam và mục tiêu 12/15/20. Không dùng số lượt cộng dồn theo chu kỳ Trang chủ.

## 2. Phần đã triển khai

- Thêm migration `086_next_campaign_candidate_daily_target_priority.sql`, bổ sung `today_tested_count` và `stats_date` vào RPC ứng viên. Giữ tên hàm, tham số, quyền và tập ứng viên, bao gồm app đủ chỉ tiêu.
- Bổ sung trường nullable cho model để nhận ra phản hồi máy chủ cũ; tải bổ sung thống kê thiếu bằng một lần gọi theo lô. Dữ liệu thiếu hoặc cũ không bị ngầm coi là 0 lượt.
- Đồng nhất dữ liệu phân nhóm giữa RPC mới và đường dự phòng. Loại app vừa được ghi nhận đã test trong lúc tải bổ sung thống kê.
- Tách bộ chọn bốn nhóm thành hàm kiểm thử độc lập.
- Giữ snapshot tìm trước theo tài khoản, phiên, ngày, phiên bản dữ liệu và thời hạn hiện có; kiểm tra lại báo cáo chờ gửi và danh sách app đã cài khi chọn.
- Giữ trạng thái tài khoản mới trước khi báo cáo đầu tiên hoàn tất; tái sử dụng truy vấn lịch sử theo đúng tài khoản/phiên.
- Chặn kết quả của tác vụ cũ ghi đè lượt tải mới hoặc xuất hiện lại sau khi đóng hộp thoại.
- Phân biệt lỗi tải/dữ liệu hết hiệu lực với danh sách thực sự rỗng. Lỗi cho thử lại, không hiện thông báo đã test hết app.
- Kiểm tra hiệu lực lần nữa khi bấm Test ngay; gợi ý đã cũ không được dùng để điều hướng.
- Đếm lượt gọi thống kê tại đúng chỗ thực hiện yêu cầu mạng, không đếm trùng khi dùng bộ nhớ đệm hoặc hợp nhất yêu cầu.
- Giữ cách sắp xếp Trang chủ, chính sách thưởng và việc nhận báo cáo khi app đã đủ chỉ tiêu.

## 3. Kết quả kiểm tra thực chạy

| Kiểm tra | Kết quả | Bằng chứng |
|---|---|---|
| `gradlew.bat testDebugUnitTest assembleDebug --offline` | BUILD SUCCESSFUL; 644 tests / 82 classes; 0 failures, 0 errors, 0 skipped | `runs/reviewer-full-android-final.log`, `final-test-summary.json` |
| Bộ chọn bốn nhóm | 15/15 qua, nằm trong lượt chạy đầy đủ | `NextCampaignSelectorTest` |
| Luồng tìm trước, lỗi và điều hướng | 13/13 qua, nằm trong lượt chạy đầy đủ | `CampaignDetailNextAppPriorityTest` |
| Tương thích RPC, thống kê theo lô và ràng buộc đồng bộ | 12/12 qua, nằm trong lượt chạy đầy đủ | `EgressFinalClientReviewTest` |
| `node supabase/tests/run-egress-sync-v2-test.mjs` | Qua; áp dụng toàn bộ migrations hiện có, gồm 086, trong database kiểm thử cục bộ | `runs/reviewer-final-sql-2026-11-11.log` |
| `node supabase/tests/run-explicit-report-context-test.mjs` | Qua các ca của runner hiện hữu | `runs/reviewer-report-context.log` |
| `node supabase/tests/run-home-stats-cycle-test.mjs` | 14/14 qua theo phạm vi runner hiện hữu | `runs/reviewer-home-cycle.log` |
| Kiểm tra khoảng trắng trong phần thay đổi so với bản trước tác vụ | Không có lỗi | `runs/reviewer-diff-check.log` |

40 kiểm thử liên quan trực tiếp nằm trong tổng 644, không cộng hai lần. Các runner báo cáo/ngày và chu kỳ Trang chủ là kiểm tra hồi quy theo phạm vi riêng của chúng; runner SQL ứng viên là lượt xác minh có migration 086.

SQL đã kiểm chứng app đạt 15/15 và vượt 16/15 vẫn nằm trong tập ứng viên. Một tester mới nộp báo cáo thật qua `submit_tester_report_v2` vào app đã vượt chỉ tiêu vẫn được ghi check-in và trả thưởng tạm tính 10 theo cấu hình fixture; sau đó app bị loại khỏi gợi ý của chính tester đó trong ngày.

Các ca Android bao gồm biên 12/15/20, app đã cài đủ chỉ tiêu thua app chưa cài còn thiếu, chỉ còn app đủ chỉ tiêu, tài khoản mới, báo cáo chờ gửi, lỗi tải, chuyển ngày/phiên, dữ liệu thay đổi trong lúc tải, phản hồi máy chủ cũ, thống kê thiếu/cũ và chặn điều hướng bằng gợi ý hết hiệu lực.

Trong quá trình sửa đã phát hiện và xử lý lỗi biên dịch test, một ca kiểm thử chờ bất đồng bộ bị treo, lỗi đếm thiếu lượt gọi mạng và các nhánh xử lý dữ liệu cũ. Log các lần lỗi được giữ lại; kết quả cuối cùng là lượt chạy đầy đủ thành công nêu trên. Một số lượt Gradle bên trong Antigravity không kết nối được daemon; lượt nghiệm thu cuối do bộ điều khiển chạy độc lập thành công. Log còn có cảnh báo biên dịch không chặn build; không có lỗi build hoặc kiểm thử cuối.

## 4. Tệp nguồn và kiểm thử thay đổi

Các đường dẫn dưới đây tính từ `F:/AI/12Tester`:

- `app/src/main/java/com/ntt/crosstest/data/Models.kt`
- `app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt`
- `app/src/main/java/com/ntt/crosstest/ui/viewmodels/CampaignDetailViewModel.kt`
- `app/src/main/java/com/ntt/crosstest/ui/screens/CampaignDetailScreen.kt`
- `app/src/main/java/com/ntt/crosstest/util/NextCampaignSelector.kt` — mới
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-en/strings.xml`
- `app/src/main/res/values-vi/strings.xml`
- `app/src/test/java/com/ntt/crosstest/data/EgressFinalClientReviewTest.kt`
- `app/src/test/java/com/ntt/crosstest/ui/viewmodels/CampaignDetailNextAppPriorityTest.kt` — mới
- `app/src/test/java/com/ntt/crosstest/util/NextCampaignSelectorTest.kt` — mới
- `supabase/migrations/086_next_campaign_candidate_daily_target_priority.sql` — mới
- `supabase/tests/run-egress-sync-v2-test.mjs`

Tổng cộng 13 tệp nguồn/kiểm thử thay đổi: 9 tệp sửa, 4 tệp mới. Danh sách: `final-changed-files.json`. Bản đối chiếu 313 tệp trước tác vụ còn nguyên vẹn theo SHA-256; không reset, clean, commit hoặc push dự án.

## 5. Bản dựng và triển khai

- APK debug: `F:/AI/12Tester/app/build/outputs/apk/debug/app-debug.apk`.
- Kích thước: 32.427.829 byte.
- SHA-256: `D0901AEEEFDBEF1F33A1362381C2E230BA132BC48AB67DCC8AA2449DA4A715BF`.

Khi triển khai thật: đối chiếu hàm hiện tại trên máy chủ, áp dụng migration 086 trước, rồi phát hành Android đã sửa. Phản hồi bổ sung trường vẫn tương thích với client cũ bỏ qua trường lạ; client mới có đường tải thống kê theo lô khi máy chủ chưa trả trường bổ sung.

Nếu cần quay lui hành vi gợi ý, dùng bản Android trước thay đổi; có thể giữ các trường bổ sung của RPC. Tác vụ này chưa ghi thay đổi vào database sản xuất hoặc phát hành bản ứng dụng.

## 6. Dấu vết Antigravity CLI

- Mô hình đã chọn và được CLI xác nhận: `gemini-3.8-flash-high`; `--effort high`.
- Phiên triển khai ban đầu: `b47c7667-bc93-4acb-b6f3-175f6f49b406`.
- Phiên sửa kiểm thử tập trung: `e8d422a0-5d25-4e67-bdd6-a7fcaf3af03a`.
- Phiên hoàn tất các sửa cuối: `a156386f-f531-4304-9506-306e96ad5f0e`.
- Nhật ký CLI và kết quả từng bước nằm trong `runs/`, `STEP1_RESULT.md`, `STEP2_RESULT.md`.
