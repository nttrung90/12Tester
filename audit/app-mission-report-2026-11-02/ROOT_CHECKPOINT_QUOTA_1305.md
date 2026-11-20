# Checkpoint triển khai báo cáo nhiệm vụ app — 02/11/2026

**Trạng thái: CHƯA HOÀN TẤT. Hai phiên OpenCode đã dừng do quota Gemini ở ngưỡng95%.**

Yêu cầu đã chốt: cửa sổ nhiệm vụ riêng, hai nút Mở app/Link Play Store, tối đa3 ảnh có xem/thêm/xóa; báo cáo thường1/ngày và thêm1 báo cáo cho mỗi nhiệm vụ đã tham gia/ngày trong thời gian nhiệm vụ nhận bài. Báo cáo nhiệm vụ có nhãn riêng; dùng chung pipeline nén/cache/upload/retry. Không tự đổi model, tài khoản hoặc ngưỡng quota.

## Công cụ/model đã dùng

- OpenCode CLI chọn chính xác `google/antigravity-gemini-3.8-flash --variant medium`.
- Phiên Android: `ses_e65c3bed6ffev0XseA6OqhRpVp`; xuất metadata cuối xác nhận522 assistant messages đều đúng provider/model/medium.
- Phiên SQL: `ses_e65993ca4ffencJ3tDmB7qckbi`;166 assistant messages đều đúng provider/model/medium.
- Log cuối hai phiên báo cả3 tài khoản vượt95%. Lần báo06:02:31UTC còn khoảng10 giờ6 phút, tương đương khoảng23:09 ngày02/11/2026 tại Việt Nam. Đây là ước lượng do OpenCode báo, không phải lịch tự động tiếp tục.
- Đã giữ nguyên cấu hình tài khoản/quota/model. Không chuyển phần triển khai sản phẩm sang model khác.

## Đã kiểm chứng trên mã hiện tại

| Kiểm tra | Kết quả | Bằng chứng |
| --- | --- | --- |
| Toàn bộ Android +13 test audit cũ +17 test độc lập mới | **535 test /74 suite,0 failure,0 error,0 skip** | `root-full-android-fixture-fixed.log`, XML snapshot `root-full-android-fixture-fixed-20261102-060324-743` |
| Build debug APK | Đạt | `root-android-checkpoint-result.json` |
| SQL độc lập gồm coexistence, trạng thái/ngày, nâng cấp dữ liệu,3 ảnh/replay, media grant, tách biệt duyệt báo cáo | **36 nhóm đạt** | `root-backend-final36-results.json` và6 log `*-root-final36.log` |
| Toàn bộ `supabase/tests npm test` | Đạt trên SQL ổn định | `root-full-backend-final36.log`, `root-full-backend-final36-result.json` |
| Gateway HTTP cục bộ |13/13 đạt | `root-gateway-final.log` |
| Dọn storage |8/8 đạt | `root-storage-cleanup-final.log` |

Migration074 SHA256: `ED7E17603DA72F6A2C7CAE9DE5B26B6E3A69C99CB6ABE6ED7E157126EE00B97A`. Không thay đổi migration062..073. Đã kiểm tra giữ ngày báo cáo lịch sử, privateACL, validation từng ảnh, fingerprint idempotency, chữ ký media chính/phụ, và duyệt/từ chối/tự duyệt nhiệm vụ không sửa check-in/thưởng báo cáo thường. Thưởng escrow nhiệm vụ vẫn được kiểm tra trả đúng một lần, báo cáo ngày sau vẫn được duyệt.

Một fixture do coordinator bổ sung khởi tạo Coil trước khi gắn dispatcher Main giả gây treo ca Egress khi chạy chung. Đã sửa **chỉ setup/teardown của fixture**, giữ nguyên toàn bộ assertions; lần chạy chung535 test sau đó đạt54 giây. Ba lần treo trước và thread dump được giữ lại, không tính là lần chạy thành công. Root giữ manifest trước/sau thay đổi hygiene để đối chiếu. Gemini cũng đã đặt test executor heap2GiB; không thay đổi dependencies/version app.

APK checkpoint: `F:\AI\12Tester\app\build\outputs\apk\debug\app-debug.apk`,35.464.633 byte, version1.0.9(code29), SHA256 `A88713C8A5ECAE81D677B4A057AE74A90F3E2443FDD8C2700541B0ABF744CEB7`. Đây là bản đang triển khai, **chưa nghiệm thu tính năng**.

## Việc còn lại — không được coi test đạt là đã hoàn tất

Chi tiết có vị trí/callsite trong `root-gallery-review.md`; task tiếp theo đã soạn ở `task-opencode-android-round5.txt` và **chưa giao chạy vì quota**:

1. Gallery chủ app lặp3 attachment nhưng từng ô vẫn gọi request ảnh chính; admin hiện chỉ xem được ảnh chính. Cần hiển thị/chọn/mở đúng từng ảnh, giữ expiry/grant và thêm ngày báo cáo cạnh nhãn nhiệm vụ.
2. Admission đã chuyển kiểm tra dung lượng khỏi luồng UI, nhưng kiểm tra rồi persist vẫn cần bảo vệ nguyên tử giữa các request/ngữ cảnh. Giữ giới hạn20MiB/MAX_FILES, không xóa bài đã nhận.
3. Chốt ngày request thường một lần, xử lý pending cũ/ngày mới và các trạng thái failure/retry đúng user/context/mission/ngày.
4. Acknowledge nháp phải so **toàn bộ snapshot**, gồm thứ tự/ID/hash ảnh, thay vì chỉ comment/rating; xử lý cả startup reconcile trước xóa receipt. Sửa lease lặp khi loadData và chỉ dọn local form đúng snapshot đã gửi, giữ ảnh/nội dung mới hơn.
5. Hoàn thiện preflight hạn/trạng thái nhiệm vụ, ảnh chính mới khi bị từ chối, guard10 giây và khôi phục timer/boot; các ngoại lệ cập nhật nhận xét giữ theo luồng thường.
6. Không âm thầm cắt kết quả picker trước ViewModel; thông báo giới hạn/thay bộ ảnh rõ ràng và dùng lỗi/chuỗi bản địa hóa thân thiện.

Không sửa assertions để che lỗi. Sau các sửa trên, chạy lại root17 và toàn bộ Android/build; chỉ chạy lại backend khi có thay đổi backend. Đọc toàn bộ review và kế hoạch đã chốt, không chỉ chạy một vài test được chọn.

## Cách tiếp tục sau khi quota khả dụng

Từ `F:\AI\12Tester`, tiếp tục đúng phiên Android/model/medium qua helper có chặn đọc file bí mật:

```powershell
& .\audit\app-mission-report-2026-11-02\start-opencode.ps1 `
  -SessionId ses_e65c3bed6ffev0XseA6OqhRpVp `
  -PromptFile task-opencode-android-round5.txt `
  -LogName opencode-round5
```

SQL đã qua kiểm chứng cục bộ cuối, phiên SQL chỉ cần mở lại khi phát hiện lỗi backend mới. `COORDINATOR.md` ghi phiên/log và quá trình kiểm chứng. `root-source-changes-checkpoint.json` đối chiếu snapshot363 file trước công việc:32 file thay đổi,6 file sản phẩm/test mới, không mất file gốc; giữ các thay đổi có sẵn của người dùng.

Chưa áp migration lên production, chưa commit/push/phát hành, chưa kiểm tra thiết bị thật hay PostgreSQL đa kết nối. Không có tiến trình model/test đang chạy sau checkpoint này. Chỉ tiếp tục triển khai sản phẩm qua model mà người dùng đã chọn khi quota cho phép.
