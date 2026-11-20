# Supervisor corrections during final client verification

The actual server removal contract returns `removed` for owner RPC and `deleted` for admin RPC. Both completed states must be recognized. A missing status is allowed only for the legacy admin response; the owner repository requires an explicit known state before acknowledging it.

Supervisor made small client corrections after reviewing actual SQL and SDK code:

- Recognize the existing admin `deleted` state, and use the validated completed-state property in the owner-management screen as well.
- Reject failed/unknown owner results, missing owner status and a different campaign ID before mutating caches. Reject failed/unknown admin results while preserving legacy omitted-status compatibility.
- Check identity/generation again after loading the caller profile and before any legacy table-delete fallback. A role lookup from another session must not initiate a mutation.
- Use a localized generic error instead of displaying the implementation label `unknown_status`.

The CLI is still completing its focused lifecycle tests. Final full Android verification must include these source changes; an earlier test result is not final acceptance.
