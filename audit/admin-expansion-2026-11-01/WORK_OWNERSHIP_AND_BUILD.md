# Concurrent work ownership and build coordination

All implementing sessions use google/antigravity-gemini-3.8-flash variant high. Preserve every other session's changes. Do NOT repair compileerrors by editing anotherowner'sfiles; report exacterror forowner or waituntilitstabilizes.

- **Core Android** (`ses_e694e3214ffeA65KCtmBe1HUpC`): AdminUsersScreen/ViewModel, TestCheoApp, NavGraph; AdminManagementModels/Repository; MemberDetailScreen/ViewModel; new AdminCampaigns, AdminTesterReportsQueue, AdminAudit, AdminSystemMonitor, Carryover editor components/ViewModels; admin_management.xml resources; core-admintests/fixtures.
- **Business Android** (`ses_e6937dd3effeGOX6UypJnYdiYq`): BusinessPolicyModels/Repository, AdminBusinessPolicyScreen/ViewModel; FINANCIAL parts of data/Models.kt, CampaignRepository.kt, Profile/CreateCampaign/OwnerAppDetail/AppUpdateReleaseViewModels and theirscreens; admin_business_policy.xml resources; financial/push/app-update tests. Does NOT edit NavGraph/AdminUsersScreen/ViewModel/TestCheoApp orcorefiles.
- **Notification module** (`ses_e69529f82ffe1p2VaHO0W18bJK`): staged072/notificationworker/tests, NEW AdminNotificationCampaign* Androidfiles, admin_notifications.xml resources andnotificationtests. No sharedAndroidfileedits.
- **Core backend/audit** (`ses_e69574b3affete6nQiA7OjlCBB`): 070core; STAGED073audit/roleswrappers; core/audittest andcontractdocs. NoAndroid/071/072edits.
- **Business backend** (`ses_e69545598ffeaW7TJ8FAw0BsPq`): staged071/businessrunner/contract/docs. NoAndroid/070/072/073edits.
- **Codex**: checkpoints/review/probes/integration/finalverification. LastnavigationwiringassignedtocoreAndroidafterothershandover. PackagejsonfinaltestintegrationcoordinatedbyCodex.

## Gradle MUST be serialized and output-redirected

DirectconcurrentGradlebuilds already caused missinggenerated.class files in bundleDebugClassesToRuntimeJar. OpenCodebashcanalsohangforeverafterGradleBUILD SUCCESSFUL becausenewdaemoninheritsitsstdio. Use ONLYthishelper (mutex+offline+nodaemon+redirectedoutput):

```powershell
& audit/admin-expansion-2026-11-01/run-gradle-logged.ps1 -LogName unique-owner-log -GradleArguments @(':app:compileDebugKotlin')
& audit/admin-expansion-2026-11-01/run-gradle-logged.ps1 -LogName unique-owner-tests -GradleArguments @(':app:testDebugUnitTest','--tests','your.specific.TestClass')
```

Useunique lognames perrun. Do not stopsharedGradledaemonswhileotherownersbuild. Ifhelperiswaitingforitsmutex, continueotherworkuntilfinished; don'tlaunchanotherdirectbuild. Thisfileandhelperareauditartifacts, notproductionbuildconfigurationchanges. Don'tclaimalltestsfailedfrom missing.class duringconcurrentwrites; rerunexclusivelyafterallsourcecompiled.
