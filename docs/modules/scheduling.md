# Scheduling

Spring's `@Scheduled` on a **virtual-thread** scheduler, plus `@LockedSchedule` — a marker that makes
a scheduled job run on a single instance across the cluster.

## What you get

- **Virtual-thread `TaskScheduler`** — each firing runs on a virtual thread, so a blocking scheduled
  job never starves a small platform thread pool. `@EnableScheduling` is on by default.
- **`@LockedSchedule(name, atMost)`** — put it next to `@Scheduled` and the platform runs the method
  under `LockManager.withLock(name, atMost, …)`. An instance that can't acquire the lock skips that
  firing, so the job runs once cluster-wide. Without a `LockManager` bean the method runs unlocked and
  the platform warns once at startup — combine with a locking starter to enforce the lock.

## Starter coordinates

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-scheduling</artifactId>
</dependency>
```

For cluster-wide single execution, also add a locking starter (`platform-starter-locking-jdbc` or
`platform-starter-locking-redis`). No `aspectjweaver` is needed — the `@LockedSchedule` advisor uses
the plain Spring AOP auto-proxy creator.

## Usage

```java
@Scheduled(cron = "0 0 2 * * *")
@LockedSchedule(name = "nightly-reconcile", atMost = "5m")
public void reconcile() {
    ...
}
```

`atMost` is a duration string (`5m`, `PT30S`). Set it above the method's worst-case runtime so a slow
run isn't pre-empted, but low enough that a crashed holder frees the lock promptly.

## Properties

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.scheduling.enabled` | `true` | Kill switch for the whole capability. |

## Replace / Disable

- Define your own `TaskScheduler` bean to replace the platform virtual-thread scheduler (it backs off).
- `dc.platform.scheduling.enabled=false` switches the capability off wholesale.

## Local dev notes

No Docker. Single-execution across two nodes is proven by a two-context test sharing one H2 lock
table; the `@LockedSchedule` advisor uses the plain (non-AspectJ) auto-proxy creator, so no
`aspectjweaver` is required.
