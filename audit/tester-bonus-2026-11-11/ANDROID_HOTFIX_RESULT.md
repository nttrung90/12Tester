# Android Hotfix Result: Tester Bonus Financial Integrity & Responsive Layout

**Status**: `SOURCE_STABLE`  
**Execution Date**: 2026-11-11  
**Target Platform**: Android (app module in F:/AI/12Tester)  
**Gradle Build Status**: `BUILD SUCCESSFUL` (both `testDebugUnitTest` and `assembleDebug` completed offline with code 0)

---

## 1. Summary of Changes

### Fix 1: Strict Receipt Validator & Safe Wallet (`Models.kt`, `CampaignRepository.kt`)
- **Shared Receipt Validator (`validateTesterBonusReceipt`)**:
  - Requires `rewardCredits > 0` and `maxRecipients > 0`.
  - Requires `rewardCredits.toLong() * maxRecipients <= Int.MAX_VALUE`.
  - Verifies exact product conservation: `reservedCredits == rewardCredits.toLong() * maxRecipients`.
  - Validates recipient boundaries: `paidCount in 0..maxRecipients` and `remainingSlots == maxRecipients - paidCount`.
  - Validates credit expenditures: `spentCredits == rewardCredits.toLong() * paidCount`.
  - Non-negative and bounded checks: `remainingCredits`, `spentCredits`, and `refundedCredits` must all be in `0L..Int.MAX_VALUE.toLong()`.
  - Enforces total credit conservation: `reservedCredits == remainingCredits + spentCredits + refundedCredits`.
  - Recognized status enforcement: status must be in `{"active", "closing", "exhausted", "closed"}`.
  - Status invariants:
    - `closed`: `remainingCredits == 0L`.
    - `exhausted`: `paidCount == maxRecipients` and `refundedCredits == 0L`.
    - `active` / `closing`: `refundedCredits == 0L` (unsettled rounds are not yet refunded).
  - Terminal/mutable replay safety: valid terminal replays with partial refunds satisfy conservation without requiring initial zero counters.
  - Wallet balance validation: `ownerBalance` must be present and in `0L..Int.MAX_VALUE.toLong()`.
- **Safe Wallet Calculation (`safeOwnerBalanceInt`)**:
  - Never clamps or fabricates wallet values (no fallback to `0` or `Int.MAX_VALUE`).
  - Returns `ownerBalance.toInt()` only if `ownerBalance in 0L..Int.MAX_VALUE.toLong()`, otherwise returns `null`.
- **Create & Reconcile Evidence Models**:
  - `CreateTesterBonusResult.isValidEvidenceFor`: validates `clientRequestId`, `campaignId`, `rewardCredits`, `maxRecipients`, non-blank `roundId`, and `validateTesterBonusReceipt`.
  - `ReconcileTesterBonusResult.isValidEvidenceFor`: validates `found`, `clientRequestId`, `campaignId`, `expectedRewardCredits`, `expectedMaxRecipients`, non-blank `roundId`, and `validateTesterBonusReceipt`.
- **Repository Wire & Cache Discipline**:
  - `CampaignRepository.reconcileCampaignTesterBonus`: added `expectedRewardCredits: Int, expectedMaxRecipients: Int` Kotlin parameters. RPC wire parameters remain ONLY `p_campaign_id` and `p_client_request_id`.
  - Wallet cache invalidation and profile writes in both `createCampaignTesterBonus` and `reconcileCampaignTesterBonus` are executed strictly after receipt validation succeeds and require `safeOwnerBalanceInt != null`.

---

### Fix 2: Strict Server Rollback Classification & Error Localization (`TesterBonusViewModel.kt`)
- **Strict Server Rollback Detection (`isDefinitiveServerValidationError`)**:
  - Requires actual `io.github.jan.supabase.postgrest.exception.PostgrestRestException`.
  - Requires exact `.code == "P0001"` AND `.statusCode == 400`.
  - Removed generic message heuristics (such as matching on `"Postgrest"` or `RestException`).
  - Strict preservation: `CLIENT_REQUEST_ID_CONFLICT`, authentication errors, account locked errors, and HTTP 500/503 responses NEVER clear the pending intent; original UUID and payload are preserved for reconciliation.
  - Genuine known fresh rollback validation codes can release the pending intent: `INSUFFICIENT_CREDITS`, `CAMPAIGN_NOT_OPEN`, `CAMPAIGN_NOT_ELIGIBLE`, `NOT_CAMPAIGN_OWNER`, `PERMISSION_DENIED`, `INVALID_INPUT`, `INVALID_ARGUMENT`, `OVERFLOW_DETECTED`, `INPUT_OVERFLOW`, `ACTIVE_ROUND_ALREADY_EXISTS`.
- **Error Mapping (`mapTesterBonusError`)**:
  - Unknown fallback branch maps to localized generic error string (`tester_bonus_err_unknown`) without leaking raw server strings or `e.localizedMessage`.
  - Storage/corruption exceptions (`TesterBonusStoreCorruptedStateException`) and corruption indicators are explicitly localized to `tester_bonus_err_store_corrupted`.
- **Reconciliation Calls**:
  - Updated all three ViewModel intent reconciliation call sites to supply `expectedRewardCredits` and `expectedMaxRecipients` from the persistent intent.

---

### Fix 3: Responsive Layout Coexistence (`CampaignCard.kt`)
- **Badge Integrity (`TesterBonusBadge`)**:
  - Added `maxLines = 1` and `softWrap = false` to guarantee credit amounts (such as `+2147483640`) never wrap onto multiple lines or split digits across badges.
- **Component Modularization**:
  - Extracted `UninstallButton` helper composable preserving shapes, container/border styling, icon, localized text, click handler, and accessibility semantics.
- **Adaptive Responsive Flow**:
  - For large credit labels (> 6 digits) OR constrained width with large font scale:
    - App title receives full available title row width (`Modifier.fillMaxWidth()`) with readable text ellipsis and positive measured bounds.
    - Tester bonus badge and uninstall button move into a full-card-width `FlowRow` below the main card row (wrapping onto two lines if horizontal space is constrained).
    - Top row right uninstall button and inline title badge are hidden in this branch.
  - Normal reward badges (e.g. `+5`) remain inline to the right of the app name in the top row.
  - Renderer stress fixture height in `TesterBonusVisualQATest.kt` updated to 280dp to accommodate the expanded card height.

---

## 2. Test Verification

### Targeted Unit Tests
Ran:
```powershell
.\gradlew.bat testDebugUnitTest --tests "com.ntt.crosstest.*TesterBonus*" --offline
```
**Result**: `BUILD SUCCESSFUL` (100% pass, 0 failures, 0 ignored).

### Full Suite & Compilation
Ran:
```powershell
.\gradlew.bat testDebugUnitTest assembleDebug --offline
```
**Result**: `BUILD SUCCESSFUL in 1m 14s` (49 actionable tasks, debug APK assembled).

### Key Test Cases Verified
1. `budget, wallet, and counter inconsistencies reject evidence and safeOwnerBalanceInt never clamps`
   - Negative owner balance returns `false` and `safeOwnerBalanceInt == null`.
   - Overflow owner balance (> Int.MAX_VALUE) returns `false` and `safeOwnerBalanceInt == null`.
   - Null owner balance returns `false` and `safeOwnerBalanceInt == null`.
   - Arithmetic product mismatch (`reserved != reward * max`) returns `false`.
   - Remaining slots mismatch (`remainingSlots != max - paid`) returns `false`.
   - Spent credits mismatch (`spent != reward * paid`) returns `false`.
   - Conservation law violation (`reserved != remaining + spent + refunded`) returns `false`.
   - Unrecognized or inconsistent statuses reject evidence.
2. `coherent wrong reconcile payload retains intent and does not write wallet`
   - Reconcile response with mismatched `reward_credits` / `max_recipients` rejects evidence; session wallet cache is untouched.
3. `valid closed partial refund replay passes strict validation`
   - Replayed closed round with partial refund satisfies conservation invariants and passes strict evidence check.
4. `typed HTTP 503 with INSUFFICIENT_CREDITS retains UUID and does not clear intent`
   - Transient 503 errors preserve the local intent UUID in `CampaignTesterBonusStore`.
5. `non-typed or non-P0001 JSON validation error retains intent`
   - Non-P0001 status or generic JSON error retains intent for reconciliation.
6. `CLIENT_REQUEST_ID_CONFLICT retains original UUID and intent`
   - Conflict error preserves original UUID and prevents clearing intent.
7. `genuine P0001 400 fresh rejection known handled and releases intent`
   - Confirmed 400 P0001 rollback safely releases intent and displays localized error.
8. `CampaignCard responsive branch guarantees nonzero title bounds and visible bonus badge and uninstall`
   - Verified that long app titles at 320dp width with 1.3 font scale maintain positive width bounds, legible ellipsis, and render with non-zero dimensions.
9. `render CampaignCard coexistence 320dp narrow long app name uninstall and large valid reward label`
   - Visual preview rendered to `audit/tester-bonus-2026-11-11/previews/campaign_card_narrow_longname_uninstall_large_reward.png` (47,180 bytes).

---

## 3. Verified Files

- [`app/src/main/java/com/ntt/crosstest/data/Models.kt`](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/data/Models.kt)
- [`app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt`](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt)
- [`app/src/main/java/com/ntt/crosstest/ui/viewmodels/TesterBonusViewModel.kt`](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/viewmodels/TesterBonusViewModel.kt)
- [`app/src/main/java/com/ntt/crosstest/ui/components/CampaignCard.kt`](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/components/CampaignCard.kt)
- [`app/src/test/java/com/ntt/crosstest/data/TesterBonusRepositoryTest.kt`](file:///F:/AI/12Tester/app/src/test/java/com/ntt/crosstest/data/TesterBonusRepositoryTest.kt)
- [`app/src/test/java/com/ntt/crosstest/ui/viewmodels/TesterBonusViewModelTest.kt`](file:///F:/AI/12Tester/app/src/test/java/com/ntt/crosstest/ui/viewmodels/TesterBonusViewModelTest.kt)
- [`app/src/test/java/com/ntt/crosstest/ui/TesterBonusVisualQATest.kt`](file:///F:/AI/12Tester/app/src/test/java/com/ntt/crosstest/ui/TesterBonusVisualQATest.kt)
