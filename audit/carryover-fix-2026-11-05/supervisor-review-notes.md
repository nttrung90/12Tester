# Independent review of first 080 draft

The task is not accepted. Keep the same Gemini model/high session and fix these without weakening supervisor assertions. These notes refer to the first 080 draft; re-check final state before applying.

## R01: Historical finalized getter snapshot was removed

080 replaces get_daily_task_status but omits 079's early return of finalized historical user_daily_tasks. Source changes after sealing now change historical status even though the ledger itself remains unchanged. Independent acceptance `Late source changes do not rewrite an already finalized historical day` fails: expected carry=2, got 0. Restore immutable snapshot behavior with all fields, historical streak and policy percent semantics. Snapshot must remain authoritative even when migration reclassifies source journals.

## R02: Privileged financial finalizers are exposed to authenticated clients

080 explicitly grants authenticated execute on apply_daily_inactivity_penalty(integer) and finalize_daily_tasks_for_date(date). Previously these are service_role only (baseline062 ACL). Both ordinary-member denial probes fail. This is especially dangerous because finalize_daily_tasks_for_date now honors test.allow_future_finalize, and _apply also honors this trusted-test setting; a client who can run the privileged wrapper can spoof that GUC. Revoke client access to all overloads; retain only appropriate trusted server roles. Preserve cron compatibility and trusted tests. Do not grant ordinary clients privileged global finalization just to make test fixtures easier.

## R03: New diagnostic functions bypass identity/locked-admin checks

diagnose_daily_task_stuck_users and audit_carryover_journal_reconciliation only check role IF auth.uid() is non-null. Both allow an unidentified authenticated session to inspect every user's data. Both also allow a locked administrator. All four probes fail. Match assert_unlocked_admin and trusted service execution protections already established in 079. Include ordinary member, UID-null client, moderator policy as appropriate, locked admin, authorized admin and service-role tests.

## R04: Old unknown report context must remain unresolved; preserve valid ordinary checkins

080 backfill uses ELSE for all non-mission report source contexts, including NULL. That sets verified_app_test and is_qualifying=true for legacy reports whose context cannot be proven. This contradicts the plan and the new trigger's own strict unknown behavior. Backfill must explicitly recognize app_test, mission, and unresolved unknown separately.

Correction after inspecting the actual generating functions: the earlier supervisor probe treated a freeform checkin note containing 'mission' as proof of mission origin. That inference is not justified. Existing regular checkins may have arbitrary notes, and no current mission generator was found emitting that invented fixture note. The actual mission RPC is already tested independently with its typed report context. Do not exclude a legitimate ordinary checkin merely because its freeform note mentions 'mission' or 'nhiệm vụ'. The supervisor upgrade probe now preserves an ordinary checkin whose note is 'Test thường: đã thử tính năng mission trong app' (D must stay 1). Remove broad keyword exclusions introduced by the first 080 draft, in both live trigger and backfill. Preserve existing known generated-report note exclusions from 066, and use trusted explicit origin metadata if any additional automatic source requires separation.

## R05: Bounded recovery still starves healthy users behind an unresolved source day

The candidate query checks only predecessor existence; it can repeatedly choose a date whose get_daily_task_status history_ready=false due to unresolved source events. That date never seals and remains the batch start forever. A healthy other user's later dates never execute. Reproduced in safety-probes: owner checkpoint 40 days ago, unresolved report day39, healthy admin supplier history pending. Six automatic runs fail to advance healthy user to yesterday. Skip unready/unprovable candidates when finding work, keep diagnostics for unresolved users, and ensure repeated automatic jobs still advance all recoverable chains. Consider same-day partial coverage_start and old account creation day candidates too; don't repeatedly select an unfinalizable boundary date.

The second draft's NOT EXISTS unresolved-source filter handles the initial source case, but candidate readiness also depends on a verified predecessor and full historical coverage. Safety probes now explicitly exercise the two boundary cases already noted above: a legacy account created 90 days ago with no checkpoint when coverage began 60 days ago; and a known day61 checkpoint followed by a partial coverage day60 whose history cannot be proven. Other healthy users must still reach yesterday. Candidate selection should agree with actual getter readiness/exemption, not merely check that no unresolved-source row exists. Do not block a historical candidate based on events only observed after its day ended.

## R06: Preserve member login completion while separately showing unknown carryover history

The first UI draft changes DailyTaskStatus.isPendingCarryover from owner-only to all modes. DailyTaskCard's met calculation checks pendingCarryover before dailyTask.met, so a member who has logged in can now be shown as not having completed the daily login solely because yesterday is unfinalized. The accepted contract explicitly keeps member login completion independent from debt settlement/readiness. Do not just change existing tests to bless a new requirement to wait for cron to complete a member login task. Unknown debt must not look debt-free, but show the pending debt/history explanation separately from the member's already-completed login goal. Test a logged-in member with carryoverReady=false/historyReady=false and server met=true, alongside known-debt member and owner pending states. Retaining owner-only isPendingCarryover and adding a distinct cross-mode readiness property is one option; a presentation helper separating the two concepts is also acceptable.

## R07: New debt text is still clipped away in the compact card

The initial UI draft appended remaining-debt text to completedText (e.g. 'Đã hoàn thành 5/5 app hôm nay; còn 3 nhiệm vụ bảo lưu') in one Text with ellipsis. The first handover improves maxLines from 1 to 2, but still has a hard clipping limit on this long combined sentence. On a narrow display or large font, the appended number can still disappear. The main purpose of C02 is visible remaining debt. Render the debt count on its own wrapping line/row without clipping that number, or use a layout that demonstrably keeps it visible at larger fonts. The same applies to the longer member login + debt sentence. Review actual constraints rather than just testing that a string resource exists.

## R08: Expanded recovery now charges OLD days before an existing trusted checkpoint

Critical financial regression found from the second-turn acceptance output: the 3-day scenario used to leave 9726/10000 Credits (34+75+165 penalties for only those three days), but the second draft reports creditsAfterSecond=6. The new global generate_series reaches the user's old account creation day and _apply seals/penalizes many days BEFORE the trusted checkpoint at day4. Those dates are not part of the current chain to recover; the checkpoint/current balance already bounds the trusted state. This rewrites the financial effect of unknown/pre-checkpoint history, contrary to the task safeguards.

The independent-acceptance 3-day case now asserts that automatic catch-up creates no owner ledger rows before its trusted checkpoint and that balance reduction equals only the sum of penalties AFTER that checkpoint. This is not a change to the B+C formula; it catches excess debit outside the authorized recovery window.

Fix both selection AND execution scope. Filtering candidate starts alone is insufficient: _apply loops ALL profiles on each globally selected day, so another user's older start can still seal/debit this user before its checkpoint. Preserve explicit historical backfill behavior required by prior owner-auto-pause probes; direct _apply(date) historically allows a deliberately targeted older gap without replacing the latest profile streak. A protected helper with an explicit checkpoint horizon/scope parameter is one option: automated catch-up respects each user's latest trusted finalized state at/before the target, while deliberate direct/date-targeted older repair retains its specified boundary. Never invent finalized rows with zero debt to hide this and never forgive/double-charge balances as a workaround.

## Supervisor commands and evidence

- node audit/carryover-fix-2026-11-05/independent-acceptance.mjs
  - First draft: mission exclusion/resume now passes; historical snapshot fails.
  - Backfill three-day test was adapted ONLY to wait for bounded batches to finish before asserting C=3. Original first failure there was expected due to the legitimate new batch limit and account-creation chain; don't weaken the final debt/idempotency assertions.
- node audit/carryover-fix-2026-11-05/legacy-classification-probes.mjs
  - Seeds actual source rows under 062–079, then applies new migrations; checks source classification, immutable finalized rows, NULL-context report, and preservation of ordinary freeform checkin notes. The earlier fictional mission-note expectation was explicitly corrected above after source inspection; historical failure logs preserve that audit history.
- node audit/carryover-fix-2026-11-05/safety-probes.mjs
  - First draft: seven failures, as described above.

Logs: independent-first.log, legacy-first.log (first five simpler cases only), safety-first.log. Do not edit these supervisor-owned probes, generators, before/ files or historical recheck evidence. Production source behavior must satisfy the invariants. Report any truly incorrect fixture assumption explicitly.

First-turn completion was then independently retested: independent-handover1.log still fails historical snapshot; safety-handover1.log still has all seven failures; legacy-handover1.log covers the expanded upgrade/cascade scenarios. Full npm/Android checks reported by first turn do not eliminate these missing edge cases.

Also complete full SQL suite, Android unit tests/build and operational instructions as originally requested. Avoid pinning active coverage to older migrations to hide new failures.
