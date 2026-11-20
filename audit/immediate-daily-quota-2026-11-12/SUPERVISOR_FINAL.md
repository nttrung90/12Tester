# Nghiệm thu: cập nhật nhiệm vụ ngay khi đăng/gỡ app

Ngày hoàn tất: 12/11/2026. Kết quả: **đạt các kiểm tra cục bộ đã thực hiện**.

Đã giao triển khai qua Antigravity CLI với đúng `gemini-3.8-flash-high`, mức `high`, và giám sát bằng các kiểm tra độc lập. Các bản bàn giao còn lỗi đã được yêu cầu sửa tiếp.

## Hành vi đã hoàn thành

- App hợp lệ vừa đăng được cộng ngay vào chỉ tiêu ngày khi chính sách mới có hiệu lực. Tỷ lệ lấy từ cấu hình máy chủ; ví dụ q=12 thì 1 → 2 → 3 app tương ứng 12 → 24 → 36 nhiệm vụ cơ bản.
- Gỡ app hoặc đóng để chờ gỡ làm giảm chỉ tiêu ngay. Dọn xóa vật lý sau kết toán không trừ lần thứ hai.
- Giữ nguyên lượt đã test và nợ đã chốt từ những ngày trước; tiếp tục áp dụng giới hạn nguồn app, điều kiện tiếp tục test, quyền hạn và bảo vệ lịch sử/tài chính.
- Tài khoản mới đăng app trong ngày đăng ký nhận nhiệm vụ ngay và được chốt ngày bình thường; không yêu cầu lịch sử trước khi tài khoản tồn tại.
- Home cập nhật theo tài khoản, phiên, ngày Việt Nam và phiên bản thay đổi. Phản hồi cũ hoặc bản lưu cũ không được ghi đè dữ liệu mới trong các ca kiểm tra.
- Lỗi đọc chỉ tiêu sau khi đăng/gỡ thành công hiển thị đang cập nhật và thử lại có giới hạn, giữ nguyên kết quả thao tác đã thành công.

Đã sửa các lỗi phát hiện trong quá trình giám sát: lời gọi chốt ngày bị trùng chữ ký, quyền cấu hình/ngày áp dụng và nhật ký quản trị, kẹt khóa khi thao tác đồng thời, phản hồi sai ngày, ghi đè bộ nhớ/ổ đĩa, lặp thử lại không giới hạn và tác vụ kiểm thử chưa được đóng hết.

## Bằng chứng nghiệm thu

| Kiểm tra | Kết quả | Bằng chứng |
|---|---|---|
| PostgreSQL mới, API đăng/gỡ thật | 26 ca đạt; áp dụng lại migration không đổi lịch chính sách | `independent-final.log`, `fresh-acceptance-snapshot.json` |
| Quyền thành viên, thiếu danh tính, admin, dịch vụ | 4 ca đạt | `governance-final.log` |
| Đăng/gỡ và chốt ngày/gỡ đồng thời | 4 ca đạt | `independent-concurrency-final.log`, `independent-finalization-final.log` |
| Quỹ thưởng và tiếp tục test đồng thời | 9 ca đạt | `supervisor-native-final.log` |
| Bộ SQL hiện có và các bộ bổ sung liên quan | Đạt | `supervisor-sql-full.log`, `supervisor-posting-policy.log`, `supervisor-removal-bonus.log`, `supervisor-bonus-sql.log`, `supervisor-egress-v2.log`, `supervisor-mission-rewards.log` |
| Android toàn bộ sau sửa dọn tác vụ nền | **716/716, 0 lỗi, 0 bỏ qua**, qua hai lượt đầy đủ | `test-lifecycle-fix.ndjson` bước 1379; `supervisor-android-stability-final.log`; `android-full-final-summary.json` |
| Sáu ca độc lập về ngày, phản hồi chậm, cache và thử lại | 6/6 đạt trong lượt đầy đủ cuối | `final-SupervisorImmediateQuotaRaceTest.xml` |
| Đóng gói debug | Thành công | `debug-apk-build.log` |
| APK và chữ ký | Phiên bản 1.2.4, build 44; chữ ký v2 hợp lệ | `debug-apk-badging.txt`, `debug-apk-signature.txt` |

Một lượt kiểm thử trước đó đã bị chồng quá trình chạy và được chạy lại tuần tự. Lượt nghiệm thu độc lập tiếp theo phát hiện lỗi vòng đời tác vụ kiểm thử; đã sửa bằng cách hủy và chờ kết thúc tác vụ, đóng client trước khi trả bộ điều phối về trạng thái ban đầu. Không bỏ hoặc làm yếu các khẳng định kiểm thử để lấy kết quả đạt.

Bước giám sát cuối cũng bỏ lần đặt lại token dư trong tác vụ dọn đĩa bất đồng bộ: token đã được đặt lại khi đổi thế hệ phiên, tác vụ dọn đĩa đến muộn không được xóa token mới. Bộ đầy đủ cuối đã kiểm tra mã này.

## APK thử nghiệm và phạm vi thay đổi

Ban đầu tác vụ không tạo APK/AAB. Sau đó người dùng cho phép: “nếu cần kiểm thử tạo apk/aab thì tạo cũng được”. Vì vậy đã tạo **một APK debug** để kiểm tra đóng gói:

- Tệp: `F:/AI/12Tester/app/build/outputs/apk/debug/app-debug.apk`
- Phiên bản: **1.2.4 (44)**, giữ nguyên phiên bản đang có trong workspace.
- Dung lượng: **36.594.496 byte**.
- SHA-256: `cc6c8ce4a0fa76cb8bd90fd664fd3b86f3d4e1e54cdb2f05f5cbcac504884704`.

Chỉ APK debug được thay đổi sau khi được cho phép. Sáu APK/AAB cũ còn lại giữ nguyên hash, dung lượng và thời điểm sửa. Không tạo AAB phát hành, không ký phát hành hay xuất bản lên cửa hàng.

So với bản chụp trước tác vụ: 9 tệp có sẵn được sửa đúng phạm vi; thêm 5 tệp mã/kiểm thử chính. Migration 062–087 và tệp phiên bản ứng dụng giữ nguyên. Chi tiết hash trong `verified-source-fingerprint.json`, `modified-baseline-files.json`, `packages-after.json`.

Migration 088 đã được kiểm tra với SHA-256:
`494beab9f961724b4a87b00a0531092b9e3f5245aaebc32c6be38e7ef036f2a5`.

## Áp dụng lên môi trường thật

**Chưa triển khai migration hoặc kích hoạt trên máy chủ thật; chưa cài APK lên thiết bị thật.** Các kiểm tra máy chủ dùng PostgreSQL riêng trên máy và dữ liệu mẫu. Cơ sở dữ liệu thử nghiệm này đã được dừng; bằng chứng trong `postgres-stop-final.log`.

Mã máy chủ mới nằm trong `supabase/migrations/088_immediate_daily_quota_on_campaign_changes.sql`. Sau khi triển khai, chính sách ban đầu vẫn chưa bật. Quản trị viên hợp lệ hoặc dịch vụ máy chủ lên lịch từ một ngày Việt Nam **trong tương lai**, ví dụ ngày mai:

```sql
select public.set_daily_task_immediate_quota_policy(
    (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date + 1,
    true,
    'Ap dung chi tieu ngay khi dang hoac go app'
);
```

Không đổi hồi tố các ngày đã áp dụng và không sửa lại các ngày đã chốt. Không có commit, push hoặc triển khai ngoài máy trong tác vụ này.
