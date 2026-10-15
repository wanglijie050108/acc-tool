#!/usr/bin/env bash
# 在 github.com:443 不通时，用 GitHub REST API 把本地提交推送上去。
#
# 背景：2026-10-15 实测到这台机器上网络会间歇性抽风 ——
# `curl https://github.com` 通（HTTP 200，0.4 秒），
# 但 `git push` 和 `gh api` 同时超时（21 秒 TLS handshake timeout）。
# 也就是说 curl 和 git/gh 走的不是同一条网络路径。
# 网络正常时**请直接用 git push**，本脚本只是没别的办法时的兜底。
#
# 用法：bash tools/push_via_api.sh
#
# ⚠️ 已知局限（没能在网络抽风期间验证成功，所以标为「未验证」）：
#   - 它同样依赖 api.github.com 稳定，网络全断时一样会失败
#   - 只推送**当前 HEAD 这一个提交**的内容快照，不是完整 git 历史
#   - 推完后本地 origin/main 引用需要 git fetch 同步
#
# 在 network 恢复后、又想走它验证的话，跑完请确认最后一步的 tree 比对为 ✅。

set -euo pipefail
cd "$(dirname "$0")/.."

GH="/c/Program Files/GitHub CLI/gh.exe"
REPO="wanglijie050108/acc-tool"
TMP='/c/Users/24996/AppData/Local/Temp/mf_push'
PY=/c/Users/24996/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/python/python.exe

rm -rf "$TMP"; mkdir -p "$TMP"

echo "=== 1/6  取远程当前 HEAD ==="
BASE_SHA=$("$GH" api "repos/$REPO/commits/main" --jq '.sha')
echo "  base: ${BASE_SHA:0:8}"

echo
echo "=== 2/6  上传改动文件为 blob ==="
# 用 Python 一次性完成「读文件 → base64 → 调 gh api → 收集条目」，
# 避免 bash 循环里的两个坑：
#   1. 大文件写成命令行参数会 Argument list too long
#   2. /tmp 在不同 shell 调用之间不保持
PYTHONIOENCODING=utf-8 "$PY" - "$REPO" "$GH" "$TMP" <<'PYEOF'
import base64, json, os, subprocess, sys

repo, gh, tmp = sys.argv[1], sys.argv[2], sys.argv[3]

files = subprocess.run(
    ["git", "show", "--name-only", "--format=", "HEAD"],
    capture_output=True, text=True, encoding="utf-8"
).stdout.split()

entries, failed = [], []
for path in files:
    if not os.path.isfile(path):
        print(f"  跳过（已删除）: {path}")
        continue
    with open(path, "rb") as fh:
        data = fh.read()

    payload = {"encoding": "base64",
               "content": base64.b64encode(data).decode("ascii")}
    pj = os.path.join(tmp, "blob.json")
    with open(pj, "w", encoding="utf-8") as fh:
        json.dump(payload, fh)

    # 走 --input 读文件，避开命令行长度限制
    r = subprocess.run(
        [gh, "api", f"repos/{repo}/git/blobs", "--input", pj, "--jq", ".sha"],
        capture_output=True, text=True, encoding="utf-8",
    )
    sha = r.stdout.strip()
    if len(sha) != 40:
        failed.append((path, (r.stdout + r.stderr).strip()[:120]))
        continue

    mode = "100755" if path.endswith(".sh") else "100644"
    entries.append({"path": path, "mode": mode, "type": "blob", "sha": sha})
    print(f"  ✅ {path}")

print(f"\n  成功 {len(entries)} 个，失败 {len(failed)} 个")
for p, err in failed:
    print(f"  ❌ {p}: {err}")

with open(os.path.join(tmp, "entries.json"), "w", encoding="utf-8") as fh:
    json.dump({"tree": entries}, fh, ensure_ascii=False)

sys.exit(1 if failed else 0)
PYEOF

echo
echo "=== 3/6  创建 tree ==="
TREE=$("$GH" api "repos/$REPO/git/trees" \
  --input "$TMP/entries.json" --jq '.sha')
echo "  tree: ${TREE:0:12}"

echo
echo "=== 4/6  创建 commit ==="
PYTHONIOENCODING=utf-8 "$PY" - "$BASE_SHA" "$TREE" "$TMP" <<'PYEOF'
import json, os, subprocess, sys
base, tree, tmp = sys.argv[1], sys.argv[2], sys.argv[3]
msg = subprocess.run(["git", "log", "-1", "--format=%B"],
                     capture_output=True, text=True, encoding="utf-8").stdout
with open(os.path.join(tmp, "commit.json"), "w", encoding="utf-8") as fh:
    json.dump({"message": msg, "tree": tree, "parents": [base]}, fh, ensure_ascii=False)
PYEOF

NEW=$("$GH" api "repos/$REPO/git/commits" \
  --input "$TMP/commit.json" --jq '.sha')
echo "  commit: ${NEW:0:12}"

echo
echo "=== 5/6  更新 main 引用 ==="
"$GH" api -X PATCH "repos/$REPO/git/refs/heads/main" \
  -f sha="$NEW" --jq '.object.sha' | sed 's/^/  main -> /'

echo
echo "=== 6/6  校验远程与本地是否一致 ==="
LOCAL_TREE=$(git rev-parse "HEAD^{tree}")
REMOTE_TREE=$("$GH" api "repos/$REPO/commits/main" --jq '.commit.tree.sha')
echo "  本地 HEAD tree:   ${LOCAL_TREE:0:12}"
echo "  远程 main  tree:  ${REMOTE_TREE:0:12}"
if [ "$LOCAL_TREE" = "$REMOTE_TREE" ]; then
  echo "  ✅ 完全一致，推送成功"
else
  echo "  ❌ tree 不一致，推送有问题"
  exit 1
fi
