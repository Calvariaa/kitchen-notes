#!/usr/bin/env python3
"""把 git 提交按 conventional commit 前缀分组，产出 release notes 的 markdown 片段。

用法:
    python3 .github/scripts/changelog.py                 # 上一个 tag..HEAD
    python3 .github/scripts/changelog.py --range A..B    # 指定区间
    python3 .github/scripts/changelog.py --limit 100     # 最多取多少条提交

约定: `type(scope): subject`，`!` 或正文里的 `BREAKING CHANGE` 视为破坏性变更。
输出只有正文小节（以 "### " 开头），直接接在 GitHub 自动生成的 notes 后面。
任何异常都退化为输出空内容并返回 0 —— changelog 不该让发布失败。
"""

import argparse
import re
import subprocess
import sys

# (分组标题, 命中的 type 集合)；None 表示破坏性变更的专用桶
GROUPS = [
    ("⚠️ 破坏性变更", None),
    ("🚀 新功能", ("feat",)),
    ("🐛 修复", ("fix", "hotfix")),
    ("🎨 界面 / 体验", ("ui", "ux", "style")),
    ("⚡ 性能", ("perf",)),
    ("♻️ 重构", ("refactor",)),
    ("🔧 构建 / CI", ("build", "ci")),
    ("📝 文档", ("docs",)),
    ("✅ 测试", ("test",)),
    ("📦 其他", ("chore", "revert", "deps")),
]

# 不值得写进发布说明的提交（自动化产生的版本提交之类）
SKIP_TYPES = {"release"}

HEADER_RE = re.compile(r"^(?P<type>[A-Za-z]+)(?:\((?P<scope>[^)]*)\))?(?P<bang>!)?:\s*(?P<desc>.+)$")
BREAKING_RE = re.compile(r"BREAKING[ -]CHANGE", re.IGNORECASE)
RECORD_SEP = "\x1e"
FIELD_SEP = "\x1f"


def git(*args):
    return subprocess.run(
        ["git", *args],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )


def previous_tag():
    """最近的 v* 标签（不含 HEAD 自己）。"""
    for args in (
        ["describe", "--tags", "--abbrev=0", "--match", "v[0-9]*", "HEAD^"],
        ["describe", "--tags", "--abbrev=0", "--match", "v[0-9]*"],
    ):
        out = git(*args)
        if out.returncode == 0 and out.stdout.strip():
            return out.stdout.strip()
    return ""


def collect(range_spec, limit):
    fmt = FIELD_SEP.join(["%h", "%s", "%b"]) + RECORD_SEP
    out = git("log", "--no-merges", f"--format={fmt}", f"--max-count={limit}", range_spec)
    if out.returncode != 0:
        out = git("log", "--no-merges", f"--format={fmt}", f"--max-count={limit}")
    if out.returncode != 0:
        return []

    commits = []
    for raw in out.stdout.split(RECORD_SEP):
        raw = raw.strip("\n")
        if not raw:
            continue
        parts = raw.split(FIELD_SEP)
        if len(parts) < 2:
            continue
        commits.append(
            {
                "sha": parts[0].strip(),
                "subject": parts[1].strip(),
                "body": parts[2] if len(parts) > 2 else "",
            }
        )
    return commits


def classify(commit):
    """返回 (type, 分组标题, 展示行)。"""
    subject = commit["subject"]
    m = HEADER_RE.match(subject)
    ctype = m.group("type").lower() if m else ""
    scope = (m.group("scope") or "").strip() if m else ""
    bang = bool(m.group("bang")) if m else False
    desc = m.group("desc").strip() if m else subject

    if bang or BREAKING_RE.search(commit["body"] or ""):
        title = "⚠️ 破坏性变更"
    else:
        title = "📦 其他"
        for group_title, types in GROUPS:
            if types and ctype in types:
                title = group_title
                break

    text = f"{scope}: {desc}" if scope else desc
    return ctype, title, f"- {text} (`{commit['sha']}`)"


def build(range_spec, limit):
    buckets = {}
    for commit in collect(range_spec, limit):
        ctype, title, line = classify(commit)
        if ctype in SKIP_TYPES:
            continue
        buckets.setdefault(title, []).append(line)

    if not buckets:
        return ""

    lines = ["### 本次提交", ""]
    for title, _ in GROUPS:
        items = buckets.get(title)
        if not items:
            continue
        lines.append(f"**{title}**")
        lines.extend(items)
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def main():
    # CI 上是 UTF-8，但 Windows 本地跑默认是 GBK，emoji 会炸；统一成 UTF-8
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8")
        except (AttributeError, ValueError):
            pass

    parser = argparse.ArgumentParser(add_help=True)
    parser.add_argument("--range", dest="range_spec", default="")
    parser.add_argument("--limit", dest="limit", type=int, default=300)
    args = parser.parse_args()

    try:
        range_spec = args.range_spec.strip()
        if not range_spec:
            tag = previous_tag()
            range_spec = f"{tag}..HEAD" if tag else "HEAD"
        sys.stdout.write(build(range_spec, max(1, args.limit)))
    except Exception as exc:  # noqa: BLE001 - changelog 失败不该阻断发布
        print(f"[changelog] skipped: {exc}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
