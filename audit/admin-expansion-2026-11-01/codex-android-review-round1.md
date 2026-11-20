# Codex Android core review round1 (during implementation)

## Confirmed independent runtime failure

`MemberDetailPaginationIndependentTest` has been added ONLY under audit/admin-expansion-2026-11-01/independent-tests. It uses realMemberDetailViewModel and supplied fakeAdminManagementRepository with realauthenticatedfixture. Test compiled and FAILED as expected:

```
Starting warning pagination must not strand the credit spinner
```

Evidence: independent-android-pagination-before.log and member-pagination-before.xml.

Repro: initialoverview/credits/warnings complete with hasMore=true. Begin creditloadmore, hold networkresponse. Start warningloadmore and allow it finish. Release creditresponse. Current single shared `reqSeq` invalidates the creditrequest, discards its validpage and never clears isCreditsLoading. Initialrefresh/loadmore races can similarly strand isLoading; otherpaginationpairs likelyaffected.

Fix with independent per-stream sequence/ownership + shared refresh/sessionepoch as needed. Refresh invalidates oldpages and resets theirflags; twoindependentstreams must NOT invalidate eachother. Keep stale-query/session guards, don't solve by removing guards or merely hiding spinner while discardingvaliddata. Addauthorregressions and run independentprobe:

```
gradlew.bat --offline -I audit/admin-expansion-2026-11-01/layout-review.init.gradle :app:testDebugUnitTest --tests com.ntt.crosstest.audit.MemberDetailPaginationIndependentTest
```

## Additional source-review issues to correct before handover

- In MemberDetailViewModel actioncoroutine preflight earlyreturn sets `isActionInProgress=false` unconditionally when stale. An oldqueued A action can run after sessionreset and a new B action acquiredtheguard, releasing B'sguard. Only ownerseq/session may releaseguards; audit samepattern othernewViewModels. Existingfinally guards checktoken but preflightdoesnot. Add test holdingoldcoroutine until new sessionactionaccepted; oldmustneither execute norrelease newowner'sguard.
- getMyCapabilities via runCatching swallowsCancellationException. Preserve coroutinecancellation. Reset requestintent IDs/payload/draftownership on sessionreset. Financial requestID mustremain same for retryofambiguousfailure; newintent onlyafterclearresolution/changedpayload, and persist through state restoration where appropriate. Use structuredpayloadequality rather than concatenatedfreeformstrings.
- Role/revoke actions generate newrequestIDevery retry; backendround1 isaddingconsistent replay. Preserve stableIDforunchanged ambiguousmutation intents, separateoperationtypes. No automaticretry.
- DTO for emptycarryoveractivecurrentlyrequires nonnull id/effectiveFrom, whilebackendcontract defines nullbaselineemptyvalues. Align nullablefields+UIemptyhandling or use a backendexplicitnullableactive; do not silently fallbackknownfalse.
- Backendcore reviewer isadding bounded slotdetailsRPC/carryoverhistorypage and fixing Homewarningcounts/audit. Read updatedapi-contract.md before finalintegration; use additions, notfakecounts-onlydetail. Documentneededbackendgaps withouteditSQL.
- Keeporiginalfirst5cardorder, allstringsVI/EN; ensure newcardsactuallynavigate. Layoutbaselineprobe already verifies existingFlowRowcreditfix at9configurations.

Continue the full task-b-android-core scope afterfixing these; this is earlyreview, not acancellationorpermissionrequest. Backendcore070, staged071 and staged072 areownedelsewhere. Don't edit them.
