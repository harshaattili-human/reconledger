# Review workflows across three browser engines

The demo and secured review suites now share six Playwright projects: Chromium,
Firefox and WebKit at 1440x1000 and 390x844. The same six demo workflows and four
secured workflows run against the packaged JAR on an isolated H2 database. Projects
use distinct synthetic business dates so rows from a prior engine cannot change a
later engine's batch counts or selection.

The demo checks keyboard focus/review, exact amounts and cursors, literal note/issuer
rendering, retained drafts after optimistic conflict, uncertain-save inspection and
batch/audit pagination. Secured checks cover verified attribution and tenant
isolation, storage clearing/disconnect/reload, read-only access, rejected credentials,
late-response suppression after a simulated pagehide, and 401/403 save recovery
without automatic retry. Existing route interception still verifies the real decoder
or scope status before forwarding it to the page.

There are 36 demo executions (six workflows × three engines × two widths) and 24
secured executions (four × three × two), not 60 distinct workflows. CI retains the
existing ten Chromium repetitions of the previously intermittent access-rejection
workflow; these are repeats, not additional behavioral coverage. One worker and
zero retries keep failures visible. All engines use desktop browser contexts resized
to the specified widths; this does not establish physical-device, touch, branded
Safari, assistive-technology or full accessibility behavior.

## Reproduce

```sh
./mvnw -DskipTests package
cd browser-tests
npm ci
npx playwright install --with-deps chromium firefox webkit
npm test
npx playwright test --config=playwright.secured.config.js
# Optional original Chromium stability check:
npx playwright test --config=playwright.secured.config.js \
  --grep 'access rejection clears' --project=chromium-desktop \
  --project=chromium-narrow --repeat-each=5 --reporter=line
```

Select a project with `--project=firefox-narrow` or `--project=webkit-desktop` when
investigating a failure. Engine versions are printed from the running browser.
Demo traces and failure screenshots remain enabled. Secured traces, video and
automatic screenshots remain disabled because network artifacts can contain bearer
headers. Only intentional screenshots captured after the token field is cleared
are attached. Do not enable token-bearing traces to diagnose a failure.

The CI HTML report contains assertions and these screenshots and is retained for
14 days. Re-run the commands after artifacts expire. Screenshot inspection is a
layout/visible-state review, not independent review or accessibility certification.
Runtime identity, review, persistence and retry rules are unchanged by the matrix.
Local JavaScript syntax and project discovery are checked where dependencies are
available; actual browser/JAR execution and screenshot evidence are recorded after CI.

Reference: [Playwright projects](https://playwright.dev/docs/test-projects).

Interview exercise: why must a shared-database browser matrix isolate fixtures,
and why do passing resized WebKit checks not prove the experience on an iPhone?

## Hosted evidence — October 28, 2026

Implementation `8e56bcc146110ac70bdc87ef119e6daa1a6adb43` passed all five jobs in
[run 40697093721](https://github.com/harshaattili-human/reconledger/actions/runs/40697093721):
browser 122846657997, H2 122846657901, PostgreSQL 122846658118, restore 122846657768
and audit 122846658104. The browser job passed 36 demo and 24 secured executions,
plus ten Chromium stability repetitions, with one worker and zero retries. These
are ten distinct workflows exercised across contexts, not 70 distinct workflows.

Playwright 1.62.1 ran Chromium 151.0.7922.34, Firefox 153.0 and WebKit 26.5 on
Ubuntu 24.04 with Node 24.22.1 and Java 17.0.21. Eight intentional screenshots were
visually inspected: saved secured reviews and demo conflict drafts for Firefox and
WebKit at both widths. The evidence panels, wrapped identity text, retained notes,
disabled conflict-save controls and reload actions were visible without overlapping
content. No bearer token was visible. Narrow native select labels can truncate;
the selected reference and status are repeated in the evidence panel below.

The [HTML report artifact](https://github.com/harshaattili-human/reconledger/actions/runs/40697093721/artifacts/12820141799)
is retained for 14 days (scheduled expiry November 11, 2026); the commands above
reproduce the workflows after expiry. Its GitHub test-merge checkout was
`104f6eb04b8f3525c577a7d5c28c34aa79959953`. Screenshots are observations of synthetic
fixtures, not a pixel baseline or manual accessibility evaluation.

The same 118 Java cases passed with zero failures/errors/skips on each H2 2.3.232
and PostgreSQL 16.15. Existing default/secured JAR, restart, pinned-key replacement,
HTTPS JWKS, Boot 3 retained-data upgrade and PostgreSQL restore checks also passed.
The dated JAR audit inventoried 73 libraries with zero advisory entries; that is
not a vulnerability-free claim. Existing Java deprecation diagnostics remain.
Local JavaScript syntax and six-project/fixture-date enumeration passed. Local
Playwright discovery could not run because the package was absent from the offline
cache; Maven's Boot parent was likewise uncached. Browser/JAR/database execution
and screenshot evidence therefore came from the hosted run.
