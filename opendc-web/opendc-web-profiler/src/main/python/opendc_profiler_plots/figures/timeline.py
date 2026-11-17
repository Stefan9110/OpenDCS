"""Resident memory and heap after collection over one run per trace: when the memory is made."""

from __future__ import annotations

from math import ceil
from pathlib import Path
from string import ascii_uppercase

import pandas as pd

from .. import style
from ..data import FINISHED, Dataset, base_points

NAME = "timeline"

RESIDENT = "rss"
HEAP = "heap_after_gc"
COLUMNS = 3


def base_timelines(data: Dataset) -> dict[str, pd.DataFrame]:
    """The timeline of each trace's base point, when the profiler wrote one."""
    base = base_points(data.measurements)
    return {
        row.trace: data.timelines[f"{row.point}-{row.repeat}"]
        for row in base.itertuples()
        if f"{row.point}-{row.repeat}" in data.timelines
    }


def killed_bases(data: Dataset) -> set[str]:
    """The traces whose base run did not finish, so their timeline stops where the run was killed."""
    base = base_points(data.measurements)
    return set(base.loc[base["exit_reason"] != FINISHED, "trace"])


def without_exit_sample(series: pd.DataFrame) -> pd.DataFrame:
    """The sampler's last reading can land after the process is gone and read as zero; it is no measurement."""
    exited = (series["kind"] == RESIDENT) & (series["mb"] == 0)
    return series[~exited]


def render(data: Dataset, out: Path) -> Path | None:
    series = base_timelines(data)
    if not series:
        return None
    traces = [trace for trace in style.trace_order(data.measurements) if trace in series]
    colors = style.trace_colors(style.trace_order(data.measurements))
    killed = killed_bases(data)
    ncols = min(COLUMNS, len(traces))
    nrows = ceil(len(traces) / ncols)
    fig, axes = style.new_figure(style.DOUBLE if ncols > 1 else style.COLUMN, 1.5 * nrows + 0.3, ncols=ncols, nrows=nrows, squeeze=False)
    for ax, trace, letter in zip(axes.flat, traces, ascii_uppercase):
        frame = without_exit_sample(series[trace])
        resident = frame[frame["kind"] == RESIDENT]
        heap = frame[frame["kind"] == HEAP]
        ax.plot(resident["t_s"], resident["mb"], color=style.MUTED, linewidth=1.0, label="resident")
        ax.step(heap["t_s"], heap["mb"], where="post", color=colors[trace], linewidth=1.2, label="heap after GC")
        ax.set_xlabel("Time [s]")
        ax.set_ylim(bottom=0)
        style.panel(ax, letter, f"{trace} (killed)" if trace in killed else trace)
    for ax in axes[:, 0]:
        ax.set_ylabel("Memory [MB]")
    for ax in axes.flat[len(traces) :]:
        ax.set_visible(False)
    handles, labels = axes.flat[0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="outside lower center", ncol=2)
    return style.save(fig, out, NAME)
