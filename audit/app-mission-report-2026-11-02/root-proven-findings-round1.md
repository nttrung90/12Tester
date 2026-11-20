# Proven coordinator findings against initial migration 074

These are required corrections before handover. All reproductions use the real deployable migration chain in local PGlite and never use live credentials. Do not edit coordinator root-*.mjs tests or weaken their assertions. Fix product/migration behavior and rerun them; use the exact Gemini/medium model already requested.

## Initial positive results

`root-daily-state-matrix.mjs` passed all six same-day combinations (both type orders × first report pending/approved/rejected), plus the next-day mission report after actual owner approval. Keep these working. `root-three-image-probe.mjs` passed three-image create/order, exact immutable receipt replay, 4-image rejection and changed-order/request-ID rejection.

## Required fixes

1. **Historical report dates are corrupted and upgrades fail with more than one historic day.** `ADD COLUMN report_date ... DEFAULT today` backfills every existing row with today; following `UPDATE ... WHERE report_date IS NULL` changes nothing. Independent upgrade with one yesterday report records today instead of yesterday; two historic days for same tester/campaign fail 23505 creating the new unique index. Add the column without default, backfill actual created_at/Vietnam date, validate historical identities, then set default/not-null/index. Ensure partial/rerun safety. Evidence `root-migration-upgrade-initial.log`; test `root-migration-upgrade-probe.mjs`.

2. **New SECURITY DEFINER core is executable directly by clients.** `_submit_tester_report_core` has no explicit private ACL. `has_function_privilege('authenticated', ... ,'EXECUTE')` is TRUE under Supabase legacy default grants. Explicitly revoke PUBLIC/anon/authenticated access, allow appropriate service role only. Review default table write grants for tester_report_attachments as well: existing REVOKE does not remove inherited authenticated INSERT/UPDATE/DELETE grants. RPC-only writes should not depend solely on absence of RLS write policies. Same independent ACL probe.

3. **Secondary attachment URLs are unvalidated.** A valid first image plus image2 URL `https://attacker.invalid/not-a-report.jpg` is accepted. Existing report-row triggers validate the primary URL only. Validate EVERY item's URL/bucket/user/campaign through existing canonical validators before mutations, including pending replacement/resubmission.

4. **Secondary hashes are unvalidated.** image2 `image_hash='not-a-valid-hash'` is accepted. Validate required dHash format on every attachment, and validate IDs/positions/SHA-256 schema appropriately.

5. **Thumbnail/full pairing is not checked on secondary items.** image2 can reference image1's thumbnail. Apply `is_valid_report_thumbnail_url` for each full/thumbnail pair.

6. **Empty attachment array silently retains pending report images.** Plan explicitly distinguishes null=retain from []=invalid. Pending v3 update with [] is accepted. Enforce array length 1..3 whenever array is supplied, across branches; null only means keep existing where allowed.

7. **Legacy v2 cover update leaves inconsistent report and attachment rows.** After a v3 three-image report, v2 new-primary-image request succeeds, updating tester_reports.screenshot_url but leaving position0 at the old URL. Reject unsupported legacy image edits on a multi-image report, or coherently preserve/synchronize the whole set without dropping secondary images. Exact receipt and replay must remain valid. Independent probe accepts either explicit appropriate rejection or a coherent complete set.

Items 3–7 are independently reproduced in `root-three-image-initial.log`, test `root-three-image-probe.mjs`.

8. **V3 accepts a missing client request ID.** Expanded three-image probe supplies no p_client_submit_id and update succeeds, bypassing the durable identity/receipt contract. Require non-null request identity on v2/v3 as appropriate to existing explicit-context API, while preserving the separately documented legacy endpoint compatibility. Evidence `root-three-image-expanded.log`. The same expanded test confirms direct client report_date movement is already blocked; keep that guard working.

9. **Admin queue overload breaks existing production API calls.** Latest-chain `run-admin-expansion-integration-test.mjs` fails with SQL 42725 on `select public.admin_get_tester_reports_queue(p_status=>null)`: old 8-parameter and new 7-parameter overloads both match. Keep existing published signature/return contract (cursor IDs, has-more, financial/media metadata etc.) while adding attachments; avoid default-argument overload ambiguity. Root evidence `root-admin-chain-initial.log`.

## Additional review still needed

- Verify new queue returns attachments through the exact endpoint invoked by existing app callers, not only a differently named/new overload tested in isolation.
- Pending/rejected mission updates check snapshot deadline but need review of current mission paused/stopped/deleted/deadline changes; keep committed replay semantics. Do not silently relax current eligibility.
- Root checklist `root-review-checklist.md` covers downstream gallery/descriptor/grants/durable queue/ordinary quota and mission settlement. Full Android/UI implementation is still required; passing backend probes alone is not completion.
