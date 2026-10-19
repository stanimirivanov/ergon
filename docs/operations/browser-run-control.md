# Browser run control

## TL;DR

- Control coordinates browsers; it neither authorizes tools nor cancels admitted work.
- Leases last 120 seconds, renew explicitly, and belong to one server session.
- Deploy schema, all backend writers and the dependent UI before enabling
  `ergon.run-supervision.control.enabled`; quiesce commands during the switch.
- Preserve exact uncertain intent; never silently replace its UUID or fence.

## API and recovery

Under `/bff/v1/tenants/{tenantId}/resolution-runs/{runId}`, authenticated current
supervisors GET `/control`. Status is DISABLED, AVAILABLE, THIS_SESSION,
ANOTHER_SESSION, EXPIRED or TERMINAL. Only THIS_SESSION includes a usable fence.
Expiry is advisory server time; commands check again. Reads never renew.

POST `/control-commands` with CSRF and `{commandId, expectedSupervisionRevision,
action, control?}`. ACQUIRE omits control; RENEW/RELEASE supply `{leaseId, revision}`.
No actor/holder/TTL/time is caller-selectable. Exact lifecycle replay returns its
original expiry, not an extension. After a lost response, replay unchanged intent
and then observe status before assuming control.

Every existing effect-bearing browser command accepts `control` with those same
fence fields. It is mandatory for first application when enabled. Preserve it with
the original command/attempt UUID and epoch during uncertain recovery. Historical
acknowledgements need no live lease, but retain the original fence (or omission for
legacy receipts). A new execution recovery attempt requires current control.
Acknowledgement does not restore private evidence access or permission to act again.

`run-control-required` and `run-control-conflict` are private/no-store HTTP 409;
`run-control-disabled` is 503. Existing business denials remain. Do not automatically
reacquire and resubmit uncertain intent with a new fence: recover its acknowledgement
or establish that new reviewed intent is appropriate.

## Rollout and rollback

The process-local BFF remains single-instance. “All writers” below prevents mixed
binaries during replacement; it does not establish horizontal session deployment.

1. Honor ADR 0056's authorization-writer lock-order upgrade requirement.
2. Apply the additive control migration with protection off. Existing runs and
   command histories remain unchanged; no leases are backfilled.
3. Deploy every backend writer, then compatible UI. UI/guide work follows backend
   review/merge in a dependent PR.
4. Quiesce browser ingress/in-flight command transactions while enabling all writer
   instances. Check holder/second-session denial, renewal, expiry, replay and handover.
5. Retain schema/audit for recovery. Prefer a corrected compatible writer. If
   disabling, quiesce again and record the deliberate relaxation. Disabling never
   undoes/stops effects; never run old writers while enforcement is enabled.

There is no backfill/rewrite. New indexes cost storage/WAL; FK validation briefly
locks existing referenced tables. Size immutable audit growth with command traffic;
do not truncate history or reconstruct authority from logs. A lost process-local
session waits for expiry, authenticates, re-reads supervision and acquires anew.
Handover changes epoch; retry successors acquire independently.

## Boundaries

Control loss cannot revoke an existing grant, cancel connector I/O, compensate,
close a case, refund an attempt or create follow-up. There is no forced takeover,
session transfer or autonomous renewal. Runtime progression remains unblocked.
Internal endpoints are not made production-safe by this fence. See
[ADR 0057](../decisions/0057-fence-browser-run-control.md).
