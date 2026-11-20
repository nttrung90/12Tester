# Kế hoạch triển khai mở rộng admin qua OpenCode — 01/11/2026

## Yêu cầu thực hiện

Người dùng yêu cầu lập kế hoạch, giao OpenCode CLI dùng **`google/antigravity-gemini-3.8-flash`**, **`--variant high`**, thực hiện và tiếp tục nhận sửa lỗi đến khi hoàn tất. Codex điều phối, review và kiểm thử độc lập. Không thay model nếu chưa được người dùng yêu cầu.

Phạm vi là 12 nhóm chức năng trong kết luận nghiên cứu: thành viên nâng cao; tra cứu nhiệm vụ/credit; carryover; chiến dịch tập trung; hàng đợi báo cáo test; hỗ trợ; cấu hình thưởng/phạt; cấu hình phí; phân quyền/cảnh báo; audit; thông báo nâng cao; giám sát hệ thống. Làm đầy đủ frontend/backend cho chức năng đã nhận, không tạo nút giả hoặc form lưu nhưng nghiệp vụ không sử dụng.

Giữ nguyên thứ tự năm thẻ hiện có; mở rộng hai thẻ thành viên/nhiệm vụ và đặt công cụ mới sau nhóm năm thẻ. Giữ các sửa đổi trước trong workspace. Bản sửa nút credit bằng FlowRow hiện tại phải được giữ và kiểm tra hồi quy.

Không thuộc phạm vi: kích hoạt nạp tiền đang tắt, tích hợp nhà thanh toán mới, Auth Admin xóa tài khoản, thay hạ tầng cloud hoặc tự deploy Supabase production. Đây là tính năng có điều kiện trong bản nghiên cứu, không nằm trong 12 nhóm được chọn ở kết luận. Tạo migrations và hướng dẫn triển khai để có bản thay đổi review được; không dùng secret production để thử nghiệp vụ.

## Quy tắc nền tảng

- Checkpoint toàn bộ source/config không chứa secrets trước chỉnh sửa; so sánh với checkpoint, không dùng git HEAD để kết luận về các sửa đổi vốn đã có.
- Active migrations hiện là 062–069. Chỉ thêm migration kế tiếp, không sửa baseline/archive/migration cũ.
- RPC mới kiểm tra UID, role và admin chưa bị khóa ở backend; thu hẹp EXECUTE và RLS. Không mở các hàm service-role trực tiếp cho tất cả authenticated.
- Mutations quan trọng có audit actor/reason/target/before/after/request ID. Credit và phát thông báo phải chống xử lý lại request. Audit chỉ backend ghi.
- Cursor pagination, tìm kiếm server-side; xóa giới hạn giả 500 thành viên. Không tải mọi trang/lịch sử vào một response.
- Policy có revision, ngày hiệu lực giờ Việt Nam, giới hạn giá trị và snapshot để giao dịch/lịch sử cũ không đổi theo cấu hình mới. Default behavior giữ nguyên đến khi admin đặt policy mới.
- Giữ các guard race/session generation/cache publication đang có; nội dung UI VI/EN và hỗ trợ chữ lớn/màn hình nhỏ.
- Không commit/push/deploy, không đọc/xuất secrets, không reset hoặc xóa file ngoài phạm vi. Không thay test để che lỗi.

## Các chặng và đầu ra nghiệm thu

### A. Backend quản trị và hợp đồng API

1. Đặt hợp đồng DTO/RPC rõ trong `audit/admin-expansion-2026-11-01/api-contract.md`.
2. Admin audit dùng chung; member search có cursor và detail/credit/daily-task history; carryover get/schedule/cancel/history với revision.
3. Campaign/report queue/search có cursor; dùng lại RPC lifecycle/approve/reject hiện có. Contact support dùng lại module hiện tại.
4. Role management: admin quản lý member/moderator; bổ nhiệm/hạ admin chỉ qua quyền bổ nhiệm riêng được bootstrap bởi database owner, bảo vệ admin cuối cùng và không tự nâng quyền. Nếu chưa bootstrap, backend trả lỗi rõ và UI giải thích đúng. Warning revoke lưu sự kiện và cập nhật toàn bộ phép đếm liên quan; không xóa lịch sử gốc.
5. Compensation/request ID chống trùng, liên kết giao dịch/khiếu nại khi có; audit các mutation mới và thao tác admin hiện có.
6. Summary/job monitor: dữ liệu thực từ database, không giả số lượng công việc nằm trên điện thoại. Retry settlement chỉ ID/batch cho phép, kiểm tra quyền, audit, không arbitrary SQL/cloud secrets.
7. Notification campaigns: draft, VI/EN, audience all/role/selected users, schedule/cancel future, preview counts, cursor history, request id chống gửi lặp. Server worker an toàn khi chạy đồng thời; không gọi broadcast hiện có nhiều lần. Tách sent/read/popup-confirmed đúng nghĩa. Có cơ chế scheduler migration hoặc hướng dẫn vận hành worker cụ thể.

Nghiệm thu A: runner PGlite nạp active migrations mới, test admin/member/moderator/locked/anon; pagination >500 profiles, không lặp/bỏ trang với cursor; cross-user access; request replay và request ID payload mismatch; policy revision/history/date; warning/role rules và finance/audit invariants.

### B. Android cho nghiệp vụ quản trị

1. Repository/DTO cho hợp đồng A, thêm ViewModel chuyên biệt thay vì dồn toàn bộ vào một file.
2. Tìm kiếm thành viên từ server có pagination; member detail gồm nhiệm vụ/credit/warnings/campaigns/slots, xử lý compensation và role phù hợp quyền.
3. Carryover trong thẻ Nhiệm vụ ngày; campaign search, pending report queue, audit, notification composer/history và monitor; điều hướng thẻ hỗ trợ vào màn hình đã có.
4. Xử lý empty/loading/error/retry, giữ selection theo ID, chống double-submit, stale search và phản hồi sau đổi phiên. Không tự retry mutation không có request ID.
5. Không để screen lớn, chữ lớn hoặc nested scrolling che thao tác.

Nghiệm thu B: compile/assembleDebug, các test admin/popup/session/cache hiện có và tests hành vi mới cho phân trang/race/request ID; probe layout phù hợp. UI và resource không chứa text mới hardcode.

### C. Policy thưởng/phạt và phí dùng thật

1. Versioned policy có schema/validation riêng cho credit khởi tạo, phần trăm phạt, phí đẩy app, giá slot/thời hạn, thưởng báo cáo và thưởng cập nhật app.
2. Mọi nhánh SQL tiêu thụ policy: create profile, finalize daily penalty, push/renew slot, report submit/approve/reject/auto-approve, app-update publish/claim/refund. Snapshot policy/amount tại thời điểm phát sinh nghĩa vụ. Không sửa số dư lịch sử hoặc thay đổi lịch sử đã chốt.
3. Client đọc policy/báo giá từ server cho hiển thị và xác nhận; loại bỏ các check cứng làm mâu thuẫn với server. Quote/revision + request ID cho giao dịch nhạy cảm, có phương án tương thích client cũ; không chỉ sửa label.
4. Form admin xem/schedule/cancel/history + revision; giữ default giá trị hiện hành khi chưa đặt policy mới.

Nghiệm thu C: test trước/sau ngày hiệu lực, lịch sử cũ, release escrow/paid/refunded cân đối, provisional/rejection clawback không vượt số đã cấp, slot/push display và số bị trừ thống nhất, timezone và concurrency.

### D. Review độc lập và vòng sửa

- Codex đọc diff mới so với checkpoint, review RPC privilege, tình huống tài chính và luồng phiên/client.
- Build debug và chạy bộ backend đầy đủ, các suite Android liên quan, probes độc lập cho ca biên chưa được test của tác giả bao phủ.
- Bất kỳ lỗi build/test hoặc thiếu đầu ra được trả về đúng session OpenCode/model/variant để sửa; kiểm tra lại sau mỗi vòng.
- Dừng nghiệm thu khi các nhóm trong ma trận đã hoàn tất, kiểm tra phù hợp đã đạt và không còn lỗi đã biết chưa xử lý trong phạm vi. Không tuyên bố tuyệt đối “hết mọi lỗi” ngoài phạm vi kiểm tra.
- Báo cáo cuối: chức năng được thêm, file/migration, kết quả test, artifact APK, session/model/variant, hướng dẫn deploy thứ tự và giới hạn chưa kiểm tra trên thiết bị/production.

## Theo dõi

- [x] Checkpoint và xác nhận model/variant; baseline build/admin Android và backend 069 đạt.
- [x] A — Backend quản trị/API + tests.
- [x] B — Android/UI + tests.
- [x] C — Policy có consumer backend/client + tests.
- [x] D — Review độc lập, vòng sửa và kiểm thử cuối.
- [x] Tài liệu bàn giao và ma trận nghiệm thu đủ 12 nhóm.

Hoàn tất nghiệm thu cục bộ: toàn bộ 499 unit test Android đạt, không bỏ qua ca nào; APK debug build thành công; các bộ backend/hồi quy/tích hợp và worker đạt. Xem `KET_QUA_MO_RONG_ADMIN_OPENCODE_2026-11-01.md` và `audit/admin-expansion-2026-11-01/FINAL_ACCEPTANCE_MATRIX.md`.

Điều chỉnh được người dùng cho phép ở cuối quá trình: khi Gemini chạm quota lần hai, người dùng chọn **“Cho Codex trực tiếp sửa phần còn lại để hoàn tất sớm”**. Codex hoàn tất việc sửa fixture kiểm thử và nghiệm thu; không đổi model hoặc nâng ngưỡng quota của OpenCode.
