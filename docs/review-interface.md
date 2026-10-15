# Local review workspace

The review screen makes the existing reconciliation API usable without writing a
review request by hand. It browses batch summaries, compares immutable left/right
source records, records review transitions and reads paginated audit history.
It does not upload source files, correct amounts, issue tokens or move money.

## Open it

```sh
./mvnw verify
java -jar target/reconledger-0.1.0-SNAPSHOT-app.jar
# In another terminal, create a synthetic demonstration batch:
python3 scripts/demo.py
```

Open <http://127.0.0.1:8081/review/index.html>. Filter by business date/currency,
choose a batch and reference, then compare the records. `INV-104` illustrates why
two left records of 20.00 are still a duplicate-reference exception against one
right record of 40.00. Resolving that exception records a decision; it does not
change the matching outcome or source evidence.

This first interface is deliberately for the default loopback demo. Its reviewer
name is a self-reported label. The existing secured profile denies these static
routes, even with an otherwise valid API token; it remains an API-only profile.
No bearer-token entry, browser storage, cookie login or authentication rule was
added. Authenticated UI integration and its deployment model need separate work.

## Review and read behavior

- Batch and audit pages show ten rows. Batch filters and the exclusive cursor are
  sent together; refreshing starts from the first page. Audit pages start oldest
  first. Neither is a snapshot or complete synchronization stream.
- Selecting another reference clears the unsaved note. Reloading the current
  result preserves it. Drafts live only in the page; refreshing or closing the tab
  loses them. The UI does not put notes in local storage or URLs.
- A review sends the version actually displayed. A 409 retains the draft and
  disables saving until the user explicitly reloads the result and history.
  Reload does not submit anything. The user must choose a valid transition and
  save again after inspecting the current state.
- A timeout/disconnection can happen after commit. The UI never retries a review
  automatically. It retains the draft, blocks another save and asks the user to
  reload to determine whether the previous decision is already in the history.
  The client fetch has a 15-second abort timer; it does not cancel or roll back a
  server transaction. Review POSTs still have no idempotency key.
- The screen performs one UI operation at a time and disables controls while it
  is pending. A failed history read prevents review submission until reload.
  This prevents mixing pending selections; it is not server-wide concurrency control.
- Amount and cursor JSON number tokens are retained as strings through
  `JSON.parse`'s reviver `context.source`. Amounts are displayed without arithmetic
  or rounding; 64-bit cursors are sent back without conversion through `Number`.
  Browsers lacking that capability receive an explicit error. Review versions are
  bounded Java integers and remain numeric. The API representation is unchanged.
- Server-returned text, including notes and actor labels, is inserted with
  `textContent`. It is never interpreted as HTML. No CDN or runtime JavaScript
  dependency is needed; HTML/CSS/JS are packaged in the executable JAR.

Semantic labels, headings, table captions, visible focus outlines and status/alert
regions support keyboard navigation. After selection/reload/save, focus moves to
the relevant heading. Narrow layouts stack the source tables. This implementation
and automated keyboard checks do not establish screen-reader accessibility conformance.

## Reproduce browser checks

Java 17, Node.js 24 and Python 3 are required for the test runner, but Node/Python
are not required to serve the already packaged interface.

```sh
./mvnw verify
cd browser-tests
npm ci
npx playwright install --with-deps chromium
npm test
npx playwright show-report
```

Playwright starts the actual executable JAR on loopback port 8801 with an isolated
H2 in-memory database and refuses to reuse an existing server. The runner excludes
ambient Spring/Reconciliation configuration and Java startup overrides. It stops
its own process and removes its temporary working directory. Existing databases
are not read or modified. Browser failures retain traces/screenshots in the report;
CI retains the report for 14 days. There are no automatic retries.

Five workflows run at 1440×1000 and 390×844: ten executions, not ten distinct
workflows. They cover keyboard evidence/review, literal markup in notes, a real
two-page competing review, a deliberately dropped response after a real commit,
batch/audit pagination, and exact cursor/amount decoding. The large-cursor check
injects an API response: it does not advance a database sequence toward overflow.
The other workflows use live API/JDBC responses and synthetic fixtures.

The Java suite additionally asserts that unauthenticated and authorized API callers
cannot load the demo UI assets in secured mode. Browser verification here is H2
and resized desktop Chromium; PostgreSQL retains its existing API/package checks.
Firefox/WebKit, physical devices, assistive technology and authenticated browser
workflows remain unverified for ReconLedger.

Implementation references: Spring Boot's [static resources](https://docs.spring.io/spring-boot/3.5/reference/web/servlet.html#web.servlet.spring-mvc.static-content)
and MDN's [JSON parse source context](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/JSON/parse).

Interview exercise: why is incrementing the version and resending a stale draft
unsafe, and why does a failed browser fetch not establish that the review rolled back?

## Observed verification — October 15, 2026

Implementation `bc93780` passed all existing Java and packaged checks in
[run 38759914562](https://github.com/harshaattili-human/reconledger/actions/runs/38759914562),
but eight browser executions stopped at reference selection. The two large-cursor
executions passed. The browser snapshot showed a combobox named `Reference`; the
test's exact-label lookup included the wrapped select's option text and did not
match. Commit `79342397fac334d615757b4e2b576dc20a5872b8` selects controls by their
accessible role/name. It did not skip assertions or change application behavior.

[Run 38760972219](https://github.com/harshaattili-human/reconledger/actions/runs/38760972219)
then passed all ten browser executions (five workflows at two widths), with one
worker and no retries. Desktop and narrow evidence/conflict screenshots were
inspected. The same run passed 64 Java cases with no failures/errors/skips on each
H2 2.3.232 and PostgreSQL 16.15, both packaged demo/body-bound/process-restart and
secured pinned-key workflows, and the PostgreSQL logical restore rehearsal.
The secured asset assertions extend an existing Java case; this remains 64 cases,
not 128 distinct cases across the databases.

Observed browser environment: Chromium 151.0.7922.34, Playwright 1.62.1, Node.js
24.21.0, Temurin Java 17.0.20.1 and Ubuntu 24.04. Browser state was backed by the
packaged application's H2 in-memory database. The
[HTML report](https://github.com/harshaattili-human/reconledger/actions/runs/38760972219/artifacts/11985074226)
expires after 14 days; the commands above reproduce the workflows and screenshots.
No pixel baseline, PostgreSQL browser execution or manual assistive-technology
result is claimed.

Local JavaScript/Python syntax, test discovery, exact source-token decoding,
documentation links and whitespace checks passed. Local Maven lacked the parent
POM cache, so Java/database/browser execution evidence is hosted. A subsequent
copy edit corrects singular source counts and keeps implementation terminology out
of the evidence explanation; it does not alter API or review behavior.
