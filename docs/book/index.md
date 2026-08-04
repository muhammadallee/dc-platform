# The DC Platform Book

> **A working guide to the internal Spring Boot chassis** — what every capability does, why it was
> built that way, how to use it well, and how to run it in production.
>
> Platform version `0.2.0-SNAPSHOT` (release train) · Spring Boot 4.x · Java 25

---

## What this book is

The rest of `docs/` is **reference**: [capability pages](../modules/core.md) tell you the starter
coordinates, the property keys, and the bean to override. They are deliberately terse, and they are
the right thing to read when you already know what you are doing.

This book is the other half. It exists to answer the questions reference pages structurally cannot:

- *Why* does this capability exist at all, and what breaks in a fleet of services without it?
- *What Spring Boot mechanism* is underneath — and how do I debug it when it does not fire?
- *How do I operate it* — what do I monitor, what do I alert on, what fails first under load?
- *How do I practise it* until it is muscle memory?

Everything here is traceable to the code. Every Java example tagged `snippet:` in this book is
**compiled against the live platform API on every build** — if an API changes, the example stops
compiling and the build goes red. Examples in this book cannot silently rot.

!!! note "This book does not replace the reference"
    Each chapter links to the capability page it teaches, and to the generated
    [configuration property reference](../reference/properties.md). When a fact belongs in a lookup
    table, the book links to the table rather than copying it — copies drift, links do not.

## Who it is for

The book serves two audiences in every chapter, and marks which is which.

**Application developers** consuming the chassis. You write business logic in a service generated
from the archetype, and you want the platform to carry the cross-cutting weight. Your sections are
*Introduction & Business Value*, *Core Concepts*, *Feature Reference*, *How-to Guide*, and
*Exercises*. You do not need to be a Spring expert — the [primer](primer/01-spring-boot.md) gives you
everything the rest of the book assumes.

**Platform and DevOps engineers** who operate, extend, and evolve the chassis. You need to know what
a capability does under failure, what it costs, what knobs exist, and how to add a provider without
forking. Your sections are *Operations & Management*, *Deep Dive*, and the
[cross-cutting chapters](crosscutting/security-model.md).

!!! tip "Read the other audience's sections anyway"
    The most expensive incidents in a platform of this shape come from a developer who did not know
    what an operator would see, or an operator who did not know what a default was protecting. The
    sections are short. Read both.

## How a chapter is built

Every capability chapter follows the same eight-section shape, so you can navigate any chapter once
you have read one:

| Section | Audience | What it gives you |
|---|---|---|
| **1. Introduction & Business Value** | Everyone | The problem this solves, and what a fleet looks like without it |
| **2. Core Concepts & Underlying Principles** | Developers | The Spring Boot / Spring Cloud / industry concepts underneath — the *why* |
| **3. Feature Reference** | Everyone | Complete coverage: every feature, property, annotation, and extension point |
| **4. How-to Guide** | Developers | Step-by-step recipes, from the happy path to advanced usage |
| **5. Operations & Management** | Operators | Configure, monitor, troubleshoot, scale, secure |
| **6. Deep Dive** | Both | Internal architecture, performance, extension points, common pitfalls |
| **7. Exercises & Labs** | Developers | Progressive hands-on labs with expected outcomes and hints |
| **8. Checklist / Quick Reference** | Everyone | One screen, for daily use |

Read sections 1–4 to become productive. Read 5–6 to become dangerous. Do 7 to make it stick.

### Callout conventions

!!! note
    Context or clarification. Safe to skim on a second read.

!!! tip
    A shortcut, a diagnostic trick, or a non-obvious use of a feature.

!!! success "Best practice"
    The thing to do by default. If you deviate, know why.

!!! warning
    A real trap — something that compiles, boots, and is wrong. Do not skim these.

## Learning paths

Three routes through the book. Pick the one that matches where you are, not where you want to be.

### Path 1 — Beginner: "I have to ship a service next sprint"

You have never used the chassis, and Spring Boot auto-configuration is roughly magic to you.

1. [Primer 1 — Spring Boot foundations](primer/01-spring-boot.md) — auto-configuration, starters,
   conditions, `@ConfigurationProperties`, profiles, Actuator. **Do not skip this.** The rest of the
   book assumes it.
2. [Primer 2 — Microservices foundations](primer/02-microservices.md) — the fleet-level problems the
   chassis exists to solve.
3. [Chassis overview](overview.md) — the architecture and all 24 capabilities in one sitting.
4. [Quickstart](../quickstart.md) — generate a service from the archetype and run it. Ten minutes.
5. Chapters [1 (Core)](chapters/01-core.md), [2 (Errors & Validation)](chapters/02-errors-validation.md),
   [3 (Logging & Observability)](chapters/03-logging-observability.md) — the request lifecycle. Every
   service has these whether it asks for them or not.
6. Chapter [5 (Security & Authorization)](chapters/05-security-authz.md) — because your service is
   authenticated by default and you need to know what that means.
7. Chapter [14 (Testing & Developer Experience)](chapters/14-testing-dx.md) — the test slices, so you
   stop booting full contexts.
8. Do the **basic** lab in each chapter you read.

**You are done when** you can generate a service, add a capability starter, write a test with a
platform slice, and explain what `/actuator/platform` is telling you.

### Path 2 — Intermediate: "I am building something real"

You have shipped a service on the chassis. Now you are integrating.

1. Chapter [4 (OpenAPI)](chapters/04-openapi.md) and
   [6 (REST Client & Resilience)](chapters/06-restclient-resilience.md) — your service's contract, and
   what happens when the service you call is having a bad day.
2. Chapter [7 (Messaging & Events)](chapters/07-messaging-events.md) — publishing and handling
   integration events, retry, and the DLQ.
3. Chapter [8 (Data & Persistence)](chapters/08-data.md) and
   [9 (Caching & Redis)](chapters/09-cache-redis.md).
4. Chapter [10 (Coordination)](chapters/10-coordination.md) — locking, scheduling, idempotency. The
   three capabilities that separate a service that works from a service that works when you run three
   of it.
5. Chapters [11 (Storage & Files)](chapters/11-storage-files.md),
   [12 (Audit)](chapters/12-audit.md), [13 (Rate Limiting & Feature Flags)](chapters/13-ratelimit-flags.md)
   as your requirements demand.
6. Cross-cutting: [Patterns and anti-patterns](crosscutting/patterns.md) and
   [Local development vs production](crosscutting/local-vs-production.md).
7. Do the **intermediate** labs.

**You are done when** you can reason about your service's behaviour under partial failure without
reading platform source.

### Path 3 — Advanced: "I operate or extend the platform"

You are on the platform team, or you are the person your team sends when it breaks.

1. [Chassis overview](overview.md), then the *Deep Dive* and *Operations* sections of every chapter —
   in any order, driven by what you run.
2. Cross-cutting, all five: [Patterns and anti-patterns](crosscutting/patterns.md) ·
   [Security model](crosscutting/security-model.md) ·
   [Observability strategy](crosscutting/observability-strategy.md) ·
   [Local vs production](crosscutting/local-vs-production.md) ·
   [Extending the chassis](crosscutting/extending.md).
3. The source of truth beneath the book: [architecture](../concepts/architecture.md) ·
   [dependency constitution](../concepts/constitution.md) ·
   [extension model](../concepts/extension-model.md) · the
   [decision records](../decisions/decision-log.md).
4. [Appendix C — Troubleshooting cookbook](appendix/c-troubleshooting.md), which is written for you.
5. Do the **advanced** labs — several of them are "add a provider" and "certify it against the TCK".

**You are done when** you can add a capability provider without touching a platform module, and
prove it with the capability's TCK.

## Conventions used throughout

- **Property keys** are always fully qualified: `dc.platform.<capability>.<key>`. Every capability has
  a kill switch at `dc.platform.<capability>.enabled`, defaulting to `true`.
- **Starter coordinates** omit `<version>` — the [platform BOM](../reference/bom.md) manages it. If
  you are writing a version number for a platform artifact, something is wrong.
- **Java examples** tagged `snippet:` are complete, compiled units. Untagged Java blocks are
  deliberately partial — fragments, elisions, and undeclared domain types — and are there to be read,
  not pasted.
- **Diagrams** are ASCII, inside fenced blocks. They render identically in the terminal, in your IDE,
  on the docs site, and in a diff.
- **Labs** always state a starting point that already exists: the
  [archetype](../quickstart.md) or one of the four [example services](../examples.md). No lab asks you
  to build scaffolding.

## Where to go when this book is wrong

The book is authored; the code is the truth. Three generated references are regenerated from the
build on every run and cannot drift:

- [Configuration properties](../reference/properties.md) — every `dc.platform.*` key with its type,
  default, and description, straight from the binding metadata.
- [Platform BOM](../reference/bom.md) — every artifact on the train.
- [Error codes](../reference/error-codes.md) — the registry.

If a chapter disagrees with one of those, the generated file wins — and the chapter is a bug. The
full capability inventory with rationale lives in
[the feature catalog](../reference/feature-catalog.md).

## Start reading

- Never used Spring Boot in anger → [Primer 1 — Spring Boot foundations](primer/01-spring-boot.md)
- Comfortable with Boot, new to the chassis → [Chassis overview](overview.md)
- Here to fix something right now → [Appendix C — Troubleshooting cookbook](appendix/c-troubleshooting.md)
