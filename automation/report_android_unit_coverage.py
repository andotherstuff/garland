#!/usr/bin/env python3

from __future__ import annotations

import re
from pathlib import Path


def main() -> None:
    report_path = Path("app/build/reports/jacoco/jacocoDebugUnitTestReport/html/index.html")
    html = report_path.read_text(encoding="utf-8")
    footer_match = re.search(r"<tfoot><tr>(.*?)</tr></tfoot>", html)
    if footer_match is None:
        raise SystemExit("Could not locate JaCoCo summary row")

    columns = [
        re.sub(r"<.*?>", "", cell)
        for cell in re.findall(r"<td[^>]*>(.*?)</td>", footer_match.group(1))
    ]
    if len(columns) < 13:
        raise SystemExit("Unexpected JaCoCo summary shape")

    missed_lines = int(columns[7].replace(",", ""))
    total_lines = int(columns[8].replace(",", ""))
    covered_lines = total_lines - missed_lines
    line_coverage = covered_lines / total_lines * 100 if total_lines else 0.0

    print("Android JVM coverage summary")
    print(f"- instructions: {columns[2]}")
    print(f"- branches: {columns[4]}")
    print(f"- lines: {line_coverage:.1f}% ({covered_lines}/{total_lines})")
    print(f"- report: {report_path}")


if __name__ == "__main__":
    main()
