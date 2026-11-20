# Hai chế độ thưởng, sửa nhiệm vụ và quyền xóa của Admin

## Phạm vi

Triển khai Android và Supabase cục bộ bằng OpenCode CLI `google/antigravity-gemini-3.8-flash --variant high`. Giữ phiên bản 1.1.2/build 32. Không build APK/AAB, deploy, commit/push, nâng cấp phụ thuộc, đổi cấu hình model/quota/tài khoản hoặc đọc bí mật. Giữ mọi thay đổi sẵn có; dùng migration mới sau 076, kiểm tra số mới nhất trước khi tạo. Báo cáo tiến độ và kiểm thử trong thư mục audit này.

## 1. Mở nhiệm vụ và tiến độ tester

- Màn hình Mở nhiệm vụ dùng lựa chọn đơn: **Trả thưởng một lần** (mặc định) hoặc **Trả thưởng hàng ngày**. Hiện đơn vị Credit/lượt hoặc Credit/ngày, phí nền tảng, quỹ thưởng tối đa, tổng Credit cần có, ngày bắt đầu và thời điểm hết hạn. Hiện chế độ và tiến độ ở danh sách, màn hình nhiệm vụ và quản lý của chủ app. Dùng luồng nén, cache, tối đa 3 ảnh, nháp và gửi nền hiện có.
- Một lần: mỗi lượt tham gia có một báo cáo nhiệm vụ xuyên suốt thời hạn. Cho sửa báo cáo chờ duyệt hoặc nộp lại báo cáo bị từ chối trong hạn trên cùng bản ghi, kể cả sang ngày mới, với quy tắc ảnh mới hiện hành. Sau khi duyệt thì hoàn thành và trả thưởng đúng một lần. Dữ liệu nháp, hàng đợi và tìm báo cáo phải bám đúng lượt, không tạo nhầm bản ghi mới khi đổi ngày. Giữ ngày nộp gốc và receipt đã chốt.
- Hàng ngày: nhận nhiệm vụ một lần, mỗi ngày Việt Nam kể từ ngày tham gia có một lượt độc lập. Không nộp bù ngày chưa gửi. Ngày cuối vẫn có thể gửi trước mốc hết hạn. Chốt N ngày lịch liên tiếp theo `Asia/Ho_Chi_Minh` từ ngày mở, hết hạn ở 00:00 đầu ngày sau cửa sổ; ngày đầu có thể ngắn, phải hiển thị thời điểm thực tế trước khi xác nhận. Số chỗ tính theo người, không theo ngày. Duyệt ngày đầu không khóa ngày sau. Báo cáo ngày đã nộp dùng ngày báo cáo gốc để trả thưởng, kể cả duyệt vào ngày khác hoặc sau khi đóng. Với bản đầu, sửa/nộp lại lượt hàng ngày trong chính ngày đó và trước hạn. Dùng ngày máy chủ cho quyền nộp, không tin ngày do client truyền.
- Nhiệm vụ và API tạo cũ giữ chế độ nội bộ legacy: nộp theo ngày như hiện tại, thưởng một lần, không truy thu quỹ. Dùng API tạo mới rõ chế độ, giữ tương thích chữ ký cũ và dữ liệu cũ. Giữ quyền nhiệm vụ cho app open/production/completed của 076, và tách biệt báo cáo kiểm thử thường, check-in, phí và thưởng thường.

## 2. Quỹ, duyệt và trả thưởng

- Nhiệm vụ mới giữ trước quỹ tối đa: số chỗ × Credit/lượt (một lần), hoặc số chỗ × số ngày báo cáo × Credit/ngày (hàng ngày). Phí nền tảng tiếp tục theo công thức hiện hành, hiển thị riêng. Giữ quỹ và thu phí nguyên tử khi tạo; nếu thiếu Credit thì không tạo hoặc trừ một phần. Ràng buộc số ngày, chỗ, mức thưởng và chủ app ở máy chủ. Khoản trả thưởng mới lấy từ quỹ đã giữ, tránh trừ lại chủ app.
- Dùng định danh khoản phải trả theo người tham gia hoặc người tham gia + ngày báo cáo. Duyệt thủ công, tự duyệt và retry cùng chung cơ chế, khóa và ràng buộc chống trả trùng. Bảo toàn lịch sử giao dịch và receipt. Không dựa vào cờ participant completed để chặn lượt ngày khác. Báo cáo hợp lệ đã nộp giữ quyền được xét thưởng khi quá hạn, tạm dừng hoặc bị xóa, với điều khoản đã lưu. Với nhiệm vụ cũ không có quỹ, tiếp tục dùng cơ chế chi trả cũ; khi Admin đóng, cho quyết toán báo cáo đủ điều kiện đã nộp trước lúc đóng, giữ trạng thái có thể xử lý lại nếu chủ app thiếu Credit. Không bảo đảm quỹ cho nhiệm vụ cũ bằng cách tự truy thu.
- Hết hạn hoặc xóa: dừng lượt mới; bảo toàn báo cáo đang chờ và quỹ cần thiết cho các lượt đã nộp. Hoàn quỹ dư một lần khi các nghĩa vụ đã được duyệt/từ chối và quyết toán xong. Xử lý đóng, retry, trả thưởng, hoàn quỹ trong giao dịch nhất quán. Bổ sung đường kết thúc tự động, tránh giữ quỹ vô thời hạn hoặc hoàn nhầm phần đang chờ. Giữ lịch sử để đối soát.

## 3. Chủ app sửa nhiệm vụ

- Từ Quản lý nhiệm vụ → **Sửa nhiệm vụ**, mở biểu mẫu điền sẵn. Trước lượt nhận đầu tiên cho sửa tiêu đề, mô tả, chế độ, thưởng, số chỗ và thời hạn. App gắn với nhiệm vụ cố định. Dùng thời điểm bắt đầu gốc khi tính lại thời hạn, từ chối hạn mới đã qua. Hiện và xác nhận chênh lệch quỹ trước khi lưu; API tính lại và từ chối giá trị xem trước lỗi thời. Phí theo số ngày đã thu là mốc cao nhất: tăng thu phần chênh, giảm không hoàn phí nền tảng đã thu. Quỹ thưởng chênh lệch được giữ thêm hoặc hoàn lại nguyên tử.
- Sau lượt nhận đầu tiên cho sửa tiêu đề, mô tả và thông báo tester; khóa chế độ, thưởng, chỗ và thời hạn. Không sửa nhiệm vụ đã đóng. Cùng khóa hàng nhiệm vụ khi nhận hoặc sửa, kiểm tra quyền chủ nhiệm vụ và chống ghi đè bản sửa mới. Nhiệm vụ legacy giữ điều khoản thưởng cũ, chỉ sửa văn bản.

## 4. Admin xóa nhiệm vụ

- Tích hợp vào khu quản trị hiện có: tìm/chọn đúng nhiệm vụ, **Xóa nhiệm vụ**, nhập lý do bắt buộc và xác nhận. Chỉ Admin đủ quyền theo cơ chế kiểm tra quyền hiện hành; kiểm tra ở RPC, không dựa vào UI. Ghi audit actor/mission/reason/time, thông báo chủ app và tester đã nhận, áp dụng cả hai chế độ và nhiệm vụ legacy. Xóa mềm, ẩn khỏi danh sách đang mở, giữ lịch sử báo cáo/giao dịch và quyền xử lý báo cáo đã gửi.
- Khóa theo cùng nguyên tắc với join/submit/review, chặn yêu cầu mới sau khi đóng nhưng cho replay receipt đã cam kết. Lặp lại yêu cầu không phát trùng audit/thông báo/hoàn quỹ. Đường xóa của chủ app cho nhiệm vụ mới dùng cùng cơ chế đóng/quỹ, không đi vòng qua API cũ. Không mở rộng quyền cho Moderator hoặc tài khoản bị khóa.

## 5. Kiểm chứng và bàn giao

- Kiểm tra schema nâng cấp 062–migration mới, RPC cũ và grant; đối chiếu 076 để tránh mất logic ảnh phụ/receipt/phân quyền báo cáo. Dùng PGlite cho luồng thực: một lần qua nhiều ngày, hàng ngày 3 ngày với ngày bỏ lỡ và duyệt khác thứ tự, nộp trùng/replay, từ chối/nộp lại, duyệt sau hạn, cùng ngày với báo cáo thường, app đã hoàn thành, Admin xóa với báo cáo chờ, retry quyết toán, hoàn quỹ, sửa trước/sau khi nhận và thiếu Credit. Kiểm tra quyền Admin/chủ app/Moderator, mất kết nối khi sửa/xóa, trạng thái và số dư. Kiểm chứng race bằng PostgreSQL đa kết nối nếu có môi trường cục bộ, ghi rõ nếu chỉ có PGlite.
- Kiểm tra Android hành vi tạo/sửa/đổi ngày/quản trị và chạy bộ test Gradle phù hợp qua helper có sẵn. Chỉ dùng kiểm thử/biên dịch cần thiết, không assemble/bundle. Dùng các ca hồi quy 3 ảnh, tách báo cáo thường và app đã hoàn thành đã có. Không nới assertions để che lỗi. Ghi lệnh, số ca, log, giới hạn thực tế và migration mới. Giao đủ mã nguồn và kết quả kiểm thử, không chỉ báo kế hoạch.
