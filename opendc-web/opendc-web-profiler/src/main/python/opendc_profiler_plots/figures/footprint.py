"""Resident memory over live heap under each collector, at one unit: what a grant has to cover beyond the data itself."""

from __future__ import annotations

from pathlib import Path

import pandas as pd

from .. import style
from ..data import Dataset, axis

NAME = "footprint"

COLLECTORS = (("SERIAL", "Serial", "#0072B2"), ("G1", "G1", "#D55E00"))

JVM_CHOICE = "DEFAULT"


def resident_and_live(frame: pd.DataFrame) -> pd.DataFrame:
    """Per trace and collector named on the axis, at one unit: peak heap after collection, resident peak, and their ratio."""
    points = axis(frame, "collector")
    points = points[(points["exit_reason"] == "OK") & (points["parallelism"] == 1) & (points["collector"] != JVM_CHOICE)]
    return pd.DataFrame(
        {
            "trace": points["trace"].to_numpy(),
            "collector": points["collector"].to_numpy(),
            "live_mb": points["heap_after_gc_peak_mb"].to_numpy(),
            "resident_mb": points["rss_peak_mb"].to_numpy(),
            "ratio": (points["rss_peak_mb"] / points["heap_after_gc_peak_mb"]).to_numpy(),
        }
    )


def render(data: Dataset, out: Path) -> Path | None:
    table = resident_and_live(data.measurements)
    if table.empty:
        return None
    traces = [trace for trace in style.trace_order(data.measurements) if trace in set(table["trace"])]
    collectors = [collector for collector in COLLECTORS if collector[0] in set(table["collector"])]
    slot = 0.8 / len(collectors)
    fig, ax = style.new_figure(style.COLUMN, 2.0)
    for offset, (key, label, color) in enumerate(collectors):
        rows = table[table["collector"] == key].set_index("trace").reindex(traces)
        positions = [index + (offset - (len(collectors) - 1) / 2) * slot for index in range(len(traces))]
        ax.bar(positions, rows["ratio"].fillna(0), width=slot * 0.85, color=color, label=label)
    ax.axhline(1, color=style.INK, linestyle="--", linewidth=0.8, label="resident = live heap")
    ax.set_xticks(range(len(traces)), traces)
    ax.set_xlim(-0.6, len(traces) - 0.4)
    style.linear_axis(ax, "y")
    ax.set_ylim(0, table["ratio"].max() * 1.12)
    ax.set_ylabel("Resident / live heap")
    ax.legend(loc="upper center", bbox_to_anchor=(0.5, -0.18), ncol=len(collectors) + 1)
    return style.save(fig, out, NAME)
