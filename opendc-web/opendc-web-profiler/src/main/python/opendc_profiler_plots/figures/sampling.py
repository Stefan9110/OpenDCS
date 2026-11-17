"""Peak heap and wall time at a sample fraction, relative to the whole trace: sampling buys time and should buy memory."""

from __future__ import annotations

from pathlib import Path

import pandas as pd

from .. import style
from ..data import Dataset, axis

NAME = "sampling"

PANELS = (("heap_rel", "Peak heap"), ("wall_rel", "Wall time"))


def relative_to_full(frame: pd.DataFrame) -> pd.DataFrame:
    """Per trace and sample fraction, peak heap and wall time divided by the same trace run whole."""
    points = axis(frame, "sample_fraction")
    points = points[points["exit_reason"] == "OK"]
    full = points[points["sample_fraction"] == 1.0].set_index("trace")
    relative = points.assign(
        heap_rel=points["heap_after_gc_peak_mb"] / points["trace"].map(full["heap_after_gc_peak_mb"]),
        wall_rel=points["wall_s"] / points["trace"].map(full["wall_s"]),
    )
    return relative[["trace", "sample_fraction", "heap_rel", "wall_rel"]].sort_values(["trace", "sample_fraction"], ascending=[True, False])


def render(data: Dataset, out: Path) -> Path | None:
    table = relative_to_full(data.measurements)
    if table["sample_fraction"].nunique() < 2:
        return None
    traces = style.trace_order(data.measurements)
    colors = style.trace_colors(traces)
    fractions = sorted(table["sample_fraction"].unique(), reverse=True)
    fig, axes = style.new_figure(style.COLUMN, 1.8, ncols=2, sharex=True, sharey=True)
    for ax, (column, label), letter in zip(axes, PANELS, "AB"):
        ax.plot(fractions, fractions, color=style.MUTED, linestyle=":", linewidth=0.9, label="proportional")
        ax.axhline(1, color=style.MUTED, linewidth=0.6)
        for trace in traces:
            rows = table[table["trace"] == trace]
            ax.plot(rows["sample_fraction"], rows[column], marker="o", color=colors[trace], label=trace)
        ax.set_xscale("log")
        ax.set_xticks(fractions, [f"{fraction:g}" for fraction in fractions])
        ax.minorticks_off()
        ax.set_xlabel("Sample fraction")
        style.panel(ax, letter, label)
    axes[0].invert_xaxis()
    axes[0].set_ylim(0, 1.25)
    axes[0].set_ylabel("Relative to whole trace")
    axes[0].legend(loc="lower left")
    return style.save(fig, out, NAME)
