# Declared starter dependencies

The full build declaration is `pom.xml`. Versions are pinned; no latest-version, snapshot, or range selectors are used.

| Dependency | Version | Scope |
| --- | --- | --- |
| com.google.code.gson:gson | 2.13.2 | JSON runtime, included in executable JAR |
| com.google.errorprone:error_prone_annotations | 2.41.0 | Gson-declared annotation dependency, included by this build |
| org.junit.jupiter:junit-jupiter | 5.12.2 | Tests only; Maven resolves its declared platform/test dependencies |

Maven resources, compiler, surefire, jar, and shade plugins are pinned in the build file. Additional transitive build/test artifacts are resolved from those published POMs and cached before offline builds. Organizers should record the resolved dependency tree for the accepted version. Do not upload a Maven cache.

Gson is maintained at https://github.com/google/gson and distributed under Apache License 2.0. Maven Shade bundles ordinary Java dependencies; it does not hide participant strategy source. https://maven.apache.org/plugins/maven-shade-plugin/

The starter source is organizer-provided material for participants to modify for this competition. The eboard should select an explicit repository license before a public release; this review package does not grant rights to unrelated third-party poker code or data.
