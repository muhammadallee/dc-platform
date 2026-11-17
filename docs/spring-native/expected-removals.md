# Expected removals

Plan §6 step 6.
- **What it compares:** at Phase 11, japicmp runs against the `pre-spring-native` baseline in `~/.m2-native-baseline`.
  Every break it reports must appear here, and any unlisted break is a finding. This also covers HI-0081: the gate has no
  published release to compare against.
- **How it is kept:** each phase adds its lines when it removes something. The list starts with what the plan already decides.

## Whole modules (Phase 1, scope cut; ND2)

Every public type in these artifacts is removed with the artifact:
- `platform-audit-api`, `-spi`, `-autoconfigure`, `-jdbc`, `-log`, `-messaging-autoconfigure`, `platform-starter-audit`;
- `platform-idempotency-api`, `-autoconfigure`, `platform-starter-idempotency`;
- `platform-redis-autoconfigure`, `platform-starter-redis`;
- `platform-ratelimit-api`, `-spi`, `-autoconfigure`, `-inmemory`, `-redis`, `platform-starter-ratelimit`;
- `platform-storage-api`, `-spi`, `-autoconfigure`, `-fs`, `-s3`, `platform-starter-storage-fs`, `platform-starter-storage-s3`;
- `platform-files-api`, `-autoconfigure`, `platform-starter-files`;
- `platform-flags-api`, `-spi`, `-autoconfigure`, `-inmemory`, `-openfeature`, `platform-starter-flags`;
- `platform-messaging-kafka`, `platform-starter-messaging-kafka`;
- `platform-starter-cache-redis`, `platform-locking-redis`, `platform-starter-locking-redis`;
- `platform-tck-audit`, `-flags`, `-idempotency`, `-ratelimit`, `-storage`.

## API types (Phases 2 to 9; subject to each phase's Gate A)

Filled in by each phase from [`phase-00-baseline/removal-map.md`](phase-00-baseline/removal-map.md):

| Phase | Removed | Notes |
|---|---|---|
| 2 | — | |
| 3 | — | |
| 4 | — | |
| 5 | — | |
| 7 | — | |
| 8 | — | |
| 9 | — | |
