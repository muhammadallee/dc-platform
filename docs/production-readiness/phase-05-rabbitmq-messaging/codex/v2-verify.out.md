Reviewed `69721ae` and surrounding code. Static review only; no tests executed. **One concrete defect remains before closing runs.**

| Pass-1 finding | Assessment of HEAD |
|---|---|
| 1 — registration/start race | **Right under the eager-singleton contract.** Registration cannot modify routes after freezing, and post-processing no longer creates subscriptions. |
| 2 — registration lock deadlocks | **Right.** Post-processing no longer acquires `lifecycle`; the `routes` monitor is released before collaborator resolution or transport calls. The reported lock cycles are removed. |
| 4 — duplicate singleton suppression | **Right.** Equal destination/type registrations throw, including two beans of the same class. |
| 5 — full-queue return | **Partial.** Both reported branches now log and decrement on failed return, but ordinary retry still bypasses that handling. |
| 6 — DEBUG payload disclosure | **Right.** The throwable log is removed; the replacement records exception class names and a throw location. |

**Remaining defect — Medium: ordinary retry silently drops a message and leaks `inFlight`.**

[SubscriptionDispatcher.java:109](/C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/messaging/platform-messaging-inmemory/src/main/java/ae/gov/dubaicustoms/platform/messaging/inmemory/internal/SubscriptionDispatcher.java:109)

- **Trigger:** With `maxAttempts > 1`, the listener processing A throws an ordinary `RuntimeException`. A publisher fills the queue’s freed slot with B before A’s retry is offered.
- **Consequence:** `queue.offer(message.withAttemptIncremented())` returns `false`, which is ignored. A disappears **without ERROR logging**, and its `inFlight` count remains outstanding. Even after B finishes and the queue empties, subsequent `awaitIdle()` calls time out.
- **Correction:** Pass the incremented message through the existing `requeue(...)` helper.

This branch predates HEAD. The defect is its missing logging/accounting, within the accepted policy allowing full-queue drops with an ERROR.

On `frozen`:

- **Ordinary bean created by `serializer.get()`:** Allowed. Only annotated handler methods enter the freeze check.
- **Handler created while resolving a collaborator inside `start()`:** Rejected, because freezing precedes those resolutions at [EventHandlerRegistrar.java:326](/C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/internal/EventHandlerRegistrar.java:326). This follows the stated contract; a lazy or factory-created handler first materialized there must instead be initialized before registrar startup. Normal eager singletons finish creation before lifecycle startup. [Spring refresh implementation](https://raw.githubusercontent.com/spring-projects/spring-framework/v7.0.7/spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java)
- **Context restart:** Stop/start retains the registered beans and routes, creates a new cancellation latch, and resubscribes. Keeping `frozen` set does not reject existing handlers.
- **`ApplicationContextRunner`:** Normal registrations occur before refresh; each ordinary run creates a fresh context. Creating a new handler inside the post-refresh callback falls under the intentional late-registration rejection. [Boot runner implementation](https://raw.githubusercontent.com/spring-projects/spring-boot/v4.1.0/core/spring-boot-test/src/main/java/org/springframework/boot/test/context/runner/AbstractApplicationContextRunner.java)
- **Child context:** A parent’s post-processor does not process child beans, so its frozen state cannot reject them. A separately configured child registrar has its own state. This does not imply automatic discovery of child handlers by the parent. [Spring post-processor scope](https://docs.spring.io/spring-framework/reference/core/beans/factory-extension.html)

There is **no delivery path reading unfrozen routes**: subscriptions are created only after freezing. Registration itself reads the table while unfrozen under its monitor. Whole-bean scanning is not atomic against arbitrary concurrent manual startup, but normal eager-singleton initialization completes before that startup.

VERDICT: REJECT
