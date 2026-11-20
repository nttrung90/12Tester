# Notification Campaigns Backend API Contract (Admin Expansion)

Document version: `1.1.0` (Round 1 Codex Review Hardened)  
Date: `2026-11-01`  
Target: Supabase PostgreSQL RPCs & Android Admin Notification Management (`072_admin_notification_campaigns.sql`)

---

## 1. Architectural Principles & Security Rules

1. **Authentication & Authorization**:
   - All admin-facing RPCs strictly enforce unlocked administrator credentials using `public._assert_daily_task_admin()`.
   - Denied callers (`anon`, standard `member`, `moderator`, locked `admin`, or missing credentials) immediately raise `FORBIDDEN: Admin access required` or `UNAUTHORIZED: Authentication required`.
   - Client roles (`PUBLIC`, `anon`, `authenticated`) have **NO DIRECT PERMISSIONS** on internal campaign tables (`notification_campaigns`, `notification_campaign_recipients`, `notification_campaign_requests`, `notification_campaign_events`). All direct modifications are blocked via RLS and revoked privileges.
   - Core background worker functions (`service_process_notification_campaigns`) are restricted to `service_role` execution only. A bounded, authenticated wrapper (`admin_process_due_notification_campaigns`) is provided for manual admin operation and testing.

2. **Idempotency & Replay Protection (Canonical JSON Object Hashing)**:
   - All state-mutating RPCs require a client-generated UUID `p_request_id`.
   - Request-scoped transaction advisory locking (`pg_advisory_xact_lock(hashtext('notif_req:' || p_request_id))`) serializes concurrent duplicate submissions before lookup, ensuring identical requests receive the committed response instead of unique constraint errors.
   - Cross-admin collision protection: A `p_request_id` already claimed by another administrator raises `REQUEST_ID_CONFLICT: Request ID already claimed by another administrator`.
   - Mutation payloads are hashed using deterministic canonical `jsonb` object serialization (`md5(jsonb_build_object(...)::text)`), preventing delimiter-based hash collisions (e.g. `'A|B', 'C'` vs `'A', 'B|C'`).
   - Replaying the same `p_request_id` with an identical payload returns the previous response with `"replayed": true` and **does NOT emit duplicate audit events**.
   - Replaying the same `p_request_id` with a mismatched payload or action fails with `REQUEST_ID_CONFLICT: Request ID already used with different payload`.

3. **Concurrency & Safe Optimistic Locking (CAS)**:
   - Campaign scheduling, updating, sending, and cancelling require `p_expected_revision integer`.
   - If the database revision has advanced, the call fails immediately with `REVISION_CONFLICT`.

4. **Deterministic Recipient Snapshot & Delivery Semantics**:
   - Recipient snapshots are taken deterministically at campaign scheduling / send-now into `public.notification_campaign_recipients`.
   - The snapshot is frozen with `recipient_snapshot_taken = true` and `snapshot_taken_at = clock_timestamp()`.
   - If an audience has `0` matching recipients at schedule time (e.g. `role = 'moderator'` when zero moderators exist), the zero count is permanently frozen and the campaign completes with 0 delivered. Late profile or role changes will **never** expand a frozen scheduled campaign.
   - Recipient fanout uses `FOR UPDATE SKIP LOCKED` on recipient rows to guarantee that concurrent workers and worker restarts never duplicate notifications.
   - Batch inserts create rows in `public.notifications` with:
     ```json
     {
       "campaign_id": "uuid",
       "broadcast": true,
       "sender": "admin"
     }
     ```
   - **No popup states** (`notification_popup_states`) are registered for campaign mass broadcasts, preserving compatibility with 067/069 direct single-user notifications.
   - `sent_count` is tracked per recipient receipt. When a user deletes their notification (`public.notifications` row deleted, setting `receipt.notification_id` to NULL), `sent_count` remains stable.
   - `read_count` represents the live read status among extant remaining notifications (`r.notification_id = n.id AND n.is_read = true`). It reflects current user read state rather than an immutable delivery receipt.

5. **Locale Fallback & Content Structure**:
   - Campaigns define `title_vi`, `title_en`, `body_vi`, `body_en`, and `default_locale` (`'vi'`, `'en'`, or `'bilingual'`).
   - Profile table has no `locale` column; delivered text is resolved from `default_locale`:
     - `'vi'`: delivers Vietnamese text (falls back to English if empty).
     - `'en'`: delivers English text (falls back to Vietnamese if empty).
     - `'bilingual'`: delivers formatted composite text (`title_vi / title_en` and `body_vi \n\n---\n\n body_en`).
     - Localized variants are also attached inside `notifications.data`.

6. **Common Admin Audit Integration Interface**:
   - Dedicated event logging is maintained in `public.notification_campaign_events`.
   - When core admin migration `070_admin_management.sql` is active with `admin_action_events`, campaigns can seamlessly dual-write or bridge actions (`campaign_create`, `campaign_schedule`, `campaign_cancel`, `campaign_send_now`) into the unified admin audit log using the caller's admin UID and request ID.

---

## 2. DTO Specifications & Endpoints

### 2.1 `admin_create_notification_campaign`

Creates a new notification campaign in `draft` status.

#### Signature
```sql
public.admin_create_notification_campaign(
    p_request_id uuid,
    p_title_vi text,
    p_title_en text,
    p_body_vi text,
    p_body_en text,
    p_default_locale text DEFAULT 'vi',
    p_audience_type text DEFAULT 'all',
    p_target_role text DEFAULT NULL,
    p_selected_user_ids uuid[] DEFAULT NULL,
    p_reason text DEFAULT NULL
) RETURNS jsonb
```

#### Response (`200 OK`)
```json
{
  "success": true,
  "replayed": false,
  "campaign": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "title_vi": "Bảo trì hệ thống",
    "title_en": "System Maintenance",
    "body_vi": "Hệ thống sẽ bảo trì từ 23:00 đến 01:00.",
    "body_en": "System will undergo maintenance between 23:00 and 01:00.",
    "default_locale": "vi",
    "audience_type": "all",
    "target_role": null,
    "selected_user_ids": [],
    "status": "draft",
    "revision": 1,
    "total_recipients": 0,
    "recipient_snapshot_taken": false,
    "snapshot_taken_at": null,
    "sent_count": 0,
    "read_count": 0,
    "failed_count": 0,
    "scheduled_for": null,
    "started_at": null,
    "completed_at": null,
    "cancelled_at": null,
    "cancelled_by": null,
    "cancel_reason": null,
    "created_by": "11111111-1111-1111-1111-111111111111",
    "created_at": "2026-11-01T10:00:00.000000Z",
    "updated_at": "2026-11-01T10:00:00.000000Z"
  }
}
```

---

### 2.2 `admin_update_notification_campaign`

Updates content and audience criteria of a campaign in `draft` or pending `queued` status. Requires optimistic locking revision check.

#### Signature
```sql
public.admin_update_notification_campaign(
    p_request_id uuid,
    p_campaign_id uuid,
    p_expected_revision integer,
    p_title_vi text,
    p_title_en text,
    p_body_vi text,
    p_body_en text,
    p_default_locale text DEFAULT 'vi',
    p_audience_type text DEFAULT 'all',
    p_target_role text DEFAULT NULL,
    p_selected_user_ids uuid[] DEFAULT NULL,
    p_reason text DEFAULT NULL
) RETURNS jsonb
```

---

### 2.3 `admin_preview_notification_campaign`

Calculates expected recipient count and renders delivered titles/messages without altering database state. Uses frozen snapshot count if already scheduled/sent.

#### Signature
```sql
public.admin_preview_notification_campaign(
    p_campaign_id uuid DEFAULT NULL,
    p_audience_type text DEFAULT NULL,
    p_target_role text DEFAULT NULL,
    p_selected_user_ids uuid[] DEFAULT NULL,
    p_default_locale text DEFAULT 'vi',
    p_title_vi text DEFAULT '',
    p_title_en text DEFAULT '',
    p_body_vi text DEFAULT '',
    p_body_en text DEFAULT ''
) RETURNS jsonb
```

---

### 2.4 `admin_schedule_notification_campaign`

Transitions a draft or queued campaign into `queued` with a future `scheduled_for` timestamp. Freezes deterministic recipient snapshot in `notification_campaign_recipients`.

#### Signature
```sql
public.admin_schedule_notification_campaign(
    p_request_id uuid,
    p_campaign_id uuid,
    p_expected_revision integer,
    p_scheduled_for timestamptz,
    p_reason text DEFAULT NULL
) RETURNS jsonb
```

---

### 2.5 `admin_send_now_notification_campaign`

Queues a campaign for immediate delivery (`scheduled_for = clock_timestamp()`).

#### Signature
```sql
public.admin_send_now_notification_campaign(
    p_request_id uuid,
    p_campaign_id uuid,
    p_expected_revision integer,
    p_reason text DEFAULT NULL
) RETURNS jsonb
```

---

### 2.6 `admin_cancel_notification_campaign`

Cancels a campaign. Only future scheduled campaigns or drafts can be cancelled; past or in-flight campaigns reject cancellation.

#### Signature
```sql
public.admin_cancel_notification_campaign(
    p_request_id uuid,
    p_campaign_id uuid,
    p_expected_revision integer,
    p_reason text
) RETURNS jsonb
```

---

### 2.7 `admin_list_notification_campaigns`

Cursor-paginated query of notification campaigns. Whitelist-validates `p_status` and `p_audience_type`.

#### Signature
```sql
public.admin_list_notification_campaigns(
    p_status text DEFAULT NULL,
    p_audience_type text DEFAULT NULL,
    p_limit integer DEFAULT 20,
    p_cursor text DEFAULT NULL
) RETURNS jsonb
```

#### Keyset Cursor Specification
- Format: `ISO8601|uuid` (e.g. `2026-11-01T12:00:00.123456Z|550e8400-e29b-41d4-a716-446655440000`).
- Rejects malformed strings, `infinity`, `-infinity`, and `NaN`.
- Filter values for `p_status`: must be in `('draft', 'queued', 'sending', 'completed', 'cancelled', 'failed')`.
- Filter values for `p_audience_type`: must be in `('all', 'role', 'selected_users')`.

---

### 2.8 `admin_get_notification_campaign_detail`

Retrieves deep detail for a campaign, with bounded events log.

#### Signature
```sql
public.admin_get_notification_campaign_detail(
    p_campaign_id uuid,
    p_events_limit integer DEFAULT 50
) RETURNS jsonb
```

---

### 2.9 `admin_list_notification_campaign_recipients`

Cursor-paginated view of individual recipient deliveries and live read statuses for a campaign.

#### Signature
```sql
public.admin_list_notification_campaign_recipients(
    p_campaign_id uuid,
    p_status text DEFAULT NULL,
    p_limit integer DEFAULT 50,
    p_cursor text DEFAULT NULL
) RETURNS jsonb
```

- Filter values for `p_status`: must be in `('pending', 'sent', 'failed')`.
- Cursor format: `ISO8601|uuid` with microsecond precision.

---

### 2.10 `admin_get_notification_scheduler_health`

Exposes status of pg_cron, background daemon heartbeat, backlog counts, and operational readiness.

#### Signature
```sql
public.admin_get_notification_scheduler_health() RETURNS jsonb
```

#### Response (`200 OK`)
```json
{
  "pg_cron_available": true,
  "pg_cron_job_active": true,
  "scheduler_ready": true,
  "pending_due_campaigns_count": 0,
  "in_flight_campaigns_count": 0,
  "last_worker_run_at": "2026-11-01T12:01:00.000000Z",
  "lifetime_campaigns_processed": 15,
  "lifetime_notifications_sent": 1420,
  "health_status": "healthy"
}
```

*Note: UI must inspect `scheduler_ready: boolean`. If `false`, UI displays manual runner fallback instructions rather than claiming automated scheduling is active.*
