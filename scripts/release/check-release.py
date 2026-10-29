#!/usr/bin/env python3
"""Validate that a candidate release is newer and does not reuse a tag."""
import argparse
import json
import sys


def fail(message: str) -> None:
    print(f"check-release: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--latest", required=True)
    parser.add_argument("--candidate-version-code", required=True, type=int)
    parser.add_argument("--candidate-tag", required=True)
    parser.add_argument("--latest-tag", required=True)
    parser.add_argument("--candidate-sha", required=True)
    parser.add_argument("--existing-tag-sha", default="")
    args = parser.parse_args()

    try:
        with open(args.latest, encoding="utf-8") as handle:
            latest = json.load(handle)
    except Exception as exc:  # noqa: BLE001
        fail(f"读取最新 update.json 失败: {exc}")

    if not isinstance(latest, dict):
        fail("最新 update.json 不是对象")
    version_code = latest.get("versionCode")
    if not isinstance(version_code, int) or version_code <= 0:
        fail("最新 update.json 缺少合法 versionCode")
    if version_code >= args.candidate_version_code:
        fail(f"候选版本 {args.candidate_version_code} 不高于已发布版本 {version_code}")
    if args.latest_tag == args.candidate_tag:
        fail("候选 Release tag 与已发布 tag 相同")
    if not args.candidate_sha.strip():
        fail("候选提交 SHA 为空")
    if args.existing_tag_sha.strip():
        fail(f"候选 tag 已存在，拒绝复用: {args.candidate_tag}")

    print(
        f"check-release: ok candidate={args.candidate_version_code} "
        f"({args.candidate_tag}) latest={version_code}"
    )


if __name__ == "__main__":
    main()
