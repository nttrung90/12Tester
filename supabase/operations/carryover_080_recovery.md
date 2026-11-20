# Vận hành cộng dồn nhiệm vụ sau migration 080

Tài liệu này là hướng dẫn cho người vận hành. Các lệnh dưới đây chưa được chạy trên máy chủ thật trong đợt sửa ngày 05/11/2026. Phải dùng đúng môi trường thử/nghiệm thu trước khi áp dụng cho môi trường đang phục vụ người dùng.

## Thứ tự áp dụng

1. Lưu bản sao dữ liệu nhiệm vụ, nhật ký sự kiện, chính sách, số dư và Credit logs của môi trường cần nâng cấp.
2. Xác nhận máy chủ đã có các migration 062–079; áp dụng **080_report_context_carryover_and_gap_recovery.sql** sau 079. Không chạy lại 066 sau các migration mới, không chạy lại baseline 062 lên cơ sở dữ liệu đã có dữ liệu.
3. Chạy kiểm tra quyền và chẩn đoán bên dưới bằng kết nối máy chủ được phép. Không đưa khóa máy chủ vào ứng dụng Android.
4. Nghiệm thu báo cáo thường, báo cáo nhiệm vụ, nhiệm vụ thiếu qua nhiều ngày, thẻ hiển thị nợ và nút tiếp tục test app; sau đó mới phát hành APK tương ứng.

## Kiểm tra quyền chốt ngày

Chỉ tiến trình máy chủ được chốt ngày toàn hệ thống. Thành viên và quản trị viên dùng phiên đăng nhập trên ứng dụng không được gọi trực tiếp các hàm này.

```sql
select p.oid::regprocedure::text as signature,
       has_function_privilege('authenticated', p.oid, 'EXECUTE') as client_can_execute,
       has_function_privilege('service_role', p.oid, 'EXECUTE') as server_can_execute
from pg_proc p
join pg_namespace n on n.oid = p.pronamespace
where n.nspname = 'public'
  and (p.proname like '%apply_daily_inactivity_penalty%'
       or p.proname = 'finalize_daily_tasks_for_date')
order by signature;
```

`client_can_execute` phải là false đối với các hàm tài chính và hàm nội bộ xử lý chốt ngày. Kiểm tra thêm quản trị viên hợp lệ đọc được hàm chẩn đoán; thành viên thường, phiên thiếu danh tính và quản trị viên bị khóa đều bị từ chối.

## Tìm tài khoản đang chờ và lịch sử cần đối soát

Các truy vấn này chỉ đọc dữ liệu:

```sql
select * from public.diagnose_daily_task_stuck_users();
select * from public.audit_daily_task_ledger_gaps();
select * from public.audit_carryover_journal_reconciliation();
```

Kết quả chẩn đoán là danh sách cần kiểm tra, không phải lệnh sửa số dư. Một ngày có báo cáo mission chưa đủ chứng minh ngày đó bị đếm sai: có thể cùng ngày còn có báo cáo thường hợp lệ cho chính app ấy. Cần đối chiếu loại nguồn, trạng thái, thời điểm quan sát, số app khác nhau đã tính, checkpoint và kết quả đã chốt.

Phân biệt ba trường hợp:

- **Khoảng thiếu có đủ lịch sử:** tiến trình máy chủ chạy chốt bù theo đợt, từ mốc đã xác nhận của từng người. Những lần chạy sau tiếp tục phần còn lại. Đối chiếu số dư giảm đúng bằng tổng `penalty_credits` của các ngày mới được xử lý sau mốc đó.
- **Thiếu tiền nhiệm hoặc lịch sử không phủ đủ ngày:** giữ trạng thái chờ; tìm bằng chứng để đối soát. Tài khoản khác đủ dữ liệu vẫn phải tiếp tục được chốt. Không đặt nợ về 0 để làm hết trạng thái chờ.
- **Ngày đã chốt có ứng viên đếm nhầm:** giữ nguyên bản ghi lịch sử và Credit logs. Lập bảng đối chiếu từng người/ngày, số cũ, số đã xác minh, nguồn bằng chứng, chênh lệch nợ/Credit và người chịu trách nhiệm. Không tự tính lại hàng loạt từ trạng thái app hiện tại.

## Khôi phục có bằng chứng

`reconcile_legacy_daily_task_checkpoint` là cơ chế hiện có cho **ngày chưa chốt** cần một checkpoint đã xác minh. Hàm yêu cầu tài khoản máy chủ, số nợ đầu/cuối và ghi chú vận hành; không dùng được để ghi đè ngày đã chốt. Chỉ gọi khi đã xác minh đủ các giá trị, giữ lại chứng cứ và kết quả hàm trả về. Không dùng một checkpoint 0 giả định để xóa khoảng thiếu.

Nếu dữ liệu **đã chốt** cần điều chỉnh, phải thiết kế/giao dịch một bản điều chỉnh riêng có dấu vết kiểm toán sau khi đối soát dữ liệu thực tế; không cập nhật trực tiếp hàng loạt `carry_out_tests`, `profiles.credits` hoặc xóa `credit_logs`. Đợt sửa mã này chưa thực hiện điều chỉnh dữ liệu production và không tự cấp/thu hồi Credit lịch sử.

Khi không còn khoảng lịch sử chưa xác minh, dùng kết nối máy chủ chạy:

```sql
select public.apply_daily_inactivity_penalty();
```

Chạy tiếp các đợt theo lịch cho đến khi các chuỗi có đủ bằng chứng bắt kịp hôm qua. Không coi số ngày được hàm duyệt là bằng chứng mọi tài khoản đều đã chốt thành công; kiểm tra ledger và các cờ `carryover_ready`, `history_ready`.

## Nghiệm thu trên môi trường thật

- Báo cáo mission không tăng chỉ tiêu test thường, không trả nợ và không mở được app còn nợ; báo cáo thường pending/approved được tính, rejected bị loại, trùng app không nhân đôi.
- Chủ app đạt chỉ tiêu khả dụng vẫn thấy phần thiếu bảo lưu. Member đăng nhập được ghi nhận hoàn thành đăng nhập, đồng thời thấy nợ cũ hoặc trạng thái đang cập nhật.
- Chốt bù 3/31/40 ngày giữ nguyên các ngày trước checkpoint, không trừ Credit ngoài phạm vi và không trừ lại khi chạy lặp.
- Kiểm tra cron theo ngày Việt Nam và thử qua mốc chuyển ngày; chạy bộ kiểm thử nhiều kết nối bằng `test:owner-auto-pause-concurrency` trên cơ sở dữ liệu thử có cấu hình phù hợp. Kết quả PGlite tuần tự không thay thế bước này.
