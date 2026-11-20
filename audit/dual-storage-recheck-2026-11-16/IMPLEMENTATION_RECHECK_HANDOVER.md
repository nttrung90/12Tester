# BÁO CÁO BÀN GIAO TRIỂN KHAI VÀ KIỂM CHỨNG TOÀN DIỆN DUAL STORAGE
**Dự án**: 12Tester  
**Ngày thực hiện**: 2026-11-16  
**Phạm vi**: Khắc phục dứt điểm R01 (P0), R02 (P1), R03 (P1) trong kiến trúc Dual Storage (Supabase Storage + Google Cloud Storage / Firebase Storage)  
**Trạng thái kiểm chứng**: **13/13 Validation Jobs PASS (100%)** — Exit code 0  

---

## 1. TỔNG QUAN VÀ NGUYÊN NHÂN GỐC CỦA CÁC VẤN ĐỀ

### R01 (P0): Lỗi 42501 Khi Tải File Lên Supabase Storage (Server Permission Probe Metadata)
- **Bản chất kỹ thuật**:
  - `R01` bắt nguồn từ payload metadata do Supabase Storage server (GoStorage / PostgREST) đưa vào PostgreSQL RLS policy probe function `_can_insert_report_object`, dạng `{"contentLength": 100, "mimetype": "image/jpeg", "contentEncoding": "..."}`, chứ không phải HTTP header do client-side `supabase-js` gửi.
  - Hàm RLS policy `_can_insert_report_object` trong Migration 092 chỉ kiểm tra duy nhất `metadata->>'size'`, dẫn đến việc preflight probe từ Storage server bị từ chối với mã lỗi `42501 (Permission Denied)`.
- **Giải pháp triển khai trong Migration 093**:
  - Hỗ trợ an toàn cả 2 trường: `coalesce(metadata->>'size', metadata->>'contentLength')`.
  - Ép kiểu và kiểm tra nghiêm ngặt: regex `^[0-9]+$`, độ dài chuỗi $\le 15$ ký tự, giá trị $> 0$. Mọi trường hợp null, empty, âm, số thực, boolean, hoặc ký tự lạ đều bị từ chối lập tức (`fail-closed`).
  - Áp dụng cap dung lượng phân tầng:
    - Ảnh chính (`object_path`): giới hạn theo `least(expected_main_bytes, 2000000)` (tối đa 2MB).
    - Ảnh thu nhỏ (`thumbnail_object_path`): giới hạn theo `least(expected_thumbnail_bytes, 150000)` (tối đa 150KB).
  - Không nới lỏng bất kỳ kiểm tra bảo mật nào về owner JWT (`request.jwt.claim.sub`), prefix đường dẫn (`reports/<campaign>/<user>/...`), thời hạn giữ chỗ (`reservation_expires_at > now()`), hoặc bucket (`report-screenshots`).

### R02 (P1): Quota Race Condition Khi Storage Provider Rollback Savepoint
- **Nguyên nhân gốc**:
  - Storage server thực hiện kiểm tra quyền (permission probe) bằng cách gọi RLS policy trong một `SAVEPOINT storage_permission_probe`. Sau khi kiểm tra thành công, server gọi `ROLLBACK TO SAVEPOINT` trước khi nhận stream dữ liệu và thực hiện privileged write (`INSERT` dưới quyền superuser).
  - Khi `ROLLBACK TO SAVEPOINT` xảy ra, toàn bộ row lock trên `report_media_uploads` bị giải phóng ngay lập tức.
  - Nếu reconcile chạy xen kẽ giữa lúc probe rollback và lúc privileged insert hoàn tất, hoặc nếu worker dọn dẹp chạy absence ACK xóa hold trong khi file đang in-flight, hệ thống sẽ undercount quota ($U + R < Actual$).
- **Giải pháp triển khai trong Migration 093**:
  - **Lịch sử hoàn tất đơn điệu theo từng path (Monotonic Per-Path Completion)**:
    - Bổ sung 2 cột boolean monotonic vào `public.report_media_uploads`: `main_committed` và `thumbnail_committed`.
    - Khi một object xuất hiện trong `storage.objects`, cờ tương ứng chuyển sang `true` và không bao giờ quay về `false`.
  - **Kế toán bảo thủ (Conservative Accounting) & Cơ chế Tombstone**:
    - **Path CHƯA TỪNG commit có thể giữ tombstone vô thời hạn khi chưa có completion fence từ Storage provider; TTL hoặc absence ACK của cleanup worker KHÔNG tự giải phóng hold này**: Đây là lựa chọn thiết kế bảo thủ có chủ đích nhằm đảm bảo writer bị trễ mạng (delayed write) sau khi đã qua bước kiểm tra quyền không bao giờ làm quota bị undercount.
    - Trong `_finalize_report_media`: nếu thumbnail chưa commit, giữ nguyên hold `v_main_bytes + coalesce(expected_thumbnail_bytes, 150000) - quota_accounted_bytes`.
    - Trong `_submit_tester_report_core`: tính tổng `active_reserved_bytes` theo điều kiện `reserved_bytes > 0` (thay vì `status <> 'deleted'`), bảo toàn tombstone hold khi có submission của report khác.
    - Trong `record_report_media_deleted`:
      - Nếu cả 2 path đã commit (`main_committed AND thumbnail_committed`): giải phóng toàn bộ hold về 0 ($R = 0$).
      - Nếu có path chưa từng commit (partial pair deletion hoặc total absence ACK): tiếp tục bảo lưu hold tương ứng cho path chưa commit để chống late commit race.
      - Xóa sạch `size_bytes = 0, thumbnail_size_bytes = 0` khi record chuyển sang `status = 'deleted'` để các lần dọn dẹp hồi sinh sau chỉ tính đúng `bytes_freed` của file hiện tại.
    - Trong `reconcile_storage_organization_usage`:
      - Cập nhật đơn điệu `main_committed` và `thumbnail_committed`.
      - Khi toàn bộ files đã commit: hold được giải phóng về 0 ($R = 0$). Khi file còn pending: hold giữ đúng cap.
      - Sử dụng CTE `updated` với `RETURNING reserved_bytes` để tính tổng $R$ toàn cục nhất quán, loại bỏ stale read.
    - Trong `get_expired_report_media_for_cleanup`:
      - Live claim ghi nhận lịch sử commit đơn điệu từ actual objects trong storage trước khi worker xóa vật lý.
      - Resurrected objects giữ deadline dọn dẹp nghiêm ngặt: `least(coalesce(delete_deadline_at, now() + 24h), min(created_at) + 240h)`, không nới lỏng deadline gốc 10 ngày.
    - DML Upgrade Backfill: cập nhật dữ liệu 091/092 cũ sang 093 an toàn, đặt `is_org_metric_valid = false`, `dispatch_mode = 'google_overflow'` để bảo vệ quota cho tới lần fresh reconcile đầu tiên.

### R03 (P1): Reconciler Bỏ Qua Cờ `is_org_metric_valid` Trả Về Từ Database ACK
- **Nguyên nhân gốc**:
  - Script định kỳ `backend/storage-cleanup/reconcile-org-storage.mjs` không kiểm tra trường `is_org_metric_valid` trong kết quả RPC của Postgres. Dù SQL kích hoạt fail-safe Google overflow (`is_org_metric_valid = false`), worker vẫn log thành công và thoát với `exit 0`.
- **Giải pháp triển khai trong `reconcile-org-storage.mjs`**:
  - Hợp đồng kiểm tra nghiêm ngặt: chỉ khi `r.data?.is_org_metric_valid === true && summary.is_metric_valid` thì metric mới được coi là hợp lệ.
  - Bất kỳ giá trị nào khác (`false`, `undefined`, `null`, `'true'`, `'false'`, `0`, `1`, `[]`, `{}`) đều kích hoạt:
    - Đặt cờ `summary.is_metric_valid = false`.
    - Đặt cờ `summary.fail_safe_triggered = true`.
    - Ghi log cảnh báo chi tiết `[WARN] Reconcile RPC returned is_org_metric_valid !== true ...`.
    - Ở chế độ live (không phải dry-run), thoát với mã lỗi `process.exitCode = 1`.

---

## 2. DANH MỤC THAY ĐỔI MÃ NGUỒN

| File | Thành phần | Mô tả thay đổi |
| :--- | :--- | :--- |
| `supabase/migrations/093_dual_storage_runtime_recheck.sql` | SQL Migration | Toàn bộ logic SQL mới cho R01 (server metadata probe), R02 (monotonic completion, conservative accounting, CTE RETURNING, deadline preservation, RPC grants, reset sizes on delete), và upgrade backfill từ 092. |
| `backend/storage-cleanup/reconcile-org-storage.mjs` | Node.js Worker | Kiểm tra nghiêm ngặt `r.data?.is_org_metric_valid === true`, kích hoạt fail-safe và exit 1 khi false/missing/malformed. |
| `backend/storage-cleanup/reconcile-org-storage.test.mjs` | Unit Tests | Cập nhật mock fixture trả boolean chuẩn; bổ sung 8 ca kiểm thử ma trận ACK malformed. |
| `supabase/tests/run-dual-storage-hardening-test.mjs` | SQL Test Suite | Cập nhật kỳ vọng kiểm thử cho thiết kế monotonic completion mới: khi thumb chưa commit giữ $R = 150000$, khi cả 2 files đã commit thì $R = 0$ và reconcile lặp giữ $R = 0$. |
| `supabase/tests/run-dual-storage-concurrency-test.mjs` | Multi-connection PG Runner | Sửa lỗi tham số `reserve(id, size, thumb)`; tách Phase B dung lượng 500KB; sửa typo dọn fixture `upload_id='same'` scoped campaign/user cho tính độc lập giữa các phase; dời expire sau savepoint rollback để probe hợp lệ; bổ sung application finalize trên connection 2; bỏ reset used_storage_bytes=800 cuối Phase B giữ snapshot U0/R0 sạch; bổ sung kiểm tra coverage ngay sau late INSERT trước reconcile; khôi phục xóa `user_role_journal` sau delete user/profile; loại bỏ `process.exit(0)` để caller giữ nguyên exitCode; ghi nhận trung thực `[NOT RUN]` khi thiếu `TEST_DATABASE_URL`. |
| `docs/ai/sql-index.json` | SQL Tool Index | Tái tạo chỉ mục tự động qua `node tools/ai-sql-index.mjs build` (32 migrations, 262 functions). |

---

## 3. MA TRẬN KẾT QUẢ KIỂM CHỨNG

### A. Supervisor Final Validation (`SUPERVISOR_FINAL_VALIDATION.json`) — 13/13 JOBS PASS (100%)
Chạy qua lệnh `node audit/dual-storage-recheck-2026-11-16/run-supervisor-final-validation.mjs`:

| STT | Suite / Job | Trạng Thái | Ghi Chú |
| :---: | :--- | :---: | :--- |
| 1 | `ack-matrix` | **PASS** | 12 ca kiểm thử ACK matrix (boolean, malformed types, exit codes). |
| 2 | `backend` | **PASS** | 59 unit tests backend cleanup + 23 media gateway adapter tests. |
| 3 | `independent-followup` | **PASS** | 27 ca độc lập tái hiện toàn bộ các chuỗi sự kiện race condition R01/R02. |
| 4 | `original-ack` | **PASS** | Kiểm tra hợp đồng ACK gốc của reconciler. |
| 5 | `original-contract` | **PASS** | Kiểm tra hợp đồng preflight binary storage R01 gốc. |
| 6 | `media-probes` | **PASS** | 14 ca kiểm thử media gateway và authorization policy. |
| 7 | `sql-quota` | **PASS** | 8 ca kiểm thử đơn vị logic hạn mức Dual Storage trên PGlite. |
| 8 | `sql-upgrade` | **PASS** | Kiểm thử nâng cấp tự động từ 091 lên 092. |
| 9 | `sql-hardening` | **PASS** | 20 ca kiểm thử hardening SQL (bao gồm monotonic partial pair completion). |
| 10 | `sql-upgrade-093` | **PASS** | Kiểm thử nâng cấp từ 092 lên 093 ngay tại cửa sổ trước reconcile. |
| 11 | `business-comparison`| **PASS** | 9 ca kiểm thử quy tắc nghiệp vụ so sánh và trừ điểm. |
| 12 | `mission-rewards` | **PASS** | 15 ca kiểm thử chế độ trả thưởng nhiệm vụ và carryover. |
| 13 | `tester-bonus` | **PASS** | 17 ca kiểm thử vòng thưởng tester và quota tức thì. |

### B. Kiểm Thử Tích Hợp Media Gateway HTTP
- Chạy kiểm thử mới độc lập: `node --test backend/media-gateway/media-gateway-server.test.mjs`
- File log kiểm chứng mới: `audit/dual-storage-recheck-2026-11-16/gateway-http-recheck.log`
- File log kiểm chứng trước: `audit/dual-storage-review-2026-11-16/gateway-http-final.log`
- Kết quả: **13/13 tests PASS (100%)**
  - Chặn 401 khi thiếu Bearer token.
  - Chặn 400 khi thiếu query hoặc sai variant.
  - Chặn 410 khi token hoặc báo cáo quá hạn SLA.
  - Chặn 403 khi chữ ký HMAC bị sửa đổi hoặc trộm token (token replay).
  - Thành công 200 truyền bytes trực tiếp cho author, campaign owner, system admin, và child attachments.
  - Fail-closed khi thiếu cấu hình môi trường.

### C. Kiểm Thử Phía Client Android
- Bằng chứng kiểm thử trước: Bộ 170 unit tests Android (`util.*`, `SelectedMediaRegressionTest`, `ScreenshotManager`, `ImageViewerComponents`) đạt **170/170 PASS (100%)**.
- Mã nguồn Android trong đợt này được giữ nguyên tuyệt đối, không có thay đổi phát sinh.

---

## 4. BÁO CÁO CÁC MỤC KHÔNG CHẠY [NOT RUN] (MINH BẠCH & TRUNG THỰC)

Tuân thủ nghiêm ngặt quy định tại `AGENTS.md`:
1. **Kiểm thử PostgreSQL multi-connection đồng thời (`run-dual-storage-concurrency-test.mjs`)**:
   - Trạng thái: **`[NOT RUN]`**
   - Lý do: Môi trường cục bộ không cấu hình biến môi trường `TEST_DATABASE_URL` trỏ tới PostgreSQL database riêng biệt dùng một lần (`12tester_test_*`). Hệ thống in thông báo chuẩn `[NOT RUN] PostgreSQL multi-connection: TEST_DATABASE_URL is missing.` và thoát an toàn không làm ảnh hưởng exitCode của caller. **Không gọi kết quả này là đã chứng minh lock thực tế trên PostgreSQL**.
2. **Endpoint Supabase Storage / Google Cloud Storage Production**:
   - Trạng thái: **`[NOT RUN]`**
   - Lý do: Không kích hoạt network call tới bucket storage thật trên cloud để tránh phát sinh chi phí và rủi ro dữ liệu production; toàn bộ kiểm chứng được thực hiện trên môi trường cô lập Node.js, PGlite, và fake Storage container.
3. **Cron Job / Cloud Scheduler Thực Tế**:
   - Trạng thái: **`[NOT RUN]`**
   - Lý do: Trình lập lịch định kỳ là hạ tầng vận hành ngoài phạm vi kiểm thử mã nguồn cục bộ.
4. **Phạm vi chứng minh bất biến kế toán**:
   - Bất biến kế toán $U + R \ge \text{Actual Live Bytes}$ được chứng minh và kiểm chứng thông qua 28 ca probe độc lập trên môi trường cô lập Node.js / PGlite; **không khẳng định bất biến này đã được chứng minh trên production đa luồng thực tế**.

---

## 5. HÀNH VI KẾ TOÁN QUOTA VÀ CHI PHÍ DUNG LƯỢNG

### Cơ Chế Kế Toán Đơn Điệu (Monotonic Accounting Behavior)
- Khi chỉ có ảnh chính được commit (100 bytes) và ảnh thumbnail đang trong quá trình upload:
  - $U = 100$ bytes (hoặc 300 nếu có remote project).
  - $R = 150,000$ bytes (giữ đúng cap thumbnail tối đa để writer không bị undercount).
- Khi ảnh thumbnail (50 bytes) hoàn tất commit vào storage và reconcile ghi nhận:
  - Cả hai cờ `main_committed = true` và `thumbnail_committed = true`.
  - Cặp file đã hoàn tất đầy đủ.
  - $U$ đo lường chính xác dung lượng thực tế của cả hai file ($100 + 50 = 150$ bytes).
  - $R$ được giải phóng về **0 bytes** ($R = 0$).
  - Các lần reconcile lặp lại tiếp tục duy trì $R = 0$, ngăn chặn triệt để việc giữ 149,950 bytes phantom hold vĩnh viễn gây lãng phí dung lượng tổ chức.

### Chi Phí Dung Lượng Giữ Chỗ Bảo Thủ (Tombstone Hold Cost)
- **Path chưa từng commit**:
  - Giữ hold tối đa: $2\text{MB}$ cho file ảnh chính nếu chưa commit; $150\text{KB}$ cho file thumbnail nếu chưa commit.
  - Do chưa có completion fence từ Storage provider, hold này được giữ vô thời hạn (kể cả sau TTL expiry hoặc absence ACK) để đảm bảo an toàn tuyệt đối trước delayed write.
- **Giải phóng tự động**:
  - Dung lượng này được tự động giải phóng hoàn toàn về 0 khi:
    1. Writer hoàn tất ghi file và reconcile đưa file vào $U$; HOẶC
    2. File đã từng commit bị dọn dẹp vật lý và worker xác nhận ACK hoàn tất.

---

## 6. KẾT LUẬN VÀ BÀN GIAO
- Cả 3 lỗi kỹ thuật R01 (P0), R02 (P1), R03 (P1) đã được giải quyết triệt để ở tầng gốc (Root Cause).
- Toàn bộ 13 validation suites đều đạt kết quả **PASS 100%**.
- Chỉ mục SQL (`docs/ai/sql-index.json`) đã được cập nhật đồng bộ.
- Không còn bất kỳ tiến trình nền (background task) nào chạy dở dang.
- Không thực hiện APK build, commit, push, version bump, hoặc deploy.
- Sẵn sàng bàn giao cho người giám sát nghiệm thu kết quả cuối cùng.
