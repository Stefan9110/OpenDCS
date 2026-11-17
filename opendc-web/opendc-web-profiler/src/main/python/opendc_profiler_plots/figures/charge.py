"""What the platform bills for a bag, wall time times the cores it granted, against the CPU the JVM consumed, at one and at four units."""

from __future__ import annotations

from pathlib import Path

import pandas as pd

from .. import style
from ..data import Dataset, axis

NAME = "charge"

CONSUMED = "#D55E00"


def billed_and_consumed(frame: pd.DataFrame) -> pd.DataFrame:
    """Per trace and unit count along the parallelism axis: billed core-seconds, wall time times units, and CPU seconds consumed."""
    points = axis(frame, "parallelism")
    points = points[(points["exit_reason"] == "OK") & points["cpu_total_s"].notna()]
    return pd.DataFrame(
        {
            "trace": points["trace"].to_numpy(),
            "parallelism": points["parallelism"].astype(int).to_numpy(),
            "billed_s": (points["wall_s"] * points["parallelism"]).to_numpy(),
            "consumed_s": points["cpu_total_s"].to_numpy(),
        }
    )


def render(data: Dataset, out: Path) -> Path | None:
    table = billed_and_consumed(data.measurements)
    if table.empty:
        return None
    order = {trace: index for index, trace in enumerate(style.trace_order(data.measurements))}
    table = table.sort_values(["trace", "parallelism"], key=lambda column: column.map(order) if column.name == "trace" else column)
    fig, ax = style.new_figure(style.COLUMN, 0.26 * len(table) + 0.9)
    for y, (_, run) in enumerate(table.iterrows()):
        ax.plot([run["billed_s"], run["consumed_s"]], [y, y], color=style.MUTED, linewidth=1.4, zorder=1)
        ax.plot(run["billed_s"], y, "o", markerfacecolor="white", markeredgecolor=style.INK, markersize=6, zorder=2, label="billed" if y == 0 else None)
        ax.plot(run["consumed_s"], y, "o", color=CONSUMED, markersize=6, zorder=2, label="consumed" if y == 0 else None)
    ax.set_yticks(range(len(table)), [f"{run['trace']} P={run['parallelism']}" for _, run in table.iterrows()])
    ax.invert_yaxis()
    style.scale(ax, "x", pd.concat([table["billed_s"], table["consumed_s"]]))
    ax.set_xlabel("Core-seconds")
    ax.legend(loc="upper center", bbox_to_anchor=(0.5, -0.25), ncol=2)
    return style.save(fig, out, NAME)
