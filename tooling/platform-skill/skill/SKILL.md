---
name: dc-platform
description: Use when building or modifying a service on the DC Platform (the internal Spring Boot chassis). Triggers on "DC platform", capability names (audit, authz, cache, core, data, errors, events, files, flags, idempotency, locking, logging, messaging, observability, openapi, ratelimit, redis, resilience, restclient, scheduling, security, storage, validation, testing), platform-starter-* ids, and dc.platform.* properties. Explains which starter to add, the config keys, and the error codes. Train version 1.0.0-SNAPSHOT.
---

# DC Platform

The DC Platform is a Spring Boot chassis. A service imports one BOM and adds only the starters it needs; every capability is auto-configured with a kill switch under `dc.platform.<cap>.enabled`. Version `1.0.0-SNAPSHOT`.

## How to use

1. Pick the capability for the task (see `resources/capabilities.md`).
2. Add its `platform-starter-*` — never the wrapped library directly (the service parent bans that).
3. Configure via `dc.platform.*` (see `resources/property-cheatsheet.md`).
4. For live, authoritative facts query the platform MCP server (`platform-mcp-server --stdio`).
