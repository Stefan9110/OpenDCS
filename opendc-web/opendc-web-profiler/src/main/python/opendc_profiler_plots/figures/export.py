"""Wall time and output size under each export setting, from no export to every table every minute, per trace.

A run killed at the time limit shows as a hollow bar reaching the limit in the wall-time panel and no output.
"""

from __future__ import annotations

from pathlib import Path

import pandas as pd

from .. import style
from ..data import FINISHED, KILLED, NO_TABLES, Dataset, axis

NAME = "export"

BYTES_PER_MB = 1e6

SECONDS_PER_MINUTE = 60

HOST_TABLE = "HOST_ONLY"

TABLE_ORDER = {NO_TABLES: 0, HOST_TABLE: 1, "ALL": 2}

RAMP = ("#6baed6", "#4292c6", "#2171b5", "#08519c", "#08306b")

PANELS = (("wall_s", "Wall time [s]"), ("output_mb", "Output [MB]"))


def setting_label(tables: str, interval_s: int) -> str:
    if tables == NO_TABLES:
        return "no export"
    minutes = int(interval_s) // SECONDS_PER_MINUTE
    return f"host table, {minutes} min" if tables == HOST_TABLE else f"all tables, {minutes} min"


def export_points(frame: pd.DataFrame) -> pd.DataFrame:
    """Runs along the interval and table axes that finished or were killed at the time limit, labelled by setting and
    ordered from the least export to the most.

    The host axis stays out: more hosts also grow the simulation, so its points say nothing about export alone.
    """
    points = pd.concat([axis(frame, "export_interval_s"), axis(frame, "tables")]).drop_duplicates("point")
    points = points[points["exit_reason"].isin([FINISHED, KILLED])]
    return points.assign(
        setting=[setting_label(tables, interval) for tables, interval in zip(points["tables"], points["export_interval_s"])],
        table_rank=points["tables"].map(TABLE_ORDER).fillna(len(TABLE_ORDER)),
        output_mb=points["output_bytes"] / BYTES_PER_MB,
        finished=points["exit_reason"] == FINISHED,
    ).sort_values(["table_rank", "export_interval_s"], ascending=[True, False])


def ordered_settings(points: pd.DataFrame) -> list[str]:
    return points["setting"].drop_duplicates().tolist()


def ramp(count: int) -> list[str]:
    """`count` steps of the blue ramp, light to dark, spread over its range."""
    last = len(RAMP) - 1
    return [RAMP[round(index * last / max(count - 1, 1))] for index in range(count)]


def render(data: Dataset, out: Path) -> Path | None:
    points = export_points(data.measurements)
    if points.empty:
        return None
    settings = ordered_settings(points)
    traces = [trace for trace in style.trace_order(data.measurements) if trace in set(points["trace"])]
    slot = 0.8 / len(settings)
    fig, axes = style.new_figure(style.COLUMN, 0.13 * len(settings) * len(traces) + 1.2, ncols=2, sharey=True)
    for ax, (column, label) in zip(axes, PANELS):
        for offset, (setting, color) in enumerate(zip(settings, ramp(len(settings)))):
            rows = points[points["setting"] == setting].set_index("trace").reindex(traces)
            positions = [index + (offset - (len(settings) - 1) / 2) * slot for index in range(len(traces))]
            finished = rows["finished"].fillna(False).to_numpy(dtype=bool)
            ax.barh(positions, rows[column].where(finished, 0).fillna(0), height=slot * 0.85, color=color, label=setting)
            if column == "wall_s":
                ax.barh(positions, rows[column].where(~finished, 0).fillna(0), height=slot * 0.85, **style.CENSORED_BAR)
        style.linear_axis(ax, "x")
        ax.set_xlim(left=0)
        ax.set_xlabel(label)
    axes[0].set_yticks(range(len(traces)), traces)
    axes[0].set_ylim(len(traces) - 0.5, -0.5)
    handles, labels = axes[0].get_legend_handles_labels()
    if not points["finished"].all():
        handles.append(style.censored_handle())
        labels.append(style.KILLED_LABEL)
    fig.legend(handles, labels, loc="outside lower center", ncol=2)
    return style.save(fig, out, NAME)
