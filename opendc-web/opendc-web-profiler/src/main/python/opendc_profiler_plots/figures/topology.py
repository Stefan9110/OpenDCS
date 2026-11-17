"""Wall time at each host count the same workload ran on, grouped by trace: a topology moves the run time either way."""

from __future__ import annotations

from pathlib import Path

import pandas as pd
from matplotlib.transforms import blended_transform_factory

from .. import style
from ..data import FINISHED, KILLED, Dataset, axis

NAME = "topology"

BAR = "#0072B2"

BAR_STEP = 1.5
GROUP_GAP = 1.4


def time_by_hosts(frame: pd.DataFrame) -> pd.DataFrame:
    """Per trace, the runs along the host axis that finished or were killed at the time limit, in host order."""
    points = axis(frame, "hosts")
    points = points[points["exit_reason"].isin([FINISHED, KILLED])]
    table = points.assign(finished=points["exit_reason"] == FINISHED)
    return table[["trace", "hosts", "wall_s", "finished"]].sort_values(["trace", "hosts"])


def render(data: Dataset, out: Path) -> Path | None:
    table = time_by_hosts(data.measurements)
    if table.empty:
        return None
    traces = [trace for trace in style.trace_order(data.measurements) if trace in set(table["trace"])]
    fig, ax = style.new_figure(style.COLUMN, 2.2)
    positions, hosts, centers = [], [], []
    cursor = 0.0
    for trace in traces:
        first = cursor
        for run in table[table["trace"] == trace].itertuples():
            bar = {"color": BAR} if run.finished else style.CENSORED_BAR
            ax.bar(cursor, run.wall_s, width=0.8, **bar)
            positions.append(cursor)
            hosts.append(str(int(run.hosts)))
            cursor += BAR_STEP
        centers.append((first + cursor - BAR_STEP) / 2)
        cursor += GROUP_GAP
    ax.set_xticks(positions, hosts)
    ax.tick_params(axis="x", labelsize=6)
    below_axis = blended_transform_factory(ax.transData, ax.transAxes)
    for center, trace in zip(centers, traces):
        ax.text(center, -0.16, trace, transform=below_axis, ha="right", va="top", fontsize=7, rotation=25)
    ax.set_xlabel("Hosts", labelpad=26)
    style.linear_axis(ax, "y")
    ax.set_ylim(bottom=0)
    ax.set_ylabel("Wall time [s]")
    if not table["finished"].all():
        ax.legend(handles=[style.censored_handle()], loc="upper right")
    return style.save(fig, out, NAME)
