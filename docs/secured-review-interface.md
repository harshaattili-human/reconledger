# Authenticated local review

The opt-in review screen uses an already-issued bearer token to browse one trusted
tenant and record reviews as the verified subject. It does not issue tokens,
implement an identity-provider login or let the browser choose tenant ownership.

## Enable explicitly

Configure the [secured profile](authentication.md) with its pinned public key,
issuer and audience, then add `--recon.security.review-ui-enabled=true` to the
executable-JAR command. Open <http://127.0.0.1:8081/review/index.html> on the same
loopback server. The default is false: existing secured installations continue to
deny the three UI assets. Enabling permits only GET of the exact HTML, JavaScript
and stylesheet paths; every data route retains its existing scope and tenant rules.
The default unauthenticated demo continues to work without a token.

Enter a token with `recon:read`; decisions additionally need `recon:review`.
`GET /api/review-session` returns `secured`, `tenant`, `subject`, `issuer` and
`canReview`, with `Cache-Control: no-store`. In secured mode this read requires
`recon:read` even when the UI is disabled. The context comes from the verified
Spring Security principal, not browser decoding. It creates no server session and
is display information, not a replacement for authorization on each API call.
The demo response identifies the reserved demo workspace and has null subject/issuer.

## Credential and state lifecycle

The token is held in a module variable and sent only in same-origin API bearer
headers. The password field is cleared on submission. No token, draft or evidence
is saved to cookies, local/session storage or URL parameters. Requests omit cookies,
disable cache reuse and reject redirects. Text from the API is rendered literally.
The secured review body omits `actor`; the backend records its verified subject and
issuer using the existing atomic review/audit transaction.

Disconnect, reload, page navigation and a 401 clear the token, displayed evidence,
cursors and draft. A connection generation counter rejects late reads from a page
that disconnected while a request was pending. Disconnect is disabled during the
single pending UI operation (the fetch has a 15-second abort timer). Reconnecting
starts from batch browsing; an earlier result cannot be submitted with a new tenant.
Disconnect is local cleanup, not issuer revocation or a guarantee of memory erasure.
Browser extensions, developer tools or script injection could still access a live
token. Do not use real credentials/data or publicly deploy this local prototype.

A read-only token can inspect evidence/history but cannot enable the review form.
The API still rejects a forged POST with 403. A 409 or uncertain save retains the
draft and requires explicit reload, as in the [demo workflow](review-interface.md).
A 401 clears the draft instead: reconnect and inspect state/history before deciding
again. No review is retried automatically, and aborting a browser request does not
roll back a committed transaction. Token expiry is detected on the next API request;
there is no background expiry timer, refresh token, cookie session, external issuer
integration or automatic key rotation.

## Checks

```sh
./mvnw verify
cd browser-tests
npm ci
npx playwright install --with-deps chromium
npm test
npx playwright test --config=playwright.secured.config.js
```

The separate secured runner starts the actual executable JAR on loopback 8802 with
an isolated H2 database, temporary RSA key and synthetic signed tokens. Three
workflows run at 1440×1000 and 390×844 (six executions): verified reviews and tenant
switching; read-only/invalid/expired credentials; and response loss after commit
followed by a real decoder rejection using an expired token substituted in transit.
The last case verifies recovery without a second POST. Existing demo workflows
remain separate. Java HTTP coverage checks session-context scopes and identity on
both configured databases and retains default secured asset denial.

Secured tests disable traces, video and automatic failure screenshots because
network recordings can contain Authorization headers. Only explicit screenshots
after the input has been cleared are attached. The temporary token file is mode
0600, ignored by Git, and removed when the runner stops; it is never an artifact.
These synthetic browser checks do not verify an external identity provider,
physical device, assistive technology, TLS deployment or token theft resistance.

The authorization rules follow Spring Security's [request matching](https://docs.spring.io/spring-security/reference/6.5/servlet/authorization/authorize-http-requests.html)
and [JWT resource-server](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html)
contracts. A caller's asserted identity is not trusted until decoder validation.

Interview exercise: why is hiding a review button insufficient authorization, and
why should reconnecting after a lost save start with inspecting the audit history?
