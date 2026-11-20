# Independent acceptance/review checklist

These are coordinator review cases, not a completion claim. Product implementation remains assigned to exact Gemini/medium via OpenCode.

- Full latest migration chain must pass independent root-daily-coexistence-probe.mjs in both orders; ordinary/mission IDs distinct. Include pending/rejected/approved combinations.
- Daily uniqueness must be enforced in DB, not just latest-row lookup/UI. Different missions/context/date must not select each other's existing row.
- Day-one approval affects participant status, hasCompleted, get_open_missions, card CTA and mission settlement. Verify later-day eligibility through the read/UI path as well as submit RPC. Preserve existing mission reward escrow/settlement policy; do not accidentally pay a per-participant reserved reward once per daily report without a matching funded contract.
- Check get_daily_task_quota/next-campaign selection/check-in and pendingCampaignIds: a mission report must not consume or hide the ordinary daily report slot.
- get_campaign_detail latest definition from 070 and metadata additions from 071 must keep ordinary today's report separate from mission report. One mutable myTodayReport field cannot represent both.
- Three-image attachment snapshot must include stable ID/order/hash/paths/checkpoints/receipt across restart and next-day reconciliation. Verify failure during third image resumes prior two without reupload, and immutable receipt after full-RPC commit but lost response.
- Lease and cleanup must retain every in-flight/draft file, including thumbnails; remove only correct account/request/version. Budget counts real copies, not just first screenshot.
- Signed media grant must bind attachment ID as well as report/user/variant/expiry. Changing image selector must not reuse first-image token or silently return primary image. Existing gateway-off behavior is not proof of new gateway path.
- Gallery/thumbnail request factory must use each selected attachment's full/thumbnail/hash/expiry, not TesterReport primary fields for all items. Comparison target scoped to report context/mission.
- Exact three-image maximum enforced by VM + RPC + relational constraints. Four-image API requests fail, existing one-image request/receipt remains readable.
- Full tests must load new migration. Historical 062-065 suites are valuable compatibility evidence but don't validate 074. Adapt fixture dates only while keeping original business assertions meaningful.
- Session metadata must still show google/antigravity-gemini-3.8-flash/medium; final broad Android includes prior audit init script when reporting comparison to 511/68 baseline. Save fresh XML snapshots and actual APK metadata/hash.
- No local PostgreSQL tools on PATH were found during initial checks. PGlite tests do not prove multiple-connection races. Android SDK/emulator binaries exist with AVDs, but no device was connected via ADB during initial checks; no device validation claimed.
