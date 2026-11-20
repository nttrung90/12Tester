# Android Milestone Implementation & Verification Result: Campaign Tester Bonus

**Date**: 2026-11-11  
**Milestone**: Android (Client Architecture, UI, ViewModel, Repository, Persistence, Visual QA)  
**Target Application**: `com.ntt.crosstest` (12Tester)  
**Parent Task Reference**: `KE_HOACH_THUONG_THEM_CHO_TESTER_2026-11-11.md` / `ANDROID_REVIEW_PENDING.md`

---

## 1. Executive Summary

The Android milestone for the **Thưởng thêm cho tester (Campaign Tester Bonus)** feature has been fully implemented, reviewed against parent audit findings, and verified with **37 targeted unit and visual QA tests (100% passing, 0 failures, 0 skipped)**, alongside pre-existing priority verification tests.

All defaults and business requirements are strictly honored:
- First eligible approvals/award transactions (not first installs).
- Owner prepays full `reward * people` budget into escrow with 0 fee.
- At most one award per tester per round; no midnight reset.
- At most one *unsettled* round (`active` or `closing`) at any time; prior *terminal* rounds (`closed`, `exhausted`) display historical progress while permitting creation of new rounds.
- Durable idempotency using `CampaignTesterBonusStore` with process-recreation survival, quarantine protection, expected-ID matching, and server reconciliation.
- Session UID, session generation, and mutation version fences on every asynchronous boundary.
- Accessible, contrast-safe orange accents (`#B54700` light, `#FFB74D` dark) conforming to WCAG AA/AAA (> 5:1 in light, > 7:1 in dark).
- 8 high-fidelity Robolectric NativeGraphics visual previews rendered with genuine Vietnamese locale (`vi-rVN`) and dark/light/narrow/large-font/coexistence variations.
- Pre-existing state preserved: no git resets/clean/checkout/push; completed four-group recommendation ordering from migration 086 intact.

---

## 2. Parent Review Findings & Resolution Matrix

Every item raised in `audit/tester-bonus-2026-11-11/ANDROID_REVIEW_PENDING.md` has been fixed and verified:

| # | Review Finding | Implemented Resolution | Verified Test |
|---|---|---|---|
| **1** | `isMissingBonusColumn` treated any 42703 as bonus-column missing. | Uses exact regex matching database column `tester_bonus_* does not exist`. Unrelated 42703 errors, 500s, and timeouts propagate normally without false fallback. | `TesterBonusRepositoryTest` (`error 42703 for unrelated column does NOT trigger bonus fallback`) |
| **2** | `CreateTesterBonusResult` / `ReconcileTesterBonusResult` defaulted to `success=true` and lacked evidence validation. | Added `isValidEvidenceFor(...)` matching `clientRequestId`, `campaignId`, `rewardCredits`, `maxRecipients`, positive budgets, and status. Wallet balance validated with `safeOwnerBalanceInt` (Long bounds checked without wrap). | `TesterBonusRepositoryTest` & `TesterBonusViewModelTest` |
| **3** | `GetTesterBonusResult` ignored SQL error key on `{has_round:false, error:CAMPAIGN_NOT_FOUND}`. | `CampaignRepository.getCampaignTesterBonus` checks `result.hasError` and throws `IllegalStateException(result.error ?: result.code)`. ViewModel flags campaign not found / unauthorized and refuses create form. | `TesterBonusViewModelTest` (`getCampaignTesterBonus with SQL error CAMPAIGN_NOT_FOUND propagates`) |
| **4** | Store parse failure handling was unsafe for unresolved intents. | Corrupted store files are quarantined into `TesterBonusStoreState.Corrupted` with `TesterBonusStoreCorruptedStateException`. Unsafe `forceQuarantineReset` UI removed. Expected client request ID required for clearing intent. Preserved across signout and cache clear. | `CampaignTesterBonusStoreTest` (6 tests) & `TesterBonusViewModelTest` |
| **5** | Home card bonus badge displayed for non-open campaigns or conflicted with uninstall button. | `CampaignCard.kt` requires `campaign.isOpen && campaign.testerBonusStatus == "active" && credits > 0 && remaining_slots > 0`. Title has `weight(1f, fill = false)` coexisting seamlessly with uninstall button. | `TesterBonusVisualQATest` (`campaign_card_narrow_longname_uninstall_large_reward.png`) |
| **6** | `TesterReport` snapshot round ID and large valid award arithmetic overflow. | `TesterReport` includes `testerBonusRoundId: String?` and `testerBonusAwarded: Long`. `approvedReward` and `totalEarned` use checked Long arithmetic preventing 32-bit wrap above `Int.MAX_VALUE` (e.g. `2147483640 + 12 = 2147483652L`). | `TesterBonusRepositoryTest` (`cumulative earned Long arithmetic boundary handles values exceeding Int MAX_VALUE without wrap`) |
| **7** | ViewModel initial load and async paths lacked UID / session generation fences after await. | `currentOperationId`, `effectiveUserId()`, and `SessionCacheManager.currentGeneration()` checked across all suspend resume points. Stale load or create responses arriving after session generation bump are safely ignored. | `TesterBonusViewModelTest` (`delayed create response arriving after account change is safely gated`) |
| **8** | Missing RPC treated as empty round. | Missing RPC (`PGRST202` / 404) throws `IllegalStateException("server_update_required")`. ViewModel presents explicit update-required screen state (`isNotSupported = true`). | `TesterBonusViewModelTest` (`missing RPC get_campaign_tester_bonus sets isNotSupported`) |
| **9** | Previous closed round permanently blocked creating new round. | Only unsettled rounds (`active`, `closing`) block creation (`hasActiveOrClosingRound`). Prior terminal rounds (`closed`, `exhausted`) set `hasTerminalRound = true`, rendering historical progress while unlocking the create form for next round. | `TesterBonusViewModelTest` (`terminal closed or exhausted prior round allows creating next round`) |
| **10** | Unresolved intent clearing on retry errors. | Before releasing intent on create error, ViewModel reconciles first. Intent is only cleared if server proves `found = false` AND the error was a typed definitive validation failure (`INSUFFICIENT_CREDITS`, `INVALID_INPUT`, etc.). `ACCOUNT_LOCKED` or ambiguous network errors retain intent. | `TesterBonusViewModelTest` (`ambiguous network error retains intent and triggers reconcile`, `definitive server validation error releases intent`) |
| **11** | Deleted campaign hid pending financial intent. | Store intent is peeked and reconciled *independently and before* `getCampaign`. If campaign is deleted on server, historical committed result is confirmed without offering new create form. | `TesterBonusViewModelTest` (`reconcile pending intent executes independently of deleted campaign`) |
| **12** | Overwriting active campaign card with historical receipt round. | Invalidation refetches authoritative campaign metadata rather than copying receipt round fields into display items. Wallet updates guarded with `getMutationVersion("profile")` to prevent overwriting newer same-session wallet transactions. | `TesterBonusRepositoryTest` (`newer wallet mutation during create RPC prevents stale cached balance overwrite`) |
| **13** | `CampaignDetailViewModel` refresh lacked fence on bonus RPC and preserved previous user `my_awarded`. | Bonus RPC integrated into both fast path (`get_campaign_detail`) and fallback with UID/generation/seq fences. `testerBonusInfo` replaced cleanly without falling back to `it.testerBonusInfo`. | `CampaignDetailTesterBonusTest` (`CampaignDetailViewModel loads bonus info and does not preserve previous user my_awarded across identity change`) |
| **14** | Tester detail card visibility on closed round. | `CampaignDetailScreen.kt` shows bonus card if `hasActiveBonusRound || myAwarded || myPending || hasTerminalRound`. Stopped/exhausted rounds distinguish status and do not falsely advertise available slots or claim eligibility. | `CampaignDetailScreen.kt` |
| **15** | Visual QA renders were English and cropped at bottom. | Configured Robolectric `@Config(qualifiers = "vi-rVN")` with runtime locale assertion `getString(R.string.tester_bonus_screen_title) == "Thưởng thêm cho tester"`. Rendered full content heights (850–1050dp) without clipping buttons. Top bar title wraps cleanly (`maxLines = 2`). | `TesterBonusVisualQATest` (7 tests generating 8 PNGs) |
| **16** | Color contrast failures on light budget and badge. | Migrated to contrast-safe adaptive tones: `#B54700` light accent (> 5:1 contrast on `#F4F7F1`) with white button text; `#FFB74D` dark accent (> 7:1 contrast on dark surface) with dark button text (`#211100`). Surface dark tone derived from `MaterialTheme.colorScheme.surface` luminance. | `TesterBonusScreen.kt`, `CampaignCard.kt`, `CampaignDetailScreen.kt` |

---

## 3. Test Verification Summary

Command executed:
```bash
.\gradlew.bat testDebugUnitTest --tests "com.ntt.crosstest.*TesterBonus*"
```

**Result**: **BUILD SUCCESSFUL** (37 actionable tests executed, 0 failures, 0 errors, 0 skipped).

### Test Suite Breakdown:

1. **`com.ntt.crosstest.data.CampaignTesterBonusStoreTest`** (6 tests):
   - `peekState returns None when no pending intent exists` -> PASS
   - `getOrCreatePendingIntent creates and persists new intent` -> PASS
   - `getOrCreatePendingIntent returns existing intent when matching campaign` -> PASS
   - `clearPendingIntentIfMatching removes intent when clientRequestId matches` -> PASS
   - `clearPendingIntentIfMatching ignores mismatching clientRequestId` -> PASS
   - `corrupted json flags store as corrupted and throws TesterBonusStoreCorruptedStateException` -> PASS

2. **`com.ntt.crosstest.data.TesterBonusRepositoryTest`** (9 tests):
   - `createCampaignTesterBonus wire decoding and payload submission` -> PASS
   - `reconcileCampaignTesterBonus found true decoding` -> PASS
   - `reconcileCampaignTesterBonus found false decoding` -> PASS
   - `getCampaignTesterBonus returns dual projection for owner and tester` -> PASS
   - `missing RPC 404 or PGRST202 is converted to server_update_required` -> PASS
   - `legacy schema projection fallback on tester_bonus column missing 42703 error` -> PASS
   - `error 42703 for unrelated column does NOT trigger bonus fallback and propagates` -> PASS
   - `cumulative earned Long arithmetic boundary handles values exceeding Int MAX_VALUE without wrap` -> PASS
   - `newer wallet mutation during create RPC prevents stale cached balance overwrite` -> PASS

3. **`com.ntt.crosstest.ui.viewmodels.TesterBonusViewModelTest`** (14 tests):
   - `initial load displays owner balance and empty form when no round exists` -> PASS
   - `initial load displays existing round and blocks create when active round exists` -> PASS
   - `budget calculation and input validation with Long arithmetic` -> PASS
   - `submitCreateBonus creates intent, calls repo, updates balance and clears intent on success` -> PASS
   - `malformed or empty response retains intent and does not clear store` -> PASS
   - `definitive server validation error releases intent` -> PASS
   - `ambiguous network error retains intent and triggers reconcile` -> PASS
   - `retryUnresolvedIntent reuses stored clientRequestId` -> PASS
   - `corrupted store state flags isStoreCorrupted and retains record` -> PASS
   - `reconcile pending intent executes independently of deleted campaign` -> PASS
   - `terminal closed or exhausted prior round allows creating next round` -> PASS
   - `getCampaignTesterBonus with SQL error CAMPAIGN_NOT_FOUND propagates and does not offer create` -> PASS
   - `missing RPC get_campaign_tester_bonus sets isNotSupported` -> PASS
   - `delayed create response arriving after account change is safely gated` -> PASS

4. **`com.ntt.crosstest.ui.viewmodels.CampaignDetailTesterBonusTest`** (1 test):
   - `CampaignDetailViewModel loads bonus info and does not preserve previous user my_awarded across identity change` -> PASS

5. **`com.ntt.crosstest.ui.TesterBonusVisualQATest`** (7 tests):
   - `render TesterBonusScreen in Vietnamese Light mode with full content and buttons` -> PASS
   - `render TesterBonusScreen in Vietnamese Dark mode with full content and buttons` -> PASS
   - `render TesterBonusScreen in Narrow 320dp width without clipping` -> PASS
   - `render TesterBonusScreen in Large font scale 1_3 and 1_5 reachability` -> PASS
   - `render TesterBonusScreen with active round progress and budget breakdown` -> PASS
   - `render CampaignCard with bonus badge and uninstall button in Light and Dark mode` -> PASS
   - `render CampaignCard coexistence 320dp narrow long app name uninstall and large valid reward label` -> PASS

### Backwards Compatibility Verification:
```bash
.\gradlew.bat testDebugUnitTest --tests "com.ntt.crosstest.ui.viewmodels.CampaignDetailNextAppPriorityTest"
```
**Result**: **BUILD SUCCESSFUL** (10 pre-existing priority tests pass with 0 failures; four-group ordering from migration 086 verified intact).

---

## 4. Visual QA Previews

Rendered directory: `audit/tester-bonus-2026-11-11/previews/`

| Preview File | Resolution | Description & Verification |
|---|---|---|
| `tester_bonus_screen_vi_light.png` | 720 x 1700 (360x850dp @2x) | Vietnamese Light mode. Screen title "Thưởng thêm cho tester". Wallet balance card, Reward per tester and Recipients inputs, calculated budget in high-contrast `#B54700` (> 5:1 contrast), terms card, and fully reachable Cancel ("Hủy") & Agree ("Đồng ý") buttons. |
| `tester_bonus_screen_vi_dark.png` | 720 x 1700 (360x850dp @2x) | Vietnamese Dark mode. Adaptive dark surface `#121A13`, `#FFB74D` orange accent (> 7:1 contrast), Agree button in bright orange with dark text `#211100` (> 10:1 contrast). |
| `tester_bonus_screen_narrow_320dp.png` | 640 x 1900 (320x950dp @2x) | Narrow 320dp layout. Title wraps gracefully (`maxLines = 2`). Form inputs and Cancel/Agree action buttons fit comfortably without horizontal or vertical clipping. |
| `tester_bonus_screen_large_font.png` | 720 x 2100 (360x1050dp @2x) | Large font (`fontScale = 1.3f`). Labels, helper text, and currency notes scale cleanly. Reachable layout with no overlapping elements. |
| `tester_bonus_screen_active_round_progress.png` | 720 x 1700 (360x850dp @2x) | Active round inspection mode. Title "Đợt thưởng thêm đang chạy", status badge "Đang hoạt động" (`#2E7D32`), progress indicator (12/20 đã trả, còn 8 suất), orange progress bar, and comprehensive budget breakdown (Đã giữ trước 100 Credit, Đã chi trả 60 Credit, Còn lại trong đợt 40 Credit). |
| `campaign_card_bonus_badge_light.png` | 720 x 440 (360x220dp @2x) | Home campaign card in Light mode. Clean coexistence between app title, right-aligned high-contrast orange bonus badge (`+5 Credit`), and the Red uninstall button. |
| `campaign_card_bonus_badge_dark.png` | 720 x 440 (360x220dp @2x) | Home campaign card in Dark mode. Dark surface `#121A13`, badge `#422100` background with `#FFB74D` foreground (> 7.5:1 contrast), coexisting with dark error-container uninstall button. |
| `campaign_card_narrow_longname_uninstall_large_reward.png` | 640 x 480 (320x240dp @2x) | Stress coexistence fixture: 320dp narrow width, long app name ("Siêu Ứng Dụng Học Ngoại Ngữ Cực Kỳ Dài Và Chi Tiết Cho Mọi Lứa Tuổi"), uninstall button present, and valid large reward label `+2147483640` Credit with `fontScale = 1.3f`. Title truncates cleanly with ellipsis; badge and uninstall button remain fully rendered and non-overlapping. |

---

## 5. Scope & Integrity Adherence

- **Source Code Scope**: Only Android app source files and tests under `app/src/` were edited.
- **Audit Directory**: Only artifacts owned by this task under `audit/tester-bonus-2026-11-11/` were created or modified.
- **Untouched Areas**:
  - `supabase/` untouched.
  - `backend/` untouched.
  - `LOCK_ORDER.md` untouched.
  - Other task files and checkpoints untouched.
  - No `git checkout`, `git clean`, `git reset`, `git stash`, `git commit`, or `git push` executed.
- **Safety**: No real financial transactions, credentials, or remote endpoints invoked. Pure mock engines and local fixtures utilized.

---

## 6. Commands for Parent Joint Acceptance

To run all Android tests locally:
```powershell
.\gradlew.bat testDebugUnitTest --tests "com.ntt.crosstest.*TesterBonus*"
```

To run pre-existing regression tests:
```powershell
.\gradlew.bat testDebugUnitTest --tests "com.ntt.crosstest.ui.viewmodels.CampaignDetailNextAppPriorityTest"
```

To view generated screenshots:
```powershell
Get-ChildItem -Path "audit\tester-bonus-2026-11-11\previews"
```
