# Hợp đồng phân trang chính sách tương lai (Carryover Future Pagination Contract)

**Mục tiêu**: Bổ sung API phân trang chuyên biệt cho danh sách chính sách cộng dồn trong tương lai (`> VN today`), giải quyết triệt để vấn đề giới hạn 50 bản ghi của API tổng quan ban đầu khi số lượng lịch hẹn vượt quá 50.  
**Cơ sở dữ liệu**: Migration `070_admin_management.sql`  
**Ngày lập**: 01/11/2026  
**Trạng thái**: Hoàn thiện & đã kiểm thử tự động  

---

## 1. Giao diện RPC

### `admin_list_future_carryover_policies`

- **Tên RPC**: `public.admin_list_future_carryover_policies`
- **Chế độ bảo mật**: `SECURITY DEFINER SET search_path TO 'public'`
- **Phân quyền người gọi**: Quản trị viên chưa bị khóa (`assert_unlocked_admin()`). Người dùng thường (`member`), điều hành viên (`moderator`), tài khoản bị khóa (`locked`), hoặc khách vãng lai (`anon`) đều bị từ chối với `PERMISSION_DENIED` hoặc `NOT_AUTHENTICATED`.
- **Loại thao tác**: Chỉ đọc (Read-only) — **KHÔNG ghi sự kiện vào `admin_action_events`**.
- **Tham số đầu vào**:
  - `p_limit` (`integer`, tùy chọn, mặc định: `50`): Giới hạn số lượng bản ghi trả về, bị kẹp trong khoảng `1 <= limit <= 100`.
  - `p_cursor_date` (`date`, tùy chọn, mặc định: `NULL`): Con trỏ ngày hiệu lực để truy vấn trang kế tiếp. Khi truyền, chỉ lấy các chính sách có `effective_from > p_cursor_date`. Giá trị phải là ngày hữu hạn hợp lệ (`isfinite`), các giá trị vô hạn (`infinity`, `-infinity`) bị từ chối với lỗi `INVALID_CURSOR_DATE`.

---

## 2. Hợp đồng JSON DTO

### Định dạng phản hồi Envelope chuẩn:

```json
{
  "items": [
    {
      "id": "101",
      "effective_from": "2026-11-05",
      "is_enabled": false,
      "operator_note": "Bảo trì định kỳ",
      "created_at": "2026-11-01T10:00:00Z"
    },
    {
      "id": "102",
      "effective_from": "2026-11-12",
      "is_enabled": true,
      "operator_note": "Bật lại sau bảo trì",
      "created_at": "2026-11-01T10:05:00Z"
    }
  ],
  "has_more": true,
  "next_cursor": {
    "date": "2026-11-12"
  }
}
```

Khi không còn bản ghi tiếp theo (`has_more = false`):
```json
{
  "items": [ ... ],
  "has_more": false,
  "next_cursor": null
}
```

### Chi tiết các trường của mỗi phần tử `AdminCarryoverPolicyItem`:

| Tên trường | Kiểu dữ liệu | Mô tả |
|---|---|---|
| `id` | `text` | ID khóa chính dạng chuỗi (đảm bảo an toàn số nguyên 64-bit cho Kotlin / Gson / Kotlinx Serialization). |
| `effective_from` | `text` (định dạng `YYYY-MM-DD`) | Ngày hiệu lực bắt đầu áp dụng theo giờ Việt Nam. Luôn nghiêm ngặt `> VN today`. |
| `is_enabled` | `boolean` | Trạng thái chính sách bật (`true`) hoặc tắt (`false`). |
| `operator_note` | `text` | Ghi chú lý do của quản trị viên khi lên lịch. |
| `created_at` | `text` (định dạng ISO 8601 UTC) | Thời điểm bản ghi được tạo trong cơ sở dữ liệu. |

---

## 3. Quy tắc sắp xếp và con trỏ ổn định

1. **Sắp xếp thời gian tiến (Ascending)**:
   - Sắp xếp cố định: `ORDER BY p.effective_from ASC, p.id ASC`.
   - Các chính sách sắp diễn ra gần nhất sẽ hiển thị ở đầu danh sách.
2. **Không bỏ sót và không trùng lặp**:
   - Khi chuyển trang, client gửi `p_cursor_date = next_cursor.date`.
   - Truy vấn kế tiếp lọc `p.effective_from > p_cursor_date`, đảm bảo duyệt tuần tự 100% dữ liệu (kể cả với >100 lịch hẹn) mà không bị trùng lặp phần tử giữa các trang.
3. **Độ ổn định khi hủy lịch**:
   - Khi một lịch hẹn tương lai bị hủy (`admin_cancel_future_carryover_policy`), con trỏ ngày vẫn hoạt động ổn định và không làm gián đoạn việc tải các trang tiếp theo.
4. **Tương thích ngược**:
   - RPC hiện có `admin_get_carryover_policies()` giữ nguyên chữ ký (không tham số), cấu trúc trả về (`{ active, future, history, revision }`) để tương thích trọn vẹn với các màn hình Android hiện hành.
