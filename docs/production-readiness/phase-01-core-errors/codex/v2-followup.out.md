**ACCEPT** — [PlatformBannerRunner.java:44](C:/Users/Ali/AppData/Local/Temp/hd/codex-v2b/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformBannerRunner.java:44) restores the original `PlatformBannerAutoConfiguration` logger category, preserving consumer logging configuration. No runtime regression found in this diff.

**[P3] Documentation regression** — [CHANGELOG.md:38](C:/Users/Ali/AppData/Local/Temp/hd/codex-v2b/CHANGELOG.md:38): “still runs it once per request” overstates suppression. The filter explicitly enables ASYNC and ERROR dispatches and can run again on subsequent dispatches. Describe nested-dispatch suppression without promising one invocation across the entire request.

Source review only; tests were not rerun.
