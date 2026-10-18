# Boot 4 compatibility migration

The Java audit on Boot 3.5.16 still reported two fixable Spring MVC entries.
This candidate moves to Boot 4.0.8, whose managed Framework version is 7.0.9,
without suppressing either finding. CI must establish compatibility and rescan
the packaged JAR before this candidate can merge.

MVC and Flyway use Boot 4's dedicated starters. The MockMvc annotation follows
its new module package. Java remains 17. H2 stays at 2.3.232 to avoid combining
the framework change with a file-database upgrade. Released migrations are
unchanged.

The deprecated Jackson 2 compatibility module deliberately preserves the
existing JSON mapper, including rejection of unknown request properties.
Its BOM remains pinned to 2.21.7. Moving to Jackson 3 is separate follow-up work;
this bridge must not become an indefinite dependency. Tomcat now follows the
Boot 4 BOM rather than the incompatible Tomcat 10 override.

Required checks are both database suites, default and secured packaged HTTP
workflows, process restarts, key replacement, PostgreSQL restore, browser
workflows and the unchanged executable-JAR audit. Local Maven could not resolve
the uncached Boot 4 parent offline; no local compilation result is claimed.

References:
- [Boot 4 migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- [Managed versions](https://docs.spring.io/spring-boot/4.0/appendix/dependency-versions/coordinates.html)
- [Jackson compatibility](https://docs.spring.io/spring-boot/4.0/reference/features/json.html)
