# Kế hoạch sửa lỗi không thực hiện được “Đẩy app”

Ngày kiểm tra: **29/10/2026**. Phạm vi lần này: phân tích ảnh, đọc source, tái hiện trong database local và lập kế hoạch. **Chưa sửa code sản phẩm, chưa chạy migration lên Supabase, chưa phát hành APK.**

## 1. Kết luận và bằng chứng

Lỗi trong ảnh là **`PUSH_METADATA_READ_ONLY` / SQLSTATE `P0001`** khi gọi RPC `push_campaign`. Số dư hiển thị 483 Credit nên không có dấu hiệu đây là lỗi thiếu 100 Credit. Con số 383 trong hộp thoại chỉ là số dư dự kiến; ảnh không chứng minh đã thu tiền.

Đã tái hiện đúng thông báo lỗi trên full schema hiện tại, gồm migration 054, bằng PGlite local:

| Context gọi cùng RPC/cùng fixture | Kết quả | Số dư | Receipt / log thu phí | Metadata |
|---|---|---|---|---|
| `postgres`, chỉ đặt user claim | Thành công | 1000 → 900 | 1 / 1 | Có `push_order` |
| `SET LOCAL ROLE authenticated`, đặt cùng user claim | Bị `PUSH_METADATA_READ_ONLY`, `P0001` | 1000 → 1000 | 0 / 0 | `push_order = null` |

Hai ca chạy trong transaction độc lập và rollback sau khi đọc kết quả. **RPC hợp lệ của người dùng đang bị trigger chặn; giao dịch lỗi local đã rollback cả tiền lẫn log.** Không suy diễn số dư thực tế trên production từ fixture này; khi triển khai cần đối soát request thực tế nếu có nghi ngờ thu phí.

Bằng chứng có thể chạy lại:

- `audit/push-rpc-role-2026-10-29/probe.mjs`.
- `audit/push-rpc-role-2026-10-29/generated-probe.mjs` (harness sinh từ bootstrap/fixture có sẵn).
- `audit/push-rpc-role-2026-10-29/results.json`.
- Lệnh: `node audit/push-rpc-role-2026-10-29/probe.mjs`.

Lệnh kết thúc exit code 0 có nghĩa **probe đã xác nhận được lỗi dự kiến**, không có nghĩa tính năng Đẩy app đã hoạt động. Chưa kiểm thử HTTP PostgREST hoặc thiết bị bằng probe này.

## 2. Nguyên nhân gốc

### R01 — Trigger nhầm role của request với quyền thực thi RPC (P1)

Tại `supabase/migrations/054_push_and_carryover_hardening.sql:46–67`, trigger `trg_fn_protect_campaign_push_metadata` là hàm chạy theo quyền người gọi. Nó tính:

```sql
v_active_role := coalesce(nullif(current_setting('role', true), ''), current_user);
```

Sau đó từ chối nếu **bất kỳ** điều kiện nào đúng: active role là authenticated/anon/public, `current_user` là các role đó, hoặc `session_user` là anon.

RPC `push_campaign` tại cùng migration, dòng 71 trở đi, là `SECURITY DEFINER`. Khi chạy bên trong RPC, quyền thực thi hiệu lực chuyển sang owner của hàm; nhưng setting `role` vẫn có thể là **authenticated** của request ban đầu. Vì vậy điều kiện `v_active_role in (...)` vẫn đúng, chặn cả cập nhật trả phí hợp lệ. RPC đã đặt cờ transaction `app.allow_push_metadata_write=true` nhưng bị từ chối trước khi kiểm tra cờ này.

Thông báo trong ảnh trùng chính xác nhánh từ chối ở migration 054 dòng 60. Full schema có cùng định nghĩa tại khoảng dòng 22854–22876. Đây là nguyên nhân tái hiện được trên source hiện tại; chưa đọc định nghĩa hàm trực tiếp trên production.

### R02 — Test kiểm tra chặn giả mạo nhưng bỏ sót mua hợp lệ dưới cùng role (P1)

`run-push-and-carryover-hardening-test.mjs` nạp full schema gồm 054. Tuy nhiên:

- Các ca mua tạo receipt P01/P02 chỉ đặt claim `sub`, vẫn chạy với quyền quản trị local.
- P03 mới chuyển sang `authenticated` để thử ghi metadata trái phép và xác nhận bị chặn.
- Thiếu cặp test **authenticated gọi RPC hợp lệ phải thành công** ngay cạnh ca ghi trực tiếp phải thất bại.

Runner mua app cũ cần được kiểm tra baseline đang nạp; kết quả PASS trước migration 054 không chứng minh trigger mới không phá luồng mua. Báo cáo “tất cả PASS” trước đây chưa bao phủ đường quyền thực thi gặp trong ảnh.

### R03 — Bản đang dùng hiển thị lỗi kỹ thuật; source hiện tại có khác biệt (P2)

Ảnh hiển thị cả code, URL, header Authorization/Bearer và chi tiết request trong dialog. Đây là thông tin không phù hợp để hiển thị cho người dùng.

Source hiện tại:

- `OwnerAppDetailViewModel.kt:826` có `friendlyPushError`, chưa map riêng `PUSH_METADATA_READ_ONLY`.
- Fallback gọi `friendlyError` trong `AuthViewModel.kt:212`; **bản source đang kiểm tra đã trả chuỗi generic ở nhánh mặc định**, không trả nguyên exception cho UI.
- `OwnerAppDetailScreen.kt:790` hiển thị trực tiếp `uiState.pushError`, nên nguồn string lỗi phải được kiểm soát.
- Fallback hiện vẫn log nguyên message/exception ở `AuthViewModel.kt`, có nguy cơ mang URL/header vào log.

Do đó không kết luận source hiện tại vẫn trả lỗi thô ra màn hình. Cần xác minh versionCode/versionName, commit/build của APK cài trên máy và thử lại bản build mới. Kế hoạch phải xử lý cả thông báo riêng của Đẩy app và log an toàn, đồng thời kiểm thử source thật thay vì chỉ thay chuỗi ở một màn.

### R04 — Đường xác minh pending còn bị UI chặn khi dưới 100 Credit (P2)

`OwnerAppDetailViewModel.pushApp` đã cho phép xử lý request pending bỏ qua kiểm tra số dư. Nhưng nút xác nhận ở `OwnerAppDetailScreen.kt:806` vẫn có điều kiện `!isPushLoading && isSufficient`. Vì vậy người dùng dưới 100 Credit có thể không bấm được để xác minh giao dịch cũ, dù test gọi ViewModel trực tiếp đang PASS.

Đây là lỗi liên quan cần xử lý cùng luồng lỗi/timeout, tránh sau khi sửa RPC lại kẹt ở pending. Nó không phải nguyên nhân lỗi trong ảnh vì số dư ảnh đang trên 100.

## 3. Phương án sửa backend

1. Tạo **migration mới sau số cuối đang có** (hiện là 054, dự kiến 055 nếu chưa có công việc khác chiếm số). Không chỉ sửa file 054 đã triển khai; không chạy lại cả 054 vốn chứa nhiều thay đổi cộng dồn nhiệm vụ không liên quan.
2. Sửa trigger để phân biệt bằng **quyền thực thi hiệu lực (`current_user`) trong ngữ cảnh trigger chạy theo người gọi**, thay vì từ chối chỉ vì `current_setting('role')` còn mang role gốc của request. Không lấy `session_user` làm tiêu chí quyết định một RPC hợp lệ phải bị chặn.
3. Giữ trigger ở `SECURITY INVOKER`. Không chuyển trigger thành `SECURITY DEFINER`, vì như vậy ghi trực tiếp từ client cũng có thể nhìn như quyền tin cậy.
4. Metadata chỉ được ghi khi đồng thời: đang chạy với role owner tin cậy của RPC đã kiểm soát và có cờ transaction cho đoạn cập nhật metadata. Không chỉ kiểm tra GUC; client tự đặt GUC vẫn phải bị từ chối. Kiểm tra owner thực tế của RPC, trigger, quyền EXECUTE và các hàm definer khác có thể sửa campaign. Nếu dùng role chuyên biệt, role đó không được cấp cho client và chỉ có quyền cần thiết.
5. Giữ `push_campaign` là `SECURITY DEFINER`, search path cố định, kiểm tra `auth.uid`, chủ app, khóa tài khoản, trạng thái app, số dư và thời hạn như hiện tại. Không dùng service key hoặc tăng quyền client để né trigger.
6. Giữ một transaction cho trừ 100, log, metadata, receipt. Giữ khóa/sequence và request ID để thứ tự đẩy và replay không thay đổi. Cờ cho ghi metadata phải transaction-local và được giới hạn đúng đoạn cập nhật.
7. Đồng bộ định nghĩa cuối trong `full_schema_singapore.sql`, kiểm tra nâng cấp từ baseline 054 và dựng database mới.

Tiêu chí quan trọng: **authenticated mua đúng quyền thành công, authenticated ghi trực tiếp vẫn thất bại**, kể cả khi tự đặt `app.allow_push_metadata_write=true`. Không nghiệm thu bằng cách tắt trigger/RLS hoặc bỏ kiểm tra quyền.

## 4. Phương án sửa Android và thông báo

1. Giữ nguyên bước xác nhận trước mua, giá 100 và thời hạn đến 00:00 ngày kế tiếp giờ Việt Nam.
2. Map lỗi backend đã biết bằng mã ổn định từ response, ưu tiên trường lỗi có cấu trúc thay vì tìm trong chuỗi exception chứa thông tin HTTP. Với lỗi trigger này: **“Hiện chưa thể đẩy app. Vui lòng thử lại sau.”** Chỉ bổ sung “Chưa trừ Credit” khi đã xác định server từ chối và rollback giao dịch này; không dùng câu đó cho timeout/không rõ kết quả.
3. Mọi lỗi lạ đi về thông báo generic đã bản địa hóa. UI không hiển thị raw exception, URL, header, token, SQLSTATE, Hint hoặc Details. Kiểm tra cả build thực tế và các nhánh catch khác; giữ fallback an toàn đang có trong source.
4. Log chẩn đoán chỉ gồm mã lỗi, tên thao tác và correlation/request ID phù hợp. Không log nguyên Authorization, JWT, refresh token hoặc toàn bộ exception message có request header. Không đưa service key hay token người dùng vào báo cáo/test artifact.
5. Phân biệt rõ **mua mới**, **đang xác minh giao dịch cũ**, **server từ chối chắc chắn** và **chưa biết kết quả**. Lỗi mạng/timeout giữ request ID; không tự tạo ID khác để mua lại.
6. Request từng bị server từ chối không được tự động mua lại khi vừa mở màn sau lúc backend được sửa. Nếu gửi lại một RPC có khả năng mua khi receipt chưa tồn tại, phải có xác nhận thể hiện rõ có thể trừ 100 nếu thực hiện thành công. Nếu cần xác minh mà không mua, dùng API đọc receipt/trạng thái; không gắn nhãn “chỉ kiểm tra” cho RPC có thể thu phí.
7. Cho pending được truy vấn/xác minh cả khi số dư dưới 100. Điều kiện enable và nhãn nút phải dựa trên trạng thái pending, không chỉ `isSufficient`; không bỏ kiểm tra 100 cho **mua mới**. Bổ sung test qua nút UI, không chỉ gọi `pushApp()` trực tiếp.
8. Thành công sau sửa: số dư, lịch sử và thứ tự Trang chủ cập nhật; mua mới tiếp theo vẫn yêu cầu xác nhận. Không thay thuật toán hết ngày hoặc quy tắc app đẩy sau đứng trên.

## 5. Kiểm thử bắt buộc trước nghiệm thu

| Ca | Kết quả phải đạt |
|---|---|
| Authenticated + đúng chủ + 100 Credit gọi RPC | Thành công, còn 0, một log -100, một receipt, metadata đầy đủ |
| Authenticated + 483 Credit | Thành công còn 383; không còn PUSH_METADATA_READ_ONLY |
| Authenticated ghi trực tiếp INSERT/UPDATE metadata | Bị chặn, không có ưu tiên miễn phí |
| Authenticated tự đặt GUC rồi UPDATE metadata | Vẫn bị chặn; không nới bảo vệ như trước 054 |
| Guest, người khác, locked, campaign không hợp lệ | Bị từ chối đúng nguyên nhân, không thu tiền |
| Đủ tiền nhưng trigger/log/receipt bị ép lỗi | Rollback tiền, log, metadata và receipt |
| Gửi lại cùng request sau thành công | Trả receipt cũ, không trừ lần hai, không tăng thứ tự |
| Từ chối xác định rồi thử lại sau sửa | Không mua tự động khi mở màn; muốn thực hiện mua phải xác nhận |
| Timeout sau commit, mở lại, số dư dưới 100 | UI cho xác minh receipt, không kẹt nút, không thu thêm |
| Hai request đồng thời / hai thiết bị | Không âm tiền, không double charge cùng ID; thứ tự commit nhất quán |
| A mua, B mua sau, A mua lại | Thứ tự B/A rồi A/B, mỗi lượt mới đúng 100 |
| Qua 00:00 VN, offline/resume/cache | Hết ưu tiên cũ, quay về comparator/xáo trộn cũ |
| Lỗi có payload giống ảnh với token giả | UI và log không chứa header/token/URL/raw SQL exception |
| Chuyển Việt/Anh, chữ lớn/màn hình nhỏ | Nội dung lỗi ngắn, dialog không bị tràn, nút vẫn thao tác được |

Test quyền phải chuyển role thật (`SET LOCAL ROLE authenticated`) và claims trước RPC; không chỉ đặt `sub` trong phiên postgres. Thử local PostgreSQL/PostgREST hoặc Supabase staging bằng tài khoản thường để xác minh HTTP → RPC → trigger; probe PGlite chỉ chứng minh lỗi role local, không thay thế bước này. Kiểm thử đồng thời cần nhiều kết nối database thực tế.

Đưa cặp test mua hợp lệ/ghi trái phép vào suite chạy **sau migration sửa mới**, đồng thời cập nhật baseline các runner liên quan để chúng không chỉ kiểm tra trước 054. Không sửa test để chấp nhận mua thất bại.

## 6. Thứ tự triển khai và bàn giao

1. Giữ nguyên thay đổi chưa commit của các công việc khác; chụp baseline source/hash trước sửa. Bổ sung test tái hiện mua thất bại bằng role người dùng trước khi sửa sản phẩm.
2. Tạo migration sửa trigger và đồng bộ full schema; chạy test quyền/rollback/replay mới và các test Đẩy app hiện có.
3. Sửa mapping lỗi/log, trạng thái pending và điều kiện nút; chạy test Android liên quan, `:app:testDebugUnitTest` và `:app:assembleDebug`.
4. Kiểm tra staging qua API bằng JWT người dùng thường. Xác minh owner/`SECURITY DEFINER`/`SECURITY INVOKER` đúng như thiết kế, ghi lại migration đã áp dụng mà không ghi token.
5. Đối chiếu APK máy đang lỗi với bản source/build đã kiểm thử; cài bản thử và thao tác qua hộp thoại. Backend sửa có thể khôi phục giao dịch cho client hiện tại; thay đổi thông báo/log/UI cần bản Android tương ứng.
6. Sau khi test đạt, triển khai migration hẹp trước, theo dõi lỗi Đẩy app và đối soát mỗi giao dịch -100 ↔ receipt. Không tự hoàn hay trừ bổ sung dựa trên ảnh; nếu nghi thu phí phải kiểm tra receipt/log theo request.
7. Bàn giao: file thay đổi, migration, kết quả test thật, version APK kiểm tra, giới hạn còn lại. Nếu chưa thử staging hoặc thiết bị, ghi rõ chưa nghiệm thu đầy đủ.

Ưu tiên sửa: **R01/R02 trước để khôi phục mua hợp lệ mà vẫn chặn giả mạo**, sau đó R03/R04 để lỗi hiển thị an toàn và giao dịch pending không bị kẹt. Không cần thiết kế lại tính năng Đẩy app hoặc thay các quy tắc đã chốt.
