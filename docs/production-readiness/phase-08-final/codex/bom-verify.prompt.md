# Phase 8: verification of the published-BOM change

Static review only. The change is `git show 847bfd0 -- build/`: in `build/platform-bom/pom.xml` and `build/platform-dependencies/pom.xml`, the flatten plugin keeps `flattenMode` `bom` and adds `<pomElements><dependencyManagement>interpolate</dependencyManagement></pomElements>`. Reason: the staged `platform-bom` kept 111 `${revision}` references, resolved by a `revision` property inside it, so a consumer building with `-Drevision` could redirect them.

Observed after a `deploy -Plocal-release -Drevision=1.0.0-RC1`: the staged `platform-bom` has 0 placeholders and 112 literal `1.0.0-RC1` versions; the staged `platform-dependencies` still has `${spring-boot.version}` (on its `spring-boot-dependencies` import) and `${archunit.version}` twice. Both BOMs keep their `<properties>`.

1. Is the `platform-bom` result reliable, or an accident of this build (for instance of `revision` being a CI-friendly property)? Would the `install` the golden path and consumers use produce the same POM?
2. Why does the same configuration leave `platform-dependencies` with references? Is it harmful beyond the case of a consumer passing `-Dspring-boot.version` or `-Darchunit.version`?
3. Can `interpolate` on `dependencyManagement` change what a consumer resolves in any other way (scopes, `type`/`import`, exclusions, the properties block now unused)?

Report concrete defects only, each with file, line, trigger, consequence and severity. End with `VERDICT: ACCEPT` or `VERDICT: REJECT`.
