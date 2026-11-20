# Recheck vòng 3 và kế hoạch sửa popup thông báo riêng admin

Ngày: **30/10/2026**. Đối tượng kiểm tra: source sau bản sửa Gemini vòng 2. Phạm vi: đọc source, chạy test/build, bổ sung kiểm thử tái hiện; không sửa code sản phẩm, không thao tác database live.

## 1. Kết luận và kết quả kiểm chứng

**6 test từng phát hiện lỗi ở vòng trước hiện đều PASS. Tuy nhiên còn 4 lỗi sản phẩm đã tái hiện và một lỗi ở runner kiểm thử concurrency.**

| Kiểm tra | Kết quả |
|---|---|
| Backend popup PGlite | 11/11 PASS |
| Bộ Android đã có sau Gemini vòng 2 | 33/33 PASS: 13 ViewModel, 6 regression vòng trước, 2 pending store, 12 localization |
| `assembleDebug` | PASS; các task build hiện up-to-date |
| Regression vòng 3 mới bổ sung | 4/4 FAIL bằng assertion đúng hành vi cần sửa |
| Chạy chung Android cũ + mới | 37 test: 33 PASS, 4 FAIL, không lỗi compile |
| Probe bước setup runner concurrency | Admin authenticated không đọc được bản tin của target; UPDATE bảng state bị permission denied |

Báo cáo Gemini vòng 2 ghi 37 test ở đầu, 35 test ở phần kết quả và 15 ViewModel test; XML thực tế trước khi thêm test lần này có **33 test, trong đó 13 ViewModel**. Cần cập nhật báo cáo theo kết quả máy, không tự cộng nhầm số test. Sau khi thêm 4 test vòng 3 mới có tổng 37 trong tập lệnh kiểm tra này.

Bằng chứng: `audit/admin-popup-review-round3-2026-10-30/` có log backend, build, regression, combined, XML cho từng suite và probe SQL setup runner.

## 2. G1 — P2: Đã đọc offline chưa cập nhật danh sách/badge

**Vị trí:** `ui/viewmodels/NotificationPopupViewModel.kt:364–404`; `data/CampaignRepository.kt:2415–2425`; `ui/viewmodels/HomeViewModel.kt:357`.

**Tái hiện:** Home đã tải bản tin N chưa đọc. Popup N hiện; bấm Đã đọc, ghi pending store thành công nhưng RPC ack trả HTTP 503.

**Thực tế:** popup đóng và pending ack còn trên đĩa đúng thiết kế, nhưng N trong Home vẫn chưa đọc, badge chưa giảm. `onMarkAsRead` không phát cập nhật local; `localReadNotificationIds` và event chỉ được ghi/phát sau khi RPC thành công.

**Test mới:** `offline_popup_read_must_update_the_existing_home_list_before_server_sync`.

**Yêu cầu sửa:** sau khi ghi pending bền vững thành công, cập nhật nguồn trạng thái đọc local và phát sự kiện có scope ngay, trước khi đợi network. Khi restart/offline, dữ liệu Home/snapshot cũng phải phủ pending read lên. Không đánh dấu local trước khi ghi pending thành công; không hoàn tác ý định đọc chỉ vì RPC lỗi.

Đây là phần sửa F6 còn thiếu, không có nghĩa popup trên cùng máy đã mất cơ chế chống nhắc lại khi offline.

## 3. G2 — P2: Dashboard trả muộn có thể khôi phục unread

**Vị trí:** `data/CampaignRepository.kt:1892–1929`; `ui/viewmodels/HomeViewModel.kt:735`; các đường snapshot ở `HomeViewModel.kt:203,320`.

**Tái hiện có kiểm soát thứ tự:**

1. Bắt đầu `getMyDashboard(forceRefresh=true)`, giữ response chứa N chưa đọc.
2. Ack N thành công; local read set/event được cập nhật.
3. Cho response dashboard cũ quay về.

**Thực tế:** result dashboard vẫn chứa N `isRead=false`. Lớp phủ mới chỉ áp vào `getMyNotifications`, không áp vào dashboard mà Home dùng làm fast path. Kiểm tra mutation version hiện ngăn ghi cache cũ nhưng không làm sạch result được trả về caller. Home có thể ghi result này đè lên state đã nhận MarkedRead.

**Test mới:** `a_dashboard_started_before_ack_must_not_return_unread_after_ack_succeeds`.

**Yêu cầu sửa:** áp một quy tắc đọc local chung ở mọi đường nhập dữ liệu thông báo: dashboard, query riêng, cache RAM, snapshot đĩa và publish vào Home. Áp theo trạng thái mới nhất ở thời điểm trả/publish, không chỉ ở lúc request bắt đầu. Giữ kiểm tra owner/generation/mutation; việc chỉ invalidation cache hoặc chỉ xử lý event không giải quyết response đang bay.

Nếu snapshot cũ được publish sau event trong cùng phiên thì cũng phải giữ trạng thái đọc. Đối với xóa cần đánh giá tombstone tương tự để không hồi sinh bản tin đã xóa.

## 4. G3 — P1: Event đọc tất cả của A trả muộn áp sang B

**Vị trí:** `data/Models.kt:698–702`; `data/CampaignRepository.kt:2377–2389`; `ui/viewmodels/HomeViewModel.kt:357–383`; `NotificationPopupViewModel.kt:118–145`.

**Tái hiện:**

1. A đang đăng nhập, request đánh dấu đọc tất cả đã gửi và bị giữ response.
2. Đổi sang B, tải Home B có bản tin chưa đọc; cache repository cũng đã được clear trong test.
3. Cho request cũ của A hoàn tất.

**Thực tế:** repository phát `MarkedAllRead` không chứa UID/generation. Home subscriber đánh dấu toàn bộ bản tin đang giữ của B thành đã đọc, badge về 0. Popup subscriber cũng không có điều kiện owner cho event này. Clear cache lúc đổi tài khoản không đủ vì request cũ vẫn có thể phát sự kiện sau đó.

**Test mới:** `a_late_mark_all_read_from_user_a_must_not_mark_user_b_home_list_as_read`.

Assertion tái hiện: `Late A MarkedAllRead must not mark B's notifications as read`.

Đây là lỗi **trạng thái UI của B bị tác động**, test không khẳng định bản ghi B trên server đã bị đánh dấu đọc hoặc nội dung riêng tư đã bị lộ.

**Yêu cầu sửa:**

- Gắn UID và generation/operation context được chụp **trước request** vào event. Consumer bỏ event không khớp phiên đang render.
- Sau await, trước khi sửa cache/local read set hoặc emit, kiểm tra context còn hiệu lực. Không chỉ thêm owner ở Home trong khi repository vẫn làm bẩn cache của tài khoản mới.
- `markAllNotificationsAsRead` đang đọc `cachedNotifications` sau await; không được lấy danh sách cache hiện tại của B để bổ sung read IDs cho request A. Chụp tập ID/owner phù hợp ở đầu thao tác hoặc dùng state theo owner.
- Kiểm tra cả A→B→A: chỉ so UID cuối request không nhận biết được phiên cũ. Hủy job là một lớp bổ sung, không thay thế guard kết quả/event.
- Áp cho read một bản tin, ack, delete, read-all và remote status result; thêm bài kiểm tra theo phạm vi thay đổi.

## 5. G4 — P1: Claim trả muộn mở lại bản tin vừa đọc

**Vị trí:** `NotificationPopupViewModel.kt:120–121,309–339`.

**Tái hiện:** RPC claim N đã được xử lý nhưng response bị chậm; trước khi response về, người dùng đọc N qua danh sách thành công. Sau đó cho claim cũ trả `claimed` với N.

**Thực tế:** lúc event MarkedRead đến, state còn Idle nên subscriber return và không ghi lại việc N đã đọc. Khi response về, ViewModel chỉ kiểm tra pending ack store; đọc từ danh sách không tạo pending ack. Nó bỏ qua read overlay mới trong repository và vẫn chuyển sang Showing/Held. Khi đủ điều kiện render, N đã đọc có thể bật popup trở lại.

**Test mới:** `a_late_claim_must_not_show_a_notification_already_read_from_the_list`.

**Yêu cầu sửa:**

- Ghi nhận read/delete mutation theo owner/generation ngay cả khi chưa có Active popup.
- Trước khi nhận candidate từ claim vào state, đối chiếu trạng thái local mới nhất hoặc mutation/tombstone của chính candidate. Khi cần, kiểm tra trạng thái nhẹ ở server, không gọi lại dashboard.
- Giữ cùng receipt/lượt server đã cấp; không hoàn lượt hoặc claim thêm để chữa response cũ. Nếu N đã read/deleted thì dừng trình bày và coi phiên có receipt đó đã dùng suất.
- Kiểm tra lại khi chuyển Held→Showing, vì một bản tin có thể được đọc/xóa trong lúc đang chờ blocker.
- Bao phủ read một bản tin, đọc tất cả, delete, popup ack và các response đang bay; không chỉ chặn duy nhất đường pending ack offline.

## 6. T1 — P2: Runner concurrency lỗi trước khi đến bước tranh lượt

**Vị trí:** `supabase/tests/run-admin-popup-concurrency-test.mjs:10,40–54`.

Runner thực hiện SET ROLE authenticated với danh tính admin, gọi gửi riêng cho target rồi:

1. SELECT notification theo `user_id=target` bằng phiên admin. Policy đọc hiện chỉ cho đọc bản tin của chính người dùng, nên query trả **0 dòng**; `notifRow.id` sẽ lỗi vì undefined.
2. Dù sửa bước lấy ID, runner tiếp tục UPDATE `notification_popup_states` khi còn role authenticated. Bảng state đã revoke quyền client nên thao tác bị **permission denied** đúng thiết kế.

Probe đã chạy bằng cùng migration/role trên PGlite:

```text
RUNNER SETUP PROBE 1: rows visible to authenticated admin sender = 0
RUNNER SETUP PROBE 2: permission denied for table notification_popup_states
```

**Đây chỉ là kiểm chứng setup SQL, không phải kiểm chứng concurrency nhiều connection.** Không có thay đổi nào được chạy trên database thật.

Các thiếu sót khác trong runner cùng file:

- Phụ thuộc `pg` nhưng `supabase/tests/package.json` chưa khai báo; hướng dẫn cài thủ công chưa tạo khả năng chạy lặp lại từ checkout sạch.
- Dùng các UUID cố định nhưng không tự tạo fixtures/kiểm tra vai trò, không bảo đảm target không có popup cũ đủ điều kiện, ID session cố định có thể replay dữ liệu từ lần chạy trước.
- Không cleanup thông báo/receipt đã tạo; query theo title chung không bảo đảm tìm đúng bản của lần chạy này.
- Fallback từ `TEST_DATABASE_URL` sang `DATABASE_URL` có thể chọn nhầm database khi vận hành. Nên yêu cầu URL test rõ ràng; không tự fallback.

**Yêu cầu sửa runner:** chuẩn bị fixtures/fast-forward count/đối soát/cleanup bằng kết nối quản trị của database test, còn thao tác kiểm chứng quyền/claim vẫn thực hiện bằng đúng user JWT role authenticated. Không grant quyền sản phẩm cho authenticated để làm runner chạy được. Dùng user/notification/session ID riêng từng lần chạy, target cô lập, cleanup finally; bổ sung dependency và lệnh chạy. Không tự dùng production.

Nếu chưa có PostgreSQL test phù hợp, bổ sung runner hoàn chỉnh và test bước setup bằng fixture sẵn có; ghi rõ phần multi-connection chưa chạy, không ghi PASS cho skipped test.

## 7. Thứ tự sửa đề xuất

1. **Context danh tính cho operation/event (G3):** chốt owner/generation trước request, giữ guard trước/sau await, tách trạng thái theo owner.
2. **Nguồn trạng thái đọc local chung (G1/G2):** pending bền vững được chiếu lên UI ngay; confirmed reads và delete tombstones nhất quán qua dashboard/query/cache/snapshot.
3. **Candidate popup tuân thủ mutation mới nhất (G4):** đối chiếu read/delete khi claim về và khi sắp render Held. Không tăng thêm lượt để giải quyết cạnh tranh UI.
4. **Runner kiểm chứng (T1):** tách quyền setup và quyền claim, fixture cô lập, dependency rõ ràng, chạy đúng môi trường test.
5. Chạy chung toàn bộ test popup/store/localization + backend popup + build, đọc XML để báo cáo đúng số test. Giữ các bài regression vòng trước xanh.

Không thấy cần sửa SQL sản phẩm chỉ để giải quyết bốn lỗi Android này. Nếu triển khai thực tế cần sửa database, tạo migration tiếp nối tương thích; không sửa riêng lịch sử 067 rồi coi database đã được vá.

## 8. Lệnh kiểm chứng

Backend hiện có:

```powershell
node supabase/tests/run-admin-direct-notification-popup-test.mjs
```

Probe runner setup (không dùng database ngoài):

```powershell
node audit/admin-popup-review-round3-2026-10-30/verify-concurrency-runner-setup.mjs
```

Chạy chung Android:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest --tests 'com.ntt.crosstest.ui.viewmodels.NotificationPopupViewModelTest' --tests 'com.ntt.crosstest.ui.viewmodels.NotificationPopupReviewRegressionTest' --tests 'com.ntt.crosstest.ui.viewmodels.NotificationPopupRound3RegressionTest' --tests 'com.ntt.crosstest.data.NotificationPendingAckStoreTest' --tests 'com.ntt.crosstest.localization.LocalizationHardeningTest'
.\gradlew.bat assembleDebug
```

Test vòng 3 nằm tại `app/src/test/java/com/ntt/crosstest/ui/viewmodels/NotificationPopupRound3RegressionTest.kt`. Test dùng response bị giữ bởi CompletableDeferred để kiểm soát thứ tự, không dựa vào sleep hoặc may rủi mạng.

## 9. Giới hạn và phạm vi thay đổi

Chưa kiểm thử UI trên thiết bị/emulator thật hoặc chạy PostgreSQL nhiều connection. Chưa xác minh trạng thái migration production. Không có lỗi vượt 5 được chứng minh từ test tuần tự hiện tại, cũng chưa thể kết luận concurrency production đã được nghiệm thu.

Lần kiểm tra này chỉ thêm regression test vòng 3, probe/log/XML bằng chứng, tài liệu này và prompt sửa vòng 3; không sửa source sản phẩm hoặc thay đổi test cũ để làm xanh kết quả.
