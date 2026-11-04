# Release decision

**Recommendation: GO for `1.0.0-RC1` to first adopters, under the conditions below. NO-GO for general production use until
the parked outbox issues are fixed and OpenShift is verified.** The decision is the owner's.

Final code `639e9d5` (2026-11-04). Evidence: [`final-release-assessment.md`](final-release-assessment.md),
[`phase-08-final/run-results.md`](phase-08-final/run-results.md).

## Why GO for a release candidate

- All seven capabilities pass their consumer runs on the final dependency set, alone and together in one app (63/63), with the
  golden path at 9/9 and 1,557 reactor tests passing, none skipped.
- A service built on `platform-service-parent`, the archetype's route, ships no known vulnerability after D133 (it had 7
  Critical and 15 High on Boot 4.1.0). Other routes must follow the compatibility appendix to get the same versions.
- Builds are reproducible for every published platform artifact, and the published BOM pins literal versions.
- HI-0088 was reviewed again as agreed (D125) and stays accepted (D134).

## Why not general production yet

| Gate | Status |
|---|---|
| Parked issues HI-0071, HI-0079, HI-0086 | The owner set them as "fix before production use" (2026-11-01) |
| OpenShift (Phase 8 step 7) | `BLOCKED`: the chart tool's details were not supplied (D134) |
| Oracle RAC | `BLOCKED`: no environment (HD4) |
| SBOM and provenance in the release pipeline (HI-0108) | Not yet; before the first real release (D134) |

## Conditions for RC adopters

See the assessment's first-adopter conditions: the service parent, a single-instance Oracle, avoiding the parked outbox paths,
an identity provider configured, and OpenShift verified before production there.

## To release

1. Close HI-0108 (SBOM and provenance in `release.yml`; `release.sh` without `-T1C`, or with a configurable thread count).
2. `tooling/scripts/release.sh 1.0.0-RC1 --rehearse`, then the real run, per [`docs/runbooks/release.md`](../runbooks/release.md).

`hardening/v1` is merged to `main` (PR #2) on completion of Phase 8; the release itself remains the owner's decision.
