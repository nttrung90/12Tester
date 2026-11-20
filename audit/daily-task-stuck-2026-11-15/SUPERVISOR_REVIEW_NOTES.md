# Ghi chú giám sát bản sửa đang thực hiện

## R1 — Timeout của chính request đang bị coi như hủy từ bên ngoài

Trong bản CampaignRepository đang được sửa lúc 18:02 ngày 15/11, các hàm getMyDailyTaskStatus, fetchMyDailyTaskStatus và refreshDailyTaskAfterCampaignChange dùng withTimeout rồi rethrow mọi CancellationException. TimeoutCancellationException của chính giới hạn 10 giây cũng đi vào nhánh này. Nếu caller coi cancellation là hủy lifecycle, nó có thể thoát retry mà không công bố lỗi/Thử lại, tiếp tục giữ pending.

Cần phân biệt timeout nội bộ với hủy từ bên ngoài. Có thể dùng withTimeoutOrNull cho chính request và chuyển kết quả hết giờ thành lỗi tải thông thường; cancellation của parent/lifecycle vẫn phải truyền tiếp. Không bắt và nuốt mọi CancellationException.

Đã thêm 3 ca độc lập trong `app/src/test/java/com/ntt/crosstest/data/SupervisorDailyTaskRecoveryReviewTest.kt`:

1. Invalidation báo cáo làm tăng phiên bản daily_task.
2. Cancellation do request truyền lên không biến thành null.
3. Request treo bị timeout có giới hạn nhưng không làm caller bị canceled; API nullable tương thích trả null cho lỗi tải.

Hãy chạy cả class, lưu kết quả và sửa tiếp nếu còn thất bại. Nếu cần thay contract tương đương, giải thích trước khi thay ý nghĩa assertion.

## R2 — Không dùng met từ snapshot cũ để bỏ qua pending

Bản nháp DailyTaskCard đang dùng `isPending = (pendingCarryover || isPendingRefresh) && !serverMet` và loại bỏ nhánh `isPending -> false` trước khi đọc `dailyTask.isMet`. Điều này có thể hiện hoàn thành từ snapshot cũ khi vừa đăng thêm app làm tăng chỉ tiêu, hoặc khi đang phải xác minh lịch sử. Nó chỉ che cờ pending trên UI chứ chưa chứng minh dữ liệu mới hợp lệ.

Giữ guard pending cho tới khi snapshot mới đã được xác nhận và ViewModel/cache gỡ cờ. Không dùng met cũ để vượt pending. Nếu met=true nhưng readiness chưa hợp lệ, không được hiện hoàn thành owner. Kiểm thử ca: hôm nay đang hoàn thành, mutation mới tăng quota, request mới chưa trả hoặc lỗi — thẻ phải chờ/cho Thử lại, sau snapshot mới hợp lệ mới xác nhận hoàn thành/chưa hoàn thành.

## R3 — Hai đường vẫn có thể hết tải mà giữ pending vô hạn

Trong HomeViewModel đang sửa:

- `triggerDailyTaskReloadAfterReport` nhận null từ API nullable rồi gọi publisher với status=null, error=null, isRetryExhausted=false. Publisher không thay trạng thái và không lên lịch retry. Khi dashboard cũng lỗi, pendingRefresh=true bị giữ mãi.
- `checkAndSchedulePendingCarryoverRetry` vẫn kết thúc vòng 13 lượt trong finally mà chưa công bố lỗi/chờ có nút Thử lại. Request trả pending hợp lệ suốt cả đợt vẫn không có khả năng phục hồi bằng nút.

Cần kiểm tra cả null/lỗi và hết đợt vẫn pending, không chỉ trường hợp có snapshot sẵn sàng. Có giới hạn request, đợt retry và đường manual retry thật sự.

## R4 — Thứ tự chỉ áp dụng cho vài direct request, chưa chặn dashboard/cache

`publishDailyTaskSnapshot` chưa tăng `SessionCacheManager.currentDailyTaskVersion` hoặc sử dụng cùng sequence với full refresh/dashboard/event Updated. Full dashboard bắt đầu sau report mutation nhưng trước khi direct task trả về có thể trả snapshot cũ muộn hơn; kiểm tra `initialDailyTaskVersion` của full refresh vẫn bằng phiên bản hiện tại, nên dữ liệu cũ có thể ghi đè snapshot mới.

Ca kiểm thử: report mutation → direct task request bắt đầu và chờ → full dashboard bắt đầu, chụp trạng thái cũ và chờ → direct task trả met=true → full dashboard cũ trả met=false. UI/cache phải giữ met=true. Đồng thời repository refresh cùng mutation đang công bố cache/events trước khi ViewModel kiểm tra requestSeq; không chỉ chặn UI mà để cache hoặc Updated event cũ ghi đè.

Ngoài ra status!=null nhưng forCurrentUi trả null/không hợp lệ không được tự xóa pending và coi là công bố thành công. Xác minh ngày và trường bắt buộc trước khi công bố snapshot.

Đã thêm `app/src/test/java/com/ntt/crosstest/ui/viewmodels/SupervisorDailyTaskHomeRecoveryReviewTest.kt`: 3 ca HTTP fixture + ViewModel + coroutine clock cho report reload lỗi rồi phục hồi, hết đợt chờ lịch sử, dashboard cũ tới sau completion mới. Chạy cùng 3 ca repository phía trên (tổng 6 ca giám sát). Fixture có chờ observable state vì single-flight repository dùng Dispatchers.IO thật.

Lần chạy 11:21:26Z: 3 ca Home đều timeout ở `awaitState` khởi tạo baseline, chưa tới assertion nghiệp vụ. Người giám sát đã bổ sung diagnostics requests/V2 flags/uiState khi await hết giờ để xác định fixture hay app; không bỏ điều kiện/assertion để che lỗi. Có thể sửa fixture khi chứng minh nó chưa đúng giao thức hoặc scheduling thực tế.

Fixture Home trả payload legacy nên đã reset rõ các feature flag V2 về false và capability cache ở setup/teardown để tránh ảnh hưởng từ lớp test khác. Không thay đổi assertion hành vi; vẫn cần rerun/đọc diagnostics nếu baseline chưa khởi tạo được.

Đã xác định nguyên nhân fixture từ diagnostics lần 11:37:34Z: dashboard không có quest_summary nên Home gọi get_user_quest_summary; nhánh mặc định fixture trả [] trong khi getQuestSummary giải mã QuestSummary là object. Full refresh rơi vào catch trước khi công bố dailyTask. Đã thêm quest_summary:{} và response {} cho fallback quest, giữ toàn bộ assertions. Không tính 3 timeout baseline này là lỗi sản phẩm.

## Phần cần kiểm tra tiếp khi hoàn tất diff

- **R5, UI null fallback:** DailyTaskCard vẫn có `else -> todayCheckinCount >= 1` khi dailyTask=null. Nếu chưa tải được trạng thái chủ app mà bộ đếm cũ là 1, nhánh này có thể hiện hoàn thành dù không biết quota/nợ. Không xác nhận hoàn thành từ counter khi thiếu snapshot; hiển thị chưa tải được trạng thái và cho Thử lại. Kiểm tra cả tải lần đầu/dashboard không có block nhiệm vụ, không chỉ sau report. Đây cũng là yêu cầu trong kế hoạch mới.

- Gỡ cả pending refresh khi carryover đã ready; UI và cache cùng snapshot.
- Đợt retry kết thúc bằng trạng thái phục hồi được; nút Thử lại phải nối tới hành vi tải thật.
- Response cũ từ retry/full dashboard/delta không ghi đè kết quả sau mutation.
- Không tăng số lượt daily cho mission và report chưa commit.
- Không đổi phiên bản, chính sách, Credit, migration đã phát hành hoặc dữ liệu production.
