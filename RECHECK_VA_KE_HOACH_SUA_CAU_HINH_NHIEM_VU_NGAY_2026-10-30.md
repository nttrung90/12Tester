# Kiểm tra và kế hoạch sửa cấu hình nhiệm vụ ngày trong Admin

Ngày kiểm tra: **30/10/2026**, giờ Việt Nam. Đối chiếu kế hoạch ngày 29/10 với source hiện tại, chạy backend local bằng PGlite, unit test Android và build debug. **Có lỗi cần sửa trước khi nghiệm thu.** Lần kiểm tra này chỉ thêm tài liệu và kiểm thử trong `audit/admin-daily-task-review-2026-10-30`; không sửa code sản phẩm, không thay đổi Supabase production.

## 1. Kết quả đã xác minh

| Hạng mục | Kết quả |
|---|---|
| Backend test đang có cho 058 | 10 nhóm kiểm tra được runner báo PASS: đọc cấu hình, quyền, validation, tạo/sửa/hủy lịch, revision và actor audit. |
| Unit test `AdminDailyTaskPolicyTest` | **7/7 PASS**. Chủ yếu kiểm tra DTO/serialization và các phép kiểm tra đơn giản; chưa kiểm tra ViewModel hoặc giao diện. |
| Build `:app:assembleDebug` | **PASS**. Có APK debug ở `F:\AI\12Tester\app\build\outputs\apk\debug\app-debug.apk`. Build thành công không chứng minh các luồng đồng thời đúng. |
| Hồi quy tỷ lệ 042 | **PASS**, gồm 2016 SQL assertions và 8 runner checks. |
| Hồi quy carryover 053 | **10 nhóm PASS**. |
| Hồi quy push/carryover hardening 054 | **10 nhóm PASS**. |
| Kiểm thử Android độc lập bổ sung | **5/5 FAIL bằng assertion**, tái hiện lỗi state trên code hiện tại; không phải lỗi compile hoặc lỗi môi trường. |
| Kiểm thử SQL độc lập bổ sung | Tái hiện bỏ sót dòng khi phân trang, cắt danh sách 50 lịch và lỗi gọi lặp trong cùng transaction. |
| Áp dụng lại migration 058 riêng trên database fixture | Giữ nguyên policies/audit; function bodies khớp phần 058 trong schema gộp sau chuẩn hóa CRLF/LF. Khác biệt phần preflight/transaction trong schema gộp không được kết luận là lỗi nghiệp vụ. |
| Resource mới | Cả `values`, `values-en`, `values-vi` có đủ **49 key `admin_daily_task*`**, không thiếu hoặc trùng key. Chưa kiểm tra giao diện thật trên thiết bị. |

## 2. Các lỗi và phần thiếu

### R01 — P1: Request độc lập triệt tiêu nhau; phản hồi cũ xóa cấu hình hiện tại

Vị trí: [AdminUsersViewModel.kt:353](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/viewmodels/AdminUsersViewModel.kt:353), [421](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/viewmodels/AdminUsersViewModel.kt:421), [519](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/viewmodels/AdminUsersViewModel.kt:519).

Tải cấu hình, lưu, hủy và tải lịch sử đều tăng **cùng `dailyTaskSeq`**. Trong khi lưu đang chờ mạng, admin mở lịch sử sẽ làm request lưu trở thành “cũ”. Backend có thể lưu thành công nhưng nhánh response cũ lại đặt `dailyTaskConfig = null`, bỏ thông báo thành công và revision mới. Mở lịch sử trong khi tải cấu hình cũng gây mất kết quả tải. Sheet vẫn cho mở lịch sử trong thời gian mutation.

Nhánh bỏ response cũ còn sửa state chung thay vì chỉ bỏ kết quả: khi đổi A → B, B đã tải `rev-B`, response A trả về muộn xóa luôn cấu hình B. Đã tái hiện bằng MockEngine gọi repository/ViewModel thật.

Ba test FAIL:

- `expandingHistoryMustNotDiscardSuccessfulSave`: mong `rev-saved`, thực tế NULL.
- `expandingHistoryMustNotDiscardConfigRequest`: tải xong cấu hình vẫn NULL.
- `oldAccountResponseMustNotEraseNewAccountConfig`: mong `rev-B`, thực tế NULL.

**Cách sửa:** tách sequence/job cho config, history và mutation; serialize save/cancel chung. Dùng session epoch/UID/generation riêng để vô hiệu hóa toàn bộ request khi đổi phiên. Response không còn sở hữu state phải `return` mà không xóa config/loading/error của request hoặc phiên mới. Reset state trong bước chuyển phiên có kiểm soát. Response config được khởi động trước mutation không được ghi đè snapshot mới hơn do mutation trả về. History chạy song song không được ảnh hưởng config/mutation. Kiểm tra thêm request cũ đang lỗi, đang hủy và loading của phiên mới.

### R02 — P2: Phân trang bỏ sót một dòng ở mỗi ranh giới trang

Vị trí: [058_admin_daily_task_policy.sql:337](F:/AI/12Tester/supabase/migrations/058_admin_daily_task_policy.sql:337), [387](F:/AI/12Tester/supabase/migrations/058_admin_daily_task_policy.sql:387).

Hai hàm phân trang chọn cursor bằng **dòng dư thứ `limit+1`** (`offset v_limit`), nhưng trang sau dùng điều kiện nghiêm ngặt `>` hoặc `<`. Dòng dư chưa được trả cho client và cũng bị loại ở trang sau.

Fixture page size=2:

| Danh sách | Đúng sau hai trang | Thực tế |
|---|---|---|
| Lịch tương lai | IDs `[2,3,4,5]` | `[2,3,5,6]`, bỏ ID 4 |
| Audit giảm dần | IDs `[56,55,54,53]` | `[56,55,53,52]`, bỏ ID 54 |

Ngoài ra, các câu `SELECT ... FROM tmp_* LIMIT/OFFSET` không có `ORDER BY`, nên không nên phụ thuộc thứ tự vật lý của bảng tạm.

**Cách sửa:** đọc `limit+1` để tính `has_more`, trả đúng `limit` dòng có thứ tự; cursor lấy từ **dòng cuối thực sự trả về**, chỉ xuất cursor nếu còn trang. Các bước chọn dòng/cursor đều có thứ tự tường minh. Test duyệt hết nhiều trang, so sánh toàn bộ IDs với query gốc: không mất, không trùng.

### R03 — P2: App không xem/sửa/hủy được lịch sau 50 lịch đầu

Vị trí: [058_admin_daily_task_policy.sql:162](F:/AI/12Tester/supabase/migrations/058_admin_daily_task_policy.sql:162), [AdminDailyTaskPolicySheet.kt:585](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/components/AdminDailyTaskPolicySheet.kt:585), [CampaignRepository.kt:2122](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt:2122).

`admin_get_daily_task_config()` trả tối đa 50 lịch, không có `has_more`/cursor. Repository có `adminListDailyTaskPolicies`, nhưng không có lời gọi từ ViewModel/Sheet để tải trang tiếp theo. Fixture có 55 lịch: response chỉ có 50; năm lịch cuối vẫn tác động nghiệp vụ nhưng admin không thấy trong danh sách.

**Cách sửa:** nối API phân trang vào ViewModel và giao diện. Snapshot đầu phải có metadata trang tiếp theo, hoặc cho ViewModel dùng endpoint danh sách phân trang làm nguồn danh sách duy nhất. Hiển thị nút tải thêm; merge theo id với thứ tự ổn định. Mutation làm mới trang đầu và vô hiệu hóa cursor cũ. Không âm thầm tăng limit để che lỗi. Giữ các lịch khác khi sửa/hủy một lịch.

### R04 — P2: Thông báo xung đột bị xóa ngay khi tự tải lại

Vị trí: [AdminUsersViewModel.kt:356](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/viewmodels/AdminUsersViewModel.kt:356), [460](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/viewmodels/AdminUsersViewModel.kt:460), [510](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/viewmodels/AdminUsersViewModel.kt:510).

Khi save/cancel gặp `DAILY_TASK_POLICY_CONFLICT`, ViewModel gán lỗi rồi gọi `loadDailyTaskConfig(resetDraft=false)`. Hàm tải xóa `dailyTaskError` ngay đầu request. Kết quả: server đã đổi revision, bản nháp vẫn còn nhưng cảnh báo để admin đối chiếu biến mất. Test `conflictMustRemainVisibleAfterAutomaticReload` xác nhận config đã lên `rev-B` còn error=NULL.

**Cách sửa:** tách lỗi mutation/conflict khỏi lỗi tải; reload nền không được xóa conflict. Giữ bản nháp và hiển thị cấu hình mới để admin xem lại, có thao tác xác nhận/xóa cảnh báo rõ ràng trước khi gửi lại. Không tự gửi lại với revision mới. Áp dụng cả luồng hủy.

### R05 — P2: Tải lỗi vẫn hiển thị mức 3 như chính sách thật; form thiếu điều kiện sẵn sàng

Vị trí: [AdminDailyTaskPolicySheet.kt:247](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/components/AdminDailyTaskPolicySheet.kt:247), [287](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/components/AdminDailyTaskPolicySheet.kt:287), [525](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/components/AdminDailyTaskPolicySheet.kt:525), [AdminUsersViewModel.kt:370](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/viewmodels/AdminUsersViewModel.kt:370).

Bằng chứng source: chỉ khi `isLoading && config == null` mới ẩn nội dung. Sau lỗi tải đầu tiên, `config=null` và loading=false thì thẻ “đang áp dụng” dùng `?: 3`, hiện thêm nội dung nền dù chưa biết mức trên Supabase. `isFormValid` chỉ kiểm tra tỷ lệ, ngày không rỗng, ghi chú và `!isSaving`; không yêu cầu config/revision hợp lệ, không kiểm tra ngày > server today, `!isLoading` hoặc `!isCancelling`. ViewModel vẫn chặn thiếu revision nên đây **không phải bằng chứng đã ghi sai tỷ lệ trên server**, nhưng thông tin hiện hành bị bịa và nút lưu có thể bật khi chưa sẵn sàng.

**Cách sửa:** chỉ hiện mức khi có active policy được server xác nhận; unknown/error có retry riêng. Không fallback 3/6 hoặc ngày thiết bị khi thiếu dữ liệu bắt buộc. Cho nhập bản nháp nếu muốn nhưng chặn mutation cho đến khi snapshot/revision/server date hợp lệ. Save/cancel dùng cùng mutex hoặc trạng thái mutation, được chặn đồng bộ trước khi launch coroutine. Ngày phải parse được và ở tương lai theo ngày server; ngày snapshot đã cũ cần tải lại. Kiểm tra DatePicker ở thiết bị có timezone khác Việt Nam.

### R06 — P2: Lịch sử cũ không được cập nhật; thiếu thông tin trước/sau và múi giờ

Vị trí: [AdminUsersViewModel.kt:430](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/viewmodels/AdminUsersViewModel.kt:430), [488](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/viewmodels/AdminUsersViewModel.kt:488), [AdminDailyTaskPolicySheet.kt:680](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/components/AdminDailyTaskPolicySheet.kt:680), [745](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/components/AdminDailyTaskPolicySheet.kt:745).

- Sau khi đã mở lịch sử, save/cancel không xóa hoặc refresh danh sách/cursor. Đóng mở accordion chỉ tải nếu list rỗng; đóng/mở sheet cũng chỉ tải config. Test `successfulMutationMustInvalidatePreviouslyLoadedHistory` xác nhận lịch sử trước mutation vẫn giữ nguyên sau save thành công.
- DTO có `oldRow`/`newRow` nhưng giao diện không đọc: admin không thấy mức trước/sau hoặc ngày hiệu lực của thay đổi như kế hoạch/hướng dẫn mô tả. UPDATE ghi chú khi hủy và UPDATE đổi tỷ lệ đều hiện nhãn “cập nhật”.
- Server trả `changed_at` UTC có Z; UI cắt chuỗi bằng `take(19)` và bỏ T, không đổi sang giờ Việt Nam hoặc ghi nhãn UTC. Ví dụ `2026-10-29T17:30Z` hiển thị 29/10 17:30, trong khi giờ Việt Nam là 30/10 00:30.
- Nếu admin bị xóa profile, còn `actorId` nhưng thiếu tên/email thì UI gọi là thao tác hệ thống; cần dùng UUID làm fallback để giữ đúng người thực hiện.

**Cách sửa:** mutation thành công invalidate/refresh history và cursor; thêm refresh riêng nếu cần. Render ngày hiệu lực, mức trước→sau và phân biệt cập nhật ghi chú/hủy. Dùng `Instant` và timezone có nhãn rõ; actor fallback theo `actorId` trước khi kết luận hệ thống. Các phần hiển thị cần resource VI/EN đầy đủ.

### R07 — P2: Hủy lịch đóng hộp thoại trước khi có kết quả và mất đường thử lại với lý do cũ

Vị trí: [AdminDailyTaskPolicySheet.kt:850](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/components/AdminDailyTaskPolicySheet.kt:850), [651](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/components/AdminDailyTaskPolicySheet.kt:651).

Bấm xác nhận gọi `onCancelSchedule` rồi lập tức đặt `policyToCancel=null`. Lỗi mạng/conflict xảy ra thì hộp thoại đã mất; mở lại thao tác Hủy đặt `cancelReason=""`, admin phải nhập lại. Nút lưu chính vẫn có thể dùng trong lúc cancel vì chỉ xét `!isSaving`, đồng thời các guard save/cancel của ViewModel không khóa chéo.

**Cách sửa:** lưu target và reason của cancellation trong state có thể khôi phục, giữ dialog đến khi success được xác nhận; lỗi giữ nguyên lý do và hiện lỗi cạnh thao tác hủy. Disable các mutation khác trong thời gian request; không chỉ dựa vào nút UI. Nếu cho đóng sheet trong lúc request, lúc mở lại phải đối soát trạng thái từ server và không tự lặp mutation.

### R08 — P2: Hướng dẫn triển khai vẫn yêu cầu chạy 042, thiếu bước cài 058

Vị trí: [HUONG_DAN_DOI_TY_LE_TEST.md:23](F:/AI/12Tester/supabase/operations/HUONG_DAN_DOI_TY_LE_TEST.md:23), [37](F:/AI/12Tester/supabase/operations/HUONG_DAN_DOI_TY_LE_TEST.md:37), [55](F:/AI/12Tester/supabase/operations/HUONG_DAN_DOI_TY_LE_TEST.md:55).

Đầu tài liệu đã mô tả tính năng 058, nhưng các bước vận hành vẫn yêu cầu preflight cũ rồi chạy 042, còn ghi “Chỉ cần chạy migration 042”. Chưa có bước chạy 058 và phát hành bản app có giao diện. Preflight 042 chỉ chấp nhận body 036/042; source hiện tại có body mới hơn từ 053/054 nên không phải kiểm tra nâng cấp phù hợp. Công thức quota trong hướng dẫn cũng chưa tách phần cơ bản với carryover.

**Cách sửa:** tách rõ cài đặt một lần cho backend hiện hành, sử dụng app, và SQL dự phòng. Nếu đã có các dependency 042 và schema đang dùng: áp dụng migration bổ sung 058 (và migration sửa tiếp theo nếu 058 đã triển khai), không chạy lại 042 hoặc cả schema gộp. Ghi bước backend trước, APK/AAB sau, xác minh endpoint bằng phiên admin, xử lý thiếu migration rõ ràng. Công thức cần dẫn đúng RPC/carryover hiện tại, không yêu cầu quay về body 042.

### R09 — P3: RPC danh sách lỗi nếu gọi lặp trong cùng transaction

Vị trí: [058_admin_daily_task_policy.sql:321](F:/AI/12Tester/supabase/migrations/058_admin_daily_task_policy.sql:321), [375](F:/AI/12Tester/supabase/migrations/058_admin_daily_task_policy.sql:375).

Hai hàm tạo bảng tạm tên cố định `ON COMMIT DROP`. Gọi cùng hàm lần thứ hai trong cùng transaction phát sinh `relation "tmp_policy_history" already exists` hoặc `tmp_future_policies`. Đã tái hiện bằng hai lần gọi trong cùng câu SELECT. Request PostgREST thông thường tách transaction nên **không kết luận bấm Tải thêm trong app sẽ gặp lỗi này**; lỗi ảnh hưởng SQL tổng hợp/batch hoặc tái sử dụng hàm trong transaction khác.

**Cách sửa:** khi sửa R02, dùng CTE/query thuần thay bảng tạm; vừa tránh collision vừa giữ thứ tự rõ. Không dùng `CREATE TEMP TABLE IF NOT EXISTS` đơn thuần vì có thể dùng lại dữ liệu trang cũ.

## 3. Kế hoạch sửa theo thứ tự

### Bước 1 — Sửa state Android (R01, R04, R05, R07)

1. Tách request ownership theo config/history/mutation và session; stale callback không được sửa state của owner mới.
2. Một mutation tại một thời điểm, guard đặt trước khi launch. Mở lịch sử trong lúc mutation vẫn an toàn hoặc bị chặn có chủ đích, không triệt tiêu response.
3. Tách bản nháp với snapshot server, lỗi tải với lỗi mutation/conflict. Không ghi đè bản nháp khi refresh.
4. Trạng thái chưa có dữ liệu không render mức giả; form chỉ gửi khi có config hợp lệ. Giữ lý do hủy qua lỗi/retry.
5. Chạy lại năm test độc lập; bổ sung case cancel đang chờ, old failure sau new success, loading phiên mới và bấm liên tiếp.

### Bước 2 — Sửa backend phân trang (R02, R09)

1. Nếu 058 đã áp dụng trên môi trường dùng chung, tạo migration mới tiếp theo, tên dự kiến `059_admin_daily_task_policy_hardening.sql`; kiểm tra số còn trống. Không chỉ sửa 058 rồi cho rằng database đã tự được cập nhật.
2. Thay query hai endpoint phân trang bằng CTE có `ORDER BY`, cursor từ dòng cuối đã trả. Giữ API cũ tương thích nếu có thể.
3. Giữ nguyên quyền admin, audit actor, khóa revision, bất biến ngày và trigger commit. Không đổi lịch/tỷ lệ đã có, công thức nhiệm vụ, Credit/carryover.
4. Test danh sách 0/1/2/nhiều trang, limit 1/2/20/100, cursor cuối, gọi hai lần trong transaction; giữ nguyên bản ghi và thứ tự.

### Bước 3 — Hoàn thiện danh sách và lịch sử (R03, R06)

1. Bổ sung metadata phân trang vào snapshot hoặc thống nhất nguồn endpoint list; DTO/ViewModel/Sheet có tải thêm lịch tương lai.
2. Test ít nhất 55 lịch; tải hết, sửa/hủy lịch thứ 51 không ảnh hưởng lịch khác.
3. Invalidate mọi cursor/list liên quan khi mutation thành công, rồi tải lại theo trạng thái mở/đóng accordion.
4. Hiển thị mức trước/sau, ngày hiệu lực, phân biệt thao tác ghi chú/hủy; thời gian và actor đúng. Test timezone qua ngày và actor profile bị xóa.

### Bước 4 — Tài liệu, kiểm thử và bàn giao (R08)

1. Sửa hướng dẫn nâng cấp cho schema hiện tại, thêm backend 058/migration sửa mới và bước phát hành app ban đầu.
2. Đưa các test tái hiện cần thiết vào bộ test sản phẩm sau khi sửa; không chỉ thêm test JSON hoặc phép tính tự định nghĩa không gọi code thật.
3. Runner backend test phải nạp và kiểm tra cả migration riêng lẫn schema gộp, dữ liệu đã tồn tại và đường role `authenticated` thực tế.
4. Chạy lại baseline backend, unit test liên quan, build debug; thêm kiểm tra thiết bị cho tải lỗi, nhập liệu, lịch dài, dialog hủy, font lớn và tiếng Việt/Anh.
5. Kiểm tra nhiều connection và mốc 00:00 trên PostgreSQL/Supabase staging. Không dùng PGlite một connection để kết luận đã kiểm tra khóa chờ hoặc cron production.

## 4. Bằng chứng có thể chạy lại

- [SQL probe](F:/AI/12Tester/audit/admin-daily-task-review-2026-10-30/database-probe.mjs) và [kết quả JSON](F:/AI/12Tester/audit/admin-daily-task-review-2026-10-30/database-results.json).
- [Android test độc lập](F:/AI/12Tester/audit/admin-daily-task-review-2026-10-30/tests/com/ntt/crosstest/data/AdminDailyTaskIndependentReviewTest.kt), [tóm tắt lỗi](F:/AI/12Tester/audit/admin-daily-task-review-2026-10-30/android-summary.json), [log](F:/AI/12Tester/audit/admin-daily-task-review-2026-10-30/android-probes.log).
- [Báo cáo test HTML](F:/AI/12Tester/audit/admin-daily-task-review-2026-10-30/android-html/index.html) và XML trong thư mục `android-results` bên cạnh.
- [Fingerprint source đã review](F:/AI/12Tester/audit/admin-daily-task-review-2026-10-30/source-sha256.json), [kiểm tra resource](F:/AI/12Tester/audit/admin-daily-task-review-2026-10-30/resource-results.json).

Chạy từ `F:\AI\12Tester`:

```powershell
node audit/admin-daily-task-review-2026-10-30/database-probe.mjs
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat -I audit/admin-daily-task-review-2026-10-30/review.init.gradle :app:testDebugUnitTest --tests 'com.ntt.crosstest.data.AdminDailyTaskIndependentReviewTest' --console=plain
```

SQL probe xuất quan sát `bug:true` để giữ bằng chứng từng ca và hoàn thành cả lượt kiểm tra; exit code 0 **không có nghĩa feature không còn lỗi**. Android probe dùng assertion kỳ vọng đúng nên hiện trả exit code 1 với 5 test FAIL. Sau sửa, phải đánh giá nội dung từng kết quả và các điều kiện nghiệp vụ, không chỉ nhìn mã thoát.

## 5. Điều kiện nghiệm thu sau sửa

- Năm test Android độc lập PASS và có test bổ sung cho cancel, mutation ownership và lỗi tải lần đầu.
- Phân trang đầy đủ, không bỏ/trùng dòng; admin truy cập được lịch ngoài 50 dòng đầu.
- Lưu/hủy thành công luôn cập nhật snapshot đúng; xem history, refresh hoặc request phiên cũ không xóa kết quả mới.
- Conflict hiện rõ đến khi admin xem lại; tải lỗi không hiện mức giả 3; lỗi hủy giữ lý do.
- Audit hiện được trước/sau, ngày hiệu lực, người thực hiện và thời gian có múi giờ; mutation mới xuất hiện sau refresh.
- Backend vẫn chặn non-admin/ghi bảng trực tiếp, không sửa dữ liệu ngày cũ, Credit hoặc carryover.
- Hướng dẫn triển khai khớp migration thực tế; build và kiểm thử liên quan đạt.

Chưa xác minh tình trạng migration, tỷ lệ hiện hành hoặc hành vi trên Supabase production. Các kết luận runtime trong tài liệu dựa trên source hiện tại, database fixture local và MockEngine/Robolectric; các nhận xét giao diện chưa chạy thiết bị đã được chỉ rõ là bằng chứng source.

