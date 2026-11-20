# Phân công và giao diện tích hợp vòng sửa

Phân công cập nhật: Gemini chỉ sở hữu Android và test Android, cùng ghi tiến độ/bàn giao phần Android. Dừng sửa SQL ngay sau khi nhận phân công mới; giám sát tiếp quản migration 092, SQL tests/index và backend để hoàn thiện tích hợp quota/cleanup nhất quán.

Giám sát sở hữu `supabase/migrations/092*`, SQL tests/index, `backend/media-gateway/`, `backend/storage-cleanup/` và các runner giám sát. Không sửa file Android khi Gemini đang sửa.

## Cleanup

- `get_expired_report_media_for_cleanup(p_limit integer, p_lease_seconds integer, p_dry_run boolean)` trả array JSON. Giữ wrapper 2 tham số cũ, không default gây ambiguous overload. Dry run chỉ đọc, không claim/đổi trạng thái/counter.
- Mỗi item gồm `id` (ledger UUID duy nhất), `upload_id`, `user_id`, `provider`, `bucket`, `object_path`, `thumbnail_object_path`, `size_bytes`, `thumbnail_size_bytes`, `server_created_at`, `delete_deadline_at`, `lease_token`, `lease_expires_at`. Khi claim phải trả token MỚI sau UPDATE.
- Chỉ lấy Supabase/bucket report-screenshots; reclaim lease đúng hạn, không timeout cứng 10 giây. Retry không mất bản ghi, gồm expired/orphan.
- Overload mới: `record_report_media_deleted(p_media_id uuid, p_lease_token uuid, p_deleted_paths text[])`. Kiểm tra khóa/token, xác nhận đúng đủ paths trước finalize, dùng bytes tin cậy từ ledger/object metadata, idempotent. Không trả success khi không tìm thấy.
- Giữ overload cũ `(p_upload_id text,p_bytes_freed bigint)` tương thích test/worker cũ; khóa hàng, từ chối nếu upload_id không duy nhất, không pick LIMIT 1; không trừ counter hai lần. Không đổi tên input parameter trên signature cũ.
- Các hàm worker mới phải REVOKE PUBLIC/anon/authenticated và GRANT service_role; RLS/ACL kiểm thử cả sau upgrade.

## Đối soát dung lượng

- Giữ RPC `reconcile_storage_organization_usage(p_total_capacity_bytes bigint,p_used_storage_bytes bigint,p_measured_projects jsonb,p_is_valid boolean)`.
- Backend xác minh tổ chức/project bằng Management API và thu thập metadata read-only. Mỗi phần tử p_measured_projects có `project_ref`, `bytes` (decimal integer string), `observed_at`, `status:'SUCCESS'`, `objects` là array `{bucket_id,name,created_at,size_bytes}` của bucket report-screenshots (các project không có bucket trả array rỗng). Tổng bytes là mọi bucket, objects chỉ phục vụ đối soát phần giữ chỗ.
- Query bytes+objects trong cùng một SQL statement/snapshot. Không dùng số liệu kỳ thanh toán.
- SQL chỉ giảm R khi object thực đã được tính trong U snapshot tương ứng; finalize không làm mất dung lượng trước reconcile và không cộng đôi nếu snapshot đã tính object. Có thể đối soát local metadata atomically trong RPC rồi thay phần local snapshot trong tổng; các project khác vẫn theo snapshot có kiểm tra độ cũ/phạm vi.
- Không cấu hình hạn mức thật/project scope thì fail-safe Google, không giả định 1 GiB. Nêu rõ cấu hình staging bắt buộc.
- Khi p_is_valid=false, bảo toàn U/B lần đo tốt trước; p_total_capacity_bytes có thể NULL nếu cấu hình chưa có. Backend hiện dùng SUPABASE_ORG_SLUG, endpoint tổ chức phân trang chính thức và query metadata read-only, byte được gửi dưới dạng decimal string.

## Android

Giữ request reservation interface client hiện có hoặc thêm tham số optional có wrapper; không tự fallback vì mọi exception. Exact host/project allowlist và token chỉ gửi tới host tin cậy. Giữ nghiệp vụ core và receipt.

JDK đã biết trên máy: `C:/Program Files/Android/Android Studio/jbr`. Chỉ đặt JAVA_HOME cho process test; không cần quét toàn ổ đĩa hoặc đổi cấu hình hệ thống.
