# Nghiên cứu và kế hoạch sửa nhiệm vụ ngày bị kẹt cập nhật

Ngày: 14/11/2026 (giờ Việt Nam).

Trạng thái: đã khảo sát mã hiện tại, nạp toàn bộ 29 migration 062–090 và chạy ba ca đối chứng trên cơ sở dữ liệu thử độc lập. Chưa sửa mã ứng dụng, chưa thay đổi dữ liệu máy chủ, chưa triển khai migration hay build APK/AAB. Phiên bản đang đọc: 1.2.5, build 45.

## 1. Kết luận nghiên cứu

Có cơ chế trong mã hiện tại tái hiện đúng hiện tượng **đủ số lượt test nhưng nhiệm vụ vẫn chờ cập nhật và chưa hoàn thành**. Hai nguyên nhân dữ liệu đã được tái hiện là chưa chốt ngày liền trước và sự kiện test chưa xác minh được nguồn. Chưa có dữ liệu vận hành của tài khoản bị lỗi để khẳng định nguyên nhân nào đang xảy ra trên máy chủ thực tế.

Phải kiểm tra cả máy chủ và đường cập nhật Android; chỉ thay câu thông báo hoặc tăng số lần tải lại không xử lý được dữ liệu ngày trước chưa chốt.

| Phát hiện | Căn cứ trong mã hiện tại | Mức xác nhận |
|---|---|---|
| Không có `finalized_at` của hôm qua thì `carryover_ready=false`; với owner, nhánh chưa sẵn sàng đặt `met=false` dù D đã đủ | `supabase/migrations/088_immediate_daily_quota_on_campaign_changes.sql:567–664` | Đã thực thi ca tái hiện |
| Sự kiện có `unresolved_missing_source` hoặc `unresolved_unknown_context` làm `history_ready=false`; đủ lượt vẫn chưa được xác nhận hoàn thành | Cùng tệp, phần đọc `daily_test_events_journal`, dòng 517–537 | Đã thực thi với `unresolved_missing_source` |
| RPC tải nhiệm vụ chỉ đọc trạng thái; việc chốt bù thuộc cơ chế riêng có quyền hệ thống | `062_consolidated_baseline.sql:5395–5413`; `080_report_context_carryover_and_gap_recovery.sql:954–1055` | Đã đối chiếu mã; chưa kiểm tra cron thực tế |
| Thẻ dùng `pendingCarryover OR isPendingRefresh` và ưu tiên chưa hoàn thành khi pending | `DailyTaskCard.kt:86–105`, phần thông báo dòng 439–443 | Đã đối chiếu mã |
| Retry sau thay đổi app dừng sau 5 lượt chờ 200/500/1000/2000/4000 ms; hết lượt không chuyển sang trạng thái lỗi có thể thử lại | `HomeViewModel.kt:1702–1740` | Đã đối chiếu mã; cần test hành vi Android |
| Retry chờ lịch sử chỉ đọc lại RPC, không tự chốt ngày; có 13 lượt chờ, tổng 545 giây, chưa tính thời gian mạng | `HomeViewModel.kt:623–626`, `1626–1690` | Đã đối chiếu mã |
| Nhánh retry lịch sử cập nhật `dailyTask/missedStreak` nhưng không xóa `isDailyTaskPendingRefresh` hoặc đồng bộ cache ở chính nhánh này | `HomeViewModel.kt:1666–1676` | Điểm cần tái hiện khi hai loại pending cùng tồn tại |
| `getMyDailyTaskStatus()` gộp các exception thành `null`, làm mất phân biệt lỗi mạng, lỗi RPC và lỗi giải mã; cũng bắt cancellation | `CampaignRepository.kt:2931–2937` | Đã đối chiếu mã |
| Sau gửi báo cáo, Home nhận Success rồi tải lại toàn màn hình; invalidation báo cáo chưa tăng riêng version `daily_task` | `HomeViewModel.kt:411–423`; `CampaignRepository.kt:471–487` | Cần test response cũ đến muộn; chưa kết luận đây là lỗi đang xảy ra |

Tên câu thông báo người dùng mô tả không trùng nguyên văn các resource hiện tại. Khi triển khai phải đối chiếu đúng bản cài đang gặp lỗi, màn hình xuất hiện và payload máy chủ; không suy ra toàn bộ người dùng cùng lỗi chỉ từ một nhánh mã.

## 2. Bằng chứng thử nghiệm

Chạy:

```powershell
node audit/daily-task-stuck-2026-11-14/reproduce-pending-status.mjs
```

Kết quả: **3/3 ca đạt assertion** trên PGlite độc lập, nạp toàn bộ migration 062–090. Fixture có một app khả dụng để test, nên chỉ tiêu thực tế là 1 dù chỉ tiêu cơ bản là 3. Đây là dữ liệu thử, không phải cấu hình production.

| Ca đối chứng | Đã làm / chỉ tiêu thực tế | history_ready | carryover_ready | met |
|---|---:|---|---|---|
| Đủ lượt nhưng thiếu bản chốt hôm qua | 1/1 | true | false | false |
| Giữ nguyên lượt, có checkpoint hôm qua đáng tin trong fixture | 1/1 | true | true | true |
| Vẫn đủ lượt, xuất hiện sự kiện chưa xác minh nguồn | 1/1 | false | false | false |

Chi tiết: `audit/daily-task-stuck-2026-11-14/reproduction-result.json`.

Việc thêm checkpoint thủ công chỉ là phép đối chứng trong fixture. **Không dùng cách tạo bản chốt giả, đặt nợ bằng 0 hoặc ép ready/met để sửa dữ liệu thật.** Các ca này kiểm chứng logic SQL, chưa chứng minh hành vi thiết bị, môi trường production hoặc giao dịch đồng thời.

## 3. Mục tiêu và nguyên tắc sửa

1. Sau khi máy chủ ghi nhận lượt test hợp lệ cuối cùng và dữ liệu đã sẵn sàng, thẻ tự chuyển sang “Đã hoàn thành nhiệm vụ ngày” khi nhận kết quả mới, không cần thoát ứng dụng hay kéo tải lại.
2. Giữ riêng ba thông tin: tiến độ hôm nay, độ sẵn sàng của lịch sử và nợ bảo lưu. Đạt chỉ tiêu thực tế nhưng còn nợ phải hiển thị đúng cả hai; quyền xóa/production/bật lại vẫn theo gate hiện hành.
3. `D` tính theo số app khác nhau có sự kiện hợp lệ trong ngày Việt Nam theo quy tắc máy chủ. Báo cáo Nhiệm vụ App, hàng đợi chưa gửi thành công và gửi lặp không tự tăng D của nhiệm vụ ngày.
4. Không đánh dấu hoàn thành chỉ bằng so sánh bộ đếm trên điện thoại khi lịch sử hoặc hợp đồng dữ liệu chưa xác minh được.
5. Lỗi tải phải có trạng thái rõ và nút thử lại; pending hợp lệ phải nêu đang chờ việc gì. Không giữ một nhãn “đang cập nhật” vô thời hạn mà không có hướng phục hồi.
6. Giữ các quy tắc đang có về chỉ tiêu động, nguồn app khả dụng, nợ bảo lưu, ngày đăng ký, Credit và chốt ngày. Dữ liệu lịch sử đã chốt không được tính lại tùy tiện.

## 4. Kế hoạch thực hiện theo thứ tự

### P0 — Chẩn đoán đúng trên môi trường bị lỗi

- Ghi nhận version/build bản cài và UID được phép kiểm tra; lấy snapshot chỉ đọc ở một thời điểm cố định theo giờ Việt Nam.
- So sánh cùng UID/ngày giữa `get_my_daily_task_status`, `get_my_dashboard` và `get_my_dashboard_sync_v2`, gồm cả block thay đổi và block được giữ lại từ cache. Ghi D, chỉ tiêu thực tế, `met`, hai cờ ready và nợ còn lại.
- Đối chiếu bản ghi hôm qua trong `user_daily_tasks`, checkpoint gần nhất, ngày tạo tài khoản, coverage marker và chuỗi ngày thiếu; xác định thiếu tạm thời hay không có điểm bắt đầu đáng tin.
- Kiểm tra sự kiện journal chưa xác minh, đối chiếu nguồn báo cáo/check-in và context daily/mission. Không chuyển mọi bản ghi thiếu nguồn thành hợp lệ.
- Kiểm tra migration/function thực sự đã áp dụng, quyền RPC, lịch và kết quả cron chốt ngày, lỗi gần nhất và lượng tồn đọng. Không coi file migration trong repository là bằng chứng đã triển khai.
- Phân loại kết quả: lỗi dữ liệu chưa chốt; lỗi journal; RPC sai/thiếu trường; server đúng nhưng Android/cache sai. Chọn bản sửa tối thiểu theo bằng chứng.

Sản phẩm bước này: snapshot đã loại token/nội dung nhạy cảm, nguyên nhân của tài khoản bị lỗi và ca tái hiện tương ứng. Nếu chưa truy cập được môi trường thật, ghi rõ giới hạn và tiếp tục sửa/test các ca đã tái hiện cục bộ.

### P1 — Khôi phục đường chốt ngày và đối soát nguồn

- Nếu cron chậm/hỏng: sửa nguyên nhân vận hành, có lượt chạy bù giới hạn khối lượng và cơ chế tiếp tục xử lý tồn đọng. Giữ mốc 00:00 Việt Nam; cron chạy lúc 00:05 có thể tạo cửa sổ chờ ngắn hợp lệ.
- Dùng finalizer hiện có theo thứ tự ngày và checkpoint đáng tin; không cho ứng dụng gọi hàm phạt/quyết toán toàn hệ thống khi chỉ mở thẻ nhiệm vụ.
- Nếu tài khoản cũ không có checkpoint và dữ liệu trước coverage không thể dựng lại: thiết kế quy trình khởi tạo/đối soát riêng, có căn cứ cho nợ mở đầu và nhật ký. Không mặc định nợ bằng 0.
- Với journal unresolved: phục hồi từ nguồn thật và context thật, giữ dấu vết; trường hợp không thể xác minh tiếp tục báo chờ xử lý dữ liệu, không âm thầm miễn nhiệm vụ.
- Sau chốt bù/đối soát thành công, làm mới completion hôm nay và bảo đảm các đường RPC cùng phản ánh một kết quả. Giữ idempotency để chạy lại không phạt hoặc thưởng hai lần.
- Nếu cần thay logic SQL, thêm migration tiếp theo (dự kiến 091 nếu chưa có số mới), không chỉnh lại migration đã phát hành. Đọc `LOCK_ORDER.md`; mọi thay đổi khóa/finalizer phải có test PostgreSQL nhiều kết nối.

### P2 — Một luồng trạng thái nhiệm vụ nhất quán trên Android

- Biểu diễn rõ: đang tải lần đầu, dữ liệu cũ đang làm mới, chờ chốt lịch sử, chưa hoàn thành, hoàn thành và lỗi có thể thử lại. Có thể thêm lý do pending từ backend nếu thật sự cần; không suy diễn thiếu trường thành dữ liệu hợp lệ.
- Thay `null` chung bằng kết quả phân biệt thành công/thất bại; giữ thông tin lỗi phù hợp để chẩn đoán và luôn truyền tiếp `CancellationException`.
- Sau nộp báo cáo daily được máy chủ xác nhận, cập nhật/invalidate riêng miền `daily_task` và lấy trạng thái mới. Gửi nền và gửi trực tiếp phải đi qua cùng cơ chế; không làm mất thông báo Success vì lỗi đọc trạng thái sau commit.
- Dùng chung đường nhận snapshot mới cho RPC, dashboard và retry: cập nhật thẻ, cờ pending, missed streak và cache nhất quán. Chỉ xóa cờ chờ refresh của đúng thế hệ thay đổi đã được xác nhận; lịch sử vẫn có thể pending riêng.
- Chặn response cũ theo UID, session generation, ngày Việt Nam, mutation version và thứ tự request. Đặc biệt kiểm tra retry lịch sử chạy song song đăng app/gửi báo cáo; phản hồi cũ không được ghi đè kết quả mới.
- Giữ riêng raw fields để gate xử lý fail-closed; không để fallback UI đăng nhập của member biến thành bằng chứng server cho phép thao tác app.

### P3 — Tải lại có điểm kết thúc và khả năng phục hồi

- Gộp việc tải lại trạng thái vào một coordinator có kiểm soát phiên và lifecycle; tránh hai vòng retry ghi cùng dữ liệu mà không cùng phiên bản.
- Backoff có timeout từng request và giới hạn một đợt; hết đợt chuyển sang lỗi/thông báo chờ có nút “Thử lại”, giữ số tiến độ gần nhất kèm ngày và trạng thái chưa xác minh.
- Khởi động lại đồng bộ khi mạng trở lại, Home resume, gửi báo cáo thành công hoặc sang ngày mới; dùng single-flight/debounce để không phát nhiều RPC trùng nhau.
- Dừng retry khi logout/đổi tài khoản, ngày đổi hoặc màn hình không còn hoạt động theo thiết kế. Cache pending từ lần mở trước phải có đường kiểm tra lại, không chỉ phục hồi cờ rồi để nguyên.
- Ưu tiên lấy block nhiệm vụ hoặc RPC nhỏ; tránh polling toàn dashboard/feed và truy vấn theo từng thẻ app.

### P4 — Hiển thị chính xác, thống nhất với quyền thao tác app

- Đủ dữ liệu và `met=true`: hiện hoàn thành ngay. Nếu vẫn còn nợ theo chính sách giới hạn nguồn, hiển thị nợ riêng và lý do thao tác app bị chặn.
- Lượt test đã đủ nhưng chưa chốt lịch sử: hiện tiến độ đã ghi nhận và lý do chờ xác nhận; không yêu cầu người dùng test thêm một số lượt không có căn cứ.
- Mạng/RPC lỗi: thông báo chưa tải được trạng thái và “Thử lại”; không gán thành người dùng chưa làm nhiệm vụ.
- Khi `dailyTask=null` hoặc thiếu trường quan trọng, không dùng số check-in cũ để tự xác nhận owner đã hoàn thành hoặc mở gate.
- Đồng bộ Home, quản lý app và dialog xóa/production/bật lại với cùng trạng thái server. Đủ điều kiện phải gỡ thông báo chặn sau lần xác minh mới; còn nợ/chưa sẵn sàng vẫn chặn đúng lý do.
- Chuỗi đầy đủ default/vi/en, phân biệt Nhiệm vụ ngày với Nhiệm vụ App.

## 5. Ma trận kiểm thử và nghiệm thu

| Nhóm | Kịch bản và kết quả bắt buộc |
|---|---|
| Hoàn thành ngày | D từ N-1 sang N sau RPC nộp báo cáo thật: server `met=true` khi ready; Home chuyển hoàn thành không khởi động lại |
| Chốt chậm | Thiếu hôm qua: vẫn ghi nhận D; sau finalizer hợp lệ, tự thoát chờ và giữ nguyên tiến độ |
| Tồn đọng dài | Nhiều ngày thiếu/checkpoint trước coverage: xử lý đúng thứ tự hoặc báo cần đối soát, không tự xóa nợ |
| Nguồn chưa rõ | unresolved dù D đủ vẫn chờ đúng lý do; sau đối soát hợp lệ mới chuyển ready |
| Hai loại pending | `isDailyTaskPendingRefresh=true` cùng lịch sử pending; phản hồi mới hợp lệ xóa đúng cờ, cache và thẻ cùng kết quả |
| Lỗi và phục hồi | Timeout, RPC lỗi/không tồn tại, JSON sai, thiếu trường, mất mạng hơn cả đợt retry: có trạng thái/nút thử lại; mạng trở lại phục hồi được |
| Cache và thứ tự | Full/delta V2, changed=false có/không có base, cache pending khởi động lại, phản hồi cũ sau mutation mới: không kẹt hoặc lùi tiến độ |
| Nguồn lượt test | Daily trực tiếp/background, pending hợp lệ, bị từ chối/gửi lại theo quy tắc đang có, gửi trùng cùng app, mission report: D chỉ đổi đúng điều kiện |
| Nợ và thiếu nguồn | Đạt chỉ tiêu thực tế nhưng còn nợ: thẻ hoàn thành theo server, nợ hiện riêng, gate vẫn chặn đúng; nợ hết thì gate cho phép khi đủ các điều kiện khác |
| Thay đổi chỉ tiêu | Đăng/gỡ/tạm dừng/production: giữ D, lấy lại chỉ tiêu server; không hạ quota để vượt gate |
| Phiên và ngày | Đổi UID, logout, resume, qua 00:00 VN và lệch giờ thiết bị: không dùng ngày cũ hoặc ghi dữ liệu sang người khác |
| Vai trò và hồi quy | Owner/member/admin/moderator, tài khoản mới, app owner_inactive; Credit, chốt ngày và action gate 090 giữ đúng hành vi |

Thứ tự xác minh khi triển khai:

1. Giữ ba ca nghiên cứu làm bằng chứng baseline; bổ sung test hành vi Android tái hiện pending bị giữ, lỗi RPC và response đến muộn trước khi sửa.
2. Backend: chạy test mới qua API thực trong fixture, `run-carryover-fix-acceptance-test.mjs`, `run-immediate-daily-quota-acceptance-test.mjs`, `run-egress-sync-v2-test.mjs` và bộ action gate liên quan.
3. Android: test hành vi ViewModel/repository, serializer/cache, `PendingCarryoverRetryRegressionTest`, `OwnerDailyRefreshRaceReviewTest`, `OwnerDailyTaskOrderingReviewTest`, `SupervisorDailyTaskStatusCacheReviewTest`, `DailyTaskActionGateUnitTest`; sau đó `./gradlew.bat testDebugUnitTest`.
4. Nếu sửa khóa/chốt bù: kiểm tra đồng thời trên PostgreSQL thật; PGlite không thay thế kiểm thử nhiều kết nối.
5. Nghiệm thu thiết bị/môi trường thử với một tài khoản từ N-1 sang N và một tài khoản đang chờ chốt hôm qua. Chứng minh server, thẻ và dialog thống nhất sau phục hồi.

Chỉ kết luận đã khắc phục khi các ca trước/sau chạy đạt, có log/bằng chứng của đúng mã đã kiểm tra và nêu rõ phần chưa kiểm chứng. Trong giai đoạn nghiên cứu này mới chạy ba ca SQL mô tả tại mục 2.

## 6. Phạm vi và bàn giao

- Ưu tiên P0/P1 vì dữ liệu chưa sẵn sàng trên server có thể khiến mọi cách refresh Android vẫn trả cùng trạng thái.
- Sửa tối thiểu repository/ViewModel/cache/UI và backend khi có căn cứ; không thay chính sách nhiệm vụ hoặc số lượt cấu hình để che lỗi.
- Giữ phiên bản 1.2.5, build 45 trong đợt sửa này trừ khi có yêu cầu đổi riêng. Không tạo APK/AAB trong phạm vi nghiên cứu/lập kế hoạch.
- Bàn giao diff, test trước/sau, kết quả kiểm tra môi trường bị lỗi, hướng dẫn chạy bù có kiểm soát và thứ tự triển khai nếu cần migration. Việc sửa dữ liệu/triển khai production là bước thực hiện riêng sau khi xác định chính xác phạm vi và dữ liệu đích.
