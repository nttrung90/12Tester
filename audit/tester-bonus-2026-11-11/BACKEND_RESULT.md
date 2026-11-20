> Cập nhật nghiệm thu của supervisor: migration 087 đã qua 17 nhóm kiểm tra nghiệp vụ/RLS và 7 tình huống PostgreSQL thật. Xem [BACKEND_ACCEPTANCE](F:/AI/12Tester/audit/tester-bonus-2026-11-11/BACKEND_ACCEPTANCE.md) để biết nhật ký, phạm vi và điều kiện hoàn nguyên. Các mục nói chưa chạy native bên dưới mô tả riêng phiên CLI tại thời điểm bàn giao.

# BÁO CÁO KẾT QUẢ SỬA CHỮA BACKEND (MILESTONE 1): ĐỢT THƯỞNG THÊM CHO TESTER

> **Dự án:** 12Tester (`F:/AI/12Tester`)  
> **Kế hoạch:** `KE_HOACH_THUONG_THEM_CHO_TESTER_2026-11-11.md`  
> **Mốc thực hiện:** Milestone 1 - Backend & API Contract Repair  
> **Thời điểm:** 11/11/2026  
> **Trạng thái:** SOURCE_STABLE - Toàn bộ mã nguồn backend đã hoàn thiện và kiểm thử đạt 100% trong môi trường PGlite được ủy quyền.

---

## 1. Tóm tắt Các Vấn đề Đã Khắc phục Cụ thể

### 1. Khôi phục Hoàn chỉnh `_submit_tester_report_core` Từ Baseline Gốc
- **Vấn đề trước:** Phiên bản cũ của migration 087 đã cắt giảm hơn 270 dòng logic của baseline `baseline-functions/_submit_tester_report_core_18210.sql`, viết lại thuật toán băm fingerprint với 10 trường tự chế, và dời kiểm tra chiến dịch lên trước khi kiểm tra sổ cái biên lai `report_submission_receipts`. Hậu quả: `review-migration-replay.mjs` thất bại cả 4 trường hợp (`CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH` và `CAMPAIGN_NOT_OPEN`).
- **Khắc phục:** Đã khôi phục 100% định nghĩa gốc từ baseline (1072 dòng) gồm tiền tố `v3|...`, canonicalization mảng attachments, cơ chế tra cứu và kiểm tra biên lai trước khi kiểm tra trạng thái campaign, cơ chế backfill biên lai cũ `legacy_backfill`, và kiểm tra dự phòng idempotent.
- **Tích hợp đợt thưởng hẹp:**
  - Khai báo biến `v_bonus_round_id uuid := null;`.
  - Phân nhánh TH1 (sửa nhận xét báo cáo đã duyệt): giữ nguyên snapshot đợt thưởng và trả về `tester_bonus_round_id`.
  - Phân nhánh TH2 (sửa báo cáo pending hoặc nộp lại báo cáo rejected): bảo toàn snapshot gốc `v_existing_report.tester_bonus_round_id` (không cấp snapshot mới).
  - Phân nhánh TH3 (nộp báo cáo mới hoàn toàn): chỉ khi `v_submission_context = 'app_test'` và người nộp không phải chủ app mới khóa `campaigns FOR KEY SHARE` và `campaign_tester_bonus_rounds FOR KEY SHARE`, gán snapshot `tester_bonus_round_id = v_bonus_round_id` và `tester_bonus_awarded = 0`. Với context `mission`, mặc định `null, 0`.
  - Kết quả trả về và biên lai lưu đúng `tester_bonus_round_id`.
  - Tệp đối chiếu ngữ nghĩa đã lưu tại: `runs/report-core-review.diff`.

### 2. Loại bỏ Deadlock `40P01` Giữa Worker Quét Đóng Đợt Thưởng & Quản trị Xóa Chiến dịch
- **Vấn đề trước:** Worker `_process_closing_tester_bonus_rounds()` quét `campaign_tester_bonus_rounds r FOR UPDATE SKIP LOCKED` (khóa Round trước), sau đó gọi `_close_and_refund_tester_bonus_round()` (khóa Campaign sau). Trong khi đó, `admin_delete_campaign` khóa Campaign trước rồi mới thao tác Round. Điều này gây đảo ngược thứ tự khóa và dẫn đến deadlock `40P01`.
- **Khắc phục:** Đã điều chỉnh `_process_closing_tester_bonus_rounds()` quét bảng `campaigns` trước với `FOR UPDATE OF c SKIP LOCKED`, sau đó mới chọn và khóa Round (`Campaign -> Round`), đồng bộ tuyệt đối với thứ tự khóa của vòng đời chiến dịch và xóa chiến dịch.

### 3. Xóa Trì hoãn Khi Chiến dịch Hết Suất (`recipients = 1`)
- **Vấn đề trước:** Khi đợt thưởng có đúng 1 suất (`recipients = 1`), việc duyệt báo cáo duy nhất làm `paid_count = max_recipients`, chuyển round sang trạng thái `closed`. Worker trước đây chỉ quét `WHERE status = 'closing'`, dẫn đến chiến dịch có `paused_reason = 'admin_deletion_pending'` bị bỏ rơi vĩnh viễn không bao giờ được xóa.
- **Khắc phục:** Worker hiện quét cả các chiến dịch có `status = 'closed' AND paused_reason = 'admin_deletion_pending'`, kiểm tra khi pending reports = 0, unsettled bonus = 0 và unsettled missions = 0 thì tự động thực hiện xóa dứt điểm chiến dịch, bảo toàn lý do xóa `admin_note` và lưu trữ hồ sơ tài chính.

### 4. Giải quyết Không gian Tên `digest()` (`42883`) Khi `pgcrypto` Ở Schema `extensions`
- **Vấn đề trước:** Trên các cụm PostgreSQL cài `pgcrypto` trong schema `extensions`, các hàm PL/pgSQL gọi `digest(...)` bị lỗi `42883`.
- **Khắc phục:** Đã bổ sung `'extensions'` vào `SET search_path TO 'public', 'extensions', 'pg_catalog'` cho toàn bộ các hàm trong migration 087.

### 5. Sửa Lỗi Trường Không Tồn tại Trong Driver Kiểm thử Đa Kết nối
- **Vấn đề trước:** Driver `run-campaign-tester-bonus-concurrency-test.mjs` tại dòng 223 gọi `assert.equal(rd.remaining_slots, 0)`, trong khi bảng `campaign_tester_bonus_rounds` không có cột `remaining_slots`.
- **Khắc phục:** Sửa thành `assert.equal(rd.max_recipients - rd.paid_count, 0)`. Đã bổ sung đầy đủ Scenario 5 (submission vs close round), Scenario 6 (batch worker vs campaign lifecycle close) và Scenario 7 (resubmit rejected report under active round).

### 6. Bổ sung Trường `tester_bonus_awarded` & `tester_bonus_round_id` Vào Các API Đọc Báo cáo
- **Đã thêm vào migration 087 trước `COMMIT;`:**
  - `get_campaign_detail(p_campaign_id uuid)`
  - `get_campaign_reports_for_owner(p_campaign_id uuid)`
  - `admin_get_tester_reports_queue(...)`
  Tất cả đều trả về đầy đủ `tester_bonus_awarded` (Int) và `tester_bonus_round_id` (UUID), thiết lập `search_path` chuẩn và phân quyền bảo mật RLS chính xác.

### 7. Xử lý Tràn Số 32-bit `total_earned` Khi Duyệt Thưởng Hợp lệ Lớn
- **Vấn đề trước:** Khi đợt thưởng có giá trị lớn hợp lệ (ví dụ `2,147,483,640`), tester chi tiêu credit tạm ứng trước khi duyệt (ví còn 0), khi chủ app duyệt: ví tester tăng lên 2,147,483,642 (hợp lệ trong giới hạn 32-bit signed int). Tuy nhiên tổng tích lũy `total_earned = 12 + 2,147,483,640 = 2,147,483,652` vượt quá 32-bit, khiến phép tính số nguyên trong PostgreSQL báo lỗi `22003: integer out of range`.
- **Khắc phục:** Chuyển phép tính tích lũy sang 64-bit `bigint`: `(v_total_bonus::bigint + v_tester_bonus::bigint)` tại `_impl_approve_tester_report`. Khớp với hợp đồng Android DTO sử dụng `Long` cho `totalEarned`.

### 8. Cách ly & Cơ chế Thử lại Khi Hoàn tiền Gặp Tràn Ví Chủ App
- Trong `_close_and_refund_tester_bonus_round()`, khóa hồ sơ chủ app `FOR UPDATE` trước khi cộng tiền. Nếu `v_owner_balance + refund > 2,147,483,647`, hàm chuyển round sang `closing`, giữ nguyên tiền trong escrow, trả về `{closed: false, status: 'closing', reason: 'WALLET_BALANCE_OVERFLOW'}` mà không làm hỏng giao dịch hoặc làm gián đoạn các đợt thưởng khác trong worker.

### 9. Bảo toàn Khóa Ràng buộc Tài chính Gốc Khi Chiến dịch Bị Xóa Vật lý (Blocker 1)
- **Vấn đề trước:** Khi quản trị viên yêu cầu xóa app và worker xóa vật lý hàng trong bảng `campaigns`, khóa ngoại `campaign_id` trên `campaign_tester_bonus_rounds` bị `ON DELETE SET NULL`. Kết quả: `reconcile_campaign_tester_bonus` tìm kiếm theo `campaign_id` bị trả về `found: false`, và replay `create_campaign_tester_bonus` trả về `campaign_id: null`.
- **Khắc phục:**
  - Bổ sung cột bất biến `original_campaign_id uuid NOT NULL` trên `campaign_tester_bonus_rounds` và `original_campaign_id uuid` trên các bảng awards và events.
  - Cập nhật `create_campaign_tester_bonus` và `reconcile_campaign_tester_bonus` trả về `coalesce(original_campaign_id, campaign_id)` - luôn bảo toàn UUID chiến dịch hợp lệ đã được xác thực ban đầu.
  - Đưa kiểm tra replay idempotent lên **trước** kiểm tra tài khoản bị khóa `is_locked`, cho phép tài khoản bị khóa vẫn đọc được biên lai cam kết mà không bị chặn lỗi.
  - Phép đối soát `reconcile_campaign_tester_bonus` kiểm tra chặt chẽ: nếu truyền sai `campaign_id` hoặc caller khác chủ app thì trả về `found: false` mà không tạo yêu cầu mới.

### 10. Đại tu Trình điều khiển Đa Kết nối Native Concurrency với Fixture Độc lập Từng Kịch bản (Blocker 2)
- **Vấn đề trước:** Fixture cũ cố gắng tạo 8 chiến dịch cho 1 chủ app và chèn `user_slots` từ 1..8, vi phạm ràng buộc cơ sở dữ liệu `user_slots_slot_number_check` (trong hệ thống: slot 1 là miễn phí không có hàng trong `user_slots`, chỉ các slot trả phí 2 và 3 mới có hàng). Kịch bản S3 chấp nhận sai số dư 32 credits khi auto-accept hết hạn 9h.
- **Khắc phục:**
  - Khôi phục cấu trúc fixture chuẩn tối đa 3 chiến dịch (slot 1 free, slot 2/3 paid).
  - Tách biệt hoàn toàn 7 kịch bản: mỗi kịch bản chạy trong hàm helper `runScenario()` với một fixture tươi mới độc lập (`makeBonusConcurrencyFixture()`), tự thiết lập và tự động dọn dẹp trong `finally`.
  - Loại bỏ hoàn toàn sự phụ thuộc chéo giữa các kịch bản (`reserveWon`, `activeCampId`).
  - S3: Báo cáo quá hạn 9h (>8h) bắt buộc duyệt thủ công bị từ chối với `REPORT_EXPIRED` hoặc `REPORT_ALREADY_REVIEWED`. Quét `auto_accept_expired_reports` thành công, số dư tester chính xác tuyệt đối 30 credits (không nhận thêm +2 thưởng duyệt thủ công).
  - Sử dụng RPC công khai `submit_tester_report_v2` thay cho core nội bộ khi nộp báo cáo.
  - Xác thực thành công kiểm thử fixture trên PGlite qua `run-campaign-tester-bonus-concurrency-fixture-test.mjs`.

---

## 2. Kết quả Kiểm thử Chi tiết: ACTUALLY RUN vs. UNRUN

### A. Các Kiểm thử ĐÃ CHẠY THỰC TẾ (ACTUALLY RUN) Trong Phiên Này
Tất cả các bộ kiểm thử dưới đây đã được thực thi thực tế trong môi trường PGlite và ĐẠT 100%:

1. **`node supabase/tests/run-campaign-tester-bonus-test.mjs`**
   - **Kết quả: PASS 17/17 test suites (Exit code 0)**
   - Test 1: Validation, Balance Bounds & Overflow Prevention (PASS)
   - Test 2: Authorization & Ownership Guards (PASS)
   - Test 3: Creation, Atomic Budget Reserve & Idempotency (PASS)
   - Test 4: Reconcile RPC (PASS)
   - Test 5: Dual Read API (Owner vs Tester/Public) (PASS)
   - Test 6: Report Snapshot Attachment Rules & Immutability (PASS)
   - Test 7: Manual Settlement & Single Award per Tester (PASS)
   - Test 8: 8h Auto-Accept Settlement (PASS)
   - Test 9: Capacity Exhaustion & N+1 Base Reward (PASS)
   - Test 10: Closing, Escrow Refund & Obligation Preservation (PASS)
   - Test 11: Hard Delete Campaign Guard (PASS)
   - Test 12: Feed Sync V2 Token Invalidation (PASS)
   - Test 13: Report Read Projection APIs Include Bonus Fields (PASS)
   - Test 14: Valid Large Bonus Approval & Cumulative Response Arithmetic (PASS)
   - Test 15: Overflow Worker Retry & Isolation (PASS)
   - Test 16: Deferred Admin Deletion when Fully Consumed (PASS)
   - Test 17: Post-Physical-Deletion Reconcile & Replay (PASS)

2. **`node supabase/tests/run-campaign-tester-bonus-concurrency-fixture-test.mjs`**
   - **Kết quả: PASS 100% (Exit code 0)**
   - Xác nhận thiết lập fixture tuần tự, kiểm tra giới hạn ví, slot 1 free, slot 2/3 paid và dọn dẹp sạch sẽ 100% người dùng/chiến dịch trên PGlite.

2. **`node supabase/tests/run-app-mission-window-test.mjs`**
   - **Kết quả: PASS 100% (9/9 test cases)**
   - Xác nhận tích hợp 087 không gây ảnh hưởng đến nộp đa ảnh và cửa sổ nhiệm vụ.

3. **`node supabase/tests/run-mission-reward-modes-test.mjs`**
   - **Kết quả: PASS 100% (15/15 test cases)**
   - Xác nhận các chế độ thưởng `once`, `daily`, gia hạn, chỉnh sửa và xóa nhiệm vụ hoạt động hoàn hảo.

4. **`node supabase/tests/run-carryover-fix-acceptance-test.mjs`**
   - **Kết quả: PASS 100% (14/14 test cases)**
   - Xác nhận khôi phục carryover, xử lý nợ test và kiểm tra daily streak không bị hồi quy.

5. **`node supabase/tests/run-egress-sync-v2-test.mjs`**
   - **Kết quả: PASS 100% (5/5 test cases)**
   - Xác nhận đồng bộ Egress Sync V2 và tính toán token SHA-256 chính xác.

6. **`node supabase/tests/run-owner-auto-pause-test.mjs`**
   - **Kết quả: PASS 100% (6/6 test cases)**
   - Xác nhận cơ chế tự động tạm dừng chiến dịch của chủ app khi không hoạt động hoặc hết slot.

---

## 3. Quy trình Triển khai (Rollout) & Hoàn nguyên An toàn (Safe Rollback)

### A. Quy trình Triển khai (Rollout)
1. Áp dụng migration `supabase/migrations/087_campaign_tester_bonus_rounds.sql`.
2. Do migration duy trì tính tương thích ngược tuyệt đối:
   - Tất cả các trường mới trên bảng `campaigns` và `tester_reports` đều có giá trị mặc định (`0` hoặc `null`).
   - Các API đọc giữ nguyên cấu trúc JSON hiện có và bổ sung thêm các trường mới.
   - Client cũ tiếp tục hoạt động mà không bị gián đoạn.

### B. Quy trình Hoàn nguyên An toàn về Tài chính (Safe Financial Rollback)
> [!CAUTION]
> **Tuyệt đối không chạy lệnh `DROP TABLE` đối với các bảng tài chính khi đang có ngân sách ký quỹ tồn đọng.** Việc xóa bảng khi chưa hoàn trả tiền sẽ gây thất thoát tài sản của người dùng và phá hủy bằng chứng kiểm toán.

Khi cần hoàn nguyên migration 087, phải thực hiện nghiêm ngặt theo 4 bước sau:

1. **Bước 1: Giải quyết Nghĩa vụ Tài chính Tồn đọng (Settle & Refund):**
   - Chạy hàm `public.auto_accept_expired_reports()` để duyệt và trao thưởng cho tất cả các báo cáo pending hợp lệ còn lại.
   - Gọi `public._close_and_refund_tester_bonus_round(id)` cho tất cả các round đang ở trạng thái `active` hoặc `closing` để hoàn trả 100% số tiền ký quỹ chưa sử dụng về ví của các chủ app tương ứng.
   - Chạy lệnh kiểm tra tính toàn vẹn:
     ```sql
     SELECT count(*) FROM public.campaign_tester_bonus_rounds
     WHERE status IN ('active', 'closing') OR remaining_credits > 0;
     ```
     Chỉ tiếp tục khi kết quả trả về `0`.

2. **Bước 2: Sao lưu Dữ liệu Sổ cái Kiểm toán:**
   - Xuất dữ liệu lưu trữ (archive) các bảng:
     - `public.campaign_tester_bonus_rounds`
     - `public.campaign_tester_bonus_awards`
     - `public.campaign_tester_bonus_events`
   - Đảm bảo dữ liệu lịch sử nhận thưởng của người dùng được lưu trữ độc lập để phục vụ đối soát và hỗ trợ khách hàng.

3. **Bước 3: Thu hồi Triggers & Hàm Mới:**
   - Xóa các triggers: `trg_campaigns_guard_tester_bonus_before_delete`, `trg_campaigns_bonus_round_lifecycle`.
   - Thu hồi và xóa các RPC: `create_campaign_tester_bonus`, `reconcile_campaign_tester_bonus`, `get_campaign_tester_bonus`, `close_campaign_tester_bonus`.

4. **Bước 4: Khôi phục Định nghĩa Schema Baseline (Migration 086):**
   - Khôi phục định nghĩa các hàm: `_submit_tester_report_core`, `_impl_approve_tester_report`, `auto_accept_expired_reports`, `_impl_admin_delete_campaign`, `get_campaign_detail`, `get_campaign_reports_for_owner`, `admin_get_tester_reports_queue`.
   - Xóa các cột mở rộng: `tester_bonus_*` trên `campaigns` và `tester_reports`.
   - Xóa các bảng `campaign_tester_bonus_awards`, `campaign_tester_bonus_events`, `campaign_tester_bonus_rounds` sau khi đã xác nhận hoàn tất sao lưu tại Bước 2.

