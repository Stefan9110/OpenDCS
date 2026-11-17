"""Measurement rows shaped like the profiler's CSV, for tests that need a small grid."""

from __future__ import annotations

import math

import pandas as pd

DEFAULTS = {
    "repeat": 1,
    "tasks": 6295,
    "fragments": 2_323_475,
    "span_s": 604_800.0,
    "hosts": 16,
    "export_interval_s": 300,
    "tables": "ALL",
    "sample_fraction": 1.0,
    "loader": "trace",
    "parallelism": 1,
    "active_processors": 1,
    "collector": "DEFAULT",
    "heap_mb": 8192,
    "heap_factor": math.nan,
    "predicted_heap_mb": 280.0,
    "predicted_seconds": 21.0,
    "exit_code": 0,
    "exit_reason": "OK",
    "wall_s": 5.0,
    "unit_s": "4.0",
    "cpu_total_s": 12.0,
    "cpu_simulation_s": 5.0,
    "cpu_writer_s": 2.0,
    "cpu_gc_s": 2.0,
    "cpu_jit_s": 2.5,
    "cpu_other_s": 0.5,
    "rss_peak_mb": 600.0,
    "vm_hwm_mb": 610.0,
    "heap_peak_used_mb": 400.0,
    "heap_after_gc_peak_mb": 150.0,
    "gc_pause_s": 0.3,
    "gc_count": 12,
    "output_bytes": 9_000_000,
    "java_loading_share": 0.3,
    "java_simulating_share": 0.5,
    "java_exporting_share": 0.15,
    "java_other_share": 0.05,
}


def row(trace: str = "surf", **overrides) -> dict:
    values = {"trace": trace, **DEFAULTS, **overrides}
    heap = "measure" if math.isnan(values["heap_factor"]) else f"f{values['heap_factor']}"
    values["point"] = (
        f"{trace}-h{values['hosts']}-i{values['export_interval_s'] // 60}m-{values['tables'].lower()}"
        f"-s{values['sample_fraction']}-p{values['parallelism']}-{values['collector'].lower()}-{heap}"
    )
    return values


def grid_frame(traces: tuple[str, ...] = ("surf", "borg"), repeats: int = 1) -> pd.DataFrame:
    """One base point per trace plus one point on every axis the profiler varies, each measured `repeats` times."""
    rows = []
    for trace in traces:
        for repeat in range(1, repeats + 1):
            slower = 1.0 + 0.1 * (repeat - 1)
            rows.append(row(trace, repeat=repeat, wall_s=5.0 * slower))
            rows.append(row(trace, repeat=repeat, hosts=64, wall_s=7.0 * slower))
            rows.append(row(trace, repeat=repeat, export_interval_s=60, wall_s=9.0 * slower, output_bytes=45_000_000))
            rows.append(row(trace, repeat=repeat, tables="NONE", wall_s=3.5 * slower, output_bytes=0))
            rows.append(row(trace, repeat=repeat, tables="HOST_ONLY", wall_s=4.0 * slower, output_bytes=2_000_000))
            rows.append(row(trace, repeat=repeat, sample_fraction=0.1, wall_s=1.5 * slower))
            rows.append(row(trace, repeat=repeat, collector="SERIAL", rss_peak_mb=450.0))
            rows.append(row(trace, repeat=repeat, parallelism=4, active_processors=4, unit_s="4.0;4.5;5.0;6.0", wall_s=6.5 * slower))
            rows.append(row(trace, repeat=repeat, heap_factor=1.0, heap_mb=280, exit_code=3, exit_reason="OOM", wall_s=2.0))
            rows.append(row(trace, repeat=repeat, heap_factor=2.0, heap_mb=560))
            rows.append(row(trace, repeat=repeat, parallelism=4, active_processors=4, collector="G1", exit_code=23, exit_reason="UNITS_FAILED", unit_s=math.nan))
    return pd.DataFrame(rows)
