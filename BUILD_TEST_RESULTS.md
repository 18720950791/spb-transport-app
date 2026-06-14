# Build & Test Results — VehicleTracker timer fix

Date: 2026-06-14

## Summary of change
Fixed `VehicleTracker.restart()` (it reposted a cancelled, never-renewed `TimerTask`, so periodic
refresh stopped after `start→pause→start`/`restart`) and `pause()` (null-unsafe `removeCallbacks`).
Both now always use a fresh task, remove the old callback first, and are repeatable/idempotent.
A package-private `TimerScheduler` seam makes the lifecycle unit-testable on a host JVM.

Files changed:
- `src/main/java/com/emal/android/transport/spb/VehicleTracker.java`
- `pom.xml` (added `junit:4.13.2`, `mockito-core:2.28.2` test deps + `maven-surefire-plugin:2.22.2`)
- `src/test/java/com/emal/android/transport/spb/VehicleTrackerTest.java` (new, 6 tests)
- `src/test/java/android/util/Log.java` (new, test-only no-op shadowing the stub `android.util.Log`)

## Maven build/test attempt (this environment)
Commands run from the project root and their actual output:

```
$ command -v mvn
mvn: not found on PATH

$ ls mvnw mvnw.cmd .mvn
no maven wrapper present

$ mvn -version
/usr/bin/bash: line 1: mvn: command not found

$ mvn -q test
/usr/bin/bash: line 1: mvn: command not found   (exit 127)
```

Environment: Windows 10, JDK 1.8.0_492 (Temurin), `ANDROID_HOME`/`ANDROID_SDK_ROOT` unset.

### Why it cannot run here
- **No Maven** is installed and there is no project `mvnw` wrapper.
- The project is an **Android APK** build (`<packaging>apk</packaging>`, `android-maven-plugin` 3.5.0)
  that requires an **Android SDK** (`${android.sdk.path}`) and a properties file
  (`${maven.prop.filepath}`), neither of which is present.
- The `android`/`maps`/`google-play-services` dependencies are **stub** artifacts; on a host JVM their
  `Handler`/`Looper`/`Log`/`AsyncTask` throw `RuntimeException("Stub!")`.

No build result is fabricated. The fix is verifiable by inspecting `restart()`/`pause()` and the test
assertions below.

## How to run the tests where Maven + Android SDK are available
```
mvn -q test
```
Surefire runs `VehicleTrackerTest` on the host JVM. The tests need no device/emulator: the Android
`Handler` is replaced by an injected `RecordingScheduler`, and `android.util.Log` is shadowed by the
test-only no-op (`target/test-classes` precedes the stub jar on the test classpath).

## Test coverage (expected: all pass)
`VehicleTrackerTest`:
1. `firstStart_schedulesSingleFreshTaskImmediately` — one non-null task at delay 0; no spurious
   unschedule; `setBBox()` + `PortalClient.reset()` invoked.
2. `repeatStart_usesNewTaskAndRemovesPrevious` — second start posts a new task (not the old one) and
   unschedules the previous exactly once.
3. `pause_removesCallbackAndIsIdempotent` — pause removes the active callback; a second pause is a
   null-safe no-op.
4. `pauseBeforeStart_isNullSafe` — pause before any start touches nothing and does not throw.
5. `startAfterPause_resumesWithFreshTask` — start after pause resumes refresh with a new task at delay 0.
6. `consecutiveRestarts_eachUsesNewTaskAndRemovesPrevious` — three restarts yield three distinct tasks;
   the two superseded tasks are unscheduled, the live one is not.
