# Production readiness — capability hardening

This folder is the record of the capability-hardening initiative defined in
[`plan-v3.md`](plan-v3.md). It certifies seven chassis capabilities (core + errors, logging +
observability, data access, transactional outbox, RabbitMQ messaging, REST client + resilience,
security) and states explicitly what is **not** certified.

## How to read this folder

| File | What it holds |
|---|---|
| [`STATE.md`](STATE.md) | Live execution state: current phase, gate, last commit, blockers, next action. Read first. |
| [`plan-v3.md`](plan-v3.md) | The execution plan (scope, gates, contracts, phase criteria). |
| [`decisions.md`](decisions.md) | Hardening decisions HD1–HD10 and the conventions agreed with the repository owner. |
| [`capability-status.md`](capability-status.md) | Per-capability verdict and evidence level. |
| [`issue-register.md`](issue-register.md) | Every finding: severity, disposition, owner, evidence, fix commit. |
| [`exceptions.md`](exceptions.md) | Human-approved exceptions for Critical/High findings (none yet). |
| `phase-XX-<slug>/` | Per-phase `scope.md`, review rounds, closure, verbatim Codex I/O (`codex/`), trimmed evidence (`evidence/`). |

Decision IDs here are `HD<n>` so they never collide with the platform decision log
([`docs/decisions/decision-log.md`](../decisions/decision-log.md), `D1`–`D88`). Plan text that says
`D5` means `HD5`.

## Tooling

| Tool | Version / invocation |
|---|---|
| JDK | Amazon Corretto 25.0.3 (`C:\Users\Ali\.jdks\amazon-corretto-25.0.3.9.1\jdk25.0.3_9`; the shell default is JDK 21, so every Maven call sets `JAVA_HOME`) |
| Maven | 3.9.9 |
| Docker | Engine 29.9.2 (Docker Desktop, linux/amd64, 8 CPUs, 7.7 GiB) |
| Codex (independent reviewer) | `codex-cli 0.164.0`, model `gpt-6-astra` (provider `openai`) |
| Shell timeout | `BASH_MAX_TIMEOUT_MS=2700000` (45 min) |

### Codex invocation

Every review round runs non-interactively, read-only, from the repository root, with the prompt in a
file and stdout/stderr captured verbatim:

```bash
codex exec --skip-git-repo-check -s read-only - \
  < docs/production-readiness/phase-XX-<slug>/codex/rN-review.prompt.md \
  > docs/production-readiness/phase-XX-<slug>/codex/rN-review.out.md \
  2> docs/production-readiness/phase-XX-<slug>/evidence-raw/rN-review.err
```

Probe (Phase 0): `codex exec --skip-git-repo-check -s read-only "Reply with exactly: CODEX_OK …"` →
exit 0, `CODEX_OK GPT-6`, header `model: gpt-6-astra`.

Independence rule (plan §4.2): the first-pass prompt of every round contains only the phase
`scope.md`, the diff against the phase start commit, and file paths. Claude's conclusions and test
summaries enter only at the rebuttal step.

## Evidence levels

`UNIT` · `SLICE` · `CONTAINER` · `CONSUMER` · `ENVIRONMENT` (plan §5.3). H2 evidence never counts as
PostgreSQL or Oracle certification.
