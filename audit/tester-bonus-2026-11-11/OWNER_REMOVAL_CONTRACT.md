# OWNER REMOVAL CONTRACT: TESTER BONUS LIFECYCLE INTEGRATION

**Date**: 2026-11-11  
**Status**: `SOURCE_STABLE`  
**Migration Target**: `supabase/migrations/087_campaign_tester_bonus_rounds.sql`  
**Automated Tests**:
- `supabase/tests/run-campaign-tester-bonus-test.mjs` (17 tests PASS)
- `supabase/tests/run-owner-tester-bonus-removal-test.mjs` (8 tests PASS)

---

## 1. Problem Statement & Root Cause

In the original schema, `OwnerAppDetailViewModel.deleteCampaign` delegates to `repo.deleteCampaign`, which attempted a direct table `DELETE` on `public.campaigns`.

When Migration 087 introduced the tester bonus feature, Section 9.1 added the guard trigger:
```sql
CREATE TRIGGER trg_campaigns_guard_tester_bonus_before_delete
BEFORE DELETE ON public.campaigns
FOR EACH ROW EXECUTE FUNCTION public.trg_campaigns_guard_tester_bonus_before_delete();
```
This guard strictly forbids deleting a campaign while active or closing bonus rounds have unrefunded/unsettled funds (`remaining_credits > 0`).

Because only `admin_delete_campaign` implemented deferred two-phase removal, app owners were unable to request campaign deletion whenever a tester bonus was active or closing with pending reports:
- Direct `DELETE` was rejected by the trigger (`CANNOT_DELETE_CAMPAIGN_WITH_UNSETTLED_BONUS_ROUND`).
- Owners could not trigger deferred removal or settle remaining escrow upon deletion.
- Section 6 of the plan required real delete integration for owners without relaxing the financial deletion guard.

---

## 2. Architecture & Implementation

### 2.1 Private Shared Helper: `_request_campaign_removal`

Extracts the deletion logic into a private, security-definer helper shared between admin and owner workflows:

```sql
CREATE OR REPLACE FUNCTION public._request_campaign_removal(
    p_campaign_id uuid,
    p_reason text,
    p_by_admin boolean
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
```

**Security & Permissions:**
- `REVOKE ALL FROM PUBLIC, anon, authenticated;`
- `GRANT EXECUTE TO service_role;`
- `p_by_admin` is NOT exposed to public callers.

**Lock Hierarchy & Validations:**
1. Validates caller `auth.uid() IS NOT NULL` (`NOT_AUTHENTICATED`).
2. Validates caller `is_locked = false` (`ACCOUNT_LOCKED: Tài khoản của bạn đã bị khóa`).
3. If `p_by_admin = true`: asserts caller `role = 'admin'`.
4. **Level 3 Lock**: Locks the target campaign row `FOR UPDATE` BEFORE touching any round or mission locks.
5. If `p_by_admin = false`: strictly asserts `v_owner_id = v_caller_id` (`PERMISSION_DENIED: Chỉ chủ sở hữu ứng dụng mới có quyền gỡ bỏ ứng dụng`). Even admins cannot call the owner RPC on another user's campaign.
6. Transitions active missions to `deleted` and refunds remaining mission escrow.
7. Transitions active bonus rounds to `status = 'closing'`.
8. Checks for in-flight pending reports (`public.tester_reports WHERE campaign_id = p_campaign_id AND status = 'pending'`).

**Branch A: Pending Reports Exist**
- Updates campaign:
  - `status = 'closed'`
  - `paused_reason = case when p_by_admin then 'admin_deletion_pending' else 'owner_deletion_pending' end`
  - `admin_note = v_effective_reason`
- Returns `status = 'pending_removal'`.
- Pending tester reports remain reviewable by the owner or auto-accepted by the 8h daemon.

**Branch B: No Pending Reports**
- Closes and refunds all active/closing bonus rounds via `_close_and_refund_tester_bonus_round`.
- Checks for integer overflow or unrefunded escrow:
  - If wallet overflowed (`remaining_credits > 0`), campaign transitions to `owner_deletion_pending` for safe retry.
  - If fully settled (`remaining_credits = 0`): physically executes `DELETE FROM public.campaigns WHERE id = p_campaign_id;`.
- Returns `status = 'removed'` (or `'deleted'` for admin).

---

### 2.2 Public Owner RPC: `delete_owned_campaign`

Exposed for authenticated owners to delete or request deferred removal of their campaign:

```sql
CREATE OR REPLACE FUNCTION public.delete_owned_campaign(
    p_campaign_id uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
```

**Security & Permissions:**
- `REVOKE ALL FROM PUBLIC, anon;`
- `GRANT EXECUTE TO authenticated, service_role;`

**Parameters:**
| Parameter | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `p_campaign_id` | `uuid` | Yes | Campaign UUID owned by the caller |

**Return Format (JSONB):**
```json
{
  "success": true,
  "status": "removed" | "pending_removal",
  "campaign_id": "b1000000-0000-0000-0000-000000000013",
  "app_name": "My App",
  "owner_id": "c3000000-0000-0000-0000-000000000001",
  "reason": "Gỡ bỏ bởi Chủ sở hữu",
  "message": "Ứng dụng đã được gỡ bỏ hoàn toàn" | "Chiến dịch đã được đóng để giải quyết các báo cáo tester đang chờ trước khi hoàn tất gỡ bỏ"
}
```

**Error Codes / Exceptions:**
| Error Message | Cause |
| :--- | :--- |
| `NOT_AUTHENTICATED` | Caller is not authenticated |
| `CAMPAIGN_ID_REQUIRED` | `p_campaign_id` is null |
| `ACCOUNT_LOCKED: Tài khoản của bạn đã bị khóa` | Caller account is locked |
| `CAMPAIGN_NOT_FOUND` | Campaign does not exist |
| `PERMISSION_DENIED: Chỉ chủ sở hữu ứng dụng mới có quyền gỡ bỏ ứng dụng` | Caller is not the campaign owner (including admin calling owner RPC) |
| `CANNOT_DELETE_CAMPAIGN_WITH_UNSETTLED_BONUS_ROUND` | Thrown by BEFORE DELETE guard trigger on direct `DELETE` table bypass |

---

### 2.3 Worker Updates: `_process_closing_tester_bonus_rounds`

The periodic batch closing processor now supports both admin and owner removal markers:
1. **Query Predicate**:
   ```sql
   WHERE c.id IN (
       SELECT campaign_id FROM public.campaign_tester_bonus_rounds
       WHERE status IN ('active', 'closing') AND campaign_id IS NOT NULL
       UNION
       SELECT id FROM public.campaigns
       WHERE status = 'closed' AND paused_reason IN ('admin_deletion_pending', 'owner_deletion_pending')
   )
   ```
2. **Round Transition & Settle**:
   Uses reason `'owner_delete_settled'` when `paused_reason = 'owner_deletion_pending'`.
3. **Deferred Deletion Completion**:
   When no pending reports, unsettled bonus rounds, or unsettled missions remain:
   ```sql
   DELETE FROM public.campaigns WHERE id = v_camp.id;
   ```
   Durable deletion completes independently of whether the round was partially refunded or fully consumed (`status = 'closed'`).

---

## 3. Android Client Integration Specification

> [!IMPORTANT]
> The Android codebase is managed by another CLI. **Do not edit the Android app files in this session.**
> The contract below specifies how the parent CLI should wire the Android app.

### 3.1 Repository Method
Replace direct table deletion with RPC call:

```kotlin
suspend fun deleteCampaign(campaignId: String): Result<CampaignRemovalResult> {
    return supabaseClient.postgrest.rpc(
        function = "delete_owned_campaign",
        parameters = mapOf("p_campaign_id" to campaignId)
    ).decodeAs<CampaignRemovalResult>()
}
```

### 3.2 Data Transfer Object
```kotlin
@Serializable
data class CampaignRemovalResult(
    val success: Boolean,
    val status: String, // "removed" vs "pending_removal"
    val campaignId: String,
    val appName: String,
    val ownerId: String,
    val reason: String,
    val message: String
)
```

### 3.3 ViewModel Handling (`OwnerAppDetailViewModel`)
- **`status == "removed"`**:
  - Show success feedback: `message` (e.g., "Ứng dụng đã được gỡ bỏ hoàn toàn").
  - Navigate back / pop detail screen.
- **`status == "pending_removal"`**:
  - **Do NOT claim "Đã xóa" false success.**
  - Show status-aware dialog/banner:
    > "Chiến dịch đã được đóng để giải quyết các báo cáo tester đang chờ trước khi hoàn tất gỡ bỏ."
  - Refresh campaign detail or transition UI to closed/pending-removal state.

---

## 4. Test Verification Summary

| Test Suite | Tests Run | Result | Notes |
| :--- | :--- | :--- | :--- |
| `run-campaign-tester-bonus-test.mjs` | 17 | **PASS** | Original 17 tests verify all existing behaviors, arithmetic, and admin flows |
| `run-owner-tester-bonus-removal-test.mjs` | 8 | **PASS** | New dedicated test suite covering owner auth, denial, clean delete, pending review, worker settlement, full consumption, replay, and overflow |
| `run-carryover-fix-acceptance-test.mjs` | 14 | **PASS** | Regression verification across 062..087 baseline |
| `run-owner-auto-pause-test.mjs` | 6 | **PASS** | Auto-pause lifecycle and pause reason compatibility |

**Verification Status**: `SOURCE_STABLE`
