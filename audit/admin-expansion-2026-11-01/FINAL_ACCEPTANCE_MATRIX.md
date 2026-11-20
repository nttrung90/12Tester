# Admin expansion acceptance

Status: implementation complete and accepted in local verification on 2026-11-01. All 499 Android tests in 67 suites passed twice consecutively without errors or skips, and the debug APK built successfully. Backend suites use the active migration chain. Actual-device and production deployment checks remain outside this local acceptance.

| # | Feature group | Backend | Android entry | Final evidence |
|---|---|---|---|---|
| 1 | Advanced member management | 070 server search, cursor, overview, slots | First member card and member detail | PASS: admin-management; independent member paging/layout; Android full suite |
| 2 | Daily task and credit investigation | 070 daily/credit history and linked compensation | Daily/member detail, compensation | PASS: admin-management; durable compensation/process recreation; session races |
| 3 | Carryover policies | 070 dated policies, CAS, cancellation, history | Daily task card → carryover editor | PASS: 125 future policies; CAS; independent streams/network recovery; Android full suite |
| 4 | Central campaign management | Existing lifecycle RPCs, 070 search, 073 audit | Campaign management card | PASS: action-audit; campaign action/session races; route/build review |
| 5 | Global tester review queue | 070 media expiry, 071 reward snapshots, 073 staff audit | Report queue card | PASS: business policies/full-chain integration; DTO/media/snapshot review; Android full suite |
| 6 | Contact support | Existing support RPCs, 073 common audit | Support card → existing support screen | PASS: action-audit; admin flow regressions; existing support route retained |
| 7 | Configurable rewards and penalties | 071 welcome, daily, reports, update-release consumers | Business policy editor and actual consumer screens | PASS: business 21 groups; independent release escrow/snapshot probe; financial VM tests |
| 8 | Configurable fees | 071 push, slot quotes, receipts, dated terms | Business policy editor, push/slot confirmations | PASS: actual ALL quote immediate/post-activation replay; immutable intents; financial consumer tests |
| 9 | Roles and warning revocation | 070 capabilities/revocation, 073 coupled role/lock protection | Member detail | PASS: backend role/lock/capability/warning cases; route/build review |
| 10 | Common admin audit | 070 append-only events, 073 existing mutation wrappers | Audit card and paginated audit screen | PASS: action-audit; inherited-grant/full-chain integration; owner-vs-staff independent probe |
| 11 | Targeted and scheduled notifications | 072 frozen audiences, idempotency, scheduler/worker | Existing fifth card → notification manager | PASS: notification backend; past-due replay; Android repository/VM/independent ownership; worker smoke |
| 12 | Operational monitoring and bounded retries | 070 summaries/jobs, 072 scheduler health | Monitor card, notification monitor | PASS: backend summary/job retry cases; scheduler/worker recovery; compiled routes/DTOs |

Original five cards must retain their order: member management, daily tasks, home statistics cycle, app update configuration, system-wide notifications. New tools follow these cards.

Excluded from the selected scope: enabling disabled top-ups, a new payment provider, Supabase Auth account deletion, cloud infrastructure changes, and automatic production deployment.

Verified acceptance checks:

- [x] All deployable migrations 062–073 apply, with inherited Supabase grants covered.
- [x] New backend behavioral suites and existing backend regressions pass.
- [x] Cross-module integration verifies that 073 wraps the actual 071 snapshot implementations.
- [x] Android debug APK builds; all 499 tests pass, including independent pagination, account ownership, layout, and unresolved-request probes.
- [x] Worker dependency lockfile, safe local smoke tests and recovery tests pass.
- [x] Workspace comparison against the turn-start checkpoint: no deleted files, no changes to migrations 062–069. The existing version 29 / 1.0.9 in the workspace was preserved.

Evidence: `root-final-android-acceptance.log`, `root-final-android-results.json`, `root-admin-expansion-after-slot-replay.log`, `root-admin-management-final.log`, `root-baseline-regressions.log`, `root-worker-final-after-tls.log`, `root-worker-packaging-smoke-after.log`, `root-resource-validation-final.json`, `current-change-manifest.json`. JUnit XML is retained in immutable `test-result-snapshots` directories. Backend and worker tests use isolated fixtures; there was no live Supabase write.

OpenCode implemented the features using Gemini 3.8 Flash/high. After the second quota stop, the user explicitly authorized Codex to finish the remaining work. Codex completed the test fixture fixes: Auth initialization must finish before session import, non-lifecycle fixtures disable platform callbacks, and the anonymous admin-action test waits for unrelated read-only initialization to settle. Business assertions and production authorization guards were retained.

No production database or actual-device verification has been performed.
