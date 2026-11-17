"""Where a run's Java samples fall: loading the trace, simulating, collecting telemetry or writing it, with export and with no tables.

The samplers run every export interval whether or not a table is written, so a run with no tables still collects.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd

from .. import style
from ..data import Dataset, with_and_without_export

NAME = "components"

COMPONENTS = (
    ("LOADING", "loading", "#009E73"),
    ("SIMULATING", "simulating", "#0072B2"),
    ("COLLECTING", "collecting telemetry", "#CC79A7"),
    ("WRITING", "writing tables", "#E69F00"),
    ("OTHER", "other", "#9a9a9a"),
)

CONDITIONS = ((True, "with export"), (False, "no tables"))

PERCENT = 100


def component_shares(data: Dataset) -> pd.DataFrame:
    """Per trace, with export and with no tables: each component's share of the run's Java samples, where a recording was read."""
    rows = []
    for run in with_and_without_export(data.measurements).itertuples():
        stem = f"{run.point}-{run.repeat}"
        if stem in data.components:
            shares = data.components[stem].set_index("component")["share"]
            rows.append({"trace": run.trace, "exporting": run.exporting} | {key: shares.get(key, 0.0) for key, _, _ in COMPONENTS})
    return pd.DataFrame(rows, columns=["trace", "exporting", *[key for key, _, _ in COMPONENTS]])


def render(data: Dataset, out: Path) -> Path | None:
    table = component_shares(data).set_index(["trace", "exporting"])
    if table.empty:
        return None
    traces = [trace for trace in style.trace_order(data.measurements) if trace in table.index.get_level_values("trace")]
    bars = [(trace, exporting, label) for trace in traces for exporting, label in CONDITIONS if (trace, exporting) in table.index]
    rows = table.loc[[(trace, exporting) for trace, exporting, _ in bars]]
    fig, ax = style.new_figure(style.COLUMN, 0.24 * len(bars) + 1.0)
    positions = np.arange(len(bars))
    left = np.zeros(len(bars))
    for key, label, color in COMPONENTS:
        values = rows[key].to_numpy() * PERCENT
        ax.barh(positions, values, left=left, color=color, label=label, height=0.7, edgecolor="white", linewidth=0.5)
        left += values
    ax.set_yticks(positions, [f"{trace}, {label}" for trace, _, label in bars])
    ax.set_ylim(len(bars) - 0.5, -0.5)
    ax.set_xlim(0, PERCENT)
    ax.set_xticks([0, 25, 50, 75, 100])
    ax.set_xlabel("Share of Java samples [%]")
    ax.legend(loc="upper center", bbox_to_anchor=(0.5, -0.22), ncol=3)
    return style.save(fig, out, NAME)
