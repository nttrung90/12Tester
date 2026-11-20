# Kế hoạch sửa nhiệm vụ ngày bị kẹt cập nhật

Ngày lập: 15/11/2026. Mã hóa: UTF-8.

**Cập nhật nghiệm thu:** Đã thực hiện và kiểm thử cục bộ ngày 15/11/2026: 18/18 ca tập trung và 762/762 ca Android toàn bộ đạt. Xem `BAO_CAO_SUA_NHIEM_VU_NGAY_2026-11-15.md` để biết bản sửa cuối, bằng chứng và giới hạn xác minh production.

## 1. Mục tiêu và phạm vi

Sửa tình trạng thẻ nhiệm vụ ngày luôn chờ cập nhật và chưa ghi nhận hoàn thành sau khi người dùng đạt chỉ tiêu. Thực hiện sửa mã và kiểm thử trong dự án `F:\AI\12Tester`; giữ phiên bản 1.2.5, build 45; không build APK/AAB. Không thay đổi chính sách tính lượt test, nợ bảo lưu, Credit hoặc điều kiện xóa/chuyển production/bật lại app để che lỗi.

File này thay kế hoạch ngày 14/11 đã bị hỏng dấu tiếng Việt. Thực hiện các bước dưới đây, không chỉ trả lại đề xuất. Không tuyên bố hết lỗi trên production nếu mới kiểm thử cục bộ.

## 2. Căn cứ đã kiểm tra lại

| Vị trí | Hiện trạng | Việc cần chứng minh hoặc sửa |
|---|---|---|
| `CampaignRepository.getMyDailyTaskStatus` | Bắt mọi Exception và trả null, gồm cả cancellation | Truyền tiếp cancellation; phân biệt lỗi tải với kết quả hợp lệ; không tạo hoàn thành giả |
| `CampaignRepository.invalidateReportsAndStats` | Chưa tăng mutation version của daily_task | Báo cáo daily đã commit phải vô hiệu hóa trạng thái cũ và làm mới nhiệm vụ, cả gửi trực tiếp và gửi nền |
| `HomeViewModel.observeSubmittedReports` | Thành công chỉ đánh dấu campaign và refresh toàn Home | Chứng minh lượt cuối cập nhật nhiệm vụ ngay, không phụ thuộc cache cũ |
| `HomeViewModel.checkAndSchedulePendingCarryoverRetry` | Chỉ cập nhật dailyTask và missedStreak; chưa dùng cùng đường nhận snapshot/cache của cập nhật app | Bảo vệ UID, session, ngày, mutation và thứ tự; cập nhật pending và cache nhất quán |
| `HomeViewModel.scheduleDailyTaskRetry` | Hết 5 lần có thể vẫn giữ cờ pending | Kết thúc đợt tải bằng trạng thái lỗi hoặc chờ có lý do và nút Thử lại |
| `DailyTaskCard` | Gộp pending lịch sử với pending làm mới; ưu tiên met=false | Hiển thị đúng nguồn chờ, dữ liệu đã nhận và lỗi tải; chỉ hoàn thành theo kết quả máy chủ hợp lệ |
| Migration 080, 082, 088, 090 | Thiếu bản chốt hôm qua hoặc journal unresolved có thể khiến met=false dù D đã đủ | Giữ tính đúng của lịch sử; xử lý chốt bù/đối soát theo nguồn thật, không ép ready/met hoặc xóa nợ |

Ba ca fixture ngày 14/11 chứng minh nhánh SQL: (a) đủ 1/1 nhưng thiếu bản chốt hôm qua: chưa hoàn thành; (b) cùng tiến độ, có checkpoint đáng tin: hoàn thành; (c) journal unresolved: chưa hoàn thành. Đây là đối chứng logic, chưa chứng minh nguyên nhân tài khoản production. Script: `audit/daily-task-stuck-2026-11-14/reproduce-pending-status.mjs`.

## 3. Trình tự thực hiện

### Bước A — Ghi baseline và tái hiện

1. Đọc mã hiện tại và các test liên quan. Workspace có nhiều thay đổi trước đó: không reset, checkout, clean, xóa hoặc ghi đè thay đổi ngoài phạm vi. Lưu hash/diff của tập file định sửa vào `audit/daily-task-stuck-2026-11-15/`.
2. Tái chạy ba ca SQL đối chứng. Thêm test hành vi tái hiện: pending refresh cùng pending carryover; lượt daily cuối; timeout/lỗi RPC; phản hồi cũ tới sau mutation; logout/đổi UID/ngày. Ưu tiên test gọi hành vi thật, không chỉ tìm chuỗi mã.
3. Kiểm tra chỉ đọc kết nối production nếu đã có công cụ/quyền sẵn: phiên bản function, cron và lần chạy lỗi, ngày thiếu checkpoint, journal unresolved. Không in token, key hoặc dữ liệu cá nhân. Không có truy cập thì ghi rõ và tiếp tục sửa các lỗi cục bộ đã chứng minh; không để bước này chặn toàn bộ công việc.

### Bước B — Sửa đường cập nhật Android

1. Giữ chữ ký tương thích nơi cần; bổ sung kết quả lỗi có kiểu hoặc exception được xử lý rõ. Luôn rethrow CancellationException. Có giới hạn thời gian cho RPC.
2. Sau daily report commit, tăng phiên bản daily_task và dashboard đúng một quy trình chung rồi lấy trạng thái mới. Không biến lỗi tải sau commit thành lỗi nộp báo cáo; không tự cộng lượt cho mission hoặc hàng đợi chưa gửi thành công.
3. Đưa retry lịch sử và retry sau mutation về một đường công bố snapshot có kiểm tra UID/session/ngày/mutation/thứ tự yêu cầu. Không cho phản hồi cũ ghi đè trạng thái mới; chặn cả full/delta dashboard/cache cũ. Cập nhật cache và cờ pending trong cùng quy trình.
4. Retry hữu hạn, có timeout. Hết đợt phải cho phép Thử lại; giữ tiến độ gần nhất kèm trạng thái chưa xác minh. Khởi động lại hợp lý khi Home resume, mạng phục hồi, gửi thành công và sang ngày mới; tránh nhiều vòng tải trùng nhau. Hủy khi đổi phiên hoặc ViewModel bị hủy.
5. UI phân biệt đang tải, đang đồng bộ sau thao tác, đang xác minh lịch sử và lỗi tải. Khi server hợp lệ trả met=true, gỡ pending và hiện hoàn thành. Pending lịch sử hợp lệ vẫn hiện tiến độ và nguyên nhân. Null/thiếu trường không được tự mở action gate. Thêm chuỗi default/vi/en và nối nút Thử lại tới hành vi thật.

### Bước C — Backend chỉ sửa theo bằng chứng

1. Nếu test chứng minh lỗi finalizer hoặc cache version máy chủ: thêm migration tiếp theo sau số lớn nhất hiện có; không sửa migration đã phát hành. Đọc LOCK_ORDER.md trước khi sửa khóa/finalizer.
2. Chốt bù phải dùng finalizer có sẵn, theo ngày và checkpoint đáng tin, có giới hạn batch và idempotency. Không chạy finalizer toàn hệ thống từ thao tác mở thẻ Android. Journal unresolved chỉ được đối soát bằng nguồn thực.
3. Bổ sung truy vấn chẩn đoán chỉ đọc và hướng dẫn triển khai/khôi phục có phạm vi cụ thể. Không tự sửa dữ liệu thật, đặt nợ bằng 0, tạo checkpoint giả hoặc triển khai production khi chưa xác định rõ tác động và đối tượng. Nếu chưa truy cập được production, bàn giao rõ việc chưa kiểm chứng.
4. Nếu thay đổi khóa hoặc finalizer, kiểm thử PostgreSQL nhiều kết nối; PGlite không đủ để chứng minh tính đúng đồng thời.

## 4. Ma trận nghiệm thu bắt buộc

| Ca | Kết quả cần đạt |
|---|---|
| Daily từ N-1 sang N, server ready | Server trả met=true; Home tự hiện hoàn thành; pending=false; cache không kéo lùi |
| Gửi nền và gửi trực tiếp | Cùng cập nhật nhiệm vụ sau commit; giữ Success nếu tải trạng thái sau đó lỗi |
| Mission, báo cáo trùng, chưa commit | Không tự tăng lượt daily hoặc mở gate |
| Hai loại pending đồng thời | Kết quả mới hợp lệ xóa đúng pending; cache và UI nhất quán |
| Timeout, offline, RPC/JSON lỗi | Thoát spinner không kết thúc, hiện Thử lại; phục hồi được sau lần tải thành công |
| Thiếu chốt hôm qua | Giữ tiến độ; sau chốt bù hợp lệ chuyển hoàn thành; không mất nợ |
| Journal unresolved | Giữ chờ có lý do; không ép hoàn thành; chỉ ready sau đối soát hợp lệ |
| Response cũ sau report/app mutation | Không ghi đè trạng thái nhiệm vụ mới, kể cả dashboard delta/full và retry |
| Đổi UID/session/ngày Việt Nam | Hủy/bỏ phản hồi cũ; không lẫn dữ liệu tài khoản hoặc ngày |
| Action gate 090 và nợ bảo lưu | Vẫn fail-closed khi chưa xác minh/còn nợ; mở đúng khi server cho phép |
| Default/vi/en, version | Không hỏng dấu; không thiếu resource; version 1.2.5/build 45 không đổi |

## 5. Chạy kiểm tra và bàn giao

1. Test Android hành vi mới và các nhóm liên quan: PendingCarryoverRetryRegressionTest, OwnerDailyRefreshRaceReviewTest, OwnerDailyTaskOrderingReviewTest, SupervisorDailyTaskStatusCacheReviewTest, DailyTaskActionGateUnitTest, ImmediateDailyQuotaUnitTest, luồng report submission. Sau đó chạy `gradlew.bat testDebugUnitTest`. Không chạy assemble/bundle APK/AAB.
2. Backend: ba ca đối chứng; carryover acceptance; immediate quota acceptance; egress sync v2; action gate và production lifecycle liên quan. Dùng môi trường thử cô lập. Nếu thay SQL, bổ sung ca mới thật sự thực thi function.
3. Lưu lệnh, exit code, số ca và log trong audit. Lỗi có sẵn phải phân biệt với lỗi do bản sửa; không bỏ qua hoặc sửa assertions để che hành vi sai. Sửa tiếp và chạy lại nhóm thất bại cho tới khi đạt hoặc có trở ngại cụ thể đã chứng minh.
4. Ghi `audit/daily-task-stuck-2026-11-15/RESULT.md` bằng UTF-8: nguyên nhân, file sửa, test trước/sau, phần chưa kiểm chứng, triển khai cần thiết. Ghi rõ đường dẫn workspace thực tế nếu Antigravity dùng worktree.
5. Người giám sát sẽ đọc diff và chạy lại test độc lập; nếu còn lỗi sẽ giao sửa tiếp. Chỉ kết luận những gì có bằng chứng; không hứa mọi lỗi ứng dụng đã hết dựa vào vài ca unit test.
