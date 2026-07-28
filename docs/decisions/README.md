# Design decisions — how this directory is organized

The platform records decisions at **two levels**. Both are canonical; they differ in scope and
lifespan, and this file is the index that tells you which to read.

## 1. ADRs — `adr-NNN.md` (foundational, durable)

The numbered ADRs capture the **architecture-defining** decisions: the ones that shape what the
platform *is* and would require a major redesign to reverse. They are stable, curated, and few.

| ADR | Decision |
|---|---|
| [ADR-001](adr-001.md) | Parent + BOM split |
| [ADR-002](adr-002.md) | Starter philosophy |
| [ADR-003](adr-003.md) | API vs SPI |
| [ADR-004](adr-004.md) | Auto-configuration as the only wiring mechanism |
| [ADR-005](adr-005.md) | Release-train versioning |
| [ADR-006](adr-006.md) | Module boundaries by capability, layered inside |
| [ADR-007](adr-007.md) | Dependency constitution enforced as code |
| [ADR-008](adr-008.md) | Extension via Spring back-off + SPI, never modification |
| [ADR-009](adr-009.md) | Why not a God Framework |
| [ADR-010](adr-010.md) | Why Convention over Configuration |

**Read the ADRs** when you want to understand *why the platform is shaped the way it is* before
changing a boundary, adding a module category, or proposing a structural change.

## 2. Decision log — `decision-log.md` (running, tactical)

[`decision-log.md`](decision-log.md) is the **chronological implementation journal**: `D1 … Dn`,
grouped by phase. Each entry records a concrete execution choice made while building a module —
which enforcer API, why a plugin goal isn't bound to `verify`, why an example names a table `orders`,
etc. — with the rationale, so the choice isn't silently re-litigated. Entries are append-only.

**Read the decision log** when you hit a "why was this done this way?" question about a *specific*
module, gate, or workaround.

## Relationship

- A decision-log entry may **promote** to an ADR if it turns out to be architecture-defining; when it
  does, the ADR links back to the originating `Dn` and the log entry gains a "→ ADR-NNN" pointer.
- ADRs never contradict the decision log; where a foundational choice was later amended (e.g. the
  Spring Boot 3.x → 4.x / Java 21 → 25 bump, **D6**), the amendment lives in the log and the affected
  ADR/spec carries a dated note pointing at it.

When in doubt: **structural / "what is the platform" → ADRs; tactical / "why this line of build config"
→ decision log.**
