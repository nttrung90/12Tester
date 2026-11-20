# Android Milestone Progress: Campaign Tester Bonus

Date: 2026-11-11
Feature: Thưởng thêm cho tester (Campaign Tester Bonus Rounds)
Target App: `com.ntt.crosstest`

## Status Overview
- Complete end-to-end Android implementation with 37 targeted unit & visual QA tests passing (0 failures, 0 errors, 0 skipped).
- All parent review items in `ANDROID_REVIEW_PENDING.md` resolved and verified.
- Pre-existing state fully preserved (including migration 086 4-group recommendation ordering; verified with passing `CampaignDetailNextAppPriorityTest`).
- Visual QA rendered 8 high-contrast, localized Vietnamese and dark mode screenshots into `audit/tester-bonus-2026-11-11/previews/`.

## Implementation Checklist
- [x] 1. Data Models & DTOs:
  - `Campaign` model extended with safe bonus metadata: `tester_bonus_round_id`, `tester_bonus_credits`, `tester_bonus_remaining_slots`, `tester_bonus_paid_count`, `tester_bonus_status`.
  - DTOs for `create_campaign_tester_bonus`, `reconcile_campaign_tester_bonus`, `get_campaign_tester_bonus` with strict evidence validation (`isValidEvidenceFor`), Long bounds checking, and `isUnsettled`/`isTerminal`/`hasError` helpers.
  - `TesterReport` model updated for `testerBonusAwarded: Long`, `testerBonusRoundId: String?`, with non-overflowing Long arithmetic for `approvedReward` and `totalEarned`.
- [x] 2. Persistent Idempotent Store:
  - `CampaignTesterBonusStore` storing intent (UUID, payload, user_id, campaign_id, createdAtMs with `encodeDefaults = true`).
  - Quarantines corrupted records with `TesterBonusStoreCorruptedStateException`.
  - Conditional clear requiring `expectedClientRequestId`. Never clears on signout or cache purge.
- [x] 3. Repository layer (`CampaignRepository`):
  - RPC calls `create_campaign_tester_bonus`, `reconcile_campaign_tester_bonus`, `get_campaign_tester_bonus`.
  - Missing RPC detection (`PGRST202` / 404) mapped to `server_update_required`.
  - Narrow regex detection for database column missing error (`tester_bonus_*`), propagating unrelated 42703 column errors without fallback.
  - Safe cache invalidation avoiding projecting historical receipt round metadata into current cached active campaigns.
  - Wallet mutation version fence (`profile` domain) preventing stale balance overwrite when concurrent transactions occur.
  - RPC error propagation from `get_campaign_tester_bonus` (e.g. `CAMPAIGN_NOT_FOUND`).
- [x] 4. Owner Screen & Navigation:
  - `NavGraph.kt` route: `owner_app/{campaignId}/tester_bonus`.
  - `OwnerAppDetailScreen.kt`: Menu item "Thưởng thêm cho tester" below "Đẩy app", above "Tạm dừng / Tiếp tục".
  - Dedicated eligibility checks (`isActualOwner`, not dependent on push pending, view allowed on paused/finalized, create only on open active).
- [x] 5. TesterBonusScreen & TesterBonusViewModel:
  - Inputs: credit per tester, max recipients. Checked Long arithmetic, no overflow.
  - Captured UID and session generation fences on all async paths (load, create, reconcile, retry).
  - Unsettled rounds (`active`, `closing`) block creation; terminal rounds (`closed`, `exhausted`) display progress while enabling new round creation.
  - Reconcile & retry on ambiguous state; only clears intent on verified server evidence or typed PostgreSQL rejection.
  - Safe against campaign deletion: peeks and reconciles pending intent independently of `getCampaign`.
  - High-contrast adaptive orange palette (`0xFFB54700` light, `0xFFFFB74D` dark) meeting WCAG AA/AAA.
  - Agree (Đồng ý) and Cancel (Hủy).
- [x] 6. Home Cards & Tester Campaign Detail:
  - `CampaignCard.kt`: Orange Credit icon + `+N` badge aligned right of title (`weight(1f, fill = false)`), coexisting cleanly with uninstall button, adaptive dark theme.
  - Only show for open campaigns with active round and `remaining_slots > 0`.
  - `CampaignDetailScreen.kt`: Extra bonus card displayed for active round, awarded tester, pending tester, or terminal round.
  - `CampaignDetailViewModel.kt`: Bonus RPC called in both fast path and legacy path with identity fence, preventing previous user `my_awarded` retention.
- [x] 7. Localization:
  - `values/strings.xml`, `values-vi/strings.xml`, `values-en/strings.xml` for all dialogs, errors, badges, and terms.
- [x] 8. Visual QA & Unit Tests:
  - 37 unit tests covering models, store, repository, ViewModel, detail, and visual QA passing with 0 failures.
  - 8 PNG previews rendered directly into `audit/tester-bonus-2026-11-11/previews/`.
