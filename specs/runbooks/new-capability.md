# Runbook — Adding a New Capability

1. **Decide the shape.** One implementation plausible → api + autoconfigure + starter.
   ≥2 providers plausible → add spi + one LOCAL provider first (inmemory/fs/jdbc), then real providers.
   Never create an SPI with a single conceivable implementation.
2. **Scaffold:** `mvn com.acme.platform:platform-build-maven-plugin:new-module -Dcapability=<cap> -Dkind=api` (repeat per kind).
3. **Write the API** with full javadoc (coding-standards §2). Get it reviewed BEFORE autoconfigure —
   API is the irreversible part.
4. **Autoconfigure** from reference/autoconfigure-pattern.md: properties record, kill switch, back-off,
   customizers, CapabilityDescriptor, imports file.
5. **Starter POM(s)**, one per provider.
6. **Tests:** ContextRunner matrix + behavior tests; provider TCK if multi-provider (copy an existing TCK's shape);
   docker-only tests tagged + assumption-guarded.
7. **Docs:** docs/modules/<cap>.md using the mandatory template sections.
8. **Wire:** platform-bom, root modules, CHANGELOG.
9. **Verify:** module `-am verify`, root `verify`, golden-path script still green.
10. **Error codes:** claim a namespace (`ACME-<CAP>-…`) in the registry (uniqueness test will police it).
