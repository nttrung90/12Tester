# Đánh giá kết hợp cộng dồn nhiệm vụ ngày và nút Đẩy app

Ngày: 29/10/2026. Phạm vi: đọc hai kế hoạch và đối chiếu mã nguồn local; chưa sửa code sản phẩm, chưa áp migration, chưa kiểm thử tích hợp trên Supabase live.

## 1. Kết luận

**Có thể triển khai cả hai. Không có mâu thuẫn bắt buộc về nghiệp vụ, nhưng hai kế hoạch chưa đủ để triển khai độc lập rồi ghép mà không kiểm tra tích hợp.**

- Cộng dồn thay chỉ tiêu test mà người dùng phải hoàn thành và ảnh hưởng chấm phạt/chuỗi thiếu.
- Đẩy app thu 100 Credit cho mỗi lượt mua mới và thay thứ tự hiển thị campaign còn hợp lệ trong ngày.
- Đẩy app không phải hoàn thành nhiệm vụ, không xóa phần thiếu, không reset chuỗi và không bảo vệ campaign khỏi tự tạm dừng.
- Tester không đăng app vẫn hoàn thành nhiệm vụ theo quy tắc đăng nhập hiện tại. Việc thấy app được đẩy trên feed không làm họ phát sinh nghĩa vụ test.

Điểm cần thống nhất trước khi triển khai: khóa giao dịch, số dư tại lúc chấm phạt, trạng thái chưa chốt ngày, tạm dừng campaign đã mua đẩy, cache chung và thứ tự migration.

## 2. Bằng chứng đã kiểm tra

| Nguồn | Kết quả |
| --- | --- |
| `KE_HOACH_CONG_DON_APP_TEST_CON_THIEU_2026-10-29.md` | Có công thức B/C/A/D, phạt theo T, trạng thái `carryover_ready`, giữ quy tắc member và kế hoạch sửa pause/resume. |
| `KE_HOACH_DAY_APP_2026-10-29.md` | Có giá 100, receipt chống thu trùng, mua chỉ khi campaign hợp lệ, hết ưu tiên tại 00:00, cập nhật comparator và cache. |
| `042_configurable_daily_task_ratio.sql:361–410` | K/A/D được đếm theo campaign/test; không phụ thuộc thứ hạng feed hoặc phí đẩy. |
| Cùng file, dòng 493–498 và 565–620 | Finalizer khóa profile, lấy số dư hiện tại để tính phạt rồi ghi số dư mới. |
| `036_daily_task_quota.sql:964, 1010` | Resume khóa campaign trước rồi gọi refresh. |
| Cùng file, dòng 272–278 | Refresh có thể UPDATE profile để reset chuỗi: có thể cần khóa profile sau khóa campaign. |
| Cùng file, dòng 897–918 | Auto-pause kiểm tra chuỗi và `met`, rồi đổi campaign sang paused; chưa có khái niệm mua đẩy. |
| `HomeViewModel.kt:47–59` | `CampaignDisplayItem.isTargetReached` so số lượt campaign nhận được hôm nay với `campaign.dailyTarget`. Đây là số khác nghĩa với chỉ tiêu nhiệm vụ cá nhân. |
| `DailyHomeOrder.kt`, `sortOpenCampaigns` | Comparator hiện ưu tiên chưa đủ lượt → Boost cũ → tip → hash ngày → ID; chưa có đẩy trả phí. |
| `CampaignRepository.kt:301–326, 487–489` | Profile/campaign đều tác động dashboard cache; invalidate profile chưa tự xóa lịch sử Credit. |
| `HomeViewModel.kt:217–235, 771–820` | Có scheduler đổi ngày và guard khi publish; cả hai tính năng phải mở rộng chung các đường này. |

Tìm trong SQL/Kotlin chưa thấy `push_campaign`, metadata `push_*` hoặc `carryover_ready`/`carry_in_tests`. Do đó đây là phân tích rủi ro của thiết kế kết hợp, không phải khẳng định đã tái hiện lỗi ở tính năng đã triển khai.

## 3. Các điểm có thể xung đột

### 3.1. Khóa profile và campaign — cần xử lý trước khi mở mua

Kế hoạch Đẩy app yêu cầu khóa profile và campaign theo thứ tự nhất quán nhưng chưa chọn thứ tự cụ thể. Resume hiện khóa campaign trước, sau đó có thể khóa profile qua refresh.

Nếu triển khai mua bằng `profile → campaign` trong khi giữ resume `campaign → profile`, có tình huống:

```text
Phiên mua:    giữ profile, chờ campaign
Phiên resume: giữ campaign, chờ profile
```

Đây là nguy cơ deadlock có căn cứ từ các đường khóa; chưa phải kết quả test đồng thời. Campaign đang paused không loại bỏ hoàn toàn nguy cơ: RPC mua có thể chờ khóa campaign trước khi tới bước kiểm tra trạng thái và từ chối mua.

Yêu cầu phối hợp:

- Lập bảng thứ tự khóa thực tế cho mua, resume, refresh, finalizer và các RPC chi Credit/cập nhật campaign liên quan; bao gồm trigger được gọi bên trong.
- Chọn thứ tự nhất quán trên các đường có thể tranh chấp, sửa các đường ngược thứ tự trong phạm vi cần thiết. Không tuyên bố chỉ thêm `FOR UPDATE` là đã an toàn.
- Finalizer hiện khóa profile nhưng không khóa campaign qua các SELECT trong helper; không khẳng định chỉ riêng cron hiện tại và mua chắc chắn tạo vòng khóa campaign.
- Khóa chung cấp `push_order` chỉ dùng trong luồng mua, giữ ở đoạn cuối; không tái dùng khóa này để chốt toàn bộ nhiệm vụ ngày.
- Kiểm thử nhiều kết nối PostgreSQL thật, đặc biệt mua cùng lúc resume/auto-pause và mua cùng lúc chấm phạt. Retry lỗi giao dịch dùng lại request ID của lượt đã xác nhận.

### 3.2. Cùng dùng số dư Credit — kết quả phụ thuộc thứ tự giao dịch

Không được để RPC mua ghi số dư từ cache hoặc ghi đè số dư của cron. Cả hai phải đọc/ghi theo khóa profile và transaction đã thống nhất.

Ví dụ số dư 100, người dùng thiếu nhiệm vụ hôm qua, mức phạt 1% và tỷ lệ thiếu 100%:

| Thứ tự xử lý | Kết quả hợp lệ theo chính sách hiện tại |
| --- | --- |
| Chấm phạt trước | Phạt 1, còn 99; mua đẩy bị từ chối do không đủ 100. |
| Mua đẩy trước | Thu 100, còn 0; đến chấm phạt không còn số dư để trừ; chuỗi thiếu và phần thiếu vẫn được ghi. |

Đây là hệ quả của việc phạt theo **số dư tại lúc xử lý**, không phải lỗi cộng/trừ nếu hai giao dịch được tuần tự hóa đúng. Cơ chế cộng dồn không tự tạo nghĩa vụ Credit phải thu sau này khi số dư lúc phạt bằng 0.

Phương án tương thích đề xuất: giữ chính sách hiện tại, ghi log/receipt đúng số dư tại từng giao dịch và kiểm thử cả hai thứ tự. Số dư hiển thị trong hộp xác nhận chỉ là dự kiến; backend quyết định đủ tiền khi đã lấy khóa.

Nếu muốn luôn phạt trước rồi mới cho mua trong ngày mới thì đó là thay đổi nghiệp vụ bổ sung: phải có cổng kiểm tra ngày hôm qua đã chốt, xử lý cron trễ và giải thích tại UI. Không tự đưa quy tắc này vào chỉ vì triển khai hai tính năng chung.

Receipt `balance_after` là số dư tại giao dịch mua, có thể đã cũ khi response về vì cron vừa phạt tiếp. Giữ receipt làm bằng chứng giao dịch; sau mua tải profile mới với guard chống response cũ ghi đè. Không coi số dư trên receipt là số dư hiện tại vĩnh viễn, cũng không sửa lại receipt lịch sử.

### 3.3. Mua đẩy rồi bị auto-pause — cần mô tả rõ hành vi

Kế hoạch cộng dồn giữ auto-pause khi thiếu 7 ngày; kế hoạch Đẩy app chỉ ưu tiên app đang mở và hợp lệ. Vì vậy app vừa mua đẩy vẫn có thể bị tự tạm dừng sau đó.

Quy tắc kết hợp đề xuất:

1. App đang paused: từ chối mua, không thu tiền. Không tự resume để hoàn tất giao dịch mua.
2. App đang open và đủ điều kiện: có thể mua dù chủ app còn phần thiếu; mua không gọi reset chuỗi hoặc ghi hoàn thành nhiệm vụ.
3. Sau mua bị auto-pause: app rời danh sách đang mở, hiệu lực đẩy không vượt qua điều kiện trạng thái campaign.
4. Mở lại hợp lệ trong cùng ngày: giữ thứ tự đẩy cũ và hạn cũ, không thu lại tiền. Ngày sau không còn ưu tiên của lượt cũ.
5. Theo phương án không tự hoàn tiền trong kế hoạch Đẩy app, đề xuất áp dụng rõ cho cả auto-pause; bổ sung dòng trợ giúp về khả năng app bị tạm dừng do chưa hoàn thành nhiệm vụ. Đây là phần cần bổ sung vào đặc tả trước khi mở mua.
6. Khi trạng thái chuyển giữa hai request, backend kiểm tra dưới khóa. Nếu mua đã commit trước rồi app mới paused thì đó là hai giao dịch hợp lệ, không báo rằng giao dịch mua bị rollback.

Trigger bảo vệ metadata đẩy cần chỉ chặn tạo/sửa metadata trái phép; không chặn việc cron/resume đổi `status` mà giữ nguyên metadata, không tự xóa receipt khi paused.

### 3.4. Hai mốc 00:00 và 00:05 — không chờ chung một điều kiện

- Đẩy hôm qua hết hiệu lực đúng 00:00, kể cả offline hoặc cron hôm qua chưa chốt.
- Nhiệm vụ owner có thể chờ `carryover_ready` tới sau 00:05 hoặc lâu hơn nếu cron trễ.
- Chưa ready không phải miễn nhiệm vụ; cũng không phải lý do giữ lượt đẩy hôm qua còn hiệu lực.
- Theo phương án giữ độc lập, app đang open vẫn có thể mua lượt đẩy mới khi nhiệm vụ đang chờ chốt, nếu đủ tiền và đủ điều kiện khác. Phần 3.2 giải thích hệ quả về thứ tự phạt/mua.
- Việc lấy metadata feed và sắp xếp không phụ thuộc nhiệm vụ cá nhân ready. Pending nhiệm vụ không làm cả Trang chủ dừng cập nhật.
- Không tạo hai scheduler cạnh tranh cùng reset HomeUiState. Dùng chung luồng đổi ngày; tách tác vụ hết ưu tiên tại 00:00 và retry dữ liệu nhiệm vụ sau mốc chốt.

### 3.5. Cache và file sửa chung — có thể ghi đè hoặc hiển thị dữ liệu lẫn thời điểm

Cả hai đụng `Models.kt`, `CampaignRepository.kt`, `HomeViewModel.kt`, resource ba ngôn ngữ, schema gộp và package/runner test. Cộng dồn còn sửa hành vi resume; Đẩy app thêm hành động gần nút resume.

Yêu cầu phối hợp:

- Hợp nhất thay đổi ở từng file, không dùng bản chép nguyên file cũ để ghi đè phần tính năng kia.
- Sau mua invalidate profile/Credit log/feed/dashboard; sau chốt ngày cập nhật chỉ tiêu/phần thiếu/số dư. Snapshot phải giữ cả metadata đẩy và trường nhiệm vụ mới.
- Dùng mutation version, request sequence và session guard hiện có để response cũ không khôi phục số dư trước mua, xóa metadata đẩy hoặc đưa nhiệm vụ chưa cộng dồn lên UI.
- Refresh phụ thất bại không biến giao dịch mua đã thành công thành thất bại. Không xóa trạng thái nhiệm vụ đang chờ chỉ vì tải lại campaign thành công.
- Tương thích JSON và tập cột truy vấn phải kiểm tra chung; có hai nhóm field mới không đồng nghĩa hai capability được bật đồng thời.

### 3.6. Số migration và rollout — cần thống nhất trên source cuối

Kế hoạch Đẩy app được viết lúc mới xác nhận migration 048 và có nhắc dự kiến 049 ở công việc khác. Checkout hiện đã có 049, 050, 051; kế hoạch cộng dồn dự kiến 052 nếu còn trống.

Không có va chạm migration đã xảy ra giữa hai tính năng vì chưa thấy SQL triển khai chúng, nhưng không được giữ số cũ khi bắt đầu làm.

Đề xuất nếu 052/053 còn trống lúc triển khai: 052 cho cộng dồn, 053 cho Đẩy app. Các migration phải có tên riêng, mirror đúng trong schema gộp; kiểm tra cả nâng cấp database lẫn dựng mới.

Khi thay function như dashboard/serializer, lấy định nghĩa cuối đã hợp nhất: không chép function từ schema cũ làm mất thay đổi của gói đã chạy trước. Không rollback bằng cách khôi phục toàn bộ schema của trước cả hai tính năng.

## 4. Những phần không xung đột nếu giữ đúng phạm vi

| Thành phần | Vì sao tương thích |
| --- | --- |
| Số app owner phải test | Tính bằng B/C/A/D; lượt mua đẩy không phải check-in/report và không thay K/A/D. |
| Nguồn app A | Đẩy chỉ đổi thứ hạng, không tạo bản sao campaign. Đếm app từ nguồn đầy đủ như hiện tại, không chỉ đếm trang feed đầu. |
| Chỉ tiêu của campaign nhận test | `campaign.dailyTarget` và `isTargetReached` vẫn là lượt campaign nhận hôm nay; không cộng phần thiếu cá nhân của owner vào đây. |
| Ưu tiên trả phí | Có thể đứng trước app khác dù đủ lượt hôm nay, đúng kế hoạch Đẩy app; không sửa công thức nhiệm vụ tester. |
| Tester không đăng app | Vẫn chỉ cần đăng nhập; thấy/click app được đẩy không tự tạo phần thiếu. |
| Sổ dữ liệu | Cộng dồn ghi `user_daily_tasks`; Đẩy app ghi metadata campaign và receipt; không dùng chung field cho hai nghĩa. |
| Boost/tip cũ | Giữ nguyên, không dùng `is_boosted` để biểu diễn cộng dồn hay lượt đẩy trả phí. |

Việc đẩy có thể làm một số app được test nhiều hơn, qua đó thay đổi hành vi người dùng và D thực tế. Đó là tác động phân phối lượt test, không phải xung đột công thức. Không hứa mua đẩy bảo đảm số tester hoặc giúp chủ app trả phần thiếu của chính mình.

## 5. Thứ tự triển khai đề xuất

1. Bổ sung các quy tắc kết hợp tại mục 3 vào hai đặc tả, chốt thứ tự khóa và chính sách số dư lúc phạt. Chuẩn hóa số migration theo checkout cuối.
2. Làm backend cộng dồn trước, kiểm thử sổ ngày, ready state, phạt và pause/resume. Giữ ngày có hiệu lực ở tương lai trong giai đoạn tích hợp.
3. Làm backend Đẩy app trên nền backend đã hợp nhất; giữ khả năng mua chưa mở cho người dùng trong khi kiểm tra.
4. Phát hành một bản Android hiểu cả hai nhóm metadata và xử lý cả pending nhiệm vụ lẫn hết ưu tiên theo ngày; xác nhận phiên bản tối thiểu trước khi mở bán rộng rãi.
5. Chạy bộ test kết hợp dưới đây trên source cuối và PostgreSQL staging. Chỉ bật cộng dồn/mở mua sau khi các đường tranh chấp đạt yêu cầu.
6. Có thể mở hai tính năng ở các mốc riêng để dễ đối chiếu, nhưng việc tách ngày bật không thay thế kiểm thử chúng khi cùng hoạt động.

Hai tính năng không có phụ thuộc bắt buộc về schema khiến chỉ có một thứ tự triển khai hợp lệ; thứ tự trên giúp ổn định logic phạt/đổi ngày trước khi thêm giao dịch trả phí.

## 6. Kiểm thử tích hợp cần bổ sung

| Ca | Kết quả cần chứng minh |
| --- | --- |
| Còn phần thiếu, mua đẩy thành công | Trừ đúng 100; C/D/met/chuỗi không thay chỉ vì mua; thứ hạng thay đúng. |
| Chỉ còn 100, cron phạt và mua chạy đồng thời | Kết quả khớp một trong hai thứ tự hợp lệ; không âm tiền, không mất cập nhật. |
| Có 150, mức phạt 10%, thiếu 100% | Phạt trước: còn 135 rồi mua còn 35; mua trước: còn 50 rồi phạt 5, còn 45. Log/receipt đúng từng thời điểm. |
| Mua trùng request đồng thời cron | Một receipt, một log -100; phạt một lần; phần thiếu chốt một lần. |
| Response mua đến sau giao dịch phạt | Không dùng receipt cũ để ghi đè profile mới; receipt vẫn giữ balance_after lịch sử. |
| Mua tranh chấp resume/auto-pause | Không deadlock theo thiết kế khóa đã chọn; chỉ thu tiền nếu mua hợp lệ tại điểm xử lý. |
| Đã mua rồi đủ điều kiện auto-pause | App rời feed; phần thiếu giữ đúng; mở lại cùng ngày không phát sinh giao dịch mua mới. |
| Chủ đang pending sau 00:00 | Ưu tiên cũ đã hết; report mới vẫn tính hôm nay; pending không reset chuỗi; mua mới tuân quy tắc đã chọn. |
| Trigger bảo vệ metadata | Chặn tự đẩy trực tiếp; vẫn cho phép cron/resume hợp lệ đổi trạng thái và giữ metadata. |
| Campaign nhận đủ test nhưng owner còn thiếu | Thứ hạng theo lượt đẩy, nghĩa vụ owner theo B/C/A/D; hai chỉ tiêu không bị trộn. |
| Member nhìn feed có app được đẩy | Đăng nhập vẫn đủ hoàn thành nhiệm vụ member, không phát sinh chỉ tiêu test. |
| Refresh/restore đồng thời | Giữ được metadata đẩy và nhiệm vụ mới, không phục hồi số dư trước giao dịch hoặc ưu tiên hôm qua. |
| Tắt một tính năng | Tính năng kia tiếp tục đúng; receipt, sổ ngày và các kết quả đã chốt không bị xóa/ghi lại. |

## 7. Giới hạn kết luận

Đã đọc kế hoạch và truy dấu các hàm/cột/cache dùng chung. Chưa có bản triển khai của hai tính năng để chạy kiểm thử tích hợp; chưa xác minh definition/quyền/cron trên live. Vì vậy có thể kết luận thiết kế tương thích có điều kiện và chỉ ra chỗ phải xử lý, chưa thể chứng nhận bản triển khai thực tế không có lỗi.
