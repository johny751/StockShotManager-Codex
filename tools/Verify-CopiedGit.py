import json, subprocess
from pathlib import Path
from datetime import datetime,timezone,timedelta
root=Path(r"D:\Program\Projects\Android\projects\ControlMonitor\StockShotManager\build\codex-transfer\package-20261125_071840")
source=Path(r"D:\Program\Projects\Android\projects\ControlMonitor\StockShotManager")
copy=root/"StockShotManager"
def git(project,*args):
    process=subprocess.run(["git","--no-optional-locks","-c",f"safe.directory={project.as_posix()}",*args],cwd=project,capture_output=True,text=True,encoding="utf-8")
    if process.returncode: raise RuntimeError(process.stderr)
    return process.stdout,process.stderr
original=git(source,"status","--porcelain=v2","--branch")[0]
current=git(copy,"status","--porcelain=v2","--branch")[0]
if original!=current:
    raise RuntimeError("Copied working-tree status differs from source")
fsck,fsckerr=git(copy,"fsck","--full")
count=int(git(copy,"rev-list","--all","--count")[0].strip())
result={"verified_at":datetime.now(timezone(timedelta(hours=8))).isoformat(),"fsck_exit_code":0,"fsck_output":fsck+fsckerr,"working_tree_status_matches_source":True,"working_tree_status":current,"reachable_commit_count":count,"source_head":git(source,"rev-parse","HEAD")[0].strip(),"copied_head":git(copy,"rev-parse","HEAD")[0].strip()}
(root/"evidence"/"migration-git-verification.json").write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding="utf-8")
print(json.dumps({"working_tree_status_matches_source":True,"reachable_commits":count,"fsck_exit_code":0},ensure_ascii=False))