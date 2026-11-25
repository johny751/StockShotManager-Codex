import json, os, re, shutil, subprocess, hashlib
from pathlib import Path
from datetime import datetime, timezone, timedelta
import xml.etree.ElementTree as ET

source = Path(r"D:\Program\Projects\Android\projects\ControlMonitor\StockShotManager")
stage = source / "build" / "codex-transfer" / "package-20261125_071840"
dest = stage / "StockShotManager"
SAFE = "safe.directory=" + source.as_posix()

def git(*args):
    return subprocess.check_output(["git", "--no-optional-locks", "-c", SAFE, *args], cwd=source).decode("utf-8")

def digest(path):
    hasher = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(block)
    return hasher.hexdigest()

records = []
def copy(src, target, kind):
    src, target = Path(src), Path(target)
    if not src.is_file():
        raise RuntimeError(f"Required file missing: {src}")
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        raise RuntimeError(f"Refusing to overwrite an existing staged file: {target}")
    before = digest(src)
    shutil.copy2(src, target)
    after = digest(target)
    if before != after or digest(src) != before:
        raise RuntimeError(f"Copy integrity check failed: {src}")
    records.append({"category": kind, "source": str(src), "path": target.relative_to(stage).as_posix(), "bytes": target.stat().st_size, "sha256": after})

tracked = [item for item in git("ls-files", "-z").split("\0") if item]
if not tracked or any(name.startswith("build/") for name in tracked):
    raise RuntimeError("Unexpected source selection")
head = git("rev-parse", "HEAD").strip()
branch = git("branch", "--show-current").strip()
status = git("status", "--porcelain=v2", "--branch")
for name in tracked:
    copy(source / name, dest / name, "tracked_source")
for name in ["_analyze.py", "local.properties"]:
    copy(source / name, dest / name, "untracked_tool" if name.endswith(".py") else "original_machine_config")
for name in "ABCD":
    filename = f"StockShotManager-{name}-release-latest.apk"
    copy(source / filename, dest / filename, "latest_release_apk")

git_root = source / ".git"
if not git_root.is_dir():
    raise RuntimeError("Expected a standalone .git directory")
lock_files = [str(path) for path in git_root.rglob("*.lock")]
if lock_files:
    raise RuntimeError(f"Git has active lock files: {lock_files}")
for path in sorted(git_root.rglob("*")):
    if path.is_file():
        copy(path, dest / ".git" / path.relative_to(git_root), "git_history")

parent = source.parent
for name in ["股票截图管理系统_需求文档_v20.md", "PROJECT_PROGRESS_REPORT.md"]:
    copy(parent / name, stage / "docs" / name, "reference_document")
copy(parent / "scr1.png", stage / "references" / "scr1.png", "reference_image")
for name in ["_crop_full.png", "_crop_mid.png"]:
    if (parent / name).is_file():
        copy(parent / name, stage / "references" / name, "reference_image")
for name in ["_v19_artifact_verification.log", "_v19_release_pipeline.log", "_v19_release_pipeline_error.log", "_v19_provider_probe.log"]:
    copy(source / name, stage / "evidence" / name, "previous_release_evidence")

unit = []
for flavor in ["Alpha", "Beta", "Gamma", "Delta"]:
    folder = source / "app" / "build" / "test-results" / f"test{flavor}DebugUnitTest"
    paths = sorted(folder.glob("TEST-*.xml"))
    if not paths:
        raise RuntimeError(f"Missing unit test results: {flavor}")
    counts = {"tests":0, "failures":0, "errors":0, "skipped":0}
    for path in paths:
        suite = ET.parse(path).getroot()
        for key in counts:
            counts[key] += int(suite.attrib.get(key, 0))
        copy(path, stage / "evidence" / "unit-tests" / folder.name / path.name, "previous_test_evidence")
    if counts["failures"] or counts["errors"] or counts["tests"] != 67:
        raise RuntimeError(f"Unexpected test outcome: {flavor}: {counts}")
    unit.append({"flavor":flavor.lower(), **counts, "source_modified_utc":datetime.fromtimestamp(max(path.stat().st_mtime for path in paths), timezone.utc).isoformat()})

lint = []
for flavor in ["alpha", "beta", "gamma", "delta"]:
    report = source / "app" / "build" / "reports" / f"lint-results-{flavor}Release.xml"
    issues = ET.parse(report).getroot().findall("issue")
    errors = sum(issue.attrib.get("severity") in ["Error", "Fatal"] for issue in issues)
    warnings = sum(issue.attrib.get("severity") == "Warning" for issue in issues)
    if errors:
        raise RuntimeError(f"Lint errors found: {flavor}")
    lint.append({"flavor":flavor, "errors":errors, "warnings":warnings})
    for extension in ["xml", "html", "txt"]:
        path = report.with_suffix("."+extension)
        copy(path, stage / "evidence" / "lint" / path.name, "previous_lint_evidence")
    generated = source / "app" / "build" / "generated" / "source" / "buildConfig" / flavor / "release" / "com" / "stockshot" / "manager" / "BuildConfig.java"
    metadata = source / "app" / "build" / "outputs" / "apk" / flavor / "release" / "output-metadata.json"
    copy(generated, stage / "evidence" / "build-config" / flavor / "BuildConfig.java", "previous_build_metadata")
    copy(metadata, stage / "evidence" / "build-config" / flavor / "output-metadata.json", "previous_build_metadata")

bundle = stage / "history" / "StockShotManager.bundle"
if bundle.exists():
    raise RuntimeError("Refusing to overwrite git bundle")
subprocess.run(["git", "--no-optional-locks", "-c", SAFE, "bundle", "create", str(bundle), "--all"], cwd=source, check=True, capture_output=True)
bundle_verify = subprocess.run(["git", "--no-optional-locks", "-c", SAFE, "bundle", "verify", str(bundle)], cwd=source, check=True, capture_output=True, text=True, encoding="utf-8")
(stage / "evidence" / "git-bundle-verification.txt").write_text(bundle_verify.stdout+bundle_verify.stderr, encoding="utf-8")
(stage / "history" / "git-log.txt").write_text(git("log", "--all", "--date=iso-strict", "--format=%H %ad %s", "--stat"), encoding="utf-8")
(stage / "history" / "original-git-status.txt").write_text(status, encoding="utf-8")
for label, options in [("unstaged.patch", ["diff", "--binary"]), ("staged.patch", ["diff", "--cached", "--binary"])]:
    (stage / "history" / label).write_bytes(subprocess.check_output(["git", "--no-optional-locks", "-c", SAFE, *options], cwd=source))

snapshot = {"exported_at":datetime.now(timezone(timedelta(hours=8))).isoformat(), "source_root":str(source), "head":head, "branch":branch, "tracked_files":len(tracked), "copied_source_files":len(records), "original_status":status, "unit_test_evidence":unit, "lint_evidence":lint, "source_files":records, "bundle_sha256":digest(bundle)}
(stage / "evidence" / "source-snapshot.json").write_text(json.dumps(snapshot, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({"stage":str(stage), "head":head, "branch":branch, "tracked_files":len(tracked), "copied_files":len(records), "bundle_bytes":bundle.stat().st_size, "unit_tests":unit, "lint":lint}, ensure_ascii=False))