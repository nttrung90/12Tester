# API CONTRACT: Campaign Tester Bonus Rounds (Đợt Thưởng Thêm Cho Tester)

> **Version:** 1.0 (Backend Milestone 1 - Migration 087)  
> **Target Client:** Android App (`com.ntt.crosstest`)  
> **Status:** Stable Backend Contract  

---

## 1. Overview & Authorized Business Rules

This contract defines the communication interfaces for the **Campaign Tester Bonus Rounds** feature (Plan `KE_HOACH_THUONG_THEM_CHO_TESTER_2026-11-11.md`).

### Authorized Business Defaults
1. **Settlement Mechanism:** "First eligible approvals/award transactions" (NOT first submissions). Testers who submit reports while a round is active receive an immutable round snapshot on their report. Awards are granted strictly in order of report approval (manual approval by owner or 8h auto-acceptance) until round capacity is exhausted.
2. **Escrow & Budget:** The campaign owner prepays the full budget (`reward_credits * max_recipients`) into escrow from their credit balance at round creation. There is zero additional platform service fee.
3. **Tester Quota:** Strictly at most 1 bonus award per distinct tester per round.
4. **Duration & Reset:** No midnight reset. The round persists until remaining slots reach 0 (`exhausted`), or the owner closes it, or the campaign lifecycle ends (`completed`, `production`, `closed`).
5. **Base Rewards Preserved:** Exhausted rounds still permit ordinary report submissions and approvals with standard base rewards (+2 manual review bonus). Legacy tips and missions remain completely separate.

---

## 2. Remote Procedure Calls (RPCs)

All RPCs are defined in schema `public`, execute with `SECURITY DEFINER` and a strict schema-qualified `search_path = public, auth, extensions`, and are accessible only to authenticated clients.

### 2.1. `create_campaign_tester_bonus`
Creates a new bonus round and reserves the full budget atomically from the owner's profile credits.

- **PostgREST Signature:** `POST /rest/v1/rpc/create_campaign_tester_bonus`
- **Caller Role:** Authenticated Campaign Owner only.

#### Request Parameters (JSON body)
| Parameter | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `p_campaign_id` | UUID | Yes | Target campaign ID. Must be owned by caller and status = `'open'`. |
| `p_reward_credits` | Integer | Yes | Extra bonus credits per awarded tester (must be > 0). |
| `p_max_recipients` | Integer | Yes | Number of bonus slots (must be > 0). |
| `p_client_request_id` | UUID | Yes | Client-generated idempotent request ID. Must be durable. |

#### Success Response (`200 OK`)
```json
{
  "success": true,
  "round_id": "c1111111-2222-3333-4444-555555555555",
  "client_request_id": "99999999-8888-7777-6666-555555555555",
  "campaign_id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "reward_credits": 10,
  "max_recipients": 10,
  "paid_count": 0,
  "remaining_slots": 10,
  "reserved_credits": 100,
  "remaining_credits": 100,
  "spent_credits": 0,
  "refunded_credits": 0,
  "owner_balance": 900,
  "status": "active",
  "replayed": false
}
```

*Note on Idempotent Replay:* If replayed with the identical `p_client_request_id` and parameters, returns the same JSON with `"replayed": true` without deducting additional credits. Replay is recognized even if the campaign was subsequently deleted (returns original campaign UUID) or if the caller's account was subsequently locked (returns cached committed receipt).

#### Error Responses (`400 Bad Request` / Postgres exception)
| Error Code / Exception Prefix | Cause | Android Client Handling |
| :--- | :--- | :--- |
| `CAMPAIGN_ID_REQUIRED` | `p_campaign_id` is null | Validate campaign selection |
| `CLIENT_REQUEST_ID_REQUIRED` | `p_client_request_id` is null | Ensure UUID is passed |
| `INVALID_INPUT: ...` | `reward_credits <= 0` or `max_recipients <= 0` | Validate form before submit |
| `INPUT_OVERFLOW: ...` | `reward_credits * max_recipients > 2147483647` | Display: "Ngân sách vượt quá giới hạn hệ thống" |
| `CLIENT_REQUEST_ID_CONFLICT: ...` | Same `p_client_request_id` was previously used with different parameters | Reconcile prior request or preserve intent; do NOT silently generate a new UUID while prior financial intent is unresolved |
| `CAMPAIGN_NOT_FOUND` | Campaign does not exist | Show error message |
| `PERMISSION_DENIED: ...` | Caller is not the actual owner of the campaign (even if admin) | Only actual owner can spend their own wallet |
| `CAMPAIGN_NOT_ELIGIBLE: ...` | Campaign is not in `'open'` status | Inform owner app must be open |
| `ACTIVE_ROUND_ALREADY_EXISTS: ...` | Campaign already has an active or closing round | Prompt owner to view or close existing round |
| `INSUFFICIENT_CREDITS: ...` | Owner credits < total budget required | Prompt owner to top-up credits |

---

### 2.2. `reconcile_campaign_tester_bonus`
Read-only lookup of a previously dispatched create request. Never deducts credits or creates a new round.

- **PostgREST Signature:** `POST /rest/v1/rpc/reconcile_campaign_tester_bonus`
- **Caller Role:** Authenticated Campaign Owner only.
- **SQL Signature:** `public.reconcile_campaign_tester_bonus(p_campaign_id uuid, p_client_request_id uuid)`

#### Request Parameters (JSON body)
| Parameter | Type | Required | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| `p_campaign_id` | UUID | Yes | None | Campaign ID to verify ownership and match round. |
| `p_client_request_id` | UUID | Yes | None | Client request ID to look up. |

#### Success Response: Found (`200 OK`)
```json
{
  "found": true,
  "replayed": true,
  "round_id": "c1111111-2222-3333-4444-555555555555",
  "client_request_id": "99999999-8888-7777-6666-555555555555",
  "campaign_id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "reward_credits": 10,
  "max_recipients": 10,
  "paid_count": 0,
  "remaining_slots": 10,
  "reserved_credits": 100,
  "remaining_credits": 100,
  "spent_credits": 0,
  "refunded_credits": 0,
  "owner_balance": 900,
  "status": "active"
}
```

#### Success Response: Not Found (`200 OK`)
*(Note: Does NOT throw an exception when not found; returns `found: false`)*
```json
{
  "found": false
}
```

*Note on Post-Deletion Durability:* If the campaign was subsequently deleted (e.g. via deferred admin deletion), `reconcile_campaign_tester_bonus` remains fully functional and returns the original validated `campaign_id` (via immutable `original_campaign_id`). A request with a mismatched `campaign_id` or by a non-owner will return `{"found": false}`. Locked accounts can also reconcile committed requests.

---

### 2.3. `get_campaign_tester_bonus`
Dual-projection read RPC: provides detailed financial audit for the campaign owner, or clean public summary & personal award status for testers/public.

- **PostgREST Signature:** `POST /rest/v1/rpc/get_campaign_tester_bonus`
- **Caller Role:** Any Authenticated User.

#### Request Parameters
| Parameter | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `p_campaign_id` | UUID | Yes | Campaign ID. |

#### Case A: No Round Exists
```json
{
  "has_round": false,
  "campaign_id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "is_owner": false
}
```

#### Case B: Owner View (`is_owner = true`)
```json
{
  "has_round": true,
  "is_owner": true,
  "round_id": "c1111111-2222-3333-4444-555555555555",
  "campaign_id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "reward_credits": 10,
  "max_recipients": 10,
  "paid_count": 2,
  "remaining_slots": 8,
  "reserved_credits": 100,
  "remaining_credits": 80,
  "spent_credits": 20,
  "refunded_credits": 0,
  "status": "active",
  "started_at": "2026-11-11T07:30:00Z",
  "stopped_at": null,
  "closed_at": null
}
```

#### Case C: Tester / Public View (`is_owner = false`)
*Privacy Guarantee:* Owner's wallet balance, `reserved_credits`, `spent_credits`, `remaining_credits`, `refunded_credits`, and `client_request_id` are strictly withheld from non-owners.
```json
{
  "has_round": true,
  "is_owner": false,
  "round_id": "c1111111-2222-3333-4444-555555555555",
  "campaign_id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "reward_credits": 10,
  "max_recipients": 10,
  "paid_count": 2,
  "remaining_slots": 8,
  "status": "active",
  "my_awarded": false,
  "my_award_amount": 0,
  "my_awarded_at": null,
  "my_pending_eligible": true
}
```
*If tester has already been awarded:*
```json
{
  "has_round": true,
  "is_owner": false,
  "round_id": "c1111111-2222-3333-4444-555555555555",
  "campaign_id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "reward_credits": 10,
  "max_recipients": 10,
  "paid_count": 3,
  "remaining_slots": 7,
  "status": "active",
  "my_awarded": true,
  "my_award_amount": 10,
  "my_awarded_at": "2026-11-11T08:15:22Z",
  "my_pending_eligible": false
}
```

---

### 2.4. `close_campaign_tester_bonus`
Allows the campaign owner to early-close an active round. Transitions status to `'closing'`, stops new applicant eligibility, preserves pending obligations, and refunds unused escrow back to the owner upon full settlement.

- **PostgREST Signature:** `POST /rest/v1/rpc/close_campaign_tester_bonus`
- **Caller Role:** Authenticated Campaign Owner only.

#### Request Parameters
| Parameter | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `p_campaign_id` | UUID | Yes | Target campaign ID. |

#### Success Response (`200 OK`)
```json
{
  "success": true,
  "round_id": "c1111111-2222-3333-4444-555555555555",
  "campaign_id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "status": "closed",
  "paid_count": 2,
  "refund_credits": 80,
  "remaining_credits": 0,
  "owner_balance": 980
}
```
*Note:* If pending reports submitted prior to closing still exist, `status` becomes `"closing"`, and refund occurs when `_process_closing_tester_bonus_rounds()` runs after all pending reports are settled.

---

## 3. Feed, Detail & Report Projections Extension

### 3.1. Campaign Summary in `_campaign_json`
The protected summary is embedded into the standard campaign JSON for feed and detail screens:
```json
{
  "id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "app_name": "Sample App",
  "status": "open",
  "tester_bonus_round_id": "c1111111-2222-3333-4444-555555555555",
  "tester_bonus_credits": 10,
  "tester_bonus_remaining_slots": 8,
  "tester_bonus_paid_count": 2,
  "tester_bonus_status": "active"
}
```

### 3.2. Egress & Sync V2 Invalidation
`tester_bonus_remaining_slots` and `tester_bonus_status` participate in the campaign block SHA-256 hash calculation in `_sync_block`. Any slot decrement or status transition automatically invalidates client cache tokens for `get_home_feed_sync_v2` (block `open`), guaranteeing instant feed updates without extra polling.

### 3.3. Report Projections in `tester_reports`, `get_campaign_detail`, `get_campaign_reports_for_owner`, & `admin_get_tester_reports_queue`
Each report returned in `get_campaign_detail` (field `reports`), `get_campaign_reports_for_owner`, and `admin_get_tester_reports_queue` (field `items`) includes:
- `tester_bonus_round_id`: UUID snapshot of eligible round at submission time (immutable; `null` if submitted without an active bonus round).
- `tester_bonus_awarded`: Exact extra bonus credits awarded from this round (0 if not awarded).
- `bonus_awarded`: Baseline report bonus (provisional base + manual review bonus + tip). `tester_bonus_awarded` is kept separate; total earned includes extra bonus exactly once.

### 3.4. Bigint Arithmetic for Cumulative Approval Total
In report approval responses (`approve_tester_report`), `total_earned` reflects cumulative earnings (`provisional + extra_reward + tester_bonus_awarded`). While individual wallet balance is bounded by 32-bit signed integer (`<= 2,147,483,647`), cumulative earnings on valid large bonus awards can exceed 32 bits. Therefore:
- The server returns `total_earned` as a 64-bit integer (`bigint`).
- Android Kotlin DTOs must use `Long` for `totalEarned` (e.g., `val totalEarned: Long = 0L`).
- Individual wallet balances (`tester_balance`, `owner_balance`) remain standard `Int` (32-bit signed integer).

---

## 4. Kotlin Data Transfer Objects (DTOs)

Place the following Kotlin data classes in `app/src/main/java/com/ntt/crosstest/data/Models.kt`:

```kotlin
package com.ntt.crosstest.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CampaignTesterBonusSummary(
    @SerialName("tester_bonus_round_id") val roundId: String? = null,
    @SerialName("tester_bonus_credits") val bonusCredits: Int = 0,
    @SerialName("tester_bonus_remaining_slots") val remainingSlots: Int = 0,
    @SerialName("tester_bonus_paid_count") val paidCount: Int = 0,
    @SerialName("tester_bonus_status") val bonusStatus: String? = null
) {
    val isActive: Boolean
        get() = bonusStatus == "active" && remainingSlots > 0 && bonusCredits > 0
}

@Serializable
data class CampaignTesterBonusResponse(
    @SerialName("has_round") val hasRound: Boolean = false,
    @SerialName("is_owner") val isOwner: Boolean = false,
    @SerialName("round_id") val roundId: String? = null,
    @SerialName("campaign_id") val campaignId: String? = null,
    @SerialName("reward_credits") val rewardCredits: Int = 0,
    @SerialName("max_recipients") val maxRecipients: Int = 0,
    @SerialName("paid_count") val paidCount: Int = 0,
    @SerialName("remaining_slots") val remainingSlots: Int = 0,
    @SerialName("status") val status: String? = null,
    // Owner-only fields (null when isOwner = false)
    @SerialName("reserved_credits") val reservedCredits: Int? = null,
    @SerialName("remaining_credits") val remainingCredits: Int? = null,
    @SerialName("spent_credits") val spentCredits: Int? = null,
    @SerialName("refunded_credits") val refundedCredits: Int? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("stopped_at") val stoppedAt: String? = null,
    @SerialName("closed_at") val closedAt: String? = null,
    // Tester-only fields (null when isOwner = true)
    @SerialName("my_awarded") val myAwarded: Boolean = false,
    @SerialName("my_award_amount") val myAwardAmount: Int = 0,
    @SerialName("my_awarded_at") val myAwardedAt: String? = null,
    @SerialName("my_pending_eligible") val myPendingEligible: Boolean = false
)

@Serializable
data class CreateBonusRoundRequest(
    @SerialName("p_campaign_id") val campaignId: String,
    @SerialName("p_reward_credits") val rewardCredits: Int,
    @SerialName("p_max_recipients") val maxRecipients: Int,
    @SerialName("p_client_request_id") val clientRequestId: String
)

@Serializable
data class CreateBonusRoundResponse(
    @SerialName("success") val success: Boolean = false,
    @SerialName("replayed") val replayed: Boolean = false,
    @SerialName("round_id") val roundId: String? = null,
    @SerialName("client_request_id") val clientRequestId: String? = null,
    @SerialName("campaign_id") val campaignId: String? = null,
    @SerialName("reward_credits") val rewardCredits: Int = 0,
    @SerialName("max_recipients") val maxRecipients: Int = 0,
    @SerialName("paid_count") val paidCount: Int = 0,
    @SerialName("remaining_slots") val remainingSlots: Int = 0,
    @SerialName("reserved_credits") val reservedCredits: Int = 0,
    @SerialName("remaining_credits") val remainingCredits: Int = 0,
    @SerialName("spent_credits") val spentCredits: Int = 0,
    @SerialName("refunded_credits") val refundedCredits: Int = 0,
    @SerialName("owner_balance") val ownerBalance: Int = 0,
    @SerialName("status") val status: String? = null
)

@Serializable
data class ReconcileBonusRoundResponse(
    @SerialName("found") val found: Boolean = false,
    @SerialName("replayed") val replayed: Boolean = false,
    @SerialName("client_request_id") val clientRequestId: String? = null,
    @SerialName("round_id") val roundId: String? = null,
    @SerialName("campaign_id") val campaignId: String? = null,
    @SerialName("reward_credits") val rewardCredits: Int = 0,
    @SerialName("max_recipients") val maxRecipients: Int = 0,
    @SerialName("paid_count") val paidCount: Int = 0,
    @SerialName("remaining_slots") val remainingSlots: Int = 0,
    @SerialName("reserved_credits") val reservedCredits: Int = 0,
    @SerialName("remaining_credits") val remainingCredits: Int = 0,
    @SerialName("spent_credits") val spentCredits: Int = 0,
    @SerialName("refunded_credits") val refundedCredits: Int = 0,
    @SerialName("owner_balance") val ownerBalance: Int = 0,
    @SerialName("status") val status: String? = null
)
```

---

## 5. Client Request ID Durability & Retry Best Practices

1. **Generation:** When opening the "Tạo đợt thưởng tester" dialog, generate a fresh `UUID.randomUUID().toString()`.
2. **Durability:** Store this UUID in the ViewModel state or SavedStateHandle so it survives activity recreation and configuration changes.
3. **Timeout / Disconnect Handling:**
   - If a network timeout or connection drop occurs during `create_campaign_tester_bonus`:
   - **Do NOT immediately submit a new request with a newly generated UUID.**
   - First call `reconcile_campaign_tester_bonus(p_client_request_id, p_campaign_id)`.
   - If reconcile returns `found: true`, the round was already safely created; update UI with the committed receipt.
   - If reconcile returns `found: false`, the network drop occurred before the server received the transaction; the client may safely retry `create_campaign_tester_bonus` with the original UUID.
   - If server returns `CLIENT_REQUEST_ID_CONFLICT`, prompt the user to review the conflicting request rather than silently creating an unintended second round.

---

## 6. Old Server / Schema Fallback Strategy

When running against an older Supabase backend where migration 087 has not been deployed:
1. **Feed & Details:** Fields `tester_bonus_*` will be missing or null. Kotlin default values ensure zero parsing errors.
2. **Badge Visibility:** The UI should only display the bonus badge when:
   `summary.isActive && summary.bonusCredits > 0`
   If `tester_bonus_round_id == null`, no bonus UI elements are rendered.
3. **RPC Calls:** If the user attempts to create a bonus round and the server returns Postgres error `42883` (`function create_campaign_tester_bonus does not exist`), Android catches the exception and displays: `"Máy chủ chưa hỗ trợ tính năng thưởng thêm. Vui lòng thử lại sau."`


## Owner campaign removal integration

`delete_owned_campaign(p_campaign_id uuid)` handles actual-owner removal with pending report settlement and refunds. Owner completion uses `removed`; the existing admin completion uses `deleted`; both can return `pending_removal`. See [the verified removal contract](F:/AI/12Tester/audit/tester-bonus-2026-11-11/OWNER_REMOVAL_CONTRACT.md).
