from __future__ import annotations

from pathlib import Path

import matplotlib

matplotlib.use("Agg")

import matplotlib.pyplot as plt  # noqa: E402
import pandas as pd  # noqa: E402
from matplotlib.colors import LinearSegmentedColormap  # noqa: E402
from matplotlib.patches import Patch  # noqa: E402
from matplotlib.ticker import FuncFormatter, LogLocator, MaxNLocator, NullFormatter  # noqa: E402

COLUMN = 3.5
DOUBLE = 7.16

PALETTE = ("#0072B2", "#E69F00", "#009E73", "#D55E00", "#56B4E9", "#CC79A7")
INK = "#222222"
MUTED = "#8a8a8a"
GUIDE = "#dddddd"

MAGNITUDES = ((1e9, "G"), (1e6, "M"), (1e3, "k"))

SEVERITY = LinearSegmentedColormap.from_list("severity", ["#009E73", "#E69F00", "#D55E00", "#A50F15"])
"""Green through orange to red over 0 to 1: a share that grows worse as it grows."""

CENSORED_BAR = {"color": "white", "edgecolor": INK, "linestyle": "--", "linewidth": 0.8}
"""A run killed at the time limit: its bar reaches the limit and is hollow, since the true value lies beyond it."""

KILLED_LABEL = "killed at the time limit"


def censored_handle():
    return Patch(facecolor="white", edgecolor=INK, linestyle="--", linewidth=0.8, label=KILLED_LABEL)

LOG_SPAN = 100

RC = {
    "font.family": "sans-serif",
    "font.size": 8,
    "axes.labelsize": 8,
    "xtick.labelsize": 7,
    "ytick.labelsize": 7,
    "legend.fontsize": 7,
    "legend.frameon": False,
    "legend.handlelength": 1.2,
    "axes.linewidth": 0.6,
    "xtick.major.width": 0.6,
    "ytick.major.width": 0.6,
    "axes.spines.top": False,
    "axes.spines.right": False,
    "lines.linewidth": 1.2,
    "lines.markersize": 4,
    "pdf.fonttype": 42,
    "ps.fonttype": 42,
    "savefig.dpi": 300,
}


def new_figure(width: float, height: float, ncols: int = 1, nrows: int = 1, **subplot_options):
    """A figure in the paper's style; every figure module starts here."""
    plt.rcParams.update(RC)
    return plt.subplots(nrows, ncols, figsize=(width, height), constrained_layout=True, **subplot_options)


def panel(ax, letter: str, caption: str = "") -> None:
    """A boxed letter in the top-left corner, with the panel's subject written after it."""
    ax.text(
        0.02,
        0.97,
        letter,
        transform=ax.transAxes,
        fontsize=8,
        fontweight="bold",
        va="top",
        ha="left",
        bbox={"boxstyle": "square,pad=0.2", "facecolor": "white", "edgecolor": INK, "linewidth": 0.6},
    )
    if caption:
        ax.annotate(caption, (0.02, 0.97), xycoords="axes fraction", xytext=(14, 0), textcoords="offset points", fontsize=8, va="top")


def trace_order(frame: pd.DataFrame) -> list[str]:
    """Traces from the smallest to the largest, so every figure lists them the same way."""
    return frame.groupby("trace")["fragments"].first().sort_values().index.tolist()


def trace_colors(traces: list[str]) -> dict[str, str]:
    return {trace: PALETTE[index % len(PALETTE)] for index, trace in enumerate(traces)}


def compact(value: float, suffix: str = "") -> str:
    """30000 reads as 30k, 2500000 as 2.5M, 0.03 as 0.03, 4541.2 as 4.54k: three significant digits at most."""
    for scale_, unit in MAGNITUDES:
        if value >= scale_:
            return f"{value / scale_:.3g}{unit}{suffix}"
    return f"{value:.3g}{suffix}"


def linear_axis(ax, which: str, suffix: str = "") -> None:
    """Evenly spaced ticks in plain numbers."""
    axis = ax.xaxis if which == "x" else ax.yaxis
    axis.set_major_locator(MaxNLocator(nbins=5, steps=[1, 2, 2.5, 5, 10]))
    axis.set_major_formatter(FuncFormatter(lambda value, _: compact(value, suffix)))


def log_axis(ax, which: str, suffix: str = "") -> None:
    """A logarithmic axis with one labelled tick per decade and unlabelled ticks between; no 1-2-5 labels to decode."""
    axis = ax.xaxis if which == "x" else ax.yaxis
    (ax.set_xscale if which == "x" else ax.set_yscale)("log")
    axis.set_major_locator(LogLocator(base=10, numticks=12))
    axis.set_minor_locator(LogLocator(base=10, subs=range(2, 10), numticks=12))
    axis.set_minor_formatter(NullFormatter())
    axis.set_major_formatter(FuncFormatter(lambda value, _: compact(value, suffix)))


def scale(ax, which: str, values, suffix: str = "") -> None:
    """Linear unless the values span two decades or more, which only a log axis shows whole."""
    positive = [float(value) for value in values if value > 0]
    if positive and max(positive) / min(positive) >= LOG_SPAN:
        log_axis(ax, which, suffix)
    else:
        linear_axis(ax, which, suffix)


def save(fig, out: Path, name: str) -> Path:
    out.mkdir(parents=True, exist_ok=True)
    pdf = out / f"{name}.pdf"
    fig.savefig(pdf, bbox_inches="tight", pad_inches=0.02)
    fig.savefig(pdf.with_suffix(".png"), bbox_inches="tight", pad_inches=0.02)
    plt.close(fig)
    return pdf
