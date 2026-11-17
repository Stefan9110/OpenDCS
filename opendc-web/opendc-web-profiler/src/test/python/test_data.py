import math

import pandas as pd
import pytest
from factory import grid_frame, row

from opendc_profiler_plots.data import axis, base_points, derive, load


def test_time_ratio_is_blank_for_a_killed_run():
    frame = derive(pd.DataFrame([row(), row(heap_factor=1.0, exit_reason="OOM", wall_s=2.0)]))

    ok, killed = frame.iloc[0], frame.iloc[1]
    assert ok["time_ratio"] == ok["wall_s"] / ok["predicted_seconds"]
    assert math.isnan(killed["time_ratio"])
    assert bool(killed["killed"]) and not bool(ok["killed"])


def test_export_rows_are_zero_without_tables():
    frame = derive(pd.DataFrame([row(), row(tables="NONE")]))

    assert frame.iloc[0]["export_rows"] == 16 * 604_800.0 / 300
    assert frame.iloc[1]["export_rows"] == 0


def test_cores_are_cpu_over_wall():
    frame = derive(pd.DataFrame([row(cpu_total_s=12.0, wall_s=5.0)]))

    assert frame.iloc[0]["cores"] == 2.4


def test_axis_keeps_the_base_and_one_factor_rows_only():
    frame = derive(pd.DataFrame([row(), row(hosts=64), row(export_interval_s=60), row(hosts=64, export_interval_s=60)]))

    interval_axis = axis(frame, "export_interval_s")

    assert sorted(interval_axis["export_interval_s"]) == [60, 300]
    assert list(interval_axis["hosts"].unique()) == [16]


def test_base_points_are_found_from_the_rows_that_vary_around_them():
    frame = derive(grid_frame(("surf",)))

    base = base_points(frame)

    assert len(base) == 1
    assert base.iloc[0]["hosts"] == 16
    assert base.iloc[0]["tables"] == "ALL"


def test_repeats_collapse_to_one_row_per_point_on_an_axis():
    frame = derive(grid_frame(("surf",), repeats=2))

    hosts = axis(frame, "hosts")

    assert sorted(hosts["hosts"]) == [16, 64]
    assert hosts.loc[hosts["hosts"] == 16, "wall_s"].item() == pytest.approx(5.25), "the median of 5.0 and 5.5"
    assert list(hosts["repeat"]) == [1, 1], "the first repeat names the per-point files"
    assert len(base_points(frame)) == 1


def test_heap_ratio_compares_measure_heap_rows_per_unit_only():
    frame = derive(
        pd.DataFrame([row(), row(parallelism=4, active_processors=4, heap_after_gc_peak_mb=600.0), row(heap_factor=2.0, heap_mb=560)])
    )

    assert frame.iloc[0]["heap_ratio"] == 150.0 / 280.0
    assert frame.iloc[1]["heap_ratio"] == 600.0 / (280.0 * 4)
    assert math.isnan(frame.iloc[2]["heap_ratio"]), "a run capped at factor x prediction cannot show the true peak"


def test_load_prefers_the_row_files_over_a_stale_merged_csv(tmp_path):
    frame = grid_frame(("surf",))
    (tmp_path / "rows").mkdir()
    for _, measured in frame.iterrows():
        pd.DataFrame([measured]).to_csv(tmp_path / "rows" / f"{measured['point']}-1.csv", index=False)
    frame.head(2).to_csv(tmp_path / "measurements.csv", index=False)

    dataset = load(tmp_path)

    assert len(dataset.measurements) == len(frame)


def test_load_reads_per_point_files_by_stem(tmp_path):
    grid_frame().to_csv(tmp_path / "measurements.csv", index=False)
    (tmp_path / "timeline").mkdir()
    pd.DataFrame({"t_s": [0.0, 1.0], "kind": ["rss", "heap_after_gc"], "mb": [100.0, 80.0]}).to_csv(
        tmp_path / "timeline" / "surf-h16-1.csv", index=False
    )

    dataset = load(tmp_path)

    assert set(dataset.timelines) == {"surf-h16-1"}
    assert dataset.cpu == {}
    assert "cores" in dataset.measurements.columns
