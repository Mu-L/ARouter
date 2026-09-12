# Compile-time optimization — 2026-09-12

This change affects the KSP processor, not the Android runtime module or the
Java generation templates. In the paired 128-Activity-route workload, all
134 generated Java files were byte-identical before and after optimization.

Changes:

- Registry origins contain their annotated source files, rather than every
  source and generated injector in the compilation.
- One processing-round context shares source handles and routing ancestry
  analysis between the route and injection processors.
- Analysis stops at known Activity, Service, Fragment, IProvider and Object
  roots. Their implementation ancestry is irrelevant to route classification.
- JVM scalar and array fields avoid provider-inheritance traversal.
- Cached declarations still validate each use site's type arguments. Symbol
  caches are discarded after each round, and unresolved results are not cached.

The initial cache-only trial did not improve performance consistently. The final
version also removes the unnecessary framework ancestry traversal.

## Paired results

Both arms use KSP 2.3.12 with identical application sources and public dependency
hashes, AGP 8.12.0 / Gradle 8.13 / Kotlin 2.3.20 / JDK 17, and the same controlled
cache and in-process Kotlin settings as the [original benchmark](BENCHMARK.md).
Each scenario has two warmups and ten measured samples per arm, alternating order.
The old compiler comes from the original run's frozen Maven repository.

| Scenario | Before median / IQR (s) | After median / IQR (s) | Median paired change |
| --- | ---: | ---: | ---: |
| Warm clean build | 4.991 / 0.158 | 4.808 / 0.394 | -2.9% |
| Kotlin method-body edit | 3.580 / 0.118 | 3.426 / 0.159 | -3.6% |
| Route-path edit | 3.516 / 0.322 | 3.485 / 0.124 | -0.7% |
| Add a route/group | 3.324 / 0.082 | 3.231 / 0.030 | -2.8% |
| Remove a route/group | 4.023 / 0.050 | 3.833 / 0.071 | -4.3% |

These are modest workload-specific improvements. Route-path editing is
effectively close to unchanged relative to its variability. This comparison does
not establish that KSP now outperforms KAPT in every scenario; cold starts and
no-op builds were not remeasured.

The KSP task's median time also decreased in all five scenarios, from
0.639/0.512/0.443/0.427/0.422 seconds to
0.595/0.502/0.425/0.406/0.397 seconds respectively. Task intervals are diagnostic
and are not summed to replace CLI wall time.

## Correctness gates and evidence

- 35 real KSP/JVM regressions passed, including unresolved generic arguments
  after a cache hit and Serializable provider classification.
- The complete AGP 8.12/API 34 verifier passed Debug and Release/R8 navigation,
  injection and interception, existing incremental deletion checks, and four
  additional builds changing an unannotated ancestor and inherited field type.
- The paired benchmark retained 100 measured samples plus 20 warmups, checked
  actual work after each mutation, restored both source trees and compared
  every generated Java file. Private benchmark daemons were stopped.

Local evidence (generated and intentionally untracked):

    build/reports/ksp-benchmark/run-d34rra6j/
    build/reports/ksp-consumer/run.Bq1sEU/

The earlier cache-only trial is retained at
build/reports/ksp-benchmark/run-wz9t9j_m and is not used as final optimization
evidence. Runtime performance itself was not benchmarked; identical generated
code and device tests establish the intended behavior-preservation boundary.
