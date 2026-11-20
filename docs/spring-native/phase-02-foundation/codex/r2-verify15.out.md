**ACCEPTED** for the code gate as a whole—round-1 finding 8 and round-2 P2-1/P3-1—within the documented contract.

No remaining escape found. In-memory probes confirmed that:

- Prefix/postfix `++` and `--`, including parenthesized targets, report the required writes.
- Annotation arguments pass while their whole-code literals remain counted; nested field writes remain checked.
- Constructor restrictions, reassigned values, and entry-point checks remain effective.
- The real audited enum passes.

**New defect introduced by `2737a57`: none.**

Validation compared current and parent scanners under JDK 21. JDK 25 execution was blocked by its security-configuration loading error. I did not rerun the reported 106-test Maven suite. No repository files changed.
