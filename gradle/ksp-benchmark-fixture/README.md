# Controlled KAPT / KSP benchmark

This fixture compares the current ARouter processors with identical generated
application sources. It uses AGP 8.12.0, Gradle 8.13, Kotlin 2.3.20, KSP 2.3.12,
JDK 17 and Java 8 bytecode. Kotlin and KAPT stub language/API versions are
explicitly 2.3, using the modern K2 KAPT implementation.

The default workload has 128 Activity routes, half Java and half Kotlin, with
primitive/String injection, a shared provider and an interceptor. It stays within
the feature intersection of the two processors. An instrumentation test checks
Java-to-Kotlin navigation, injection, provider lookup and interception.

After staging the current ARouter artifacts and running the AGP 8.12 verifier
once to obtain Gradle 8.13, set JAVA_HOME to JDK 17 and ANDROID_SDK_ROOT:

    python3 gradle/benchmark-ksp.py --routes 4 --iterations 2 --warmups 1

For a full run with exactly one booted API 34 emulator:

    python3 gradle/benchmark-ksp.py --device-tests --expected-api 34 +      --stop-emulator-after-preflight

The stop flag explicitly stops that selected emulator after correctness checks,
before timing. Omit it if the emulator must remain running, and account for its
background load when interpreting results. Device checks and dependency
provisioning are excluded from timings.

The driver discovers the cached Gradle 8.13 executable. Use --gradle to supply an
explicit executable. A previous completed run's immutable public dependency seed
can be reused without copying the user's global cache:

    python3 gradle/benchmark-ksp.py --seed-from build/reports/ksp-benchmark/run-EXAMPLE +      --device-tests --stop-emulator-after-preflight

Each run owns separate Gradle homes for KAPT and KSP and a frozen copy of the
selected local ARouter artifacts. Dependencies are prepared in an owned preflight
home, then frozen into a read-only seed. Copy-on-write cloning is required by
default; --copy-cache explicitly allows a full copy on other filesystems.
Only daemons in this run's explicit private homes are stopped.

Both arms use in-process Kotlin compilation, two Gradle workers, a 2 GB Gradle
heap, configuration caching and no Gradle build cache. In-process Kotlin is a
controlled nondefault setting that avoids sharing a Kotlin daemon across arms.
Inherited JVM/Gradle option overrides are discarded without logging their values.
Measurements are offline.

For each scenario, the default is two warmups followed by ten measured samples.
Arm order alternates AB/BA. Add and remove operations form a paired cycle.

| Scenario | Work inside the timer |
| --- | --- |
| cold_clean | assembleDebug in a fresh owned Gradle process after unmeasured clean/stop |
| warm_clean | assembleDebug after unmeasured clean, retaining the daemon |
| noop | assembleDebug without source changes |
| body_edit | assembleDebug after a Kotlin method-body change |
| route_edit | assembleDebug after a route-path annotation change |
| add_route / remove_route | assembleDebug after adding/removing one Fragment route |

Cold means process-cold, with warm dependency and operating-system caches.
The driver measures CLI wall time and separately records task intervals through
a configuration-cache-compatible BuildService. Task intervals may overlap and
must not be summed as build wall time.

After each sample, checks outside the timer verify source equality, fresh task
records, backend execution, and the relevant generated route or method bytecode.
Failed, stale, incomplete and build-cache-restored work is rejected.

Results are retained under build/reports/ksp-benchmark. REPORT.md and summary.json
contain medians, IQR and paired KSP/KAPT ratios; samples.json, commands.json,
task-metrics, fingerprints and logs retain the raw evidence. A small fixture or
smoke run does not establish an ecosystem-wide speedup. Remote publication and
deployment are outside this benchmark.
