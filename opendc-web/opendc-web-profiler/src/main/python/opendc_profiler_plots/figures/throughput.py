"""Simulated days and fragments per wall-clock second, with and without export: the simulator's own pace, and what export leaves of it."""

from __future__ import annotations

from pathlib import Path

import pandas as pd
from matplotlib.lines import Line2D

from .. import style
from ..data import SECONDS_PER_DAY, Dataset, with_and_without_export

NAME = "throughput"

DOT = "#0072B2"

PANELS = (("days_per_s", "Simulated days / s"), ("fragments_per_s", "Fragments / s"))


def rates(frame: pd.DataFrame) -> pd.DataFrame:
    """Per trace, at its base point and at the same point without export: simulated days and fragments per wall-clock second."""
    points = with_and_without_export(frame)
    return pd.DataFrame(
        {
            "trace": points["trace"].to_numpy(),
            "exporting": points["exporting"].to_numpy(),
            "days_per_s": (points["span_s"] / SECONDS_PER_DAY / points["wall_s"]).to_numpy(),
            "fragments_per_s": (points["fragments"] / points["wall_s"]).to_numpy(),
        }
    )


def render(data: Dataset, out: Path) -> Path | None:
    table = rates(data.measurements)
    if table.empty:
        return None
    traces = [trace for trace in style.trace_order(data.measurements) if trace in set(table["trace"])]
    fig, axes = style.new_figure(style.COLUMN, 0.3 * len(traces) + 1.0, ncols=2, sharey=True)
    for ax, (column, label) in zip(axes, PANELS):
        ax.grid(axis="y", color=style.GUIDE, linewidth=0.6, zorder=0)
        for exporting, face in ((True, DOT), (False, "none")):
            rows = table[table["exporting"] == exporting].set_index("trace").reindex(traces)
            ax.plot(rows[column], range(len(traces)), "o", markerfacecolor=face, markeredgecolor=DOT, markersize=6, zorder=3)
        style.log_axis(ax, "x")
        ax.set_xlim(table[column].min() / 3, table[column].max() * 3)
        ax.set_xlabel(label)
    axes[0].set_yticks(range(len(traces)), traces)
    axes[0].set_ylim(len(traces) - 0.5, -0.5)
    handles = [
        Line2D([], [], marker="o", markerfacecolor="none", markeredgecolor=DOT, markersize=6, linestyle="", label="simulation only"),
        Line2D([], [], marker="o", color=DOT, markersize=6, linestyle="", label="with export"),
    ]
    fig.legend(handles=handles, loc="outside lower center", ncol=2)
    return style.save(fig, out, NAME)
