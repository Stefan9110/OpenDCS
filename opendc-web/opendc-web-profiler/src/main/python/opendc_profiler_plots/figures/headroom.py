"""The share of the predicted heap that a run's live data left unused, per trace: what the estimator reserves beyond the need,
and so withholds from every other run on the node.

Only finished one-unit runs whose heap was not capped count, and only under the serial collector, since G1 keeps garbage in
the heap after a young collection and would overstate the need. A bar below zero is a trace that needed more than predicted.
"""

from __future__ import annotations

from pathlib import Path

import pandas as pd

from .. import style
from ..data import Dataset

NAME = "headroom"

PERCENT = 100

SERIAL_COLLECTORS = ("SERIAL", "DEFAULT")


def unused_share(frame: pd.DataFrame) -> pd.Series:
    """Per trace, the median share of the predicted heap left unused, in percent, over finished uncapped one-unit serial runs."""
    runs = frame[
        (frame["exit_reason"] == "OK")
        & frame["heap_factor"].isna()
        & (frame["parallelism"] == 1)
        & frame["collector"].isin(SERIAL_COLLECTORS)
    ]
    unused = (1 - runs["heap_after_gc_peak_mb"] / runs["predicted_heap_mb"]) * PERCENT
    return unused.groupby(runs["trace"]).median()


def render(data: Dataset, out: Path) -> Path | None:
    shares = unused_share(data.measurements)
    if shares.empty:
        return None
    traces = [trace for trace in style.trace_order(data.measurements) if trace in shares.index]
    shares = shares.loc[traces]
    fig, ax = style.new_figure(style.COLUMN, 0.3 * len(traces) + 0.9)
    positions = range(len(traces))
    colors = [style.SEVERITY(1.0) if share < 0 else style.SEVERITY(share / PERCENT) for share in shares]
    ax.barh(positions, shares, color=colors, height=0.6)
    ax.set_yticks(positions, traces)
    ax.set_ylim(len(traces) - 0.5, -0.5)
    style.linear_axis(ax, "x", "%")
    ax.set_xlabel("Predicted heap left unused")
    return style.save(fig, out, NAME)
