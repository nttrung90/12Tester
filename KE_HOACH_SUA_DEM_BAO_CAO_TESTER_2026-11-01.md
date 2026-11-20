# Sửa bộ đếm báo cáo của tester — 2026-11-01

## Kết quả nghiên cứu

- `OwnerAppDetailScreen` hiển thị số chờ duyệt từ `uiState.stats.pendingReportsCount`.
- `OwnerAppDetailViewModel.applyDetail()` lấy trực tiếp `detail.pendingReportsCount`.
- DTO `CampaignDetailResult` mặc định trường này bằng 0 khi JSON thiếu khóa.
- RPC `get_campaign_detail` trong baseline 062 không trả `pending_reports_count`. Các migration hiện hành 063–067 không bổ sung nó. Vì vậy đường tải nhanh luôn hiển thị 0.
- `reports_count` hiện đếm `feedbacks`, không phải `tester_reports`; dùng nó làm tổng báo cáo ở màn chủ app cũng sai. Màn tester đang dùng `reports_count` cho đánh giá, nên cần giữ tương thích.
- Danh sách `reports` của RPC giới hạn 200 và chỉ cho người thường xem báo cáo của chính họ. Không được lấy số phần tử danh sách làm bộ đếm toàn bộ trên backend mới.

## Kế hoạch thực hiện qua OpenCode

1. Dùng đúng model `google/antigravity-gemini-3.8-flash`, không đổi model hoặc tạo agent phụ. Bảo toàn các thay đổi có sẵn.
2. Tạo migration mới số tiếp theo (068 nếu còn trống), sửa `get_campaign_detail` để trả `pending_reports_count` và trường mới `tester_reports_count` từ `tester_reports`, sau auto-accept. Đếm đầy đủ vượt giới hạn danh sách 200; giữ phạm vi quyền owner/admin/moderator hoặc báo cáo của chính tester. Giữ nguyên `reports_count`, `avg_rating`, logic quyền và các dữ liệu RPC khác.
3. Android: DTO suy ra số chờ duyệt và tổng tester reports từ danh sách khi backend cũ thiếu trường. Giá trị tổng do backend mới trả phải được tôn trọng, kể cả 0. Màn chủ dùng trường tổng tester reports mới. Không đổi ngữ nghĩa bộ đếm đánh giá màn tester.
4. Viết kiểm thử hồi quy có ý nghĩa: tái hiện JSON legacy thiếu trường; JSON mới có tổng vượt 200; toàn bộ approved/rejected/auto-approved cho pending=0; owner/admin/moderator/tester và app khác; báo cáo quá 8h; đường ViewModel thực tế và cập nhật sau duyệt/từ chối.
5. Chạy test backend trên baseline + toàn bộ migrations hiện hành bằng DB cục bộ; chạy test Android liên quan và build debug. Sửa tiếp qua cùng phiên OpenCode nếu review/test phát hiện vấn đề.
6. Viết báo cáo kết quả, lệnh kiểm chứng thực chạy và giới hạn kiểm chứng. Chỉ thao tác source và DB test cục bộ; không chạy migration production, không push/commit, không đọc hoặc thay cấu hình bí mật.

## Tiêu chí hoàn tất

- Có báo cáo pending thì màn chủ hiển thị số pending đúng sau tải/refresh.
- Báo cáo đã duyệt, từ chối hoặc tự chấp nhận sau 8h không tính chờ duyệt.
- Tổng báo cáo của tester không bị lấy nhầm từ feedback hoặc bị giới hạn bởi 200 dòng.
- Android mới vẫn hoạt động với backend thiếu khóa đếm; backend mới tương thích với Android cũ.
- Test và build liên quan thành công, có báo cáo rõ bước triển khai còn cần trên môi trường thật.
