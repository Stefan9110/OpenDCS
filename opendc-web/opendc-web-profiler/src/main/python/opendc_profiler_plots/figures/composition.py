"""The live heap shared among kinds of object at each trace's base point, one panel per trace: a bar per kind, coloured from
green to red as the kind's share grows and it takes the heap over.

The recording counts objects after the last full collection and lists the classes above one percent of the heap at that
moment; the shares are of what it listed.
"""

from __future__ import annotations

from math import ceil
from pathlib import Path

import pandas as pd

from .. import style
from ..data import Dataset, base_points

NAME = "composition"

COLUMNS = 2

PERCENT = 100

GROUPS = (
    ("fragment arrays", ("[J", "[D", "[I", "TraceFragment")),
    ("task objects", (".compute.task.", "TraceWorkload", "kotlinx.coroutines", "ScenarioReplayer")),
    ("export", ("telemetry", "parquet", "Parquet", "Exporter")),
    ("byte arrays", ("[B",)),
)

OTHER = "other"

KINDS = [name for name, _ in GROUPS] + [OTHER]


def group_of(class_name: str) -> str:
    """The recording names arrays by their JVM descriptors: `[J` is long[], `[D` double[], `[I` int[], `[B` byte[]."""
    for name, patterns in GROUPS:
        if any(pattern in class_name for pattern in patterns):
            return name
    return OTHER


def heap_groups(histogram: pd.DataFrame) -> pd.Series:
    """Bytes per object kind, every kind present even when empty."""
    grouped = histogram.assign(kind=histogram["class"].map(group_of)).groupby("kind")["bytes"].sum()
    return grouped.reindex(KINDS, fill_value=0)


def heap_shares(data: Dataset) -> pd.DataFrame:
    """Per trace at its base point, each kind's share of the listed live heap, in percent."""
    rows = {}
    for run in base_points(data.measurements).itertuples():
        stem = f"{run.point}-{run.repeat}"
        if stem in data.histograms:
            kinds = heap_groups(data.histograms[stem])
            rows[run.trace] = (kinds / kinds.sum() * PERCENT).to_dict()
    return pd.DataFrame.from_dict(rows, orient="index", columns=KINDS)


def render(data: Dataset, out: Path) -> Path | None:
    table = heap_shares(data)
    if table.empty:
        return None
    traces = [trace for trace in style.trace_order(data.measurements) if trace in table.index]
    ncols = min(COLUMNS, len(traces))
    nrows = ceil(len(traces) / ncols)
    fig, axes = style.new_figure(style.COLUMN, 1.3 * nrows + 0.4, ncols=ncols, nrows=nrows, sharex=True, sharey=True, squeeze=False)
    for ax, trace in zip(axes.flat, traces):
        shares = table.loc[trace, KINDS].to_numpy(dtype=float)
        ax.barh(range(len(KINDS)), shares, color=style.SEVERITY(shares / PERCENT), height=0.7)
        ax.set_title(trace, fontsize=8, loc="left", pad=3)
        ax.set_yticks(range(len(KINDS)), KINDS)
        ax.set_ylim(len(KINDS) - 0.5, -0.5)
        ax.set_xlim(0, PERCENT)
        ax.set_xticks([0, 25, 50, 75, 100])
    for ax in axes.flat[len(traces) :]:
        ax.set_visible(False)
    fig.supxlabel("Share of live heap [%]", fontsize=8)
    return style.save(fig, out, NAME)
