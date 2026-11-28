# Business dates are calendar values

Batch creation, detail reads, idempotent replay and exact-date browsing now keep
`LocalDate` across the JDBC boundary. A business date has no time of day or timezone.
Converting it through the legacy `java.sql.Date` representation can change it when
the JVM default zone skips a civil day, or around the Gregorian calendar cutover.

A standalone Java 21.0.13.1 probe on November 28, 2026 reproduced these conversions:

| JVM zone | Input LocalDate | Date.valueOf(input).toLocalDate() |
| --- | --- | --- |
| UTC | 1582-10-10 | 1582-10-20 |
| UTC | 2011-12-30 | 2011-12-30 |
| Pacific/Apia | 1582-10-10 | 1582-10-20 |
| Pacific/Apia | 2011-12-30 | 2011-12-31 |

This probe establishes the legacy conversion hazard, not a database test result.
Reproduce it in a Java shell or small main method using
`TimeZone.setDefault(TimeZone.getTimeZone(zone))`, then
`java.sql.Date.valueOf(LocalDate.parse(date)).toLocalDate()`.

## Change

The insert already passes a `LocalDate`. The browse filter now does the same through
`PreparedStatement.setObject`, and both batch-header and summary readers use
`ResultSet.getObject("business_date", LocalDate.class)`. The [pgJDBC documentation](https://jdbc.postgresql.org/documentation/query/#using-java-8-date-and-time-classes)
describes the JDBC 4.2 DATE/LocalDate mapping. H2 and PostgreSQL behavior is checked
by the same integration suite rather than assuming driver equivalence.

SQL predicates, tenant scope, cursor ordering, idempotency fingerprints and released
migrations are unchanged. Stored SQL DATE values are not rewritten. This fixes how
calendar values are bound/read; it does not recover data altered outside this API,
add a fiscal calendar, or promise support for every extreme LocalDate year.
Timestamp fields continue to represent instants independently of business dates.

## Regression checks

Two API integration cases each exercise three synthetic dates: October 4/10/15,
1582 in UTC and December 29/30/31, 2011 in Pacific/Apia. Each checks creation,
stored SQL DATE, detail, idempotent replay and list filters with and without currency.
Reads repeat after changing the JVM default to UTC; `finally` restores the original
zone. These tests mutate process-wide timezone and rely on the suite's sequential
JUnit execution. They must not be run concurrently with timezone-sensitive tests.

```sh
./mvnw -Dtest=ReconciliationApiTest test
./mvnw verify
```

Run the same suite with the PostgreSQL test properties documented in
[the PostgreSQL guide](postgresql.md). Hosted results are recorded in the pull
request. Local Maven execution was blocked by an uncached Spring Boot 4.0.8 parent
POM; only the standalone Java conversion probe and static checks ran locally.

Optional exercise: why can a date filter fail even when the database stores the
correct date, and why is setting the server timezone to UTC insufficient to solve
all legacy calendar conversions?

Initial hosted run [46154845464](https://github.com/harshaattili-human/reconledger/actions/runs/46154845464)
failed test compilation: passing the generic JDBC query result directly into
AssertJ left overload resolution ambiguous. The fixture now assigns the result to
an explicit LocalDate before asserting it; the runtime change was not altered.
