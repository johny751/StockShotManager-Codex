import hashlib, json, re, subprocess, zipfile
from pathlib import Path
from datetime import datetime, timezone, timedelta
stage=Path(r"D:\Program\Projects\Android\projects\ControlMonitor\StockShotManager\build\codex-transfer\package-20261125_071840")
project=stage/"StockShotManager"
build_tools=Path(r"D:\Program\Projects\Android\sdk\build-tools\34.0.0")
java=Path(r"D:\Program\Projects\Android\jdk\bin\java.exe")
config=(project/"app"/"build.gradle").read_text(encoding="utf-8")
version=re.search(r'versionName\s+"([^"]+)"',config).group(1)
code=re.search(r'versionCode\s+(\d+)',config).group(1)
head=subprocess.check_output(["git","--no-optional-locks","-c",f"safe.directory={project.as_posix()}","rev-parse","--short","HEAD"],cwd=project).decode().strip()
common=None
report=[]
for edition,suffix in zip("ABCD",["",".b",".c",".d"]):
    path=project/f"StockShotManager-{edition}-release-latest.apk"
    badging=subprocess.check_output([str(build_tools/"aapt.exe"),"dump","badging",str(path)]).decode("utf-8")
    identity=re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",badging)
    expected=("com.stockshot.manager"+suffix,code,version)
    if not identity or identity.groups()!=expected:
        raise RuntimeError(f"Unexpected APK identity: {edition}")
    signature=subprocess.check_output([str(java),"-jar",str(build_tools/"lib"/"apksigner.jar"),"verify","--verbose","--print-certs",str(path)]).decode("utf-8")
    certificate=re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)',signature).group(1).lower()
    if common is None: common=certificate
    if common != certificate: raise RuntimeError("APK certificates differ")
    with zipfile.ZipFile(path) as apk:
        commit_present=any(head.encode()+b"\x00" in apk.read(name) for name in apk.namelist() if re.fullmatch(r"classes\d*\.dex",name))
    if not commit_present: raise RuntimeError(f"APK commit missing: {edition}")
    report.append({"edition":edition,"path":path.relative_to(stage).as_posix(),"package":expected[0],"version_name":version,"version_code":int(code),"git_short":head,"bytes":path.stat().st_size,"sha256":hashlib.sha256(path.read_bytes()).hexdigest(),"signature_verified":True,"certificate_sha256":certificate})
result={"verified_at":datetime.now(timezone(timedelta(hours=8))).isoformat(),"verification":"Copied APK identity, signing certificate and embedded Git commit verified independently before packaging.","apks":report}
(stage/"evidence"/"migration-apk-verification.json").write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding="utf-8")
print(json.dumps(result,ensure_ascii=False))