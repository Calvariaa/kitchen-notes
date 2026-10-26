#!/usr/bin/env python3
"""把 changelog.py 产出的提交清单追加到指定 tag 的 GitHub Release 正文末尾。

用法:
    python3 .github/scripts/append_notes.py --tag v1.0.1 --commits next/commits.md

环境变量:
    GH_TOKEN / GITHUB_TOKEN   有 contents:write 的 token
    GITHUB_REPOSITORY         owner/repo

设计要点:
    - 用 marker 包裹，重复执行是幂等的（先删旧的再写新的）。
    - 读取 release 的 404/403 会重试；重试耗尽后**退出非 0**，不再静默成功，
      否则 CI 绿了但发布说明没更新，是最难查的一类故障。
    - PATCH 后回读校验：正文里必须出现 marker 且包含本次第一条提交，
      验证失败同样退出非 0。
    - 全流程显式 UTF-8，中文提交信息不会变乱码。
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

MARKER = "<!-- kitchennotes-commit-changelog -->"
API_ROOT = "https://api.github.com"


def log(msg):
    print(msg, flush=True)


def summary(msg):
    """写进 GitHub Step Summary，并留在普通日志里。"""
    log(msg)
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not path:
        return
    try:
        with open(path, "a", encoding="utf-8") as fh:
            fh.write(msg + "\n")
    except OSError:
        pass


def build_headers(token):
    return {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "kitchennotes-ci",
    }


def call(url, headers, method="GET", payload=None, retries=5):
    """发一个 JSON 请求；对 403/404/5xx 按退避重试，耗尽后抛最后一个 HTTPError。"""
    body = None
    hdrs = dict(headers)
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        hdrs["Content-Type"] = "application/json; charset=utf-8"

    last = None
    for attempt in range(1, max(1, retries) + 1):
        req = urllib.request.Request(url, data=body, headers=hdrs, method=method)
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                raw = resp.read().decode("utf-8")
                return json.loads(raw) if raw.strip() else {}
        except urllib.error.HTTPError as exc:
            last = exc
            detail = ""
            try:
                detail = exc.read().decode("utf-8", "replace")[:200]
            except Exception:  # noqa: BLE001
                pass
            retryable = exc.code in (403, 404, 429, 500, 502, 503, 504)
            if not retryable or attempt >= retries:
                raise
            wait = min(3 * attempt, 15)
            log(f"{method} {url} 第 {attempt} 次失败 {exc.code}: {detail} —— {wait}s 后重试")
            time.sleep(wait)
        except urllib.error.URLError as exc:
            last = exc
            if attempt >= retries:
                raise
            wait = min(3 * attempt, 15)
            log(f"{method} {url} 第 {attempt} 次网络失败: {exc.reason} —— {wait}s 后重试")
            time.sleep(wait)

    if last is not None:
        raise last
    raise RuntimeError("call() 未产生结果")


def strip_existing(body):
    """删掉上一次追加的段落，让重复执行保持幂等。"""
    if MARKER not in body:
        return body.rstrip()
    head, _, tail = body.partition(MARKER)
    # marker 之后是上一次写的清单，直到正文结束（清单总在末尾）
    return head.rstrip()


def main():
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass

    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", required=True)
    parser.add_argument("--commits", required=True, help="changelog.py 的输出文件")
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""))
    parser.add_argument("--dry-run", action="store_true", help="只打印将要写入的正文")
    args = parser.parse_args()

    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN") or ""
    if not token:
        summary("::error::缺少 GH_TOKEN / GITHUB_TOKEN")
        return 1
    if not args.repo:
        summary("::error::缺少 GITHUB_REPOSITORY")
        return 1

    try:
        with open(args.commits, encoding="utf-8") as fh:
            commits = fh.read().strip()
    except OSError as exc:
        summary(f"::error::读取提交清单失败: {exc}")
        return 1

    if not commits:
        log("提交清单为空，跳过追加")
        return 0

    headers = build_headers(token)
    rel_url = f"{API_ROOT}/repos/{args.repo}/releases/tags/{args.tag}"

    try:
        rel = call(rel_url, headers)
    except urllib.error.HTTPError as exc:
        summary(f"::error::读取 release `{args.tag}` 失败 HTTP {exc.code}（已重试）")
        return 1
    except Exception as exc:  # noqa: BLE001
        summary(f"::error::读取 release `{args.tag}` 异常: {exc}")
        return 1

    release_id = rel.get("id")
    if not release_id:
        summary(f"::error::release `{args.tag}` 响应里没有 id")
        return 1

    old = strip_existing(rel.get("body") or "")
    new_body = f"{old}\n\n{MARKER}\n{commits}\n" if old else f"{MARKER}\n{commits}\n"

    if args.dry_run:
        log("--- dry-run body ---")
        log(new_body)
        return 0

    patch_url = f"{API_ROOT}/repos/{args.repo}/releases/{release_id}"
    try:
        call(patch_url, headers, "PATCH", {"body": new_body})
    except urllib.error.HTTPError as exc:
        detail = ""
        try:
            detail = exc.read().decode("utf-8", "replace")[:400]
        except Exception:  # noqa: BLE001
            pass
        summary(f"::error::PATCH release {release_id} 失败 HTTP {exc.code}: {detail}")
        return 1
    except Exception as exc:  # noqa: BLE001
        summary(f"::error::PATCH release {release_id} 异常: {exc}")
        return 1

    # 回读校验：marker 和第一条提交都在，才算真的写进去了
    try:
        after = call(rel_url, headers, retries=3)
    except Exception as exc:  # noqa: BLE001
        summary(f"::error::PATCH 后回读失败: {exc}")
        return 1

    got = after.get("body") or ""
    first_line = next((ln for ln in commits.splitlines() if ln.startswith("- ")), "")
    problems = []
    if MARKER not in got:
        problems.append("正文里没有 marker")
    if first_line and first_line not in got:
        problems.append(f"正文里找不到第一条提交: {first_line[:80]}")
    if len(got) < len(commits):
        problems.append(f"正文长度 {len(got)} 小于提交清单 {len(commits)}")

    if problems:
        summary("::error::回读校验失败 —— " + "；".join(problems))
        return 1

    summary(f"✅ release `{args.tag}` 已追加提交清单（正文 {len(got)} 字符）")
    log(f"https://github.com/{args.repo}/releases/tag/{args.tag}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
