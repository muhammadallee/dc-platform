**NOT RESOLVED** — finding 4 still has unbannered instructions on these live pages:

- [docs/book/chapters/01-core.md:589](C:/Users/Ali/AppData/Local/Temp/codex-p1v3/docs/book/chapters/01-core.md:589), also **773**: recommends the removed events capability for after-commit work and replacing `@Async`.
- [docs/book/chapters/03-logging-observability.md:674](C:/Users/Ali/AppData/Local/Temp/codex-p1v3/docs/book/chapters/03-logging-observability.md:674): prescribes the removed `@SafeText` annotation on single-line fields.
- [docs/book/crosscutting/observability-strategy.md](C:/Users/Ali/AppData/Local/Temp/codex-p1v3/docs/book/crosscutting/observability-strategy.md:229):
  - **229–230, 242–244, 373–374:** configure alerts for removed audit/rate-limit signals and the `@LockedSchedule` warning.
  - **236–239:** instrumentation and alerting instructions for `@LockedSchedule`.
  - **299–305:** an operational query against the removed audit capability.
  - **322, 357, 384:** audit appender/retention instructions.
  - **339, 383:** dashboard and review instructions for cache hit ratios.

Their existing hardening notices do not warn that these capabilities were cut. The four previously named locations are corrected or bannered; the items above remain.

New defects introduced by `e82edcf..a468d31`: **none**.
