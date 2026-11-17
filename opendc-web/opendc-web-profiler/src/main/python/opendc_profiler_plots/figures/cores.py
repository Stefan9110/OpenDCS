"""How many cores one unit keeps busy, and who burns them: the simulation thread, export writers, GC and JIT."""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd

from .. import style
from ..data import Dataset, base_points

NAME = "cores"

GROUPS = (
    ("cpu_simulation_s", "simulation", "#0072B2"),
    ("cpu_writer_s", "export writers", "#E69F00"),
    ("cpu_gc_s", "GC", "#009E73"),
    ("cpu_jit_s", "JIT", "#D55E00"),
    ("cpu_other_s", "other", "#9a9a9a"),
)

GRANTED_CORES = 1


def cores_by_group(frame: pd.DataFrame) -> pd.DataFrame:
    """Per trace at its base point, each thread group's CPU seconds over the wall seconds, in cores."""
    base = base_points(frame)
    base = base[(base["exit_reason"] == "OK") & base["cpu_total_s"].notna()].set_index("trace")
    columns = [column for column, _, _ in GROUPS]
    table = base[columns].div(base["wall_s"], axis=0)
    return table.loc[[trace for trace in style.trace_order(base.reset_index()) if trace in table.index]]


def render(data: Dataset, out: Path) -> Path | None:
    table = cores_by_group(data.measurements)
    if table.empty:
        return None
    fig, ax = style.new_figure(style.COLUMN, 0.32 * len(table) + 0.9)
    positions = np.arange(len(table))
    left = np.zeros(len(table))
    for column, label, color in GROUPS:
        ax.barh(positions, table[column], left=left, color=color, label=label, height=0.62, edgecolor="white", linewidth=0.5)
        left += table[column].to_numpy()
    for y, total in zip(positions, left):
        ax.text(total + 0.04, y, f"{total:.1f}", va="center", fontsize=7)
    ax.axvline(GRANTED_CORES, color=style.INK, linestyle="--", linewidth=0.8)
    ax.text(GRANTED_CORES, -0.6, " granted", fontsize=7, color=style.INK, va="center")
    ax.set_yticks(positions, table.index)
    ax.invert_yaxis()
    ax.set_xlim(0, max(float(left.max()) * 1.2, 1.4))
    ax.set_xlabel("Cores busy on average")
    ax.legend(loc="upper center", bbox_to_anchor=(0.5, -0.28), ncol=3)
    return style.save(fig, out, NAME)
