# Phase 4 — Codex review, round 1

Gate C of the capability recipe (plan §8). Prompts and answers, word for word, are in [`codex/`](codex/).

## The review

- **Reviewed commit:** `98191f2`, against the phase start `e9f9b80`.
- **Prompt and answer:** [`r1-review.prompt.md`](codex/r1-review.prompt.md), [`r1-review.out.md`](codex/r1-review.out.md).
- **Findings:** 7: 4 High, 3 Medium.
  - Findings 1–4 concern HI-0167, the masker and JPA values: complex map keys, Spring Data projections, JDK wrappers
    such as `Optional`, and text shortcuts that ran before the JPA check.
  - Finding 5 is AC17's scan. Finding 6 is AC18's missing nested cases. Finding 7 is AC20's classpath.
- **No finding** against the carry-over table's twenty `CARRIED` rows, the `RETIRED` row, HI-0168 or rule 12.

## What changed in the design

The cost rules of 2026-11-20 apply: narrow the contract rather than handle each case. D142's amendment, the logging
page and scope §2 state the rule:

- **What the masker renders with `toString()`:** JDK value types, character data, a logging library's objects, a
  resource and an application object with no bean properties.
- **Walked:** `Optional` and `Map.Entry`.
- **Named, with no method called:** any other JDK object (`[Type]`), a JDK proxy such as a projection
  (`[proxy Interface]`), and an entity, a Hibernate proxy or an unloaded collection.
- **Opaque first:** that check runs before any text shortcut, on every path.
- **Scalar shortcuts:** they trust a class only when the JDK's own class loader defined it.
- **Map keys:** a key that is not a plain value masks its value.
- **AC20:** the slice proves the defaults, and the golden path proves the starter's own classpath.

## The rounds

| Pass | Commit | Codex's verdict |
|---|---|---|
| Review | `98191f2` | 7 findings |
| Reassessment ([`r1-answer`](codex/r1-answer.out.md)) | `9ad0b15` | accepts every narrowed contract; 1, 4, 5, 6, 7 resolved; 2, 3 partly; 2 new (Medium) |
| Verification ([`r1-verify`](codex/r1-verify.out.md), medium effort) | `769e513` | 2 and new 2 resolved; 3 and new 1 partly; 1 new (Medium) |
| Verification 2 ([`r1-verify2`](codex/r1-verify2.out.md), medium effort) | `b362fe3` | all resolved; **no new issues** |

## Limits accepted

- **Rendered with `toString()`:** an application object with no bean properties, and any `CharSequence` that is not
  opaque.
- **Logback's own rendering:** Logback renders a message argument itself before the platform sees it (HI-0157).
- **XML-only entities:** an entity mapped only in XML is recognised only through its proxy.

Round 1 is **closed** at `b362fe3`.
