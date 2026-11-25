"""Build and fully hash-verify a prepared StockShotManager migration package."""
import argparse, hashlib, json, os, re, stat, subprocess, zipfile
from pathlib import Path
from datetime import datetime, timezone, timedelta

TZ = timezone(timedelta(hours=8))

def sha256(path):
    hasher = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024*1024), b""):
            hasher.update(block)
    return hasher.hexdigest()

def run():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage",type=Path,default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--archive",type=Path,required=True)
    parser.add_argument("--root-name",default="StockShotManager-Codex")
    args=parser.parse_args()
    stage=args.stage.resolve()
    archive=args.archive.resolve()
    root_name=args.root_name
    if not root_name or "/" in root_name or "\\" in root_name or root_name in [".",".."]:
        raise RuntimeError("Archive root name must be a single folder name")
    if archive.exists() or archive.with_suffix(archive.suffix+".partial").exists():
        raise RuntimeError("Refusing to overwrite an existing archive or partial archive")
    try:
        archive.relative_to(stage)
    except ValueError:
        pass
    else:
        raise RuntimeError("Archive must be outside its staging folder")
    required=["START_HERE.md","CONTINUE_IN_CODEX.md","docs/MIGRATION_STATUS.md","docs/ENVIRONMENT.md","StockShotManager/AGENTS.md","StockShotManager/.git/HEAD","StockShotManager/.git/index","StockShotManager/gradle/wrapper/gradle-wrapper.jar","StockShotManager/keystore/stockshot.jks","StockShotManager/app/src/main/AndroidManifest.xml","StockShotManager/_analyze.py","references/scr1.png","history/StockShotManager.bundle","codex/README.md","codex/export-report.json","tools/Configure-Environment.ps1","tools/Verify-Package.ps1","tools/Analyze-Reference.py","evidence/source-snapshot.json","evidence/migration-git-verification.json","evidence/migration-apk-verification.json","evidence/migration-environment-tool-selftest.json"]
    required += [f"StockShotManager/StockShotManager-{edition}-release-latest.apk" for edition in "ABCD"]
    for name in required:
        if not (stage/name).is_file():
            raise RuntimeError(f"Required staged file missing: {name}")
    snapshot=json.loads((stage/"evidence/source-snapshot.json").read_text(encoding="utf-8"))
    for item in snapshot["source_files"]:
        current=stage/item["path"]
        if not current.is_file() or current.stat().st_size!=item["bytes"] or sha256(current)!=item["sha256"]:
            raise RuntimeError(f"Original source snapshot changed: {item['path']}")
    gitinfo=json.loads((stage/"evidence/migration-git-verification.json").read_text(encoding="utf-8"))
    if not gitinfo["working_tree_status_matches_source"] or gitinfo["fsck_exit_code"]!=0:
        raise RuntimeError("Git validation is incomplete")
    sessions=json.loads((stage/"codex/export-report.json").read_text(encoding="utf-8"))
    if not sessions.get("all_row_counts_match") or not sessions.get("all_message_counts_match"):
        raise RuntimeError("Codex history count validation is incomplete")
    required_chat_ids={"019f949d-9276-78a0-9c7c-d2555209d57c","019f94dc-cda2-7210-a86a-bfdd669cc6ae","019f9d0c-a3c9-7c60-9992-4f989981cb68","019f9dfd-356b-73c2-bd24-44867e11f773","019fa3b0-4ea3-7d13-9ea1-79f53b846c5e","019fa933-cfa8-7863-aaa5-02d0385978b0","01a0e0d9-d0e9-7f92-b236-4271acc325fe","01a0e29e-ae43-7d71-8a33-520bb8be1ec1","01a20de5-5e5f-7e30-8f07-f34b2a3e1d56"}
    selected_ids={entry["thread_id"] for entry in sessions.get("selected_threads",[])}
    if not required_chat_ids.issubset(selected_ids) or sessions.get("raw_rollout_count",0) < 20:
        raise RuntimeError("A required related Codex conversation is missing")
    if sessions.get("unresolved_missing_reference_count",0):
        raise RuntimeError("Conversation attachment references remain unresolved")
    for entry in sessions["selected_threads"]:
        if not (stage/"codex"/entry["markdown"]).is_file():
            raise RuntimeError("A required readable conversation is missing")
    for mapping_name in ["raw-rollouts-map.json","attachments-map.json"]:
        mappings=json.loads((stage/"codex"/mapping_name).read_text(encoding="utf-8"))
        for item in mappings:
            relative=item.get("packaged_path",item.get("relative_path"))
            path=stage/"codex"/relative
            if not path.is_file() or sha256(path)!=item["sha256"]:
                raise RuntimeError(f"Conversation archive/attachment hash mismatch: {relative}")
            if item.get("decompressed_path"):
                decoded=stage/"codex"/item["decompressed_path"]
                if not decoded.is_file() or sha256(decoded)!=item["decompressed_sha256"]:
                    raise RuntimeError(f"Decompressed conversation hash mismatch: {relative}")
    session_review=stage/"evidence/migration-session-review.json"
    if not session_review.is_file():
        raise RuntimeError("Independent session review has not been recorded")
    review=json.loads(session_review.read_text(encoding="utf-8"))
    if not review.get("all_reviewed_checks_passed") or review.get("failures"):
        raise RuntimeError("Independent session review has unresolved failures")
    if review.get("export_snapshot_started_at")!=sessions["snapshot_started_at"]:
        raise RuntimeError("Independent session review is not bound to the final export snapshot")
    (stage/"evidence"/"package-preflight.json").write_text(json.dumps({"verified_at":datetime.now(TZ).isoformat(),"required_files_checked":required,"all_original_snapshot_hashes_match":True,"tracked_files":snapshot["tracked_files"],"git_fsck_exit_code":0,"reachable_commits":gitinfo["reachable_commit_count"],"session_report_present":True,"source_snapshot_verified_files":len(snapshot["source_files"])},ensure_ascii=False,indent=2),encoding="utf-8")

    excluded={"PACKAGE_MANIFEST.json","SHA256SUMS.txt"}
    payload=[path for path in sorted(stage.rglob("*")) if path.is_file() and path.relative_to(stage).as_posix() not in excluded]
    forbidden=[path for path in payload if path.name.lower() in ["auth.json","state_5.sqlite","thread_history_1.sqlite",".codex-global-state.sqlite"]]
    if forbidden:
        raise RuntimeError("Unexpected global account/state files found in package")
    files=[{"path":path.relative_to(stage).as_posix(),"bytes":path.stat().st_size,"sha256":sha256(path)} for path in payload]
    manifest={"schema_version":1,"project":"StockShotManager","package_root":root_name,"created_at":datetime.now(TZ).isoformat(),"source_root":snapshot["source_root"],"branch":snapshot["branch"],"head":snapshot["head"],"version_name":"1.9","version_code":10,"tracked_source_files":snapshot["tracked_files"],"payload_file_count":len(files),"payload_bytes":sum(item["bytes"] for item in files),"files":files,"coverage":{"source":"All tracked source files, untracked analyzer, local configuration and reference images; full standalone .git plus bundle.","documentation":"Original requirements, historical progress snapshot, current handoff and receiving-computer setup.","codex_history":"Related selected main and child conversations in Markdown, native projection and original rollout exports, plus referenced attachments. See codex/export-report.json.","release":"All four current signed v1.9 APKs and source/test/lint/signature evidence."},"checksum_scope":"files excludes PACKAGE_MANIFEST.json and SHA256SUMS.txt; SHA256SUMS.txt also authenticates PACKAGE_MANIFEST.json."}
    manifest_path=stage/"PACKAGE_MANIFEST.json"
    manifest_path.write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding="utf-8")
    sums=list(files)+[{"path":"PACKAGE_MANIFEST.json","bytes":manifest_path.stat().st_size,"sha256":sha256(manifest_path)}]
    sums.sort(key=lambda item:item["path"])
    (stage/"SHA256SUMS.txt").write_text("".join(item["sha256"]+"  "+item["path"]+"\n" for item in sums),encoding="utf-8")
    project=stage/"StockShotManager"
    executable=set()
    tracked_modes=subprocess.check_output(["git","--no-optional-locks","-c",f"safe.directory={project.as_posix()}","ls-files","--stage","-z"],cwd=project).decode("utf-8")
    for line in tracked_modes.split("\0"):
        if line.startswith("100755 "):
            executable.add("StockShotManager/"+line.split("\t",1)[1])
    all_paths=sorted(stage.rglob("*"))
    archive.parent.mkdir(parents=True,exist_ok=True)
    temporary=archive.with_suffix(archive.suffix+".partial")
    print(f"Compressing {len(sums)+1} files...",flush=True)
    with zipfile.ZipFile(temporary,"w",compression=zipfile.ZIP_DEFLATED,compresslevel=6,allowZip64=True) as output:
        for path in all_paths:
            if not path.is_file():
                continue
            relative=path.relative_to(stage).as_posix()
            info=zipfile.ZipInfo.from_file(path,arcname=root_name+"/"+relative)
            if relative in executable:
                mode=(info.external_attr>>16)|stat.S_IXUSR|stat.S_IXGRP|stat.S_IXOTH
                info.external_attr=(mode<<16)|(info.external_attr&0xFFFF)
            info.compress_type=zipfile.ZIP_STORED if path.suffix.lower() in [".zst",".apk",".png",".jks",".bundle",".pack"] else zipfile.ZIP_DEFLATED
            with path.open("rb") as input_stream,output.open(info,"w",force_zip64=True) as entry:
                for block in iter(lambda:input_stream.read(1024*1024),b""):
                    entry.write(block)
    expected={item["path"]:item for item in sums}
    expected["SHA256SUMS.txt"]={"sha256":sha256(stage/"SHA256SUMS.txt"),"bytes":(stage/"SHA256SUMS.txt").stat().st_size}
    print("Verifying every archived file, SHA-256 and ZIP CRC...",flush=True)
    verified=0
    with zipfile.ZipFile(temporary) as packed:
        infos=packed.infolist()
        if len(infos)!=len(expected) or len({info.filename for info in infos})!=len(infos):
            raise RuntimeError("Archive file count mismatch or duplicate names")
        for info in infos:
            if not info.filename.startswith(root_name+"/"):
                raise RuntimeError("Unexpected archive path")
            relative=info.filename[len(root_name)+1:]
            if relative not in expected or any(part in ["..","."] for part in Path(relative).parts):
                raise RuntimeError("Unexpected/unsafe archive entry")
            hasher=hashlib.sha256()
            with packed.open(info) as entry:
                for block in iter(lambda:entry.read(1024*1024),b""):
                    hasher.update(block)
            if info.file_size!=expected[relative]["bytes"] or hasher.hexdigest()!=expected[relative]["sha256"]:
                raise RuntimeError(f"Archive hash or size mismatch: {relative}")
            verified+=1
    os.replace(temporary,archive)
    archive_hash=sha256(archive)
    archive.with_suffix(archive.suffix+".sha256").write_text(archive_hash+"  "+archive.name+"\n",encoding="utf-8")
    result={"verified_at":datetime.now(TZ).isoformat(),"archive":str(archive),"archive_bytes":archive.stat().st_size,"archive_sha256":archive_hash,"files_verified":verified,"zip_crc_verified":True,"every_archived_sha256_verified":True,"stage":str(stage),"git_head":snapshot["head"],"tracked_source_files":snapshot["tracked_files"],"payload_bytes":manifest["payload_bytes"],"verification_note":"File count, every stored byte, size, SHA-256 and ZIP CRC were checked against the prepared package; copied source/keystore/wrapper/.git and APKs were preserved."}
    archive.with_suffix(archive.suffix+".verification.json").write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps(result,ensure_ascii=False),flush=True)

if __name__=="__main__":
    run()