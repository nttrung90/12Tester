# Bàn giao báo cáo nhiệm vụ app — 02/11/2026

**Đã hoàn tất mã nguồn và kiểm thử cục bộ. Chưa triển khai production.**

Đã tiếp tục khi Gemini có quota. Phần triển khai sản phẩm và các lần sửa lỗi đều qua OpenCode CLI với google/antigravity-gemini-3.8-flash, variant medium. Coordinator đối chiếu mã nguồn, bổ sung kiểm thử độc lập và kiểm chứng bằng chứng; không đổi model, cấu hình tài khoản hay ngưỡng quota.

## Kết quả đã triển khai

- Cửa sổ riêng theo đúng app/nhiệm vụ; hai nút Mở app và Link Play Store; biểu mẫu Báo cáo nhiệm vụ app với thêm/xóa/xem tối đa 3 ảnh.
- Báo cáo thường 01/ngày và thêm 01 báo cáo cho mỗi nhiệm vụ đã tham gia/ngày trong thời gian nhiệm vụ nhận báo cáo. Hai loại độc lập, cùng tồn tại; ngày sau vẫn được gửi sau khi báo cáo nhiệm vụ ngày trước được duyệt.
- Nhãn nhiệm vụ, tên nhiệm vụ và ngày báo cáo cho chủ app/admin; gallery mở đúng từng ảnh.
- Dùng chung nén, thumbnail, hash, cache, upload nền và retry; giữ checkpoint từng ảnh và receipt đầy đủ; bản nháp bền vững theo tài khoản/app/nhiệm vụ/ngày.
- Đã sửa các lỗi phát hiện về dung lượng hàng đợi, đổi ngày, trạng thái gửi độc lập, khôi phục timer sau reboot, bản nháp mới bị receipt cũ xóa, và nút Thử lại chọn nhầm báo cáo khác ngày.
- Xác nhận/xóa nháp dùng cùng monitor với thao tác lưu, chỉ xóa khi nội dung, đánh giá và thứ tự ID ảnh khớp snapshot đã gửi.
- Giữ chính sách tài chính hiện hành: duyệt nhiệm vụ không chiếm check-in/thưởng của báo cáo thường; escrow nhiệm vụ không được trả lại mỗi ngày.

## Bằng chứng cuối

| Kiểm tra | Kết quả | Bằng chứng trong thư mục audit này |
| --- | --- | --- |
| Toàn bộ Android, gồm 13 kiểm thử audit trước và 24 kiểm thử độc lập mới | 542 test / 75 suite; 0 lỗi, 0 thất bại, 0 bỏ qua | full-android-exact-retry-final.log; snapshot full-android-exact-retry-final-20261102-120057-492 |
| Đóng gói APK debug | BUILD SUCCESSFUL | assemble-debug-exact-retry-final.log |
| SQL độc lập: coexistence, ngày/trạng thái, nâng cấp, 3 ảnh/replay, media grant và tách duyệt | 36 nhóm đạt | root-backend-final36-results.json |
| Toàn bộ supabase/tests npm test | Đạt; migration giữ nguyên hash từ lần kiểm tra này tới bản cuối | root-full-backend-final36-result.json; root-full-backend-final36.log |
| Media gateway | 13/13 đạt | root-gateway-final.log |
| Storage cleanup | 8/8 đạt | root-storage-cleanup-final.log |

Coordinator đã đọc trực tiếp 75 XML JUnit mới, kiểm tra exit code, kiểm tra APK và đối chiếu SHA của 6 tệp kiểm thử độc lập. Không sửa điều kiện kiểm tra để che lỗi. Fixture Coil/Robolectric trước đó được sửa setup/teardown dispatcher để chạy chung; giữ nguyên assertions. Những lần kiểm tra lỗi/treo lịch sử vẫn được lưu và không tính là đạt.

Metadata cuối: phiên primary ses_e65c3bed6ffev0XseA6OqhRpVp có 818 assistant messages và phiên SQL ses_e65993ca4ffencJ3tDmB7qckbi có 166; tất cả đều đúng google/antigravity-gemini-3.8-flash/medium. Xem root-model-final-proof.json.

## APK và migration

APK: F:/AI/12Tester/app/build/outputs/apk/debug/app-debug.apk  
Phiên bản: 1.0.9 (29); kích thước 35.472.001 byte.  
SHA256: D8E3F369F2BD2513E2911F5FBBB33032FBAAEA0B2E37A373220BAAFA32F4EE15.

Migration mới: supabase/migrations/074_app_mission_window_and_multi_attachments.sql.  
SHA256: ED7E17603DA72F6A2C7CAE9DE5B26B6E3A69C99CB6ABE6ED7E157126EE00B97A.

Cần áp dụng migration 074 theo quy trình triển khai trước khi dùng tính năng mới với server production. Chưa áp dụng migration lên server, chưa commit/push hoặc phát hành release. Chưa kiểm thử trên thiết bị thật; database được kiểm tra bằng PGlite, chưa kiểm chứng tranh chấp nhiều kết nối PostgreSQL thật.

Đối chiếu snapshot 363 tệp gốc và 6 tệp mới trong phạm vi đã chụp: 34 tệp gốc thay đổi, 6 tệp mới, không mất tệp gốc; migration 062..073 không bị sửa. Giữ các thay đổi có sẵn trong workspace. Chi tiết hash và APK trong root-final-verification.json, root-source-changes-final.json và root-source-manifest-final.json.

Tất cả phiên OpenCode/Gradle của công việc này đã hoàn tất. Checkpoint quota cũ được giữ tại ROOT_CHECKPOINT_QUOTA_1305.md để lưu lịch sử.
