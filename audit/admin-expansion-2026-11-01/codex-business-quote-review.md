# Confirmed quote activation defect — Codex independent probe

Run `node audit/admin-expansion-2026-11-01/independent-business-quote-probe.mjs`. Beforefix output retained in independent-business-quote-before.log and independent-business-quote-initial.json.

CONFIRMED: quote revision currently equals fulltable adminCAS hash `_get_business_policy_revision()`. A pre-scheduled row becoming active at midnight changes NO tablecontents, so thishash stayssame. Probe uses an isolatedSQLtestdate seam in `_get_effective_business_policy_row`, notproductionwrites/clockchanges, to exerciseactivation:

- Slot2 quote: cost50, renewal30days BEFORE; cost50, renewal1day AFTER; revisionIDENTICAL.
- Appupdate quote: reward6*20claims=120 BEFORE; reward4*30claims=120 AFTER; revisionIDENTICAL.

Checking onlyexpectedcost/budget + currentglobalrevision DOES NOT detect these changedterms. Clientcanconfirm30daysandget1day, orconfirm6*20thenfund4*30withoutreconfirmation.

Fix quote revision separately from adminCASrevision: bind to effectivepolicyidentity AND allrelevantquoteparameters/terms (e.g. canonicalhash of policyid/typedterms, or globalCAS+effectivepolicyid+quotetype). Newfinancialv2consumers mustcompareagainst SAME effectivequote token, notthe globaladminrevisionhelper. APIclients treatquote.revisionasopaque andpassunchanged. Adminschedule CAS stillusesglobalconfigrevision, don't confuse thetwo. Addpolicy_id/effective_fromtoquote ifhelpful; futurepolicyactivationmustchangequotetokenwithoutnewtablewrite.

Legacy appupdatepublishmustfailCLIENT_UPDATE_REQUIRED if perclaimreward OR maxclaims differ from legacy6/20, evenifproduct=120. Slotlegacymustcompare duration+relevantfee; no silentchangedterms.

Financialreplayreceipt lookup withsameintent must occurBEFORE freshness checks: an alreadycommitted purchase returnsitsoriginalamount/duration despite policyactivation. Freshrequestwith stalequotetoken mustfailand makeclientreconfirm; nocharge/ledgerwriteonconflict. Testactualslot/push/publishv2afteractivation and sameprice/budget changedterms.

Independentprobe should end with slotRevisionChanged=true and updateRevisionChanged=true. Add these into normalbusinessregressions. Isolatedtestdateoverrideendswithdatabaseclose, no liveSupabase calls.
