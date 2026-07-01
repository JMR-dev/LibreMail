#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""PostToolUse (Write|Edit) hook: warn when a Kotlin source file is missing the
SPDX license header that every LibreMail source file must carry.

Non-blocking: prints a JSON warning (systemMessage + additionalContext so Claude
adds the header) and always exits 0. Any error is swallowed so the tool flow is
never broken by this check.
"""
import json
import sys

REQUIRED = "SPDX-License-Identifier"
HEADER_LINE = "// SPDX-License-Identifier: GPL-3.0-or-later"


def main() -> int:
    try:
        data = json.load(sys.stdin)
    except Exception:
        return 0

    path = (data.get("tool_response") or {}).get("filePath") \
        or (data.get("tool_input") or {}).get("file_path")
    if not path:
        return 0

    lower = path.lower()
    if not (lower.endswith(".kt") or lower.endswith(".kts")):
        return 0

    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            content = fh.read()
    except OSError:
        # File missing (e.g. a delete) or unreadable — nothing to check.
        return 0

    if REQUIRED in content:
        return 0

    msg = f"SPDX header missing in {path}"
    out = {
        "systemMessage": msg,
        "hookSpecificOutput": {
            "hookEventName": "PostToolUse",
            "additionalContext": (
                f'{path} is missing the license header. Add "{HEADER_LINE}" as the '
                "first line — every LibreMail source file carries it."
            ),
        },
    }
    print(json.dumps(out))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception:
        sys.exit(0)
