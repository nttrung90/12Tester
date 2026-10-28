# Runbook Thu thập Baseline & Giám sát Egress Production (7–14 Ngày)

**Mục đích:** Hướng dẫn đội ngũ vận hành thu thập số liệu egress trước và sau phát hành bản tối ưu (E00–E05), phân tách cohort người dùng, đối chiếu lưu lượng cloud và xử lý sai lệch.

---

## 1. Lưu ý quan trọng về Metric Cloud Storage

> [!WARNING]
> Metric Google Cloud Storage `storage.googleapis.com/network/sent_bytes_count` **KHÔNG TỰ ĐỘNG CÓ NHÃN (label)** `client_version` hoặc `app_build`.
> 
> Cloud Storage chỉ ghi nhận lưu lượng mạng ở cấp độ bucket, location và storage class. Do đó:
> - **Tuyệt đối không tìm kiếm nhãn phiên bản app trên dashboard Google Cloud Monitoring.**
> - Phải kết hợp số liệu từ **App Telemetry (ImageInstrumentation)** để phân nhóm theo cohort và đối chiếu với tổng egress của bucket qua công cụ `tools/telemetry-reconciliation/reconcile-telemetry.mjs`.

---

## 2. Kế hoạch thu thập Baseline 7–14 Ngày

### 2.1. Giai đoạn 1: Đo Baseline trước khi cập nhật (T - 14 ngày đến T - 1 ngày)
1. **Thiết lập theo dõi:**
   - Xác nhận bucket đang hoạt động: `cross-testers.firebasestorage.app`.
   - Bật Cloud Monitoring dashboard theo dõi:
     - `storage.googleapis.com/network/sent_bytes_count` (tổng byte truyền ra).
     - `storage.googleapis.com/storage/total_bytes` (dung lượng lưu trữ).
     - Request count theo response code: 2xx, 3xx, 404, 403, 5xx.
   - Bật BigQuery Billing Export để ghi nhận chi phí SKU:
     - `Cloud Storage - Network Internet Egress (Asia)`.
     - `Cloud Storage - Class A Operations`.
     - `Cloud Storage - Class B Operations`.
2. **Thu thập dữ liệu hoạt động:**
   - Ghi nhận DAU (Daily Active Users), số report nộp mỗi ngày, số chiến dịch active.
   - Tính toán chỉ số chuẩn hóa:
     - $\text{GiB Egress} / 1.000 \text{ lượt nộp báo cáo}$.
     - $\text{GiB Egress} / 1.000 \text{ DAU}$.

### 2.2. Giai đoạn 2: Phát hành theo Staged Rollout và đo Cohort
1. **Phát hành theo tỷ lệ:**
   - Ngày 1–2: 10% người dùng (Bản cập nhật v1.2.0 có E01/E02).
   - Ngày 3–4: 25% người dùng.
   - Ngày 5–7: 50% người dùng.
   - Ngày 8–14: 100% toàn bộ người dùng.
2. **Thu thập Telemetry từ App (`ImageInstrumentation`):**
   - Thiết bị client gửi aggregated telemetry định kỳ khi kết thúc phiên:
     - `clientVersion`: Chuỗi phiên bản (ví dụ "1.2.0").
     - `cohort`: Nhóm cấu hình ("v11_legacy", "v12_optimized_e01", "v12_thumbnail_e02").
     - `bodyBytesRead`: Tổng byte body đã tải thành công.
     - `requestCount`: Số lượt request gửi đi.
     - `memoryHits`: Số lượt tái sử dụng Bitmap từ RAM cache.
     - `statusCodeCounts`: Phân bố HTTP 200, 404, 403.

### 2.3. Giai đoạn 3: Đối chiếu hai chiều (Reconciliation)
Chạy script đối chiếu hàng ngày:
```bash
# Cách 1 (Khuyến nghị): Truyền file JSON chứa toàn bộ metric Cloud Storage kèm metadata đo lường (window, coverage)
node tools/telemetry-reconciliation/reconcile-telemetry.mjs \
  --client-file path/to/client_cohort_logs.json \
  --cloud-file path/to/cloud_metrics.json \
  --out path/to/reconciliation_report.json

# Cách 2: Truyền cờ scalar trực tiếp (lưu ý: thiếu metadata measurementWindow/coveragePercent sẽ trả status=UNKNOWN theo F13)
node tools/telemetry-reconciliation/reconcile-telemetry.mjs \
  --client-file path/to/client_cohort_logs.json \
  --cloud-bytes <sent_bytes_count_from_gcs> \
  --cloud-requests <request_count_from_gcs>
```
- So sánh tổng `bodyBytesRead` của toàn bộ client với `sent_bytes_count` của bucket.
- **Xác thực dữ liệu:**
  - Nếu số liệu Cloud = 0 trong khi Client > 0, hoặc Client > Cloud, hoặc thiếu metadata window/coverage hợp lệ, công cụ sẽ đánh dấu `status = INVALID` hoặc `UNKNOWN` và cảnh báo dữ liệu không toàn vẹn.
  - Khi dữ liệu đầy đủ, khớp cửa sổ đo và tỷ lệ chênh lệch hợp lệ, công cụ trả `summary.status = OK`.
- **Ngưỡng chấp nhận:** Chênh lệch lưu lượng không phân loại ($\text{Unclassified External Egress}$) phải $< 20\%$.
- **Nếu chênh lệch $> 20\%$:** Phát cảnh báo `ALERT`. Cần phân tích nguyên nhân (client cũ chưa cập nhật, response headers, browser/tooling) trước khi kết luận; tuyệt đối không tự quy toàn bộ delta thành lưu lượng lạm dụng. Chỉ chạy dry-run kiểm tra tiền điều kiện:
  ```bash
  node backend/storage-cleanup/dry-run-token-revocation.mjs
  ```

---

## 3. Checklist Production Gates (Ngoài môi trường Local)

| Gate | Trách nhiệm | Điều kiện mở | Trạng thái |
|---|---|---|---|
| **Gate 1: Xác nhận Region & Billing SKU** | Quản trị viên Cloud | Kiểm tra bucket location, xác nhận mức áp giá Always Free 100GB | Chờ quyền Cloud |
| **Gate 2: App Check Observation** | Đội ngũ Mobile | Bật App Check Play Integrity ở chế độ `MONITOR_ONLY`, theo dõi tỷ lệ hợp lệ | Chờ release APK |
| **Gate 3: Media Gateway Rollout** | Quản trị viên Supabase | Triển khai migration `045_thumbnail_support.sql` và `046_media_access_gateway.sql` | Mã nguồn sẵn sàng |
| **Gate 4: Kiểm thử thiết bị thật** | Đội ngũ QA / Tester | Đo frame rate cuộn danh sách (120Hz), đo heap memory thật trên thiết bị Android low-end | Cần thiết bị thật |
| **Gate 5: Quyết định CDN (E05)** | Tech Lead / FinOps | Chạy `calculate-tco.mjs` với số đo DAU thật; chỉ bật CDN khi net ROI dương | Giữ mặc định TẮT |
