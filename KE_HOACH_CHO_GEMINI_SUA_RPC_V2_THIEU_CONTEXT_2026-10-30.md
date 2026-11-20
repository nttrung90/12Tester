# Bàn giao cho Gemini: sửa lỗi test thường không gọi được RPC v2

## 1. Yêu cầu và giới hạn lượt kiểm tra

Người dùng báo vẫn không nộp được kết quả test AuraHome. Ảnh hiện thông báo hàm chưa cài đặt hoặc đang làm mới cache. Yêu cầu lượt này là kiểm tra và lập kế hoạch cho Gemini sửa. Không sửa mã sản phẩm, không deploy, không build APK mới trong lượt audit này; chỉ thêm probe kiểm chứng, SQL chẩn đoán chỉ đọc và tài liệu bàn giao.

Đã xác nhận lỗi client bằng HTTP MockEngine thực tế của Supabase SDK. Chưa có kết nối quản trị/database hoặc log HTTP của điện thoại nên chưa xác nhận trạng thái triển khai 064 trên production. Không kết luận chỉ cần chạy lại migration là xong.

## 2. P0 — Payload app_test thiếu trường bắt buộc: đã tái hiện

Vị trí:

- `app/src/main/java/com/ntt/crosstest/data/Models.kt`: SubmitTesterReportParams khai báo `submissionContext: String = "app_test"`, SerialName là p_submission_context.
- `app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt`: submitFeedback truyền context vào DTO rồi gọi submit_tester_report_v2.
- `supabase/migrations/064_explicit_report_context.sql`: v2 yêu cầu p_submission_context text và p_campaign_id uuid, cả hai không có default.

Khi context là app_test, serializer đang dùng bỏ trường có giá trị bằng default. Việc caller tường minh truyền "app_test" vẫn không làm trường này xuất hiện trên wire. Khi context là mission, trường đó được gửi vì khác default.

Probe:

`app/src/test/java/com/ntt/crosstest/audit/ReportV2WirePayloadProbeTest.kt`

Chạy từ workspace:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests 'com.ntt.crosstest.audit.ReportV2WirePayloadProbeTest'
```

Kết quả audit: BUILD SUCCESSFUL, probe xác nhận daily body KHÔNG có p_submission_context, mission body có giá trị mission. XML được giữ tại `audit/report-v2-wire-2026-10-30/TEST-com.ntt.crosstest.audit.ReportV2WirePayloadProbeTest.xml`.

Probe này cố ý assert hiện trạng sai để lưu bằng chứng, KHÔNG phải test nghiệm thu bản sửa. Gemini phải đổi assertion thành yêu cầu p_submission_context=app_test sau sửa; không giữ assertion thiếu trường rồi báo test xanh là đã sửa.

Đây là lỗi hợp đồng đủ để giải thích việc PostgREST không tìm được chữ ký v2 khớp payload. Chưa có raw response của lần gửi trong ảnh để khẳng định đó là nguyên nhân duy nhất trên máy người dùng.

## 3. Cách sửa P0

Ưu tiên bỏ default khỏi thuộc tính submissionContext của DTO: khai báo `val submissionContext: String` và bắt caller truyền rõ. Khi thuộc tính không có default, serializer phải đưa giá trị vào payload; xác nhận bằng HTTP body, không chỉ nhìn đối tượng Kotlin.

Phương án khác nếu thật sự cần giữ default: @EncodeDefault(ALWAYS) riêng trường này với import/opt-in phù hợp phiên bản thư viện. Không đổi toàn bộ Json.encodeDefaults của Supabase để chữa một trường, vì có thể đổi payload các RPC khác.

Giữ tham số context bắt buộc ở SQL. Không thêm default app_test phía server để che lỗi, không suy context theo hasJoined, không fallback về RPC cũ. Giữ mọi sửa chống chồng lấn và receipt/retry đã có.

Rà tất cả nơi tạo SubmitTesterReportParams và các API liên quan. Kiểm tra p_campaign_id, clientSubmitId, missionId, rating và thumbnail vẫn đúng khi các giá trị khác trùng default hoặc null; null p_mission_id có thể bị bỏ nếu server default null, nhưng p_submission_context không được thiếu.

## 4. P0 — Kiểm tra deployment, không suy từ thông báo UI

1. Chạy `supabase/operations/check-report-v2-contract.sql` trên đúng project của APK; thu chữ ký, default, ACL và bảng tombstone. Không in anon key/access token khi đối chiếu project.
2. Đối chiếu hàm với migration 064. authenticated phải gọi được v2/reconcile; client không được gọi core nội bộ.
3. Lấy raw mã lỗi HTTP/PostgREST và danh sách tên key payload từ lần gửi thất bại. PGRST202 có thể do thiếu hàm HOẶC key đầu vào không khớp; việc hàm tồn tại trong SQL chưa chứng minh HTTP call đúng.
4. Nếu 064 chưa triển khai, áp migration 064 sau 063 trên đúng project. Nếu đã có hàm đúng chữ ký, ưu tiên sửa payload; chỉ reload schema khi có bằng chứng cache chưa cập nhật. Không chạy lại baseline 062.
5. Test qua đường HTTP PostgREST thật ở staging với tài khoản fixture có xác thực. SQL gọi trực tiếp/PGlite không kiểm tra được bước resolve chữ ký của REST.

Không cần migration mới cho riêng lỗi serializer nếu chữ ký production đã đúng. Nếu cần đổi SQL do phát hiện khác, tạo migration tiếp theo, không sửa migration đã triển khai.

## 5. P1 — Chẩn đoán và thông báo

CampaignRepository đang bọc PGRST202 thành IllegalStateException("server_update_required"). Worker thường chỉ log mã lớp bọc nên người vận hành mất mã gốc/chữ ký không khớp. friendlyError còn dùng chung thông báo này cho REPORT_CLIENT_UPDATE_REQUIRED và nhiều lỗi schema khác.

Gemini cần giữ mã PostgREST/HTTP, tên RPC, stage/request ID và danh sách TÊN key payload trong diagnostics có cấu trúc. Không log giá trị URL ảnh/token, access token, API key hay nội dung báo cáo. Duyệt cause chain thay vì bỏ mất mã lỗi gốc sau khi bọc exception.

UI nên báo “Chưa gửi được báo cáo. Vui lòng thử lại sau hoặc cập nhật ứng dụng.” theo đúng loại lỗi. Chi tiết SQL migration dành cho log/hỗ trợ kỹ thuật; không dùng một thông báo thiếu hàm làm bằng chứng deployment chưa chạy. Giữ rõ trạng thái bài chưa gửi và giữ nháp.

## 6. Ma trận nghiệm thu bắt buộc

### HTTP client bằng MockEngine

- Test thường: body chứa p_submission_context=app_test ngay cả khi truyền tường minh giá trị bằng default; kiểm tra URL endpoint là submit_tester_report_v2.
- Mission: body chứa p_submission_context=mission và đúng p_mission_id.
- Payload qua Worker/khôi phục pending vẫn giữ đúng loại.
- Thiếu v2 trả PGRST202: chỉ một lời gọi, không fallback bỏ context.
- Mã lỗi HTTP/chữ ký được giữ trong diagnostics; lỗi mạng, permission và business error không bị gộp thành server_update_required.

Sửa ReportRpcFallbackAuditTest.sevenArgumentsSuccessPath: hiện test luôn trả success từ mock và chỉ kiểm tra một số key, bỏ sót context. Mock phải kiểm tra hợp đồng bắt buộc trước khi trả thành công, để body thiếu context thất bại.

### Database và staging

- Giữ tám tổ hợp của run-explicit-report-context-test.mjs và các regression ledger, Credit, tombstone, ACL.
- HTTP staging gửi daily và mission đúng chữ ký, kiểm tra report context và provisional_credit thực tế.
- Cùng ID/payload replay không cộng Credit lần hai. Không tự tạo ID mới khi lần gửi chưa rõ đã commit hay chưa.
- Khi p_submission_context từng bị thiếu: yêu cầu đã lưu đúng context phải retry cùng ID sau APK sửa; không xóa ảnh hay pending store để né lỗi.
- Legacy/unknown tiếp tục reconcile theo 064, không tự đoán loại hoặc bỏ tombstone.

### Build và thiết bị

- Chạy test liên quan trước, sau đó toàn bộ unit test và npm test. Báo test nào đã chạy, không tái sử dụng số 307 của bản trước làm kết quả mới.
- Build APK mới, xác minh version/build và chữ ký; hiện các APK khác nhau đều đã dùng 1.0.5/25 nên cần ghi SHA-256 để tránh người dùng cài nhầm. Không tự đổi version/build nếu người dùng chưa yêu cầu; nêu số build cần tăng trước khi phát hành lên kênh đã có build 25.
- Cài đúng APK trên thiết bị, test AuraHome hoặc campaign fixture: chọn ảnh → nộp test thường → server lưu app_test → Credit đúng một lần → retry không cộng trùng; kiểm tra mission riêng.

## 7. Prompt giao trực tiếp cho Gemini

Hãy thực hiện sửa lỗi trong tài liệu này, bắt đầu từ P0 payload app_test thiếu p_submission_context đã được tái hiện bằng ReportV2WirePayloadProbeTest. Đọc mã hiện tại và giữ nguyên các thay đổi đang có của người dùng. Ưu tiên bỏ default của submissionContext trong DTO, sau đó chuyển probe thành regression kiểm tra required key thực sự có trên HTTP body. Kiểm tra deployment bằng SQL chỉ đọc và HTTP staging nếu có quyền; không kết luận thiếu migration chỉ dựa vào thông báo UI. Giữ RPC v2/context bắt buộc, idempotency và bảo toàn nháp; không fallback RPC cũ, không xóa receipt hoặc reset Credit để chữa lỗi. Cải thiện diagnostics có mã gốc và không lộ dữ liệu nhạy cảm. Chạy các kiểm thử đã liệt kê, build APK, ghi SHA-256 và báo rõ phần chưa xác nhận trên production/thiết bị. Nếu phát hiện thêm lỗi, ghi bằng chứng, mức ưu tiên và sửa trong phạm vi luồng này; không tuyên bố hoàn tất thực tế khi mới qua local test.
