# Boot 4 compatibility migration

The Java audit on Boot 3.5.16 still reported two fixable Spring MVC entries.
This candidate moves to Boot 4.0.8, whose managed Framework version is 7.0.9,
without suppressing either finding. CI must establish compatibility and rescan
the packaged JAR before this candidate can merge.

MVC and Flyway use Boot 4's dedicated starters. The MockMvc annotation follows
its new module package. Java remains 17. H2 stays at 2.3.232 to avoid combining
the framework change with a file-database upgrade. Released migrations are
unchanged.

The first candidate used the deprecated Jackson 2 bridge. All 76 Java cases
passed on H2, but packaged checks rejected its health-response shape. The same
failure persisted after moving to Jackson 3, so it was not a mapper-only issue.
Boot 4 enables liveness/readiness groups by default. This application explicitly
disables those probes to retain its existing public health contract. The secured
HTTP test now checks the exact payload, as the packaged tests already did.
The revised candidate uses Boot's default Jackson 3 mapper and
updates test tree imports and property enumeration. Unknown request properties
remain rejected. Jackson 3.1.7 and Tomcat 11.0.26 replace vulnerable managed
versions identified by the artifact audit. No health assertion is weakened.

Required checks are both database suites, default and secured packaged HTTP
workflows, process restarts, key replacement, PostgreSQL restore, browser
workflows and the unchanged executable-JAR audit. Local Maven could not resolve
the uncached Boot 4 parent offline; no local compilation result is claimed.

References:
- [Boot 4 migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- [Managed versions](https://docs.spring.io/spring-boot/4.0/appendix/dependency-versions/coordinates.html)
- [Jackson compatibility](https://docs.spring.io/spring-boot/4.0/reference/features/json.html)
