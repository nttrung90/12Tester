# Independent Codex review: notification072 round1

Independent executable probe: independent-notification-probe.mjs. Results JSON and log saved alongside. Two defects are CONFIRMED in the first staged072:

1. **Request payload collision:** payloadhash uses md5(concat_ws('|',...)), so title_vi='A|B',title_en='C' and title_vi='A',title_en='B|C' generate samehash. Probe reuses same requestID with differentpayload and gets replayed=true instead of conflict. Replace ALL mutation payloadfingerprints with canonical jsonb object hashing, including intended normalizedfields, actor identity and operation where appropriate. Lock by request scope before replaylookup so concurrent identicalrequests both get same committedresult rather than uniqueconstraint error. Verify cross-admin sameUUID behavior; currently requestsPKglobal and lookupdoesn'tcheckadminid. Either scope(actor,requestID) or reject mismatchedactor; never replay anotheractor'sintent silently.
2. **Empty recipient snapshot is not frozen:** worker resnapshots a queuedcampaign whenever total_recipients=0. Probe queues a role=moderator campaign when there are0moderators; later a member becomesmoderator. Worker sends1notification even though scheduledsnapshotcount0. Add explicit snapshot_taken_at/boolean or equivalent state so zerois a validfrozensnapshot. Do not use recipientcount as marker. Test emptyrole snapshot plus late role/memberchange and workerrestart.

Additional review points:

- Campaignhistory/detail events should be bounded/paginated. Recipient cursor pairformat must reject malformed and infinity dates; filterstatus must be whitelistvalidated, not silently ignoreinvalid values.
- Worker-runner fallback script currently lives under audit and imports bare'pg'; no nearestnode_modules there. Move/finally package runnable serviceunder backend/notification-worker with package.json or verified module-resolution/deploymentinstructions. Scriptmust be includableinactualdeployartifact. Avoid overlapping setInterval async ticks; use completion+setTimeout or explicit inFlightguard, and sensible SSL/envhandling. Never printDATABASE_URL/secrets onerror.
- Read_count currently dynamic by extant notifications rows; make UIlabel truthful (currently-read inremainingnotifications), not historical deliveryproof. Sentcountmust remainstable afteruserdelete as tested.
- Need eventual commonadminaudit integration with070 for create/schedule/cancel/processwrapper, preservingdedicateddetailed events and avoidingauditspoofing. Don't modify070 yourself whilecoreagent ownsit; documentinterfaceintegration.
- Provide scheduler readiness status and concrete deploymentfileinstructions; no frontend claim “hẹn giờ đang hoạt động” from merelysavedqueuedrow whencron/worker is unavailable.

Fix all confirmeddefects, addauthorregressions with actualSQL, run independentprobeagain and expect payloadMismatchRejected=true; initialCount=afterCount=0. Existingdirectpopup behavior unchanged. Scope remains your staged072/notifications tests/docs; don't editAndroid,070,071 or packagejsoncurrentlyownedbyotheragent.
