"""Measured against predicted, for peak heap and for wall time, over every run of a whole trace: how the shipped model fits.

A run on the diagonal was predicted exactly; the band is a factor of two either way. Every trace and every varied setting
is a point, so where the model holds and where it does not is read off the same axes, with nothing assumed about its shape.
A run killed at the time limit is a hollow triangle at that limit: its true time lies above. Runs at a sample fraction
below one are left out, since the SDK samples with replacement and such runs spin until they are killed.
"""

from __future__ import annotations

from pathlib import Path

import pandas as pd
from matplotlib.lines import Line2D
from matplotlib.patches import Patch

from .. import style
from ..data import FINISHED, KILLED, Dataset

NAME = "fit"

FACTOR = 2

BAND = "#ebebeb"

WHOLE_TRACE = 1.0

PANELS = (("predicted_heap", "measured_heap", "heap [MB]"), ("predicted_seconds", "measured_seconds", "time [s]"))


def predicted_and_measured(frame: pd.DataFrame) -> pd.DataFrame:
    """One row per run of a whole trace that finished or was killed at the time limit: the heap predicted for the units sharing
    the JVM and the peak measured, where the run finished with an uncapped heap; the seconds predicted and the wall time."""
    runs = frame[frame["exit_reason"].isin([FINISHED, KILLED]) & (frame["sample_fraction"] == WHOLE_TRACE)]
    finished = runs["exit_reason"] == FINISHED
    uncapped = finished & runs["heap_factor"].isna()
    return pd.DataFrame(
        {
            "trace": runs["trace"].to_numpy(),
            "point": runs["point"].to_numpy(),
            "finished": finished.to_numpy(),
            "predicted_heap": (runs["predicted_heap_mb"] * runs["parallelism"]).where(uncapped).to_numpy(),
            "measured_heap": runs["heap_after_gc_peak_mb"].where(uncapped).to_numpy(),
            "predicted_seconds": runs["predicted_seconds"].to_numpy(),
            "measured_seconds": runs["wall_s"].to_numpy(),
        }
    )


def render(data: Dataset, out: Path) -> Path | None:
    table = predicted_and_measured(data.measurements)
    if table.empty:
        return None
    traces = style.trace_order(data.measurements)
    colors = style.trace_colors(traces)
    fig, axes = style.new_figure(style.COLUMN, 2.1, ncols=2)
    for ax, (predicted, measured, quantity) in zip(axes, PANELS):
        rows = table.dropna(subset=[predicted, measured])
        low = min(rows[predicted].min(), rows[measured].min()) / FACTOR
        high = max(rows[predicted].max(), rows[measured].max()) * FACTOR
        ax.fill_between([low, high], [low / FACTOR, high / FACTOR], [low * FACTOR, high * FACTOR], color=BAND, linewidth=0, zorder=0)
        ax.plot([low, high], [low, high], color=style.MUTED, linewidth=0.8, zorder=1)
        for trace in traces:
            runs = rows[rows["trace"] == trace]
            done, killed = runs[runs["finished"]], runs[~runs["finished"]]
            ax.scatter(done[predicted], done[measured], s=14, color=colors[trace], zorder=2)
            ax.scatter(killed[predicted], killed[measured], s=22, marker="^", facecolors="none", edgecolors=colors[trace], zorder=2)
        style.log_axis(ax, "x")
        style.log_axis(ax, "y")
        ax.set_xlim(low, high)
        ax.set_ylim(low, high)
        ax.set_aspect("equal")
        ax.set_xlabel(f"Predicted {quantity}")
        ax.set_ylabel(f"Measured {quantity}")
    handles = [Line2D([], [], color=colors[trace], marker="o", linestyle="", label=trace) for trace in traces]
    handles.append(Patch(color=BAND, label=f"within {FACTOR}x"))
    if not table["finished"].all():
        handles.append(Line2D([], [], marker="^", markerfacecolor="none", markeredgecolor=style.INK, linestyle="", label=style.KILLED_LABEL))
    fig.legend(handles=handles, loc="outside lower center", ncol=3)
    return style.save(fig, out, NAME)
