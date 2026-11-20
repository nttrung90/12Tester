# Round5 independent acceptance — resumed after user confirmed quota

User confirms Gemini available again; SAME primary session resumed round5 exact Google3.8Flash/medium, unified73107, log opencode-round5. Root captured stable369-file round4-checkpoint-source/manifest before new source edits. SQL ED7E... stays unchanged,36 groups +full npm already PASS. Do not rerun backend for Android-only edits.

Root added ONLY independent behavioral coverage, preserved every previous assertion/Coil Main fixture fix. Root classes now21 tests (Persistence5+Worker2+Manager7+Draft3+Media4). No implementation written by coordinator.

## Proven gaps in initial round4 checkpoint, now assigned within existing task5

root-round5-manager-initial.log and snapshot20261102-103608-712: Manager7 tests,4existingPASS/3newFAIL:
- ordinaryAdmissionFreezesItsDayBeforeWorkIsEnqueued: persisted expectedDate expected2026-11-02 actualnull.
- concurrentAdmissionsShareOnePhysicalPendingBudget:2prior5.7MB requests;4 concurrent5.7MB candidates compete for one slot. Accepted expected1 actual3, exceeds20MiB. Tests actual sparse files and real WorkManager admission; no fake implementation hook/barrier deadlock.
- photoOnlyNewerDraftSurvivesEarlierReceiptWithoutAnOpenWindow: saved3photo draft, submit, remove only first photo while text/rating unchanged, durable old receipt arrives with no VM. New2photo draft is deleted. Check FULL ordered-photo snapshot, not only comment/rating.

New Draft3 expiredMissionRejectsLocallyBeforeCreatingAnUploadAndKeepsTheDraft prepares a real compressed photo, records app-open and advances11sec, loads an expired joined mission read-only then submits. Must reject before manager admission, keep comment/photo. Run root-round5-expiry-initial.log for outcome (initially pending when written).

Root test bodies/assertions must not be weakened. Manager fixture now also clears MissionReportDraftStore before/after because new behavioral test owns a real durable draft. No product semantics changed by fixture cleanup. Run complete classes, including these new methods; initial task says17 because these4 cases were added while primary was reading/fixing source.

After source stabilizes, root rechecks updated owner/admin callsites, all21 cases, full combined Android+APK. Source remaining checks are in task-opencode-android-round5.txt/root-gallery-review.md; passing old535 alone is not full task acceptance.

UPDATE~17:46: Root added a fifth focused case after seeing NEW round5 preflight regress original policy: MissionReportViewModel rejects newly prepared primary solely when dHash equals rejectedReport.imageHash. Ordinary flow only requires a newly selected primary, SQL074 rejects reusing old URL, and065 deliberately removed perceptual-hash hard blocks. New Draft4 newlyPreparedPrimaryIsAllowedWhenOnlyPerceptualHashMatchesRejectedPhoto uses actual new blue PNG (uniform images share zero dHash), correct joined open mission, real rejected today report and11sec app-open. Must admit new primary despite equal dHash. Root classes now22 (5+2+7+4+4), existing assertions retained. No more additional cases planned absent newly reproduced task-related regression.

Expiry initial runs were blocked by in-progress UI compile errors, not behavioral acceptance failures (missing callback/import/mission.isOpen). Do not treat compile-only snapshots as a failed expiry assertion; rerun after source compiles.

Remaining direct daily-plan source check: Mission window currently captures today's scope once at init while draftKey/persist/request compute today dynamically. Add coherent refresh/rebind on Vietnam day change/foreground, preserving yesterday draft identity and accepted request date. Otherwise an old approved day-one UI can retain comment-only exemption when submitting a NEW day-two report, and an old scope state/form can be copied into today's draft silently. Original plan3.5 explicitly requires day/foreground checks. A submit action should first recognize day change and refresh the form/scope; foreground/resume should refresh server state. No need to change server authority/window/reward policy.

NEW source concern in round5 Mutex refactor: executeAdmission now checks capacity/persists INSIDE withLock BEFORE the outer try/finally that removes activeCampaigns. Early capacity/persist Blocked return can leave scope admission identity stuck forever. Existing capacity test now also completes ONE real held earlier work to free space and retries EXACT same previously capacity-blocked request; must be Accepted. No extra test count, original assertions preserved; helper key now correctly uses request campaign/user (previous constants only happened to match fixed-ID tests). Scope cleanup must cover every early return/exception/cancellation, while keeping network observation outside capacity lock. Root will verify after compile.

22-case initial run indep-all-5-classes-round5-pass: ordinary date/concurrent budget/expiry now PASS, capacity-retry and photo-only draft FAIL. New dHash case initially failed its fixture load assertion because getMyTodayReport queries /tester_reports directly; root corrected ONLY that MockEngine endpoint to return the same report (no assertion changed). It now meaningfully checks existing rejected report +new photo; see root-round5-resubmit-review. Don't change production repository to satisfy an incomplete mock. Shared ordinary policy and SQL unchanged.
