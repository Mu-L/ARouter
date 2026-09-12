#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/.." && pwd)"
local_repository="${repo_root}/build/localMaven"
run_device_tests="${AROUTER_RUN_DEVICE_TESTS:-false}"
agp_version="${AROUTER_AGP_VERSION:-9.3.2}"
case "${agp_version}" in
    8.12.0)
        gradle_version=8.13
        wrapper_properties="${script_dir}/ksp-fixture/gradle-wrapper-8.13.properties"
        ;;
    9.0.0)
        gradle_version=9.1.0
        wrapper_properties="${script_dir}/ksp-fixture/gradle-wrapper-9.1.properties"
        ;;
    9.3.2)
        gradle_version=9.5.0
        wrapper_properties="${repo_root}/arouter-compiler-ksp/gradle/wrapper/gradle-wrapper.properties"
        ;;
    *) echo "Unsupported KSP matrix AGP version: ${agp_version}" >&2; exit 1 ;;
esac
case "${run_device_tests}" in
    true|false) ;;
    *) echo "AROUTER_RUN_DEVICE_TESTS must be true or false." >&2; exit 1 ;;
esac

read_version() {
    awk -F= '$1 == "VERSION_NAME" { print $2; exit }' "$1"
}
annotation_version="$(read_version "${repo_root}/arouter-annotation/gradle.properties")"
api_version="$(read_version "${repo_root}/arouter-api/gradle.properties")"
compiler_version="$(read_version "${repo_root}/arouter-compiler/gradle.properties")"
register_version="$(read_version "${repo_root}/arouter-gradle-plugin/gradle.properties")"
ksp_compiler_version="$(read_version "${repo_root}/arouter-compiler-ksp/gradle.properties")"
for artifact in \
    "arouter-annotation/${annotation_version}/arouter-annotation-${annotation_version}.jar" \
    "arouter-api/${api_version}/arouter-api-${api_version}.aar" \
    "arouter-compiler/${compiler_version}/arouter-compiler-${compiler_version}.jar" \
    "arouter-register/${register_version}/arouter-register-${register_version}.jar"; do
    if [[ ! -s "${local_repository}/com/alibaba/${artifact}" ]]; then
        echo "Missing local ARouter artifact: ${artifact}" >&2
        echo "First run the four legacy installLocally tasks using JDK 8." >&2
        exit 1
    fi
done

java_command="${JAVA_HOME:+${JAVA_HOME}/bin/}java"
java_major="$("${java_command}" -XshowSettings:properties -version 2>&1 |
    awk -F'= ' '/java.specification.version =/ { print $2; exit }')"
if [[ "${java_major}" != 17 ]]; then
    echo "Set JAVA_HOME to JDK 17 for the pinned KSP build and Android fixture." >&2
    exit 1
fi
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ ! -d "${sdk_root}/platforms/android-36" ]]; then
    echo "Set ANDROID_SDK_ROOT or ANDROID_HOME to an SDK with Platform 36 and Build Tools 36.0.0." >&2
    exit 1
fi
export ANDROID_SDK_ROOT="${sdk_root}"
export PATH="${JAVA_HOME:+${JAVA_HOME}/bin:}${PATH}"

if [[ "${run_device_tests}" == true ]]; then
    adb="${sdk_root}/platform-tools/adb"
    device_list="$("${adb}" devices)"
    serial="$(awk 'NR > 1 && $2 == "device" { print $1 }' <<< "${device_list}")"
    if [[ "$(awk 'NR > 1 && NF { count++ } END { print count+0 }' <<< "${device_list}")" != 1 \
            || "${serial}" != emulator-* ]]; then
        echo "Connect exactly one booted emulator for the KSP consumer tests." >&2
        exit 1
    fi
    if [[ -n "${ANDROID_SERIAL:-}" && "${ANDROID_SERIAL}" != "${serial}" ]]; then
        echo "ANDROID_SERIAL does not match the KSP test emulator." >&2
        exit 1
    fi
    export ANDROID_SERIAL="${serial}"
    if [[ "$("${adb}" shell getprop sys.boot_completed | tr -d '\r')" != 1 ]]; then
        echo "The KSP test emulator has not finished booting." >&2
        exit 1
    fi
    api_level="$("${adb}" shell getprop ro.build.version.sdk | tr -d '\r')"
    expected_api="${AROUTER_EXPECT_API:-34}"
    if [[ ! "${expected_api}" =~ ^[0-9]+$ || "${expected_api}" -lt 21 \
            || "${api_level}" != "${expected_api}" ]]; then
        echo "KSP consumer expected API ${expected_api}, found API ${api_level}." >&2
        exit 1
    fi
fi

report_root="${repo_root}/build/reports/ksp-consumer"
mkdir -p "${report_root}"
test_root="$(mktemp -d "${report_root}/run.XXXXXX")"
echo "KSP verification artifacts: ${test_root}"
trap 'echo "Preserved KSP verification artifacts at ${test_root}."' EXIT
project_dir="${test_root}/project"
mkdir -p "${project_dir}"
rsync -a --exclude '.gradle/' --exclude 'build/' "${script_dir}/ksp-fixture/" "${project_dir}/"
wrapper_dir="${test_root}/wrapper/gradle/wrapper"
mkdir -p "${wrapper_dir}"
cp "${repo_root}/gradle/wrapper/gradle-wrapper.jar" "${wrapper_dir}/gradle-wrapper.jar"
cp "${wrapper_properties}" "${wrapper_dir}/gradle-wrapper.properties"
if ! grep -Eq '^distributionSha256Sum=[0-9a-f]{64}$' "${wrapper_dir}/gradle-wrapper.properties"; then
    echo "The selected KSP matrix wrapper must pin an exact SHA-256 checksum." >&2
    exit 1
fi
wrapper_command=("${java_command}" -Dorg.gradle.appname=gradlew
    -classpath "${wrapper_dir}/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain)
"${wrapper_command[@]}" --version 2>&1 | tee "${test_root}/gradle-version.log"
grep -Fx "Gradle ${gradle_version}" "${test_root}/gradle-version.log"
printf 'agp=%s\ngradle=%s\nexpected_api=%s\nactual_api=%s\nserial=%s\n' \
    "${agp_version}" "${gradle_version}" "${expected_api:-not-requested}" \
    "${api_level:-not-requested}" "${serial:-not-requested}" > "${test_root}/matrix.properties"

compiler_command=("${repo_root}/arouter-compiler-ksp/gradlew"
    -p "${repo_root}/arouter-compiler-ksp" --no-daemon --console=plain --stacktrace)
fixture_command=("${wrapper_command[@]}"
    -p "${project_dir}" --no-daemon --console=plain --stacktrace
    --configuration-cache --configuration-cache-problems=fail
    "-Parouter.repository=${local_repository}"
    "-Parouter.agp.version=${agp_version}"
    "-Parouter.api.version=${api_version}"
    "-Parouter.compiler.version=${compiler_version}"
    "-Parouter.register.version=${register_version}"
    "-Parouter.ksp.compiler.version=${ksp_compiler_version}")
if [[ -n "${AROUTER_GRADLE_INIT_SCRIPT:-}" ]]; then
    compiler_command+=(--init-script "${AROUTER_GRADLE_INIT_SCRIPT}")
    fixture_command+=(--init-script "${AROUTER_GRADLE_INIT_SCRIPT}")
fi

# Rebuild the processor before consumption: an existing local snapshot is not
# evidence that the checked-out processor generated the application routes.
"${compiler_command[@]}" test installLocally 2>&1 | tee "${test_root}/compiler.log"
# Preserve the exact inputs before any consumer build. A later compiler edit
# must never cause an earlier matrix row to be attributed to the newer source.
input_artifacts="${test_root}/input-artifacts"
mkdir -p "${input_artifacts}"
cp "${repo_root}/arouter-compiler-ksp/build/libs/arouter-compiler-ksp-${ksp_compiler_version}.jar" \
    "${input_artifacts}/arouter-compiler-ksp.jar"
cp "${repo_root}/arouter-compiler-ksp/build/libs/arouter-compiler-ksp-${ksp_compiler_version}-sources.jar" \
    "${input_artifacts}/arouter-compiler-ksp-sources.jar"
cp "${local_repository}/com/alibaba/arouter-api/${api_version}/arouter-api-${api_version}.aar" \
    "${input_artifacts}/arouter-api.aar"
cp "${local_repository}/com/alibaba/arouter-annotation/${annotation_version}/arouter-annotation-${annotation_version}.jar" \
    "${input_artifacts}/arouter-annotation.jar"
cp "${local_repository}/com/alibaba/arouter-compiler/${compiler_version}/arouter-compiler-${compiler_version}.jar" \
    "${input_artifacts}/arouter-compiler.jar"
cp "${local_repository}/com/alibaba/arouter-register/${register_version}/arouter-register-${register_version}.jar" \
    "${input_artifacts}/arouter-register.jar"
cp "${repo_root}/arouter-compiler-ksp/build/publications/mavenJava/pom-default.xml" \
    "${input_artifacts}/arouter-compiler-ksp.pom"
shasum -a 256 "${input_artifacts}"/* > "${test_root}/input-artifacts.sha256"
"${fixture_command[@]}" verifyToolchain 2>&1 | tee "${test_root}/toolchain.log"
cp "${project_dir}/build/reports/toolchain.json" "${test_root}/toolchain.json"
"${fixture_command[@]}" :app:assembleDebug :app:assembleRelease 2>&1 | tee "${test_root}/first-build.log"
grep -F 'Configuration cache entry stored.' "${test_root}/first-build.log"
"${fixture_command[@]}" :app:assembleDebug :app:assembleRelease 2>&1 | tee "${test_root}/second-build.log"
grep -F 'Configuration cache entry reused.' "${test_root}/second-build.log"

verify_registrations() {
    local variant="$1"
    local transformed_jar=""
    local candidate
    while IFS= read -r candidate; do
        if jar tf "${candidate}" | grep -Fx 'com/alibaba/android/arouter/core/LogisticsCenter.class' >/dev/null; then
            transformed_jar="${candidate}"
            break
        fi
    done < <(find "${project_dir}/app/build/intermediates/classes/${variant}" -type f -name '*.jar' | sort)
    if [[ -z "${transformed_jar}" ]]; then
        echo "Missing transformed LogisticsCenter for ${variant}." >&2
        exit 1
    fi
    local actual
    actual="$(javap -classpath "${transformed_jar}" -c -p com.alibaba.android.arouter.core.LogisticsCenter |
        awk '/^  private static void loadRouterMap\(\);$/ { capture = 1; next }
            capture && /^  (public|protected|private) / { exit }
            capture { print }' |
        grep -F '// String com.alibaba.android.arouter.routes.ARouter$$' |
        sed 's/^.*\/\/ String //')"
    local expected
    expected="$(printf '%s\n' \
        'com.alibaba.android.arouter.routes.ARouter$$Interceptors$$aptfeature' \
        'com.alibaba.android.arouter.routes.ARouter$$Interceptors$$kspapp' \
        'com.alibaba.android.arouter.routes.ARouter$$Providers$$aptfeature' \
        'com.alibaba.android.arouter.routes.ARouter$$Providers$$arouterapi' \
        'com.alibaba.android.arouter.routes.ARouter$$Providers$$kspapp' \
        'com.alibaba.android.arouter.routes.ARouter$$Root$$aptfeature' \
        'com.alibaba.android.arouter.routes.ARouter$$Root$$arouterapi' \
        'com.alibaba.android.arouter.routes.ARouter$$Root$$kspapp')"
    if [[ "${actual}" != "${expected}" ]]; then
        echo "Unexpected KAPT/KSP route registrations for ${variant}:" >&2
        echo "${actual}" >&2
        exit 1
    fi
}
verify_registrations debug
verify_registrations release

# A real source edit must update both variant tables without retaining the old
# path. The fixture copy is isolated; the checked-in sample is never modified.
perl -pi -e 's#/ksp/java"#/ksp/java-updated"#g' \
    "${project_dir}/app/src/main/java/com/alibaba/android/arouter/kspfixture/JavaActivity.java" \
    "${project_dir}/app/src/main/java/com/alibaba/android/arouter/kspfixture/ProbeActivity.java"
"${fixture_command[@]}" :app:assembleDebug :app:assembleRelease 2>&1 | tee "${test_root}/incremental-build.log"
grep -F 'Configuration cache entry reused.' "${test_root}/incremental-build.log"
for variant in debug release; do
    generated_group="${project_dir}/app/build/generated/ksp/${variant}/java/com/alibaba/android/arouter/routes/ARouter\$\$Group\$\$ksp.java"
    test -s "${generated_group}"
    grep -F '"/ksp/java-updated"' "${generated_group}"
    if grep -Fq '"/ksp/java"' "${generated_group}"; then
        echo "Stale route remains in ${variant} after source edit." >&2
        exit 1
    fi
    verify_registrations "${variant}"
    test -s "${project_dir}/app/build/outputs/apk/${variant}/app-${variant}.apk"
done
test -s "${project_dir}/app/build/outputs/mapping/release/mapping.txt"

# Aggregating outputs must account for files added after the first build and
# remove the last route in a group. Preserve the removed input as test evidence.
added_source="${project_dir}/app/src/main/kotlin/com/alibaba/android/arouter/kspfixture/AddedFragment.kt"
cat > "${added_source}" <<'KOTLIN'
package com.alibaba.android.arouter.kspfixture

@com.alibaba.android.arouter.facade.annotation.Route(path = "/addition/fragment")
class AddedFragment : androidx.fragment.app.Fragment()
KOTLIN
"${fixture_command[@]}" :app:assembleDebug :app:assembleRelease 2>&1 | tee "${test_root}/added-route-build.log"
grep -F 'Configuration cache entry reused.' "${test_root}/added-route-build.log"
for variant in debug release; do
    generated_dir="${project_dir}/app/build/generated/ksp/${variant}/java/com/alibaba/android/arouter/routes"
    grep -F '"/addition/fragment"' "${generated_dir}/ARouter\$\$Group\$\$addition.java"
    grep -F '"addition"' "${generated_dir}/ARouter\$\$Root\$\$kspapp.java"
done
mv "${added_source}" "${test_root}/removed-AddedFragment.kt"
"${fixture_command[@]}" :app:assembleDebug :app:assembleRelease 2>&1 | tee "${test_root}/removed-route-build.log"
grep -F 'Configuration cache entry reused.' "${test_root}/removed-route-build.log"
for variant in debug release; do
    generated_dir="${project_dir}/app/build/generated/ksp/${variant}/java/com/alibaba/android/arouter/routes"
    test -s "${generated_dir}/ARouter\$\$Root\$\$kspapp.java"
    if [[ -e "${generated_dir}/ARouter\$\$Group\$\$addition.java" ]] \
            || grep -Fq '"addition"' "${generated_dir}/ARouter\$\$Root\$\$kspapp.java"; then
        echo "Deleted route group remains in ${variant} outputs." >&2
        exit 1
    fi
    verify_registrations "${variant}"
done

# Remove every route annotation while keeping classes referenced by the app in
# place. The processor must replace its last aggregate maps with empty maps.
saved_sources="${test_root}/annotated-sources"
annotation_count=0
while IFS= read -r -d '' route_source; do
    if ! grep -Eq '^[[:space:]]*@Route\(' "${route_source}"; then continue; fi
    relative_source="${route_source#${project_dir}/}"
    saved_source="${saved_sources}/${relative_source}"
    mkdir -p "$(dirname "${saved_source}")"
    cp -p "${route_source}" "${saved_source}"
    sed '/^[[:space:]]*@Route[(]/d' "${route_source}" > "${route_source}.no-routes"
    mv "${route_source}.no-routes" "${route_source}"
    annotation_count=$((annotation_count + 1))
done < <(find "${project_dir}/app/src/main" -type f \( -name '*.java' -o -name '*.kt' \) -print0)
if [[ "${annotation_count}" != 7 ]]; then
    echo "Expected to remove all seven KSP fixture route annotations, found ${annotation_count}." >&2
    exit 1
fi
"${fixture_command[@]}" :app:assembleDebug :app:assembleRelease 2>&1 | tee "${test_root}/empty-module-build.log"
grep -F 'Configuration cache entry reused.' "${test_root}/empty-module-build.log"
for variant in debug release; do
    generated_dir="${project_dir}/app/build/generated/ksp/${variant}/java/com/alibaba/android/arouter/routes"
    for table in Root Providers; do
        generated_table="${generated_dir}/ARouter\$\$${table}\$\$kspapp.java"
        test -s "${generated_table}"
        if grep -Eq '(routes|providers)\.put\(' "${generated_table}"; then
            echo "Stale ${table} entry remains after deleting the module's final route (${variant})." >&2
            exit 1
        fi
    done
    if find "${generated_dir}" -type f -name 'ARouter$$Group$$*.java' | grep . >/dev/null; then
        echo "Stale KSP group sources remain in the empty module (${variant})." >&2
        exit 1
    fi
    verify_registrations "${variant}"
done
# Restore exact bytes before device checks, including the deliberate path edit.
rsync -a "${saved_sources}/" "${project_dir}/"
while IFS= read -r -d '' saved_source; do
    cmp "${saved_source}" "${project_dir}/${saved_source#${saved_sources}/}"
done < <(find "${saved_sources}" -type f -print0)
"${fixture_command[@]}" :app:assembleDebug :app:assembleRelease 2>&1 | tee "${test_root}/restored-routes-build.log"
grep -F 'Configuration cache entry reused.' "${test_root}/restored-routes-build.log"
for variant in debug release; do
    generated_dir="${project_dir}/app/build/generated/ksp/${variant}/java/com/alibaba/android/arouter/routes"
    grep -F '"/ksp/java-updated"' "${generated_dir}/ARouter\$\$Group\$\$ksp.java"
    grep -F '"ksp"' "${generated_dir}/ARouter\$\$Root\$\$kspapp.java"
    grep -F '"com.alibaba.android.arouter.kspfixture.KspService"' "${generated_dir}/ARouter\$\$Providers\$\$kspapp.java"
    verify_registrations "${variant}"
done

# A newly injected target must get its own helper; removing the source must
# remove that helper from KSP output and the next Java compilation.
injected_source="${project_dir}/app/src/main/java/com/alibaba/android/arouter/kspfixture/IncrementalTarget.java"
cat > "${injected_source}" <<'JAVA'
package com.alibaba.android.arouter.kspfixture;
public final class IncrementalTarget {
    @com.alibaba.android.arouter.facade.annotation.Autowired public KspService provider;
}
JAVA
"${fixture_command[@]}" :app:assembleDebug :app:assembleRelease 2>&1 | tee "${test_root}/added-helper-build.log"
for variant in debug release; do
    test -s "${project_dir}/app/build/generated/ksp/${variant}/java/com/alibaba/android/arouter/kspfixture/IncrementalTarget\$\$ARouter\$\$Autowired.java"
done
mv "${injected_source}" "${test_root}/removed-IncrementalTarget.java"
"${fixture_command[@]}" :app:assembleDebug :app:assembleRelease 2>&1 | tee "${test_root}/removed-helper-build.log"
for variant in debug release; do
    if find "${project_dir}/app/build/generated/ksp/${variant}" -type f \
            -name 'IncrementalTarget$$ARouter$$Autowired.java' | grep . >/dev/null; then
        echo "Deleted injection helper remains in ${variant} KSP output." >&2
        exit 1
    fi
    while IFS= read -r candidate; do
        if jar tf "${candidate}" | grep -F 'IncrementalTarget$$ARouter$$Autowired.class' >/dev/null; then
            echo "Deleted injection helper remains in ${variant} bytecode." >&2
            exit 1
        fi
    done < <(find "${project_dir}/app/build/intermediates/classes/${variant}" -type f -name '*.jar')
done

# Delete all KSP interceptor annotations but leave concrete classes referenced
# by the fixture in place. The aggregate interceptor registry must become empty.
saved_interceptors="${test_root}/annotated-interceptors"
interceptor_count=0
while IFS= read -r -d '' interceptor_source; do
    if ! grep -Eq '^[[:space:]]*@Interceptor\(' "${interceptor_source}"; then continue; fi
    relative_source="${interceptor_source#${project_dir}/}"
    saved_source="${saved_interceptors}/${relative_source}"
    mkdir -p "$(dirname "${saved_source}")"
    cp -p "${interceptor_source}" "${saved_source}"
    sed '/^[[:space:]]*@Interceptor[(]/d' "${interceptor_source}" > "${interceptor_source}.no-interceptors"
    mv "${interceptor_source}.no-interceptors" "${interceptor_source}"
    interceptor_count=$((interceptor_count + 1))
done < <(find "${project_dir}/app/src/main" -type f \( -name '*.java' -o -name '*.kt' \) -print0)
if [[ "${interceptor_count}" != 2 ]]; then
    echo "Expected two KSP interceptors, found ${interceptor_count}." >&2
    exit 1
fi
"${fixture_command[@]}" :app:assembleDebug :app:assembleRelease 2>&1 | tee "${test_root}/empty-interceptors-build.log"
for variant in debug release; do
    registry="${project_dir}/app/build/generated/ksp/${variant}/java/com/alibaba/android/arouter/routes/ARouter\$\$Interceptors\$\$kspapp.java"
    test -s "${registry}"
    if grep -Fq 'interceptors.put(' "${registry}"; then
        echo "Stale interceptor entry remains after deleting final annotation (${variant})." >&2
        exit 1
    fi
done
rsync -a "${saved_interceptors}/" "${project_dir}/"
while IFS= read -r -d '' saved_source; do
    cmp "${saved_source}" "${project_dir}/${saved_source#${saved_interceptors}/}"
done < <(find "${saved_interceptors}" -type f -print0)
"${fixture_command[@]}" :app:assembleDebug :app:assembleRelease 2>&1 | tee "${test_root}/restored-interceptors-build.log"
for variant in debug release; do
    registry="${project_dir}/app/build/generated/ksp/${variant}/java/com/alibaba/android/arouter/routes/ARouter\$\$Interceptors\$\$kspapp.java"
    grep -F 'interceptors.put(-10,' "${registry}"
    grep -F 'interceptors.put(10,' "${registry}"
    verify_registrations "${variant}"
done

# An unannotated ancestor change must invalidate every dependent route even
# when its analysis was reused within a compiler round.
dependency_dir="${project_dir}/app/src/main/java/com/alibaba/android/arouter/kspfixture/dependency"
mkdir -p "${dependency_dir}"
for source_name in Parent Base First Second; do
    test ! -e "${dependency_dir}/${source_name}.java"
done
cat > "${dependency_dir}/Parent.java" <<'JAVA'
package com.alibaba.android.arouter.kspfixture.dependency;
public class Parent extends androidx.fragment.app.Fragment {}
JAVA
cat > "${dependency_dir}/Base.java" <<'JAVA'
package com.alibaba.android.arouter.kspfixture.dependency;
public class Base extends Parent {
    @com.alibaba.android.arouter.facade.annotation.Autowired public int sharedData = 1;
}
JAVA
for source_name in First Second; do
    cat > "${dependency_dir}/${source_name}.java" <<JAVA
package com.alibaba.android.arouter.kspfixture.dependency;
@com.alibaba.android.arouter.facade.annotation.Route(path="/dependency/${source_name}")
public class ${source_name} extends Base {}
JAVA
done
"${fixture_command[@]}" :app:assembleDebug 2>&1 | tee "${test_root}/dependency-initial-build.log"
dependency_group="${project_dir}/app/build/generated/ksp/debug/java/com/alibaba/android/arouter/routes/ARouter\$\$Group\$\$dependency.java"
test "$(grep -c 'RouteType.FRAGMENT' "${dependency_group}")" = 2
test "$(grep -c 'put("sharedData", 3)' "${dependency_group}")" = 2
perl -pi -e 's/androidx.fragment.app.Fragment/android.app.Activity/' "${dependency_dir}/Parent.java"
"${fixture_command[@]}" :app:assembleDebug 2>&1 | tee "${test_root}/dependency-parent-build.log"
test "$(grep -c 'RouteType.ACTIVITY' "${dependency_group}")" = 2
dependency_helper="${project_dir}/app/build/generated/ksp/debug/java/com/alibaba/android/arouter/kspfixture/dependency/Base\$\$ARouter\$\$Autowired.java"
grep -F 'substitute.getIntent()' "${dependency_helper}"
perl -pi -e 's/public int sharedData = 1/public String sharedData = "updated"/' "${dependency_dir}/Base.java"
"${fixture_command[@]}" :app:assembleDebug 2>&1 | tee "${test_root}/dependency-field-build.log"
test "$(grep -c 'put("sharedData", 8)' "${dependency_group}")" = 2
mkdir -p "${test_root}/dependency-sources"
for source_name in Parent Base First Second; do
    mv "${dependency_dir}/${source_name}.java" "${test_root}/dependency-sources/"
done
"${fixture_command[@]}" :app:assembleDebug 2>&1 | tee "${test_root}/dependency-removed-build.log"
test ! -e "${dependency_group}"
test ! -e "${dependency_helper}"
verify_registrations debug

"${fixture_command[@]}" :app:dependencies --configuration debugRuntimeClasspath 2>&1 |
    tee "${test_root}/runtime-dependencies.log"
if grep -E 'com.google.devtools.ksp:|com.alibaba:arouter-compiler|com.squareup:javapoet|com.google.code.gson:gson' \
        "${test_root}/runtime-dependencies.log"; then
    echo "Compiler dependencies leaked onto the consumer runtime classpath." >&2
    exit 1
fi

if [[ "${run_device_tests}" == true ]]; then
    for test_type in debug release; do
        if [[ "${test_type}" == debug ]]; then test_variant=Debug; else test_variant=Release; fi
        marker="$(mktemp "${test_root}/device-${test_type}.XXXXXX")"
        "${fixture_command[@]}" --no-configuration-cache \
            "-Parouter.test.build.type=${test_type}" ":app:connected${test_variant}AndroidTest" 2>&1 |
            tee "${test_root}/device-${test_type}.log"
        report_count=0
        while IFS= read -r -d '' report; do
            if ! grep -Eq '<testsuite .*tests="[1-9][0-9]*"' "${report}" \
                    || grep -Eq ' (failures|errors|skipped)="[1-9][0-9]*"' "${report}"; then
                echo "Empty, failed, or skipped KSP device tests in ${report}." >&2
                exit 1
            fi
            report_count=$((report_count + 1))
        done < <(find "${project_dir}/app/build/outputs/androidTest-results/connected" \
            -type f -name 'TEST-*.xml' -newer "${marker}" -print0)
        if [[ "${report_count}" != 1 ]]; then
            echo "Expected one fresh ${test_type} device report, found ${report_count}." >&2
            exit 1
        fi
    done
fi

echo "KSP 2.3.12 / Kotlin 2.3.20 / AGP ${agp_version} / Gradle ${gradle_version} consumer verification passed."
