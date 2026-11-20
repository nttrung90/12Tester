# Core Android review round2 and coordination

Read WORK_OWNERSHIP_AND_BUILD.md FIRST. You were observed editing CreateCampaignViewModel.kt to repair a compileerror while businessAndroidsession owns it and is writingit. STOP editing financialfiles. Keepcoreownership. AllGradlemustuse loggedmutexhelper, notdirectgradlew, becauseconcurrentbuildsactuallycausedmissing.class errors. Do notoverwriteotherauthor'sfinancialwork.

## Source-confirmed media/language defects

- AdminTesterReportsQueueScreen passes raw item.screenshotUrl/thumbnailUrl directlyto AsyncImage and onlyURLtoFullscreenImageViewer. This bypasses existing protectedreportmedia path and expiryguardcontext. Use ScreenshotRequestFactory with a realTesterReport/descriptor carryingreportid/campaignid/userid/createdAt/mediaExpired andpassreport/descriptor toFullscreenImageViewer. Save selectedREPORTIDprimitive, notURLalone; clearpreview/selectiononownerchange. Don't altercoreImageViewerorfinancialOwnerAppDetailScreen; reuse existingfactory. IfqueueSQLmissingmedia_expired, addoptionalDTOdefault+documentbackendgap; existingfactoryservergrantstillauthoritative. Imagesexpired/accessdeniedneednormalplaceholder. Do notbypassbyusingFirebaseURLcachedtoken.
- NewqueueUIstillhardcodes Vietnamese strings (“Chiến dịch”, “Tester:”, “Nhiệm vụ:”, “Kiểm thử ứng dụng”, “Lý do từ chối:”) and rawstatus.uppercase. FixALLnewmanagementscreens'VI/ENstrings/statuslabels, notjusttitles. OthernewrawJSON/internalerrorUIshouldbeappropriatelylocalized/structured.

## Session-reset source defect and independent runtime test pending

NewMemberDetail/AdminCampaigns/AdminAudit/AdminSystemMonitor/etc handleSessionReset is unconditional. checkSessionChanged invokedbyanOLD A response callsreset(currentB) evenwhenactiveUID/generationalreadyB. ItcanerasefreshBdata andclearB'sactionguard/requestIDs. Make reset idempotent peractualowner and reconcile activeownerbeforeacceptingnewrequests, whilediscardingstaleAresponseswithouttouchingBstate. CheckALLnewVMs andoldAdminUsersedits. There is a NEWindependenttest `staleAccountAPageCannotResetFreshAccountBState` in audit/.../independent-tests/.../MemberDetailPaginationIndependentTest.kt. It holdsAcreditpage, logsinB, waitsFreshBoverview, releasesApage, assertsBoverviewandfirstpagepreserved.

The independentrun is currentlyblocked by a transient authornewtestcompileissue (`AdminManagementCoreTest.kt` OutgoingContent.toByteArray) andthenconcurrentGradle.classwrites; thisisnotclaimedasruntimeprovenyet. Fixcompileownedtestsandrunusingexclusivehelper:

```powershell
& audit/admin-expansion-2026-11-01/run-gradle-logged.ps1 -LogName core-session-independent -GradleArguments @('-I','audit/admin-expansion-2026-11-01/layout-review.init.gradle',':app:testDebugUnitTest','--tests','com.ntt.crosstest.audit.MemberDetailPaginationIndependentTest')
```

Bothoriginalparallelpaginationtestandnewcrossaccounttestmustpass; don'tchangeassertions. AddsameUID/newgenerationcaseandnewBactionguardcaseinauthorsuite.

## Finish integration, not just ViewModel fixes

- PublishedcoreAPI nowincludesoverview.slots, admin_get_member_slots, boundedcarryovergetterand admin_get_carryover_policy_history withcancellationevents. UpdateDTO/UI/pagination accordinglyfromupdatedapi-contract.md.
- Finish allcoretask screens/routes/cards/support navigation. Preserveoriginalfirst5order.
- Pending notification/adminbusinessscreens are ownedbyotherauthors; finalwiringcomesaftertheirhandover, don'tstubtheirUI.
- Scopeeditorflags/drafts andstate-restoration to currentUID/generation, targetID. MemberDetail dialogreasonfields presentlyrememberSaveablewithoutownerkeys; ensureoldadmin'sdraftcannotrestoreforanotherowner/session.
- Avoidloadmorewhileinitialrefreshinprogress; invalidateerrors/finally aswellassuccesses foroldquery/filter/owner, includingdebouncewindow. Capturequery+cursor+epochatacceptance.

Continuefullcoreimplementation; don'tstopafteronlyonenewfix. NoSQL, nofinancialownedfiles, nocommit/push/deploy.
