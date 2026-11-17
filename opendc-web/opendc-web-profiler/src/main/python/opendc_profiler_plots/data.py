"""Reads the profiler's output directory and derives the quantities the figures plot."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import pandas as pd

SECONDS_PER_DAY = 86_400

SETTINGS = ["hosts", "export_interval_s", "tables", "sample_fraction", "parallelism", "collector", "heap_factor"]

DEFAULT_SETTINGS = {
    "export_interval_s": 300,
    "tables": "ALL",
    "sample_fraction": 1.0,
    "parallelism": 1,
    "collector": "DEFAULT",
}

NO_TABLES = "NONE"

FINISHED = "OK"
KILLED = "TIMEOUT"


@dataclass(frozen=True)
class Dataset:
    measurements: pd.DataFrame
    timelines: dict[str, pd.DataFrame]
    cpu: dict[str, pd.DataFrame]
    components: dict[str, pd.DataFrame]
    histograms: dict[str, pd.DataFrame]


def load(directory: Path) -> Dataset:
    return Dataset(
        derive(read_measurements(directory)),
        point_files(directory / "timeline"),
        point_files(directory / "cpu"),
        point_files(directory / "components"),
        point_files(directory / "histogram"),
    )


def read_measurements(directory: Path) -> pd.DataFrame:
    """The row files are the authority; the merged CSV serves when they are absent, as in a copied result set."""
    rows = sorted((directory / "rows").glob("*.csv"))
    if rows:
        return pd.concat([pd.read_csv(file) for file in rows], ignore_index=True)
    return pd.read_csv(directory / "measurements.csv")


def point_files(directory: Path) -> dict[str, pd.DataFrame]:
    """One frame per point, keyed by the file stem the profiler wrote, `<point>-<repeat>`."""
    if not directory.is_dir():
        return {}
    return {file.stem: pd.read_csv(file) for file in sorted(directory.glob("*.csv"))}


def derive(frame: pd.DataFrame) -> pd.DataFrame:
    """Adds the ratios and rates every figure reads; a killed run keeps no time ratio."""
    frame = frame.copy()
    finished = frame["exit_reason"] == "OK"
    measured_heap = frame["heap_factor"].isna()
    frame["cores"] = frame["cpu_total_s"] / frame["wall_s"]
    frame["time_ratio"] = (frame["wall_s"] / frame["predicted_seconds"]).where(finished)
    frame["heap_ratio"] = (frame["heap_after_gc_peak_mb"] / (frame["predicted_heap_mb"] * frame["parallelism"])).where(measured_heap)
    frame["export_rows"] = (frame["hosts"] * frame["span_s"] / frame["export_interval_s"]).where(frame["tables"] != "NONE", 0.0)
    frame["days_per_s"] = frame["span_s"] / SECONDS_PER_DAY / frame["wall_s"]
    frame["killed"] = frame["exit_reason"] == "OOM"
    frame["default_settings"] = default_settings(frame)
    return frame


def default_settings(frame: pd.DataFrame) -> pd.Series:
    mask = frame["heap_factor"].isna()
    for column, value in DEFAULT_SETTINGS.items():
        mask &= frame[column] == value
    return mask


def collapse_repeats(frame: pd.DataFrame) -> pd.DataFrame:
    """One row per point: the median of every measured number over its repeats, the first repeat's everything else."""
    grouped = frame.groupby("point", sort=False)
    collapsed = grouped.first()
    numeric = frame.select_dtypes("number").columns
    collapsed[numeric] = grouped[numeric].median()
    collapsed["repeat"] = grouped["repeat"].min()
    return collapsed.reset_index()


def base_hosts(frame: pd.DataFrame) -> pd.Series:
    """Per trace, the host count every other axis was varied at; the smallest when nothing was varied."""
    varied = frame[~frame["default_settings"]]
    from_varied = varied.groupby("trace")["hosts"].agg(lambda hosts: hosts.mode().iloc[0])
    smallest = frame.groupby("trace")["hosts"].min()
    return from_varied.reindex(smallest.index).fillna(smallest).astype(int)


def base_points(frame: pd.DataFrame) -> pd.DataFrame:
    hosts = frame["trace"].map(base_hosts(frame))
    return collapse_repeats(frame[frame["default_settings"] & (frame["hosts"] == hosts)])


def axis(frame: pd.DataFrame, column: str) -> pd.DataFrame:
    """The base point of each trace and the points that differ from it in `column` alone, one row per point."""
    base = base_points(frame).set_index("trace")
    matches = pd.Series(True, index=frame.index)
    for setting in SETTINGS:
        if setting == column:
            continue
        expected = frame["trace"].map(base[setting])
        matches &= (frame[setting] == expected) | (frame[setting].isna() & expected.isna())
    return collapse_repeats(frame[matches])


def with_and_without_export(frame: pd.DataFrame) -> pd.DataFrame:
    """Per trace, its finished base point and the same point exporting nothing, flagged by `exporting`."""
    points = axis(frame, "tables")
    points = points[(points["exit_reason"] == "OK") & points["tables"].isin([DEFAULT_SETTINGS["tables"], NO_TABLES])]
    return points.assign(exporting=points["tables"] != NO_TABLES)
