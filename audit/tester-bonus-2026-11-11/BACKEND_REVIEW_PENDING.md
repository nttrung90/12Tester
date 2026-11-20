# Controller review of first backend draft — not acceptance

This review is based on migration SHA256 `1E42B7B58EF934F9DD8611020B5429F30FCF36EF7927CBF6F397030AB28E2504`. Recheck current source before editing. Parent made no application/backend source edits; only isolated test runtime/probes.

## Confirmed regressions

- `node supabase/tests/run-app-mission-window-test.mjs` fails with `SCREENSHOT_URL_REQUIRED` when exercising existing multi-attachment mission submissions. Log: `runs/review-early-mission-window.log`.
- `node supabase/tests/run-mission-reward-modes-test.mjs` fails with `CAMPAIGN_NOT_OPEN` on daily mission reports for a production campaign. Log: `runs/review-early-mission-rewards.log`.
- Both runners load ALL current migrations, including 087. The new report core is ~740 lines while latest pre-feature 082 core was ~1090; preserve the actual current core, including attachments, finalized-campaign missions, report context, carryover restoration, once/daily paths, exact receipts and resubmit semantics. Do not weaken existing tests to match an older copied core.
- Parent captured actual 062..086 function definitions from native PostgreSQL in `baseline-functions/`; use these to compare latest semantics without repeatedly opening the enormous baseline migration.

## Confirmed native PostgreSQL deadlock

Parent installed and started a real PostgreSQL 17.11 test cluster; see LOCAL_POSTGRES_READY.md. 087 applies successfully to that isolated database. This is NOT a PGlite simulation.

Run: `node audit/tester-bonus-2026-11-11/review-native-probes.mjs --round-close`.
Log: `runs/native-review-round-close.log`.
Result: lifecycle succeeded, simultaneous owner round-close RPC failed `40P01: deadlock detected`.

Controlled real interleaving:
1. Transaction A holds campaign row FOR UPDATE, as a lifecycle row update necessarily does.
2. Transaction B executes actual close_campaign_tester_bonus RPC. It locks the round, then waits to update campaign summary (blocked by A).
3. A sets campaign status completed. Its row trigger synchronously calls close/refund, waiting on B's round lock.
4. Cycle: campaign->round vs round->campaign. PostgreSQL detects a deadlock.

Fix lock acquisition/closing architecture, not retries or test skips. A queue/deferred closing worker that does not lock rounds while the lifecycle transaction holds campaign rows is one possible approach; document the ACTUAL consistent lock order in LOCK_ORDER.md. Plain SKIP LOCKED only in the batch loop does not fix the synchronous lifecycle trigger inversion. Ordinary lifecycle-vs-approval probe happened to pass in one interleaving; this does not negate the confirmed round-close cycle.

## Deletion contract incomplete

- Current guard throws on any unsettled bonus round. Admin delete first closes then raises if pending; raising rolls back the closing state too. A guard alone is not the plan's required deletion lifecycle: stop new eligibility, preserve pending obligations, process them, refund, and complete removal without dropping financial evidence.
- Implement a durable deferred removal/closing path or another complete design. Returning a pending-removal result can be acceptable if old/new clients are handled coherently, campaign no longer accepts new reports, pending reports remain reviewable/auto-accepted, and a job actually completes deletion after settlement. Do not claim the requirement done merely because deletion is blocked.
- Existing campaigns status check already allows `closed` (see baseline-constraints.json), which is excluded from the normal open/paused/completed public feed. Consider compatibility and owner slot behavior if using it for pending removal.

## Security and closure review requirements

- CONFIRMED: `node audit/tester-bonus-2026-11-11/review-bonus-privacy.mjs` loads all latest migrations with `createDb({legacyGrants:true})`, creates a round via the owner RPC, then reads as an unrelated tester. It returns 1 private financial round instead of 0/permission denied. Log: `runs/review-ledger-privacy-default-grants.log`. This is not an invented admin regrant after migration: it reproduces existing schema default privileges before the new tables are created. Fix explicit table ACL/RLS; preserve public summary through RPC only. Parent probe is reviewer-owned; do not weaken it to accept exposed private rows.
- New rounds table has SELECT policies USING(true) for authenticated and broad anon rows, while grants are not explicitly normalized. Native bootstrap without legacy default grants currently returns permission denied, but production may have existing default grants to anon/authenticated. Test with createDb({legacyGrants:true}) as well. Ensure unrelated callers cannot directly read round financial ledger fields, request fingerprints/IDs, etc.; use owner-only table read or explicit revokes plus the public summary RPC. Do not rely on absent grants in one test bootstrap. Owner award history should remain accessible after campaign_id becomes null on deletion (join round owner, not only campaigns).
- Snapshot acquisition must not allow a concurrent close/refund to miss newly accepted in-flight eligible reports. Add a gated native test of submission vs close and use an auditable linearization/locking strategy. Do not trust mutable created_at after resubmission for original eligibility.
- Guard all wallet additions/refunds for integer overflow; preserve escrow and retryable closing instead of losing funds or falsely closing. Keep budget equations true on every failure.
- Public extra bonus totals must remain distinct from base/review/legacy tip/mission rewards. Verify actual response, report totals, credit logs and notifications.

## API contract documentation must match SQL

Current API_CONTRACT.md has invented/mismatched fields and errors. Do not hand it to Android as stable until corrected from actual SQL responses/tests:
- Reconcile SQL requires TWO parameters (p_campaign_id, p_client_request_id), while doc lists one. SQL returns found:false when absent, not REQUEST_NOT_FOUND exception; success has found:true.
- Actual get RPC returns has_round, is_owner, my_awarded, my_award_amount, my_awarded_at, my_pending_eligible. Doc examples claim success/role/is_eligible and different award keys that SQL does not return.
- Create SQL returns owner_balance and budgets; documented JSON omits several actual fields and invents created_at. Error codes are INVALID_INPUT, INPUT_OVERFLOW, PERMISSION_DENIED, CAMPAIGN_NOT_ELIGIBLE etc., not several listed aliases.
- Never advise generating a fresh request ID automatically on CLIENT_REQUEST_ID_CONFLICT while prior financial intent is unresolved. Preserve/reconcile original intent and show conflict safely.
- Check new tester_bonus_awarded/round snapshot reach actual report detail/owner-list payloads, not only table columns and approval response. Report totals must include extra bonus once.
- Remove claims that a guessed level-number diagram eliminates all deadlocks; real probe proves otherwise. Report only actually executed evidence and keep native unrun distinct from PGlite fixture checks.

## Test runtime ready

- `TEST_DATABASE_URL=postgresql://bonus_test_admin@127.0.0.1:55439/tester_bonus_test` (dedicated local fake-data database ONLY).
- Connection restrictions inside CLI may block localhost; finish tests and parent can run them externally.
- `apply-local-bonus.mjs` checks actual cluster directory and can apply the completed migration to the task DB for review. Parent will reset/recreate an isolated test DB if schema changes require it; no production access.

Backend is not yet accepted. Resolve confirmed regressions and native deadlock, finish true multi-connection tests and required lifecycle/security behavior before Android integration.

## Parent followup review during backend repair — MUST preserve full report core

Current report-core semantic diff is saved at `runs/report-core-review.diff` against `baseline-functions/_submit_tester_report_core_18210.sql`. It confirms ~270 lines of latest core STILL lost, not formatting:
- Existing v3 multiattachment and legacy fingerprints replaced with a different universal fingerprint, invalidating stored receipt replay.
- Legacy receipt backfill/replay block deleted; receipt read moved AFTER campaign lifecycle checks, breaking replay after campaign finalized/removed.
- v_idempotent_report early detection and several receipt/response paths removed.
Restore the EXACT latest baseline core as foundation then add bonus snapshot variable/fields and narrowly scoped locking/integration. Do NOT attempt to approximate missing code piecemeal again. Preserve all baseline idempotency/attachments/rating/late retry/context/carryover semantics and fingerprint formats. Tests feature-only are insufficient. Parent can run receipt/idempotency/context/extension regression suites against all migrations.
Actual native parent followup: owner-round-close vs lifecycle now PASS; native concurrency driver scenario1 PASS, scenario2 fails rd.remaining_slots undefined (no such table column). Also batch still round->campaign inverted lock; pending deletion when final award closes round is stranded because worker only scans closing. See TASK_BACKEND_REPAIR.txt for all concrete remaining acceptance issues.

CONFIRMED by additional executed parent tests:
- `node audit/tester-bonus-2026-11-11/review-migration-replay.mjs` creates reports using actual public v2/v3 RPCs with062..086 then applies087. ALL FOUR fail: same request/payload single+attachments while open -> CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH; after completed -> CAMPAIGN_NOT_OPEN. Log runs/review-migration-replay.log. Baseline replays are asserted before migration and pass. Parent probe is reviewer-owned, don't weaken it; restore old fingerprint/replay paths.
- `node audit/tester-bonus-2026-11-11/review-native-probes.mjs --batch-close` now gates actual admin deletion against batch close on a closing round with0pending. It CONFIRMS real40P01 deadlock: admin holds campaign before refund; worker holds round then waits campaign. Log runs/native-review-batch-close.log. Prior owner-close-vs-lifecycle fix is insufficient.
- `node audit/tester-bonus-2026-11-11/review-extension-schema.mjs` places pgcrypto in extensions (valid existing installation) then actual create RPC fails42883 function digest(text,unknown) does not exist because new RPC search_path excludes extensions. Preserve correct trusted extension resolution without user-writable unsafe schema paths; baseline already supports public/extensions. Log runs/review-extension-schema.log.
- `node audit/tester-bonus-2026-11-11/review-deferred-removal.mjs` executes actual PUBLIC admin_delete_campaign, PUBLIC approve_tester_report then actual integrated auto_accept_expired_reports twice. Recipients=1/full consumed leaves campaign stranded (FAIL); recipients=2/unused refund PASS. Confirms fully-funded final payout bug, not speculation. Log runs/review-deferred-removal.log.

## Latest parent rechecks after worker/extension edits (~15:27 local)
- `review-extension-schema.mjs` NOW PASS (runs/review-extension-schema-recheck.log).
- `review-deferred-removal.mjs` NOW PASS both recipients1 fully consumed and recipients2 partial refund (runs/review-deferred-removal-recheck.log).
- Applied current087 to isolated nativePG. `review-native-probes.mjs --batch-close` NOW PASS admin delete + worker. Parent probe legitimately permits worker SKIP LOCKED to finish without waiting on held campaign, and still asserts ledger survives, closed/remaining0/budgetconservation and removed campaign after both. Original inversion still fails old version; do not remove assertions. Log runs/native-review-batch-close-recheck.log.
- Core migration-transition receipt replay still requires exact baseline restoration; parent will rerun after that edit. Native full driver not yet passed all scenarios. Android still in progress separately.

Additional confirmed boundary bug: `review-large-valid-award.mjs` reserves valid2147483640x1, tester submits (provisional received), fixture models spending provisional credits before review by setting current wallet0 (valid nonnegative state), then actual PUBLICapprove RPC grants base2+extra2147483640. Wallet2147483642 fits int, but cumulative report `total_earned=12+2147483640` overflows int at RETURN, aborting approval22003. Log runs/review-large-valid-award.log. Use bigint arithmetic for cumulative response totals, preserve wallet range guards, no arbitrary new business caps. Android cumulative-earned getters/DTOs need Long where totals may exceed wallet32-bit range. Parent probe leaves schema/data isolated and valid; don't weaken it.

NEW confirmed request-lifetime bug: parent strengthened `review-deferred-removal.mjs` to reconcile original owner+campaign+createRequest after actual worker removal. Physical removal/ledger retention stillPASS, but BOTH full/partial cases nowfail found=false (runs/review-after-removal-reconcile.log). Reconcile WHERE campaign_id=p_campaign_id can't match after ONDELETESETNULL. Create replay returns campaign_id:null too. Preserve original immutable campaign/request binding separately from nullable FK (e.g. origin_campaign_id noFK, or validate request_fingerprint against provided originalcampaign+storedimmutable reward/max before returning providedcampaign). Existing caller must not need to switch to nullcampaign to resolve. Same request under wrongcampaign must remain refused; no guessing newID. API response should bind original campaign even after removal, with read-only reconciliation and same request/payload replay recognized before campaign-existence conditions. Android pending intent must reconcile even if getCampaign now404; currentVM loads campaign before reading pending store and cannot resolve deleted app request.

## Native driver recheck after source restoration (~15:42)
- Parent applied latest087 and ran real pg driver: S1wallet PASS, S2lastslot PASS, S3fails actual REPORT_ALREADY_REVIEWED not accepted regex. Log runs/native-review-driver-recheck.log. Correct expected outcomes according to actual existing APIs: after8h manual rejects REPORT_EXPIRED if first, REPORT_ALREADY_REVIEWED if auto alreadysettled. Auto MUST succeed, exactlyoneaward/payout, no manual2bonus in8h auto. Current expectedtester3wallet32 wrong: fixtureinitial10+provisional10+bonus10=30 forauto, not32.
- S4 is labelled Close vs APPROVE but actually runs close RPC vs GET/read RPC! Replace with real eligiblependingreportapproval and assert bothfinish + payoutretention/refundcorrect. Do not claimracecoverage fromread.
- S5 checks onlystatus/budget, not whether snapshotted pendingreport gotrefundedaway. Add gated interleaving and assert snapshotnonnull implies escrowretained untilapproval/reject then correctrefund.
- S7 reuses tester2/camp3 from S2, whose report alreadyapprovedsame day, so can'tseed 'pre-round newreport' bynewUUID/photo; expectednullsnapshot isnot a newfixture. Use genuinely isolated fixture per scenario or carefullydisjointfresh campaigns/testers. Similar reuse risks afterfixingS4. Prefer freshfixture percase to avoid dependentconditionals. PublicsubmitRPCs wherepossible, correct knownalreadyreviewed/expired outcomes only, no blanketcatch/allerrorsacceptable. Keepwalletanddedupassertionsmeaningful. Parentwillrerun whenstable; don'tmarknativePASS fromPGlite orread-onlyrace.

S3 precision: auto_accept uses FORUPDATE SKIPLOCKED. If manual brieflyholdsreportthenfailsREPORT_EXPIRED, auto maylegitimatelyskipthatlockedrow onfirstsweep; afterbothtransactionsfinish runthenextactualauto sweep toproveeventualexactlyonepayout andanotherrepeatno duplicates. Do NOTremoveSKIPLOCKED or assertfirstsweepmustprocesslockedrow. Alternatively construct trueboundaryrace withmanualtransactionnowfixedbefore8h andauto transactionstartedafter8h, assertactualrevieweventwinner and wallet32manual/30auto accordingly. Finiteboundedtiming/gates and no blanketerroracceptance. AutoRPCitselfmustnotsufferunexpectederror/deadlock.
