# Kế hoạch sửa chồng lấn test hằng ngày và nhiệm vụ app

## 1. Mục tiêu và bằng chứng

Người dùng mở app để test hằng ngày phải gửi báo cáo app_test và nhận Credit theo quy tắc test thường. Chỉ thao tác chủ động mở một nhiệm vụ cụ thể mới tạo báo cáo mission. Việc đã tham gia nhiệm vụ không được tự đổi loại bài nộp.

Đã xác nhận trong mã hiện tại:

- CampaignDetailViewModel.resolveMissionContext tự chọn mission cho bài mới khi người dùng đã join, dù không có initialMissionId.
- RPC submit_tester_report tự gắn mission khi p_mission_id trống và có đúng một nhiệm vụ đang tham gia còn mở.
- Báo cáo đã tồn tại trong ngày được dùng làm nguồn quyết định loại bài; do đó báo cáo mission bị gắn nhầm có thể tiếp tục kéo màn test thường sang mission.
- Mọi MISSION_NOT_OPEN đang được dịch thành “Đã dừng (hết Credit)”, kể cả nhiệm vụ do chủ app tạm dừng.
- Probe `supabase/tests/probe-daily-mission-overlap.mjs` đã tái hiện trên baseline 062 + 063: không gửi missionId vẫn thành mission; gửi ID nhiệm vụ paused_by_creator bị MISSION_NOT_OPEN. Ca gửi thành công vẫn có provisional_amount=10. Không kết luận mọi báo cáo mission đều mất Credit; ảnh người dùng thể hiện lần gửi bị từ chối.

Migration 063 chỉ sửa khả năng tìm digest, không giải quyết các lỗi trên. Chưa có dữ liệu production để xác định toàn bộ bài nộp bị ảnh hưởng.

## 2. Hợp đồng loại bài nộp — ưu tiên P0

Thêm RPC riêng có phiên bản, đề xuất `submit_tester_report_v2`, yêu cầu rõ `p_submission_context`:

| Ngữ cảnh | missionId | Hành vi |
|---|---|---|
| app_test | null | Test thường, không tìm nhiệm vụ để tự gán |
| mission | ID cụ thể | Kiểm tra campaign, quyền tham gia, trạng thái và thời hạn đúng nhiệm vụ |
| app_test + có ID hoặc mission + thiếu ID | không hợp lệ | Từ chối trước khi ghi báo cáo/Credit |
| Giá trị loại không hợp lệ | bất kỳ | Từ chối |

Dùng tên RPC riêng để tránh nhập nhằng overload PostgREST và phân biệt rõ client mới/cũ. Client v2 không fallback sang RPC cũ vì có thể làm mất ngữ cảnh.

Giữ quy tắc hiện tại về số bài nộp theo user/campaign/ngày trong phạm vi bản sửa. Nếu đã có báo cáo khác loại hoặc khác mission, trả lỗi xung đột rõ ràng; không tự chuyển loại, không âm thầm cập nhật bài khác. Cho phép hai luồng tạo hai bài độc lập cùng ngày là thay đổi nghiệp vụ riêng, chưa nằm trong bản sửa này.

## 3. Backend — ưu tiên P0

Tạo migration mới sau 063, dự kiến 064 nếu số này còn trống khi triển khai. Không sửa lịch sử 062/063.

1. Triển khai RPC v2 theo hợp đồng trên. Có thể dùng hàm nội bộ chung nhưng phải kiểm tra quyền gọi và không để API cũ tiếp tục suy đoán loại bài ở nhánh mới.
2. Áp cùng kiểm tra loại/mission ở mọi nhánh: tạo mới, cập nhật pending, nộp lại rejected, bổ sung comment approved và replay. Kiểm tra lại sau khóa chống tranh chấp.
3. Idempotency: đưa loại bài và phiên bản fingerprint vào dữ liệu nhận dạng payload của v2. Lưu provenance/version rõ ràng; không ghi đè fingerprint hay response của receipt cũ. Cùng ID nhưng đổi loại/mission phải bị từ chối. Replay đã commit phải trả receipt trước các kiểm tra trạng thái nhiệm vụ hiện tại phù hợp với hợp đồng cũ.
4. RPC cũ: bảo toàn replay đã commit. Với yêu cầu mới không có missionId, bỏ nhánh tự tìm nhiệm vụ. Yêu cầu mới có missionId từ client cũ có thể là tự gán nên không đủ bằng chứng ý định người dùng; yêu cầu cập nhật ứng dụng trước khi nhận bài mission mới qua endpoint cũ. Kiểm thử và công bố tác động tương thích này khi triển khai.
5. Kiểm tra Credit/checkin/credit_logs/mission_participants theo từng loại, giữ giao dịch nguyên tử. Không cộng bù chỉ vì báo cáo mang nhãn mission.
6. Trả mã trạng thái nhiệm vụ chính xác: tạm dừng, dừng vì thiếu Credit, hết hạn, bị xóa. Đối với trạng thái full, kiểm tra quy tắc dành cho người đã tham gia trước khi quyết định có cho nộp hay không.
7. Giữ search_path phù hợp pgcrypto và giữ ACL/SECURITY DEFINER an toàn như bản sửa 063.

## 4. Android — ưu tiên P0

- Navigation: trang chủ/test hằng ngày/app tiếp theo mở app_test; màn nhiệm vụ mở mission với ID cụ thể. Rà cả deep link, notification, lịch sử và khôi phục màn hình.
- CampaignDetailViewModel: bỏ tự suy mission từ hasJoined hoặc mission mới nhất. Có thể hiển thị thông tin nhiệm vụ riêng nhưng không dùng nó để đổi biểu mẫu test thường.
- Báo cáo đã lưu khác loại: hiển thị xung đột và đường mở đúng báo cáo; không lấy loại của báo cáo đó ghi đè mục đích của màn đang mở.
- Models, SubmitRequest, PendingReport, ReportSubmitManager, ReportUploadWorker, CampaignRepository: truyền và lưu loại bài xuyên suốt đến RPC v2. Khóa snapshot loại/mission cùng ảnh, nội dung và request ID khi tiếp nhận. Retry giữ nguyên snapshot.
- PendingReport cũ thiếu loại phải được nhận diện là legacy/unknown, không mặc định app_test hoặc suy mission từ ID rồi gửi tự động.
- UI: tiêu đề và thông báo ghi rõ “Test hằng ngày” hoặc tên nhiệm vụ; reward hiển thị từ kết quả server. Thông báo MISSION_NOT_OPEN cũ dùng nội dung trung tính, không mặc định thiếu Credit. Bổ sung thông báo Việt/Anh cho các mã mới.
- Khi người dùng chuyển loại lúc bài cũ đang gửi, chỉ đổi màn/nháp mới; không sửa payload của work đang hoạt động.

## 5. Hàng đợi cũ và dữ liệu đã gắn nhầm — ưu tiên P0

Không thể suy chắc chắn ý định ban đầu chỉ từ missionId vì phiên bản cũ có thể tự gán.

1. Phân biệt yêu cầu đã có receipt, chưa commit được xác nhận và chưa rõ kết quả. Cần đường reconcile có xác thực theo user/request ID trước khi thay thế yêu cầu.
2. Đã có receipt: trả đúng kết quả cũ, không đổi loại dưới cùng request ID. Nếu cần sửa báo cáo đã gắn nhầm, đưa vào quy trình đối soát quản trị.
3. Đã xác nhận chưa commit: cho người dùng chọn đúng loại, giữ nháp/ảnh. Hủy và chờ work cũ dừng, đánh dấu yêu cầu được thay thế, rồi tạo request ID mới. Backend phải ngăn yêu cầu cũ commit muộn bằng cơ chế supersede/khóa giao dịch; chỉ hủy WorkManager không đủ bảo đảm.
4. Kết quả chưa rõ hoặc offline: giữ nguyên dữ liệu, chờ reconcile; không tự tạo yêu cầu mới và gửi song song.
5. Rà dữ liệu production bằng truy vấn chỉ đọc: report, receipt, checkin, credit_logs, mission participation và mốc thời gian. Xuất danh sách ứng viên để xác minh, không tự coi tất cả mission report là gắn nhầm.
6. Nếu cần sửa dữ liệu đã commit: migration/operation quản trị riêng có audit, chạy dry-run trước, bảo toàn lịch sử receipt và khóa chống thao tác trùng. Credit chỉ điều chỉnh phần chênh lệch có bằng chứng; không cấp đồng thời thưởng thường và thưởng nhiệm vụ trái quy tắc.

## 6. Kiểm thử bắt buộc

### Database

- Test thường khi chưa join, đã join một hoặc nhiều nhiệm vụ: luôn app_test.
- Nhiệm vụ open/paused/stopped/expired/deleted không ảnh hưởng bài test thường mới.
- Mission chỉ được nộp với đúng ID, campaign và quyền tham gia; kiểm tra người đã join khi mission full.
- Báo cáo existing pending/rejected/approved khác loại: lỗi xung đột, không mutation/Credit ngoài ý muốn.
- Replay cùng ID/payload: một report, một tác dụng tài chính. Đổi context/mission dưới cùng ID: từ chối.
- Mất phản hồi sau commit, replay sau khi mission hết hạn, A → B → replay A: bảo toàn receipt và dữ liệu mới.
- Legacy receipt trước/sau nâng cấp, client cũ, legacy request chưa commit và supersede race.
- Kiểm tra Credit cùng checkin và credit_logs trước/sau; các ca đã nhận thưởng hôm nay không được cộng lần hai.
- pgcrypto ở public/extensions, admin/member, ACL và migration rerun.
- Race submit/retry/supersede dùng PostgreSQL nhiều kết nối; không dùng PGlite đơn kết nối làm bằng chứng đầy đủ về race.

### Android và thiết bị

- Mở từ test hằng ngày không gọi đường tự chọn mission dù hasJoined=true.
- Mở từ nhiệm vụ có đúng context/ID qua navigation → pending store → Worker → RPC.
- Process restart, logout, đổi tài khoản, bấm gửi nhiều lần, chuyển app và retry không đổi loại hoặc mất nháp.
- JSON pending cũ thiếu context đi vào reconcile, không được tự gửi dưới loại đoán định.
- Server chưa có v2: báo cần cập nhật server, không fallback làm mất loại bài.
- Thông báo Việt/Anh và số thưởng đúng với receipt thực tế.
- Tái hiện thao tác trong ảnh trên tài khoản đã join nhiệm vụ của Pool Training hoặc campaign thử nghiệm tương đương; đối chiếu loại report và Credit phía server.

## 7. Thứ tự thực hiện và nghiệm thu

1. Viết regression tái hiện lỗi hiện tại, chốt hợp đồng v2 và quy tắc tương thích legacy.
2. Sửa backend, version fingerprint và đường reconcile/supersede; kiểm thử trước/sau migration.
3. Sửa Android và kiểm thử truyền ngữ cảnh xuyên suốt, không chỉ sửa badge giao diện.
4. Kiểm thử tích hợp và nâng cấp từ APK/pending store cũ trên môi trường thử nghiệm.
5. Chuẩn bị migration, APK và tài liệu triển khai; triển khai backend trước rồi phát hành client mới. Không tự tăng version/build ngoài yêu cầu riêng; source hiện tại đã là 1.0.5/25 và cần số build mới trước một đợt phát hành cập nhật thực tế.
6. Đối soát dữ liệu bị ảnh hưởng, theo dõi lỗi xung đột/legacy và kiểm tra báo cáo thực tế.

Hoàn tất khi test thường luôn lưu app_test, nhiệm vụ chỉ nhận bài có ý định rõ, các lần retry không cộng trùng, dữ liệu cũ được reconcile an toàn và đã xác minh trên môi trường đang lỗi. Nếu mới qua local test phải ghi rõ chưa nghiệm thu production.
