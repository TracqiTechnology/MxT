# Runner routing

The selector bootstraps on standard GitHub-hosted Ubuntu, queries all organization runners using the existing `RUNNER_CHECK_TOKEN` or `GH_PAT_WORKFLOW`, and selects a matching local label set only when a runner is online and idle. Busy, offline, absent-token and API-error cases use a standard hosted runner. Fork pull requests always stay hosted.

This is a preflight snapshot, not a reservation: another workflow may claim the idle runner before the selected job starts. Jobs are not canceled/retried after starting, and this does not provide mid-job failover. Review the emitted selection notice when diagnosing queue delays. The lookup token must already have permission to list organization runners; this change does not create or broaden credentials.

## Preserved exceptions

Linux and Windows packaging and regression legs retain their OS coverage. Only macOS legs route to idle Macs. Existing release/deployment behavior is unchanged.

## Focused validation

Run `node .github/tests/runner-routing.test.cjs` for idle, busy, offline, missing-label, fork, missing-token and API-failure cases. Run actionlint against changed workflows. These checks do not replace real hosted/local builds or an outage exercise.
