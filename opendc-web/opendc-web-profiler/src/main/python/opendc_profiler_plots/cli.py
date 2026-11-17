"""Renders every figure from a measurements directory; one figure failing does not cost the others."""

from __future__ import annotations

import argparse
import traceback
from pathlib import Path

from .data import load
from .figures import ALL


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="opendc-profiler-plots", description="Render the profiler's figures as PDF and PNG.")
    parser.add_argument("measurements", type=Path, help="directory holding the rows/ files or measurements.csv and the per-point files")
    parser.add_argument("figures", type=Path, help="directory the figures are written into")
    args = parser.parse_args(argv)
    data = load(args.measurements)
    failed = 0
    for figure in ALL:
        try:
            path = figure.render(data, args.figures)
        except Exception:
            traceback.print_exc()
            print(f"{figure.NAME}: failed")
            failed += 1
            continue
        print(f"{figure.NAME}: {path if path else 'skipped, nothing to plot'}")
    return 1 if failed else 0
