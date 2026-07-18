# Reference — Per-Module Definition of Done

- [ ] Correct category & artifactId suffix; correct parent; listed in root `<modules>` **and** `platform-bom`.
- [ ] Dependencies satisfy the constitution (enforcer green); no `<version>` tags; 3rd-party types absent from api/spi signatures.
- [ ] Packages follow convention; `package-info.java` with overview javadoc on public/spi packages; internals under `.internal`.
- [ ] Javadoc/comment policy applied (coding-standards §2), incl. autoconfigure header block.
- [ ] Properties: record + metadata + descriptions + kill switch; additional-metadata for env-post-processor keys.
- [ ] Tests: unit + ContextRunner matrix (autoconfigure) or TCK (provider impl); async via Awaitility; docker-only tests tagged.
- [ ] Local execution: module tests pass with no Docker/network/credentials.
- [ ] ArchConstitutionTest present and green; coverage ≥80%; checkstyle green.
- [ ] docs/modules/<cap>.md created/updated with the mandatory sections.
- [ ] CHANGELOG `## [Unreleased]` entry; conventional commit(s).
- [ ] `mvn -T1C verify` green at root.
