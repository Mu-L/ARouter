# KAPT / KSP2 benchmark — 2026-09-12

The measured result is mixed. In this fixture, KSP is about 8% faster for a warm
clean build and route deletion, approximately equal for a no-op build, and
4–12% slower in the other measured scenarios. Migrating ARouter to KSP2 does
not establish a universal build-time improvement.

The application has 128 Activity routes (64 Java and 64 Kotlin), primitive/String
injection, one shared Provider route and one interceptor. Both arms use identical
application source bytes. A fresh API 34 instrumentation test passed for each
backend, covering Java-to-Kotlin navigation, injection, provider lookup and
interception. The emulator was stopped before timing.

| Setting | Value |
| --- | --- |
| Host | macOS 26.6.2, arm64, 12 logical CPUs |
| AGP / Gradle | 8.12.0 / 8.13 |
| Kotlin / language / API | 2.3.20 / 2.3 / 2.3 |
| KSP | 2.3.12 |
| JDK / bytecode | 17.0.20.1 / Java 8 |
| SDK / build tools / min SDK | 36 / 36.0.0 / 21 |
| Gradle heap / workers | 2 GB / 2 |
| Kotlin execution | in-process in both arms; controlled nondefault |
| Configuration cache / build cache | enabled / disabled |
| Dependency resolution during measurement | offline |
| Samples | 2 warmups, then 10 measurements per backend per scenario |
| Order | alternating AB/BA; reverse order for paired route removals |

Public dependencies were provisioned outside timing in an owned Gradle home,
then cloned into an immutable seed. Each arm had its own Gradle home and daemon.
Runtime, Kotlin compiler and registration-plugin coordinates and hashes matched
between arms. The selected local ARouter repository was frozen for the run.

## CLI wall time

IQR is the interquartile range. The percentage compares the median of paired
KSP/KAPT ratios, so it need not equal the ratio of the two independent medians.

| Scenario | KAPT median / IQR (s) | KSP median / IQR (s) | Paired KSP change |
| --- | ---: | ---: | ---: |
| Fresh process, clean outputs | 16.743 / 0.264 | 17.522 / 0.196 | +4.4% |
| Warm process, clean outputs | 4.864 / 0.295 | 4.424 / 0.377 | -8.1% |
| No source changes | 0.443 / 0.007 | 0.444 / 0.009 | +0.3% |
| Kotlin method-body change | 3.051 / 0.296 | 3.424 / 0.095 | +11.5% |
| Route-path change | 3.318 / 0.296 | 3.448 / 0.027 | +5.4% |
| Add a Fragment route/group | 3.109 / 0.086 | 3.224 / 0.231 | +4.0% |
| Delete that route/group | 4.208 / 0.231 | 3.816 / 0.174 | -8.2% |

“Fresh process” means a new owned Gradle daemon with cleaned project outputs.
Dependency and operating-system caches were warm. Clean commands, daemon stops,
dependency preparation, compiler publication, device tests and correctness
oracles were excluded from the timer.

For method-body changes, KAPT regenerated stubs in all 10 measured samples but
its annotation-processing task was UP-TO-DATE in all 10. KSP processing executed
in all 10. This is direct task-level evidence relevant to that workload; it does
not identify every contributor to wall time. Task intervals can overlap and
were not summed to manufacture a build duration.

Every sample passed fresh-metrics, source-parity and task-state checks.
Post-timing checks verified changed method bytecode, route-path replacement,
and addition/deletion of generated route groups. No failed, stale or
build-cache-restored result was admitted. All sources returned to their initial
state, and private daemon cleanup completed.

## Reproduction and evidence

See the [fixture and measurement protocol](../gradle/ksp-benchmark-fixture/README.md).
The full command, after staging current artifacts and booting one API 34 emulator,
is:

    python3 gradle/benchmark-ksp.py --routes 128 --iterations 10 --warmups 2 +      --device-tests --expected-api 34 --stop-emulator-after-preflight

The actual run reused the immutable dependency seed from a completed smoke run.
This affects unmeasured provisioning only. The complete run retained 140 measured
samples and 28 warmups under:

    build/reports/ksp-benchmark/run-jpgp2lpw/

Raw evidence is local generated output and is not committed. Hashes:

- samples.json: 01fc3113bc3eb0a3a5cd3d744e7b8e4c2afb52c40080e833412c70b1f0fad880
- summary.json: 238100a9110c3374bd5b2c370e5f0b04ef170dd5677ebd68bf30d65c7f39f812
- run.json: b36ea7731be60294a09b555d685b6b7f2e5e1f0d9e597463f77bdbad46be8948

The run contains command logs, task events, effective compiler options, dependency
and source hashes, paired source digests, device reports and cleanup status.
These measurements apply to this single-module synthetic workload and these
settings. Larger projects, additional processors, different cache policies and
ordinary Kotlin-daemon execution require their own measurements.
