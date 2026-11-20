# Kế hoạch hạ ưu tiên gợi ý app đã đủ chỉ tiêu test

- Ngày: 10/11/2026.
- Trạng thái: đã sửa mã, tạo migration 086 và hoàn tất kiểm tra tự động cục bộ ngày 11/11/2026: 644 kiểm thử Android qua, dựng debug thành công, kiểm thử SQL qua. Chưa triển khai máy chủ thật hoặc kiểm tra trên thiết bị thật.
- Phạm vi: gợi ý app tiếp theo trong hộp thoại sau khi nộp báo cáo.
- Căn cứ: mã Android và migrations hiện có trong workspace. Khi triển khai cần đối chiếu định nghĩa hàm thực tế trên môi trường đích.

## 1. Quy tắc nghiệp vụ

App đạt hoặc vượt mục tiêu 12, 15, 20 lượt test trong ngày vẫn nhận thêm lượt test và thưởng theo chính sách đang áp dụng. Thay đổi duy nhất về nghiệp vụ là ưu tiên gợi ý app còn thiếu chỉ tiêu trước.

Danh sách đủ điều kiện vẫn loại app hiện tại, app của chính tester, app không mở, app tester đã test hôm nay và app có báo cáo test thường đang chờ gửi cục bộ. Không thêm app đủ chỉ tiêu vào danh sách loại bỏ.

Sau khi lọc, chọn app từ nhóm ưu tiên cao nhất còn ứng viên:

| Ưu tiên | Trạng thái chỉ tiêu hôm nay | Trên máy tester | Tester đã test app hôm nay? |
|---|---|---|---|
| 1 | Chưa đủ | Đã cài | Chưa |
| 2 | Chưa đủ | Chưa cài | Chưa |
| 3 | Đã đủ hoặc vượt | Đã cài | Chưa |
| 4 | Đã đủ hoặc vượt | Chưa cài | Chưa |

- Chỉ chọn nhóm 2 khi nhóm 1 rỗng; chỉ chọn nhóm 3 hoặc 4 khi cả nhóm 1 và 2 rỗng.
- Với tester thông thường: chọn ngẫu nhiên trong nhóm được chọn.
- Với gợi ý sau bài test đầu tiên của tài khoản chưa từng test: giữ tiêu chí số người tham gia/cài thấp nhất, nhưng chỉ xét trong nhóm được chọn; bằng nhau thì ngẫu nhiên. Tiêu chí này không được vượt qua thứ tự bốn nhóm.
- Boost, Tip và lượt đẩy app không thay đổi thứ tự bốn nhóm trong hộp thoại này.
- “Chưa được test” được hiểu là tester hiện tại chưa test app đó trong ngày Việt Nam. Không có nghĩa là app chưa từng có tester.
- Chỉ hiển thị gợi ý chưa được tính là đã test. Đóng hộp thoại có thể khiến app đó được gợi ý lại ở lần sau; kế hoạch này không bổ sung lịch sử app đã được gợi ý.

Ví dụ bắt buộc: A đã cài, đủ 12/12; B chưa cài, mới 2/12. Kết quả phải chọn B. Khi không còn app thiếu chỉ tiêu đủ điều kiện, A trở lại nhóm có thể được chọn.

## 2. Cách xác định đủ chỉ tiêu

- Công thức: `todayTestedCount >= campaign.dailyTarget`.
- Giữ cách chuẩn hóa `dailyTarget` hiện tại: 12, 15 hoặc 20; giá trị không hợp lệ dùng 12.
- Số lượt hôm nay lấy theo số tester khác nhau trong `checkins` của campaign trong ngày `Asia/Ho_Chi_Minh`, cùng nguồn với thống kê hiện tại.
- Không lấy `home_tested_count`: số này có thể cộng dồn theo chu kỳ Trang chủ 2–3 ngày.
- Không lấy `installed_count`: số này đếm thành viên campaign, chỉ phục vụ tiêu chí cho tài khoản mới.
- Báo cáo test thường đang chờ duyệt đã tạo check-in tạm tính được tính theo cơ chế hiện tại. Không cộng thêm số báo cáo pending vào số check-in vì sẽ đếm trùng.
- Báo cáo nhiệm vụ app không được tự cộng vào chỉ tiêu test thường.
- Các trường hợp 12/12, 13/12, 15/15, 16/15, 20/20, 21/20 đều thuộc nhóm đã đủ.

## 3. Các điểm cần sửa

Đường dẫn dưới đây tính từ `F:/AI/12Tester`.

### 3.1. Dữ liệu ứng viên và máy chủ

Hiện `NextCampaignCandidate` chỉ có `campaign` và `installed_count`; chưa đủ dữ liệu phân nhóm theo chỉ tiêu.

1. Bổ sung `today_tested_count` và `stats_date` vào dữ liệu trả về của `get_next_campaign_candidates_v2`.
2. Đếm `count(distinct user_id)` từ `checkins` theo campaign và ngày Việt Nam, khớp `get_home_feed`/`get_campaigns_stats` hiện hành.
3. Giữ nguyên tập ứng viên hiện tại, bao gồm app đã đủ chỉ tiêu. Không thêm điều kiện SQL chỉ trả app thiếu chỉ tiêu, không cắt danh sách hoặc giới hạn vài app trước khi Android phân nhóm theo cài đặt trên máy.
4. Giữ tên, tham số, quyền truy cập và các trường đang dùng của RPC. Android cũ đang giải mã bỏ qua trường lạ nên phần bổ sung cần giữ tính tương thích này.
5. Thêm migration mới theo số tiếp theo còn trống tại thời điểm triển khai; hiện migration cao nhất trong workspace là 085. Không sửa lại migration 085 đã dùng làm lịch sử triển khai.
6. Chỉ bổ sung chỉ mục nếu kiểm tra truy vấn trên môi trường kiểm thử cho thấy cần thiết; kiểm tra chỉ mục hiện có trước.

Tệp liên quan:

- `supabase/migrations/085_egress_optimization_sync_v2.sql`: định nghĩa RPC hiện tại để đối chiếu.
- `supabase/migrations/082_carryover_flow_audit_fixes.sql`: cách tính thống kê và ghi nhận test hiện hành.
- Migration mới cho phần bổ sung dữ liệu ứng viên.

### 3.2. Model và hai cách tải danh sách

Trong `Models.kt` và `CampaignRepository.kt`:

1. Bổ sung dữ liệu số lượt hôm nay và ngày thống kê vào `NextCampaignCandidate`.
2. Để các trường mới có trạng thái thiếu dữ liệu rõ ràng khi đọc phản hồi máy chủ cũ; không mặc định thiếu số lượt thành 0 vì có thể đẩy nhầm app đủ chỉ tiêu lên đầu.
3. Đường RPC v2 dùng trực tiếp các trường mới khi đầy đủ và đúng ngày.
4. Đường dự phòng dùng `CampaignStats.todayTestedCount` và `statsDate` đã lấy cùng danh sách để tạo cùng một kiểu ứng viên.
5. Nếu máy chủ chưa bổ sung trường mới, nạp thống kê cho các campaign cần bổ sung bằng một lần gọi theo lô. Không gọi riêng từng app.
6. Nếu không lấy được dữ liệu đúng ngày để phân nhóm, trả trạng thái lỗi có thể thử lại; không suy đoán là chưa đủ chỉ tiêu và không coi lỗi tải là đã test hết app.
7. Giữ các kiểm tra thay đổi phiên đăng nhập, tài khoản, ngày nghiệp vụ và mutation trong repository. Các lời gọi bổ sung cũng phải qua cùng kiểm tra trước khi trả kết quả.

Đường RPC v2 đã có đủ dữ liệu chỉ cần một lần gọi như hiện tại; không tải thêm toàn bộ Trang chủ để tính thứ tự.

### 3.3. Thuật toán chọn app

Trong `CampaignDetailViewModel.kt`:

1. Tách phần chọn app thành hàm thuần hoặc thành phần nhỏ có thể kiểm thử với dữ liệu đầu vào cố định.
2. Phân hạng từng ứng viên theo cặp `(đã đủ chỉ tiêu, chưa cài trên máy)`; chọn hạng nhỏ nhất còn ứng viên, tương ứng bốn nhóm ở mục 1.
3. Áp dụng tiêu chí tài khoản mới và chọn ngẫu nhiên sau khi đã chốt nhóm ưu tiên.
4. Giữ việc làm mới danh sách package để nhận biết app tester vừa cài.
5. Giữ bộ lọc báo cáo đang chờ gửi cục bộ; lọc lại khi dùng kết quả tìm trước để tránh chọn app vừa được gửi trong thời gian chờ.
6. Cập nhật chú thích hiện đang nói “app đã cài luôn thắng” vì quy tắc mới ưu tiên thiếu chỉ tiêu trước.

Không cần sắp xếp ngẫu nhiên toàn bộ danh sách: chọn nhóm có hạng cao nhất rồi lấy ngẫu nhiên trong nhóm đó.

### 3.4. Tìm trước và trạng thái hộp thoại

Hiện ứng dụng có thể chọn sẵn một app khi mở form và giữ đến lúc nộp. Cần tránh giữ gợi ý thuộc ngày cũ hoặc sai phiên.

- Gắn kết quả tìm trước với ngày Việt Nam và phiên/tài khoản hiện tại; hết hiệu lực khi đổi ngày hoặc đổi tài khoản.
- Giữ tìm trước để hộp thoại phản hồi nhanh. Lưu danh sách ứng viên cùng dữ liệu phân nhóm để có thể chọn lại sau bộ lọc cục bộ, thay vì chỉ giữ một app không có thông tin chỉ tiêu.
- Không bổ sung polling nền hoặc tải lại vô điều kiện mỗi khi mở hộp thoại. Kết quả phản ánh snapshot thống kê mới nhất lấy được trong lần tìm; số lượt từ tester khác có thể thay đổi sau thời điểm đó.
- Khi dữ liệu đã hết hiệu lực, tải lại rồi mới chọn. Giữ kiểm tra phiên/ngày cho cả kết quả thành công và lỗi của tác vụ cũ.
- Phân biệt ba kết quả: đang tìm; không còn ứng viên sau khi tải thành công; tải thất bại. Chỉ kết quả không còn ứng viên mới được hiện thông báo đã test hết app.
- Khi chỉ còn app đủ chỉ tiêu, vẫn hiển thị gợi ý và nút Test ngay như hiện tại. Không hiển thị đã hoàn thành tất cả chỉ vì hết app thiếu chỉ tiêu.

Tệp liên quan: `CampaignDetailViewModel.kt`, `CampaignDetailScreen.kt`, các tệp chuỗi ngôn ngữ nếu cần thêm thông báo thử lại.

## 4. Giữ nguyên hành vi đã được yêu cầu

- App đủ chỉ tiêu vẫn mở trang chi tiết, tham gia test, nộp báo cáo và nhận thưởng theo chính sách đang áp dụng.
- Không đặt giới hạn cứng 12/15/20 trong thao tác tham gia, nộp, duyệt hoặc cộng thưởng.
- Không thay đổi mức thưởng, cơ chế thưởng tạm tính và xử lý báo cáo bị từ chối.
- Không thay đổi thứ tự Trang chủ, cơ chế đẩy app hay hộp thoại xác nhận app đủ chỉ tiêu ở Trang chủ trong đợt này.
- Giữ các điều kiện chống gợi ý lại app đã test hôm nay hoặc đang chờ gửi.

## 5. Ma trận kiểm thử bắt buộc

### 5.1. Thứ tự chọn app

| Dữ liệu | Kết quả mong đợi |
|---|---|
| Có đủ bốn nhóm | Chỉ chọn nhóm 1 |
| Nhóm 1 rỗng; có nhóm 2, 3, 4 | Chỉ chọn nhóm 2 |
| Nhóm 1, 2 rỗng; có nhóm 3, 4 | Chỉ chọn nhóm 3 |
| Chỉ có nhóm 4 | Vẫn gợi ý app nhóm 4 |
| A đã cài đủ 12/12; B chưa cài mới 2/12 | Chọn B |
| A đủ chỉ tiêu có số người tham gia ít; B chưa đủ có số người tham gia nhiều; tài khoản mới | Chọn B |
| Trong cùng nhóm có nhiều app; tài khoản mới | Chỉ chọn app có số người tham gia nhỏ nhất trong nhóm |
| Trong cùng nhóm có nhiều app; tài khoản thông thường | Kết quả luôn thuộc nhóm, không có ưu tiên ngầm theo số người tham gia |
| 11/12, 14/15, 19/20 | Chưa đủ chỉ tiêu |
| 12/12, 15/15, 20/20 và các số vượt mục tiêu | Đã đủ chỉ tiêu |
| App hôm nay 2/12 nhưng chu kỳ Trang chủ cộng dồn 12 lượt | Vẫn là chưa đủ chỉ tiêu |

Với lựa chọn ngẫu nhiên, kiểm thử tập kết quả hợp lệ hoặc truyền bộ chọn cố định; không dùng phép thử xác suất dễ thất bại ngẫu nhiên.

### 5.2. Dữ liệu và tích hợp

- RPC v2 và đường dự phòng trả cùng số lượt/ngày cho cùng dữ liệu; cả hai vẫn giữ app đủ chỉ tiêu trong tập ứng viên.
- Máy chủ cũ thiếu trường mới: bổ sung thống kê theo lô, không ngầm dùng 0.
- Dữ liệu v2 đầy đủ: không phát sinh lời gọi Trang chủ hoặc lời gọi thống kê thừa.
- Đếm theo tester khác nhau, đúng ngày Việt Nam; báo cáo nhiệm vụ không làm tăng chỉ tiêu test thường.
- App hiện tại, app của mình, app đã test hôm nay, app đang chờ gửi và app không mở đều bị loại như trước.
- Vừa cài app rồi quay lại: nhận đúng nhóm đã cài.
- Vừa qua 00:00, đổi tài khoản hoặc đăng xuất khi đang tải: không dùng kết quả phiên/ngày cũ.
- Tải lỗi: hiển thị thử lại; không báo đã test hết app.
- Chỉ còn app đủ chỉ tiêu: vẫn chuyển sang chi tiết, nộp báo cáo thành công và nhận thưởng đúng chính sách. Kiểm tra bằng tài khoản khác chưa test app đó hôm nay.
- Android cũ đọc được phản hồi RPC bổ sung trường; không thay đổi hợp đồng đang sử dụng.

Cập nhật kiểm thử hiện có:

- `supabase/tests/run-egress-sync-v2-test.mjs`: phép so sánh toàn bộ JSON hiện tại phải bổ sung hai trường và thêm tình huống đạt/vượt chỉ tiêu.
- `app/src/test/java/com/ntt/crosstest/data/EgressFinalClientReviewTest.kt`: cập nhật fixture, kiểm tra trường mới, đường bổ sung dữ liệu cũ và số lời gọi mạng.
- Thêm kiểm thử hành vi cho bộ chọn bốn nhóm và trạng thái tìm trước/lỗi trong khu vực kiểm thử ViewModel tương ứng.

## 6. Trình tự triển khai và nghiệm thu

1. Chốt hợp đồng dữ liệu và ma trận bốn nhóm ở trên.
2. Tạo migration mới, đối chiếu hàm thực tế, kiểm thử trên database dành cho kiểm thử.
3. Sửa model, hai đường repository, bộ chọn và trạng thái tìm trước/hộp thoại.
4. Chạy kiểm thử tập trung cho các phần thay đổi và các kiểm tra hồi quy liên quan; không dùng dữ liệu sản xuất để tạo báo cáo/thưởng thử.
5. Triển khai phần bổ sung máy chủ trước, xác nhận Android cũ vẫn hoạt động.
6. Phát hành Android mới sau khi kiểm chứng thứ tự bốn nhóm trên thiết bị có app đã cài và chưa cài.

Điều kiện nghiệm thu chính: trong mọi snapshot đã tải thành công và đủ dữ liệu, nếu còn ít nhất một app chưa đủ chỉ tiêu, chưa được tester test hôm nay và đủ điều kiện, bộ chọn không được trả app đã đủ chỉ tiêu. Khi không còn app thiếu chỉ tiêu đủ điều kiện, app đủ chỉ tiêu vẫn được gợi ý và test bình thường.

Nếu cần quay lui, khôi phục bộ chọn Android trước thay đổi. Phần dữ liệu bổ sung trên máy chủ có thể giữ lại vì mang tính bổ sung và tương thích với ứng dụng cũ.
