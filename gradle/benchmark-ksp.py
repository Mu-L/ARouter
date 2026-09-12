#!/usr/bin/env python3
"""Controlled, local-only KAPT/KSP comparison. Provisioning is never timed."""

import argparse
import ctypes
import datetime
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET

REPO = Path(__file__).resolve().parent.parent
FIXTURE = REPO / "gradle/ksp-benchmark-fixture"
METRICS = REPO / "gradle/ksp-benchmark-metrics.gradle"
SCENARIOS = ("cold_clean", "warm_clean", "noop", "body_edit", "route_edit", "add_route", "remove_route")
BACKENDS = ("kapt", "ksp")
JVM_FLAGS = "-Xmx2g -Dfile.encoding=UTF-8"


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inside(path, parent):
    return os.path.commonpath([str(path.resolve()), str(parent.resolve())]) == str(parent.resolve())


def version(module):
    for line in (REPO / module / "gradle.properties").read_text().splitlines():
        if line.startswith("VERSION_NAME="):
            return line.split("=", 1)[1].strip()
    raise RuntimeError("Missing VERSION_NAME in " + module)


def cached_gradle(explicit):
    if explicit:
        result = Path(explicit).expanduser().resolve()
    else:
        home = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
        candidates = sorted((home / "wrapper/dists/gradle-8.13-bin").glob("*/gradle-8.13/bin/gradle"))
        if len(candidates) != 1:
            raise RuntimeError("Provide --gradle PATH to Gradle 8.13, or first run the AGP 8.12 consumer verifier.")
        result = candidates[0].resolve()
    if not result.is_file() or not os.access(result, os.X_OK):
        raise RuntimeError("Gradle executable is missing or not executable: " + str(result))
    return result


def source_hashes(project):
    result = {}
    for root in ("app/src/main", "app/src/androidTest"):
        for path in sorted((project / root).rglob("*")):
            if path.is_file():
                result[path.relative_to(project).as_posix()] = sha(path)
    return result


def combined_hash(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def iqr(values):
    if len(values) < 2:
        return 0.0
    quartiles = statistics.quantiles(values, n=4, method="inclusive")
    return quartiles[2] - quartiles[0]


class Benchmark:
    def __init__(self, args):
        self.args = args
        self.backends = ("before", "after") if args.baseline_ksp_from else BACKENDS
        self.scenarios = tuple(args.scenarios.split(",")) if args.scenarios else SCENARIOS
        self.gradle = cached_gradle(args.gradle)
        java = os.environ.get("JAVA_HOME")
        sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
        if not java or not (Path(java) / "bin/java").is_file():
            raise RuntimeError("Set JAVA_HOME to JDK 17.")
        if not sdk or not (Path(sdk) / "platforms/android-36").is_dir():
            raise RuntimeError("Set ANDROID_SDK_ROOT to an SDK containing Platform 36 and Build Tools 36.0.0.")
        self.java = Path(java).resolve()
        self.sdk = Path(sdk).resolve()
        root = Path(args.output).expanduser().resolve()
        root.mkdir(parents=True, exist_ok=True)
        self.root = Path(tempfile.mkdtemp(prefix="run-", dir=root))
        self.marker = self.root / ".arouter-benchmark-owned"
        self.marker.write_text(str(uuid.uuid4()) + "\n")
        self.logs = self.root / "logs"
        self.logs.mkdir()
        self.metrics_script = self.root / "input-tools" / METRICS.name
        self.metrics_script.parent.mkdir()
        shutil.copy2(METRICS, self.metrics_script)
        self.projects = {backend: self.root / "projects" / backend for backend in self.backends}
        self.homes = {backend: self.root / "gradle-homes" / backend for backend in self.backends}
        self.seed_home = self.root / "preflight-gradle-home"
        self.ro_cache = self.root / "read-only-dependencies"
        self.owned_homes = [self.seed_home, *self.homes.values()]
        self.commands = []
        self.samples = []
        self.last_invocation = {}
        self.counter = 0
        self.base_sources = None
        self.input_manifest = None
        self.fingerprints = {}
        self.device_reports = []
        self.properties = {
            "arouter.repository": str(REPO / "build/localMaven"),
            "arouter.api.version": version("arouter-api"),
            "arouter.compiler.version": version("arouter-compiler"),
            "arouter.register.version": version("arouter-gradle-plugin"),
            "arouter.ksp.compiler.version": version("arouter-compiler-ksp"),
        }
        self.env = dict(os.environ)
        for key in list(self.env):
            if key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JAVA_OPTS",
                       "GRADLE_OPTS", "GRADLE_USER_HOME", "GRADLE_RO_DEP_CACHE", "KOTLIN_OPTS") \
                    or key.startswith("ORG_GRADLE_PROJECT_"):
                self.env.pop(key)
        self.env.update(JAVA_HOME=str(self.java), ANDROID_SDK_ROOT=str(self.sdk), ANDROID_HOME=str(self.sdk))
        self.env["PATH"] = str(self.java / "bin") + os.pathsep + self.env.get("PATH", "")
        self.record = {
            "status": "initializing", "created_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip(),
            "run_directory": str(self.root), "platform": platform.platform(), "cpu_count": os.cpu_count(),
            "gradle_executable": str(self.gradle), "routes": args.routes, "iterations": args.iterations,
            "warmups": args.warmups, "scenarios": list(self.scenarios),
            "comparison": "KSP before/after" if args.baseline_ksp_from else "KAPT/KSP",
            "arms": list(self.backends),
            "policy": {
                "jvm_flags": JVM_FLAGS, "max_workers": 2, "gradle_build_cache": False,
                "configuration_cache": True, "measured_dependencies": "offline; immutable owned dependency seed",
                "kotlin_execution_strategy": "in-process (both arms; controlled nondefault)",
                "cold_definition": "fresh owned Gradle process and cleaned outputs; dependency and OS caches remain warm",
                "order": "alternate AB/BA by sample; reverse order for paired route removals",
                "excluded": ["dependency provisioning", "compiler publication", "clean", "daemon stop", "device tests"],
                "environment": "inherited JVM/Gradle option overrides and Gradle project environment properties removed",
            },
        }
        self.persist()
        print("Benchmark artifacts:", self.root, flush=True)

    def persist(self):
        write_json(self.root / "run.json", self.record)
        write_json(self.root / "samples.json", self.samples)
        write_json(self.root / "commands.json", self.commands)

    def processor_backend(self, arm):
        return "ksp" if self.args.baseline_ksp_from else arm

    def repository_for(self, arm):
        return self.root / ("baseline-repository" if arm == "before" else "input-repository")

    def home_env(self, home, read_only):
        if not self.marker.is_file() or not inside(home, self.root):
            raise RuntimeError("Refusing to operate on a Gradle home outside this owned run.")
        home.mkdir(parents=True, exist_ok=True)
        env = dict(self.env)
        env["GRADLE_USER_HOME"] = str(home)
        if read_only:
            env["GRADLE_RO_DEP_CACHE"] = str(self.ro_cache)
        return env

    def command(self, backend, home, tasks, offline=True, config_cache=True):
        result = [str(self.gradle), "-p", str(self.projects[backend]), "-g", str(home),
                  "--daemon", "--console=plain", "--max-workers=2", "--no-build-cache",
                  "--configuration-cache" if config_cache else "--no-configuration-cache",
                  "--configuration-cache-problems=fail", "-Dorg.gradle.jvmargs=" + JVM_FLAGS,
                  "-I", str(self.metrics_script), "-Parouter.benchmark.backend=" + self.processor_backend(backend)]
        if offline:
            result.append("--offline")
        for key, value in self.properties.items():
            if key == "arouter.repository":
                value = str(self.repository_for(backend))
            result.append("-P" + key + "=" + value)
        return result + list(tasks)

    def invoke(self, backend, tasks, label, home=None, offline=True, config_cache=True, measured=None):
        home = home or self.homes[backend]
        self.counter += 1
        stem = "%04d-%s-%s" % (self.counter, backend, label)
        log = self.logs / (stem + ".log")
        command = self.command(backend, home, tasks, offline, config_cache)
        env = self.home_env(home, read_only=offline and self.ro_cache.is_dir())
        started_ms = int(time.time() * 1000)
        timed_out = False
        with log.open("wb") as stream:
            start = time.perf_counter_ns()
            try:
                process = subprocess.run(command, cwd=REPO, env=env, stdout=stream, stderr=subprocess.STDOUT,
                                         timeout=self.args.timeout)
                exit_code = process.returncode
            except subprocess.TimeoutExpired:
                timed_out = True
                exit_code = None
            elapsed = time.perf_counter_ns() - start
        entry = {"backend": backend, "label": label, "command": command, "exit_code": exit_code,
                 "timed_out": timed_out,
                 "wall_ns": elapsed, "started_at_ms": started_ms, "log": str(log.relative_to(self.root))}
        self.commands.append(entry)
        self.persist()
        if timed_out:
            raise RuntimeError("Gradle timed out (%s); command and partial output are retained: %s" % (label, log))
        if exit_code:
            raise RuntimeError("Gradle failed (%s); see %s" % (label, log))
        if measured is not None:
            metrics_file = self.projects[backend] / "build/reports/benchmark-tasks.json"
            metrics = read_json(metrics_file)
            invocation = metrics.get("invocationId")
            if not invocation or invocation == self.last_invocation.get(backend):
                raise RuntimeError("Stale task metrics for " + stem)
            if int(metrics.get("closedAt", 0)) < started_ms or metrics_file.stat().st_mtime * 1000 < started_ms:
                raise RuntimeError("Task metrics predate the measured invocation: " + stem)
            self.last_invocation[backend] = invocation
            states = {task["path"]: task for task in metrics["tasks"]}
            frontends = [":app:kaptGenerateStubsDebugKotlin", ":app:kaptDebugKotlin"] if self.processor_backend(backend) == "kapt" \
                else [":app:kspDebugKotlin"]
            required = [":app:assembleDebug", ":app:compileDebugKotlin", *frontends]
            if not all(name in states for name in required):
                raise RuntimeError("Missing backend tasks in measured build: " + stem)
            if any(t.get("outcome") in ("failed", "from-cache") or t.get("fromCache") for t in states.values()):
                raise RuntimeError("Failed or build-cache-restored task in controlled measurement: " + stem)
            scenario = measured["scenario"]
            if scenario in ("cold_clean", "warm_clean"):
                if any(states[name].get("outcome") != "executed" for name in frontends):
                    raise RuntimeError("A clean measurement did not execute its frontend tasks: " + stem)
            if scenario == "noop" and any(not states[name].get("upToDate") for name in frontends):
                raise RuntimeError("No-op measurement unexpectedly reprocessed annotations: " + stem)
            self.verify_measured_work(backend, scenario, states)
            archived = self.root / "task-metrics" / (stem + ".json")
            write_json(archived, metrics)
            source_digest = combined_hash(source_hashes(self.projects[backend]))
            sample = {**measured, "backend": backend, "wall_seconds": elapsed / 1e9,
                      "invocation_id": invocation, "source_digest": source_digest,
                      "metrics": str(archived.relative_to(self.root)), "log": entry["log"],
                      "load_average": list(os.getloadavg()) if hasattr(os, "getloadavg") else None,
                      "frontend_tasks": {name: states[name] for name in frontends}}
            self.samples.append(sample)
            self.persist()
            print("%s %s %02d %s %.3fs" % (scenario, backend, measured["sample_index"],
                  "warmup" if measured["warmup"] else "measured", sample["wall_seconds"]), flush=True)
        return entry

    def verify_measured_work(self, backend, scenario, states):
        """Independent post-timing checks keep stale work out of the sample set."""
        project = self.projects[backend]
        if scenario == "body_edit":
            if states[":app:compileDebugKotlin"].get("outcome") != "executed":
                raise RuntimeError("Body edit did not compile Kotlin.")
            rule = self.input_manifest["mutations"]["body"]
            content = (project / rule["path"]).read_text()
            expected = 1 if rule["alternate"] in content else 0
            relative = Path(rule["path"].split("app/src/main/kotlin/", 1)[1]).with_suffix(".class")
            compiled = project / "app/build/tmp/kotlin-classes/debug" / relative
            bytecode = subprocess.check_output([str(self.java / "bin/javap"), "-c", "-p", str(compiled)],
                                              env=self.env, text=True)
            match = re.search(r"benchmarkBody\(\);\s+Code:\s+0:\s+iconst_([01])\s+1:\s+ireturn", bytecode)
            if not match or int(match.group(1)) != expected:
                raise RuntimeError("Compiled method body did not match the measured mutation.")
        if scenario not in ("route_edit", "add_route", "remove_route"):
            return
        processing = ":app:kaptDebugKotlin" if self.processor_backend(backend) == "kapt" else ":app:kspDebugKotlin"
        if states[processing].get("outcome") != "executed":
            raise RuntimeError("A route mutation did not execute annotation processing.")
        generated = project / "app/build/generated"
        groups = list(generated.rglob("ARouter$$Group$$*.java"))
        roots = list(generated.rglob("ARouter$$Root$$benchmarkapp.java"))
        if len(roots) != 1 or not groups:
            raise RuntimeError("Generated route registries are missing or ambiguous.")
        tables = "\n".join(path.read_text() for path in groups)
        root_text = roots[0].read_text()
        if scenario == "route_edit":
            rule = self.input_manifest["mutations"]["route"]
            content = (project / rule["path"]).read_text()
            desired = rule["alternate"] if rule["alternate"] in content else rule["initial"]
            old = rule["initial"] if desired == rule["alternate"] else rule["alternate"]
            desired_path = re.search(r'path\s*=\s*"([^"]+)"', desired).group(1)
            old_path = re.search(r'path\s*=\s*"([^"]+)"', old).group(1)
            if not re.search(r'atlas\.put\(\s*"' + re.escape(desired_path) + '"', tables) \
                    or re.search(r'atlas\.put\(\s*"' + re.escape(old_path) + '"', tables):
                raise RuntimeError("Route-edit sample retained stale generated route metadata.")
        else:
            path = re.search(r'path\s*=\s*"([^"]+)"', self.input_manifest["mutations"]["addition"]["content"]).group(1)
            group = path.split("/")[1]
            present = bool(re.search(r'atlas\.put\(\s*"' + re.escape(path) + '"', tables))
            root_present = bool(re.search(r'routes\.put\(\s*"' + re.escape(group) + '"', root_text))
            group_files = [p for p in groups if p.name == "ARouter$$Group$$" + group + ".java"]
            expected = scenario == "add_route"
            if present != expected or root_present != expected or bool(group_files) != expected:
                raise RuntimeError("Add/remove sample did not update the generated route and group registries.")

    def freeze_local_repository(self):
        original = REPO / "build/localMaven/com/alibaba"
        frozen = self.root / "input-repository"
        components = ["arouter-annotation", "arouter-api", "arouter-compiler", "arouter-register", "arouter-compiler-ksp"]
        modules = {"arouter-register": "arouter-gradle-plugin"}
        for component in components:
            selected = version(modules.get(component, component))
            source = original / component / selected
            if not source.is_dir():
                raise RuntimeError("Stage the current local ARouter artifacts before benchmarking: " + str(source))
            destination = frozen / "com/alibaba" / component
            destination.mkdir(parents=True)
            shutil.copytree(source, destination / selected)
            for metadata in (original / component).glob("maven-metadata*"):
                if metadata.is_file():
                    shutil.copy2(metadata, destination / metadata.name)
        self.properties["arouter.repository"] = str(frozen)
        self.record["input_artifact_hashes"] = {
            p.relative_to(frozen).as_posix(): sha(p) for p in sorted(frozen.rglob("*")) if p.is_file()
        }
        if self.args.baseline_ksp_from:
            prior = Path(self.args.baseline_ksp_from).expanduser().resolve()
            previous = read_json(prior / "run.json")
            if not (prior / ".arouter-benchmark-owned").is_file() or previous.get("status") != "complete":
                raise RuntimeError("The KSP baseline must be a completed owned benchmark.")
            baseline = self.root / "baseline-repository"
            shutil.copytree(prior / "input-repository", baseline)
            for relative, digest in previous["input_artifact_hashes"].items():
                if sha(baseline / relative) != digest:
                    raise RuntimeError("Baseline artifact changed: " + relative)
            self.record["baseline"] = {"run": str(prior), "artifact_hashes": previous["input_artifact_hashes"]}
        for directory, dirs, files in os.walk(frozen, topdown=False):
            for name in files:
                (Path(directory) / name).chmod(0o444)
            Path(directory).chmod(0o555)
        self.persist()

    def stop_home(self, home):
        if not home.exists():
            return
        env = self.home_env(home, read_only=False)
        log = self.logs / ("stop-" + home.name + "-" + uuid.uuid4().hex[:8] + ".log")
        command = [str(self.gradle), "-g", str(home), "--stop", "--console=plain"]
        with log.open("wb") as stream:
            completed = subprocess.run(command, cwd=REPO, env=env, stdout=stream,
                                       stderr=subprocess.STDOUT, timeout=120)
        if completed.returncode:
            raise RuntimeError("Could not stop this run's private Gradle daemon: " + str(log))

    def snapshot_dependencies(self):
        source = self.seed_home / "caches/modules-2"
        target = self.ro_cache / "modules-2"
        if not source.is_dir():
            raise RuntimeError("Unmeasured preflight did not produce an owned dependency cache.")
        self.ro_cache.mkdir()
        details = self.copy_dependency_tree(source, target, read_only=True)
        self.record["dependency_seed"] = {"source": "owned unmeasured preflight only", **details}
        self.persist()

    def copy_dependency_tree(self, source, target, read_only):
        byte_count = 0
        file_count = 0
        libc = ctypes.CDLL(None, use_errno=True) if sys.platform == "darwin" else None
        if libc:
            libc.clonefile.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_int]
            libc.clonefile.restype = ctypes.c_int
        for directory, dirs, files in os.walk(source):
            if any((Path(directory) / name).is_symlink() for name in dirs):
                raise RuntimeError("Unexpected directory symlink in owned dependency seed.")
            destination = target / Path(directory).relative_to(source)
            destination.mkdir(parents=True, exist_ok=True)
            for name in sorted(files):
                if name.endswith(".lock") or name == "gc.properties":
                    continue
                original = Path(directory) / name
                copied = destination / name
                if original.is_symlink():
                    raise RuntimeError("Unexpected symlink in owned dependency seed: " + str(original))
                if self.args.copy_cache:
                    shutil.copy2(original, copied)
                elif libc:
                    if libc.clonefile(os.fsencode(original), os.fsencode(copied), 0) != 0:
                        raise RuntimeError("APFS clone failed; use --copy-cache to explicitly allow copying bytes: errno="
                                           + str(ctypes.get_errno()))
                else:
                    result = subprocess.run(["cp", "--reflink=always", "--", str(original), str(copied)],
                                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                    if result.returncode:
                        raise RuntimeError("COW snapshot unsupported; use --copy-cache to explicitly allow a full copy.")
                byte_count += original.stat().st_size
                file_count += 1
        # Only the frozen seed is read-only. A cloned preflight home is writable.
        for directory, dirs, files in os.walk(target, topdown=False):
            for name in files:
                (Path(directory) / name).chmod(0o444 if read_only else 0o644)
            Path(directory).chmod(0o555 if read_only else 0o755)
        return {"files": file_count, "logical_bytes": byte_count,
                "copy_mode": "copy" if self.args.copy_cache else "COW"}

    def verify_fingerprint(self, backend):
        source = self.projects[backend] / "build/reports/benchmark-fingerprint.json"
        fingerprint = read_json(source)
        actual_backend = self.processor_backend(backend)
        expected = {"backend": actual_backend, "gradle": "8.13", "agp": "8.12.0",
                    "kotlinGradlePlugin": "2.3.20", "javaSpecificationVersion": "17",
                    "javaTarget": "1.8", "kotlinTarget": "1.8", "languageVersion": "2.3",
                    "apiVersion": "2.3", "executionStrategy": "in-process"}
        for key, value in expected.items():
            if str(fingerprint.get(key)) != value:
                raise RuntimeError("Unexpected benchmark %s for %s: %r" % (key, backend, fingerprint.get(key)))
        if actual_backend == "ksp" and fingerprint.get("kspGradlePlugin") != "2.3.12":
            raise RuntimeError("Unexpected KSP version.")
        if actual_backend == "kapt":
            if fingerprint.get("includeCompileClasspath") is not False:
                raise RuntimeError("KAPT must not discover processors on the compile classpath.")
            stub = fingerprint.get("stubOptions", {})
            if stub.get("languageVersion") != "2.3" or stub.get("apiVersion") != "2.3":
                raise RuntimeError("KAPT stub language/API is not the pinned K2 generation.")
        if not fingerprint.get("processorClasspath"):
            raise RuntimeError("Processor classpath evidence is missing.")
        self.fingerprints[backend] = fingerprint
        write_json(self.root / ("fingerprint-" + backend + ".json"), fingerprint)

    def assert_common_artifacts(self):
        for category in ("runtimeClasspath", "registerClasspath", "compilerClasspath"):
            identities = []
            for backend in self.backends:
                entries = self.fingerprints[backend].get(category)
                if not entries:
                    raise RuntimeError("Missing common artifact evidence: " + category)
                identities.append(sorted((entry["coordinates"], entry["sha256"]) for entry in entries))
            if identities[0] != identities[1]:
                raise RuntimeError("Benchmark arms have different common dependencies: " + category)
        for backend in self.backends:
            module = "arouter-compiler" if self.processor_backend(backend) == "kapt" else "arouter-compiler-ksp"
            entries = self.fingerprints[backend]["processorClasspath"]
            coordinate = "com.alibaba:" + module + ":" + version(module)
            matches = [entry for entry in entries if entry.get("coordinates") == coordinate]
            selected = version(module)
            directory = self.repository_for(backend) / "com/alibaba" / module / selected
            artifact_version = selected
            if selected.endswith("-SNAPSHOT"):
                metadata = ET.parse(directory / "maven-metadata.xml").getroot()
                versions = [item.findtext("value") for item in metadata.findall("./versioning/snapshotVersions/snapshotVersion")
                            if item.findtext("extension") == "jar" and not item.findtext("classifier")]
                if len(versions) != 1:
                    raise RuntimeError("Cannot identify the frozen snapshot compiler.")
                artifact_version = versions[0]
            frozen = directory / (module + "-" + artifact_version + ".jar")
            if len(matches) != 1 or not inside(Path(matches[0]["path"]), self.root) \
                    or matches[0].get("sha256") != sha(frozen):
                raise RuntimeError("The benchmark is not using its frozen ARouter processor: " + backend)

    def device_preflight(self):
        adb = self.sdk / "platform-tools/adb"
        output = subprocess.check_output([str(adb), "devices"], env=self.env, text=True)
        devices = [line.split() for line in output.splitlines()[1:] if line.strip()]
        if len(devices) != 1 or devices[0][1] != "device" or not devices[0][0].startswith("emulator-"):
            raise RuntimeError("Device preflight requires exactly one booted emulator.")
        serial = devices[0][0]
        api = subprocess.check_output([str(adb), "-s", serial, "shell", "getprop", "ro.build.version.sdk"],
                                      env=self.env, text=True).strip()
        if api != str(self.args.expected_api):
            raise RuntimeError("Benchmark emulator API mismatch: " + api)
        self.env["ANDROID_SERIAL"] = serial
        for backend in self.backends:
            before = time.time()
            self.invoke(backend, [":app:connectedDebugAndroidTest"], "device-preflight",
                        home=self.seed_home, offline=False, config_cache=False)
            reports = list((self.projects[backend] / "app/build/outputs/androidTest-results/connected")
                           .rglob("TEST-*.xml"))
            fresh = [p for p in reports if p.stat().st_mtime >= before]
            if len(fresh) != 1:
                raise RuntimeError("Expected one fresh device report for " + backend)
            root = ET.parse(fresh[0]).getroot()
            if int(root.attrib.get("tests", 0)) < 1 or any(int(root.attrib.get(k, 0)) for k in ("failures", "errors", "skipped")):
                raise RuntimeError("Empty or failed device preflight: " + str(fresh[0]))
            destination = self.root / "device-reports" / (backend + ".xml")
            destination.parent.mkdir(exist_ok=True)
            shutil.copy2(fresh[0], destination)
            apk = self.projects[backend] / "app/build/outputs/apk/debug/app-debug.apk"
            self.device_reports.append({"backend": backend, "api": int(api), "tests": int(root.attrib["tests"]),
                                        "report": str(destination.relative_to(self.root)), "report_sha256": sha(destination),
                                        "apk_sha256": sha(apk)})
        self.record["device_preflight"] = self.device_reports
        if self.args.stop_emulator_after_preflight:
            subprocess.run([str(adb), "-s", serial, "emu", "kill"], env=self.env, check=True,
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                if serial not in subprocess.check_output([str(adb), "devices"], env=self.env, text=True):
                    break
                time.sleep(1)
            else:
                raise RuntimeError("Selected emulator did not stop before measurement.")
            self.record["stopped_emulator"] = serial
        self.persist()

    def prepare(self):
        check = subprocess.check_output([str(self.gradle), "-g", str(self.seed_home), "--version"],
                                        env=self.home_env(self.seed_home, False), text=True)
        if "Gradle 8.13\n" not in check:
            raise RuntimeError("Benchmark requires actual Gradle 8.13.")
        (self.root / "gradle-version.txt").write_text(check)
        base = self.root / "base-project"
        shutil.copytree(FIXTURE, base, ignore=shutil.ignore_patterns("__pycache__", "build", ".gradle"))
        subprocess.run([sys.executable, str(FIXTURE / "generate_sources.py"), "--output", str(base),
                        "--routes", str(self.args.routes)], check=True, env=self.env)
        self.input_manifest = read_json(base / "benchmark-inputs.json")
        for backend in self.backends:
            shutil.copytree(base, self.projects[backend])
        self.base_sources = source_hashes(base)
        self.record["base_sources"] = self.base_sources
        self.record["base_source_digest"] = combined_hash(self.base_sources)
        source_files = [Path(__file__).resolve(), METRICS, *sorted(FIXTURE.rglob("*"))]
        self.record["benchmark_code_hashes"] = {str(p.relative_to(REPO)): sha(p) for p in source_files
                                                if p.is_file() and "__pycache__" not in p.parts}
        self.record["status"] = "provisioning"
        self.freeze_local_repository()
        if self.args.recover_preflight_cache:
            prior = Path(self.args.recover_preflight_cache).expanduser().resolve()
            previous = read_json(prior / "run.json")
            old_home = prior / "preflight-gradle-home"
            source = old_home / "caches/modules-2"
            if not (prior / ".arouter-benchmark-owned").is_file() or not source.is_dir() \
                    or previous.get("status") != "failed" or previous.get("daemon_cleanup_errors") != []:
                raise RuntimeError("Cache recovery requires a failed owned preflight with successful daemon cleanup.")
            if read_json(prior / "samples.json"):
                raise RuntimeError("Only unmeasured preflight dependencies can be recovered.")
            check_env = dict(self.env, GRADLE_USER_HOME=str(old_home))
            status = subprocess.check_output([str(self.gradle), "-g", str(old_home), "--status"],
                                             env=check_env, text=True)
            if re.search(r"^\s*\d+\s+(?:IDLE|BUSY|CANCELED|STOPPING)\b", status, re.MULTILINE):
                raise RuntimeError("The prior preflight cache still has a live daemon.")
            details = self.copy_dependency_tree(source, self.seed_home / "caches/modules-2", read_only=False)
            self.record["recovered_preflight_dependencies"] = {"previous_run": str(prior), **details}
        if self.args.seed_from:
            prior = Path(self.args.seed_from).expanduser().resolve()
            seed = prior / "read-only-dependencies/modules-2"
            if not (prior / ".arouter-benchmark-owned").is_file() or not seed.is_dir():
                raise RuntimeError("--seed-from requires a previous owned benchmark run with a frozen seed.")
            previous = read_json(prior / "run.json")
            if previous.get("status") != "complete" or previous.get("daemon_cleanup_errors"):
                raise RuntimeError("The previous seed owner did not complete and clean up successfully.")
            if any(p.stat().st_mode & 0o222 for p in seed.rglob("*") if p.is_file()):
                raise RuntimeError("The previous dependency seed is not immutable.")
            details = self.copy_dependency_tree(seed, self.seed_home / "caches/modules-2", read_only=False)
            self.record["seed_reuse"] = {"previous_run": str(prior), **details}
        self.persist()
        for backend in self.backends:
            self.invoke(backend, ["benchmarkFingerprint", ":app:assembleDebug"], "online-preflight",
                        home=self.seed_home, offline=False, config_cache=False)
            self.verify_fingerprint(backend)
        self.assert_common_artifacts()
        if self.args.device_tests:
            self.device_preflight()
        else:
            self.record["device_preflight"] = "not requested; this run alone does not establish runtime parity"
        self.stop_home(self.seed_home)
        self.snapshot_dependencies()
        for backend in self.backends:
            self.invoke(backend, ["benchmarkFingerprint", ":app:assembleDebug"], "offline-private-home-preflight",
                        offline=True, config_cache=False)
            self.verify_fingerprint(backend)
            self.invoke(backend, [":app:assembleDebug"], "configuration-cache-prime")
        self.assert_common_artifacts()
        self.assert_source_parity()
        self.record["status"] = "measuring"
        self.record["fingerprints"] = self.fingerprints
        self.persist()

    def mutate(self, backend, name, alternate):
        rule = self.input_manifest["mutations"][name]
        path = self.projects[backend] / rule["path"]
        if not inside(path, self.projects[backend]):
            raise RuntimeError("Mutation escaped the generated project.")
        if name == "addition":
            if alternate:
                if path.exists():
                    raise RuntimeError("Added route already exists.")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(rule["content"], encoding="utf-8")
            else:
                if not path.is_file() or path.read_text(encoding="utf-8") != rule["content"]:
                    raise RuntimeError("Refusing to remove an unexpected route file.")
                path.unlink()  # Only the exact disposable file created above.
            return
        desired = rule["alternate"] if alternate else rule["initial"]
        previous = rule["initial"] if alternate else rule["alternate"]
        content = path.read_text(encoding="utf-8")
        if content.count(previous) != 1:
            raise RuntimeError("Mutation literal is missing or ambiguous: " + str(path))
        path.write_text(content.replace(previous, desired, 1), encoding="utf-8")

    def assert_source_parity(self):
        left = source_hashes(self.projects[self.backends[0]])
        right = source_hashes(self.projects[self.backends[1]])
        if left != right:
            raise RuntimeError("The measured arms no longer have identical application source bytes.")
        return combined_hash(left)

    def assert_generated_parity(self):
        if not self.args.baseline_ksp_from:
            return
        trees = []
        for arm in self.backends:
            directory = self.projects[arm] / "app/build/generated/ksp/debug/java"
            tree = {p.relative_to(directory).as_posix(): sha(p)
                    for p in sorted(directory.rglob("*.java"))}
            if not tree:
                raise RuntimeError("Missing generated Java for the KSP revision comparison.")
            trees.append(tree)
        if trees[0] != trees[1]:
            raise RuntimeError("The optimization changed generated Java; runtime parity is unproven.")
        self.record["identical_generated_java"] = trees[0]

    def measure(self):
        rounds = self.args.warmups + self.args.iterations
        for scenario in ("cold_clean", "warm_clean", "noop", "body_edit", "route_edit"):
            if scenario not in self.scenarios:
                continue
            mutation = {"body_edit": "body", "route_edit": "route"}.get(scenario)
            alternate = False
            for index in range(rounds):
                order = self.backends if index % 2 == 0 else tuple(reversed(self.backends))
                if mutation:
                    alternate = not alternate
                    for backend in self.backends:
                        self.mutate(backend, mutation, alternate)
                digest = self.assert_source_parity()
                for backend in order:
                    if scenario in ("cold_clean", "warm_clean"):
                        self.invoke(backend, ["clean"], scenario + "-unmeasured-clean")
                    if scenario == "cold_clean":
                        self.stop_home(self.homes[backend])
                    self.invoke(backend, [":app:assembleDebug"], scenario,
                                measured={"scenario": scenario, "sample_index": index,
                                          "warmup": index < self.args.warmups})
                    if self.samples[-1]["source_digest"] != digest:
                        raise RuntimeError("Source changed during a measurement.")
            if mutation and alternate:
                for backend in self.backends:
                    self.mutate(backend, mutation, False)
                    self.invoke(backend, [":app:assembleDebug"], scenario + "-restore")
        # Add and remove form one measured cycle, avoiding hidden reset builds.
        for index in range(rounds if "add_route" in self.scenarios else 0):
            order = self.backends if index % 2 == 0 else tuple(reversed(self.backends))
            for scenario, present, arms in (("add_route", True, order), ("remove_route", False, tuple(reversed(order)))):
                for backend in self.backends:
                    self.mutate(backend, "addition", present)
                digest = self.assert_source_parity()
                for backend in arms:
                    self.invoke(backend, [":app:assembleDebug"], scenario,
                                measured={"scenario": scenario, "sample_index": index,
                                          "warmup": index < self.args.warmups})
                    if self.samples[-1]["source_digest"] != digest:
                        raise RuntimeError("Source changed during an add/remove measurement.")
        if self.assert_source_parity() != self.record["base_source_digest"]:
            raise RuntimeError("Benchmark did not restore its original source inputs.")
        self.assert_generated_parity()

    def summarize(self):
        rows = []
        baseline, candidate = self.backends
        ratio_key = "median_paired_after_over_before" if self.args.baseline_ksp_from else "median_paired_ksp_over_kapt"
        for scenario in self.scenarios:
            arms = {}
            for backend in self.backends:
                samples = [s for s in self.samples if s["scenario"] == scenario and s["backend"] == backend and not s["warmup"]]
                if len(samples) != self.args.iterations:
                    raise RuntimeError("Incomplete measured sample set.")
                values = [s["wall_seconds"] for s in samples]
                arms[backend] = {"median_seconds": statistics.median(values), "iqr_seconds": iqr(values),
                                 "min_seconds": min(values), "max_seconds": max(values), "samples": len(values)}
            pairs = {}
            for sample in self.samples:
                if sample["scenario"] == scenario and not sample["warmup"]:
                    pairs.setdefault(sample["sample_index"], {})[sample["backend"]] = sample
            ratios = []
            for pair in pairs.values():
                if pair[baseline]["source_digest"] != pair[candidate]["source_digest"]:
                    raise RuntimeError("Paired inputs differ.")
                ratios.append(pair[candidate]["wall_seconds"] / pair[baseline]["wall_seconds"])
            rows.append({"scenario": scenario, **arms, ratio_key: statistics.median(ratios),
                         "paired_ratio_iqr": iqr(ratios)})
        write_json(self.root / "summary.json", rows)
        labels = ("Before KSP", "After KSP") if self.args.baseline_ksp_from else ("KAPT", "KSP")
        lines = ["# Controlled ARouter " + self.record["comparison"] + " benchmark", "",
                 "Same generated application source, AGP 8.12.0 / Gradle 8.13 / Kotlin 2.3.20 / JDK 17.",
                 "Kotlin runs in-process in both arms; dependencies are warm and offline; build cache is disabled.",
                 "Configuration cache is enabled. Cold means a new owned Gradle process, not a cold OS or dependency cache.",
                 "", "| Scenario | " + labels[0] + " median / IQR (s) | " + labels[1] + " median / IQR (s) | Median paired candidate/baseline |",
                 "| --- | ---: | ---: | ---: |"]
        for row in rows:
            lines.append("| %s | %.3f / %.3f | %.3f / %.3f | %.3f |" % (
                row["scenario"], row[baseline]["median_seconds"], row[baseline]["iqr_seconds"],
                row[candidate]["median_seconds"], row[candidate]["iqr_seconds"], row[ratio_key]))
        lines += ["", "A ratio below 1 means the candidate was faster in this fixture. These results are not an ecosystem-wide speed guarantee.",
                  "Provisioning, publication, cleanup and device tests are excluded. Raw commands, samples, task events and fingerprints are retained alongside this report.",
                  "Task intervals can overlap; their durations must not be added and presented as CLI wall time.", ""]
        (self.root / "REPORT.md").write_text("\n".join(lines), encoding="utf-8")
        self.record["status"] = "complete"
        self.record["finished_at_utc"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        self.record["summary"] = rows
        self.persist()

    def run(self):
        active_error = False
        try:
            self.prepare()
            self.measure()
            self.summarize()
        except BaseException as error:
            active_error = True
            self.record["status"] = "failed"
            self.record["failure"] = str(error)
            self.persist()
            raise
        finally:
            cleanup_errors = []
            for home in self.owned_homes:
                try:
                    self.stop_home(home)
                except Exception as error:
                    cleanup_errors.append(str(error))
            self.record["daemon_cleanup_errors"] = cleanup_errors
            if cleanup_errors:
                self.record["measurement_status_before_cleanup"] = self.record["status"]
                self.record["status"] = "cleanup_failed"
            self.persist()
            if cleanup_errors:
                print("Private daemon cleanup needs attention:", cleanup_errors, file=sys.stderr)
                if not active_error:
                    raise RuntimeError("Measurements finished, but private daemon cleanup failed.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gradle", help="Exact Gradle 8.13 executable; default discovers the existing wrapper distribution")
    parser.add_argument("--output", default=str(REPO / "build/reports/ksp-benchmark"))
    parser.add_argument("--routes", type=int, default=128)
    parser.add_argument("--iterations", type=int, default=10)
    parser.add_argument("--warmups", type=int, default=2)
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--device-tests", action="store_true")
    parser.add_argument("--expected-api", type=int, choices=(21, 34), default=34)
    parser.add_argument("--stop-emulator-after-preflight", action="store_true",
                        help="Explicitly stop the selected emulator after its correctness tests, before timing")
    parser.add_argument("--copy-cache", action="store_true", help="Allow full copying of the owned dependency seed when COW is unavailable")
    parser.add_argument("--seed-from", help="Clone the immutable dependency seed from a completed owned benchmark run")
    parser.add_argument("--recover-preflight-cache", help="Recover public dependencies from a failed, cleaned-up owned preflight")
    parser.add_argument("--baseline-ksp-from", help="Compare the frozen KSP artifact in a completed run against the current KSP artifact")
    parser.add_argument("--scenarios", help="Comma-separated scenarios; route addition/removal must be selected together")
    args = parser.parse_args()
    if args.iterations < 2 or args.warmups < 1 or args.routes < 4 or args.routes % 2:
        parser.error("Use at least two measured iterations, one warmup, and an even route count of at least four.")
    if args.stop_emulator_after_preflight and not args.device_tests:
        parser.error("--stop-emulator-after-preflight requires --device-tests.")
    if args.seed_from and args.recover_preflight_cache:
        parser.error("Choose either --seed-from or --recover-preflight-cache.")
    if args.scenarios:
        selected = args.scenarios.split(",")
        if len(selected) != len(set(selected)) or any(s not in SCENARIOS for s in selected):
            parser.error("Select distinct known scenarios.")
        if ("add_route" in selected) != ("remove_route" in selected):
            parser.error("Select add_route and remove_route together.")
    Benchmark(args).run()


if __name__ == "__main__":
    main()
