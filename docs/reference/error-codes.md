# Error codes

> Generated from `platform-errors-autoconfigure/target/error-codes.csv` (emitted by `ErrorCodeRegistryTest`) at build time. Do not edit by hand.

Every platform error carries a stable `DC-<CAP>-<NNNN>` code. Codes are unique platform-wide (enforced by the phase-04 registry gate) and surface in RFC-9457 problem responses. See [errors](../modules/errors.md).

| Code | Capability | Declared by |
|------|------------|-------------|
| `DC-CORE-0400` | core | `ae.gov.dubaicustoms.platform.errors.autoconfigure.PlatformExceptionHandler` |
| `DC-CORE-0410` | core | `ae.gov.dubaicustoms.platform.errors.autoconfigure.PlatformExceptionHandler` |
| `DC-CORE-0499` | core | `ae.gov.dubaicustoms.platform.errors.autoconfigure.PlatformExceptionHandler` |
| `DC-CORE-0500` | core | `ae.gov.dubaicustoms.platform.errors.autoconfigure.PlatformExceptionHandler` |
| `DC-DATA-0409` | data | `ae.gov.dubaicustoms.platform.errors.autoconfigure.internal.DataAccessFailure$Codes` |
| `DC-DATA-0410` | data | `ae.gov.dubaicustoms.platform.errors.autoconfigure.internal.DataAccessFailure$Codes` |
| `DC-DATA-0503` | data | `ae.gov.dubaicustoms.platform.errors.autoconfigure.internal.DataAccessFailure$Codes` |
| `DC-TEST-0001` | test | `ae.gov.dubaicustoms.platform.errors.autoconfigure.internal.ProblemDetailFactoryTest` |
