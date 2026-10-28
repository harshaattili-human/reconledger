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
