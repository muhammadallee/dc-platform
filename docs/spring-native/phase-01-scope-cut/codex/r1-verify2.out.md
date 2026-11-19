1. **Finding 2 — ACCEPTED.** All seven live scenario usage lines and six sample command pages use `native-repo.sh`.

2. **Finding 4 — NOT RESOLVED.** The named pages are corrected or bannered, but other live pages still give cut-capability instructions without a “not yet updated” banner:
   - [01-core.md:688](/C:/Users/Ali/AppData/Local/Temp/codex-p1v2/docs/book/chapters/01-core.md:688): recommends the removed `@Idempotent`.
   - [08-data.md:456](/C:/Users/Ali/AppData/Local/Temp/codex-p1v2/docs/book/chapters/08-data.md:456): instructs readers to add security for authenticated auditing; the current default auditor always returns `"system"`.
   - [extension-model.md:23](/C:/Users/Ali/AppData/Local/Temp/codex-p1v2/docs/concepts/extension-model.md:23): provider instructions still require removed storage, authentication, and cache APIs.
   - [observability.md:62](/C:/Users/Ali/AppData/Local/Temp/codex-p1v2/docs/modules/observability.md:62): instructs token-authenticated scraping based on the removed security starter.

3. **New defect 1 — ACCEPTED.** Slug validation rejects traversal before deletion; parent and target symlinks are rejected.

4. **New defect 2 — ACCEPTED.** `&&` and `${repo:?...}` guards prevent the documented commands from launching scenarios after repository-build failure.

New defects introduced by these fixes: **none**.

Read-only verification included Bash syntax checks, eight slug-validation cases, and failure-stub checks for all 13 usage/page command forms. No Maven or Docker builds ran; `docs/llms-full.txt` was excluded.
