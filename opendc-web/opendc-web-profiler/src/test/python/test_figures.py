import math
from pathlib import Path
from types import SimpleNamespace

import pandas as pd
import pytest
from factory import grid_frame, row

from opendc_profiler_plots import cli, figures
from opendc_profiler_plots.data import Dataset, base_points, derive
from opendc_profiler_plots.figures import charge, components, composition, export, fit, footprint, headroom, throughput, timeline, topology

SHARES = {"LOADING": 0.15, "SIMULATING": 0.55, "COLLECTING": 0.05, "WRITING": 0.2, "OTHER": 0.05}


def dataset(histograms: dict | None = None, repeats: int = 1) -> Dataset:
    measurements = derive(grid_frame(repeats=repeats))
    timelines = {
        f"{point}-1": pd.DataFrame(
            {"t_s": [0.0, 1.0, 2.0, 3.0], "kind": ["rss", "heap_after_gc", "rss", "heap_after_gc"], "mb": [120.0, 60.0, 400.0, 150.0]}
        )
        for point in base_points(measurements)["point"]
    }
    shares = {
        f"{point}-1": pd.DataFrame({"component": list(SHARES), "share": list(SHARES.values())}) for point in measurements["point"].unique()
    }
    return Dataset(measurements, timelines, {}, shares, histograms or {})


@pytest.mark.parametrize("figure", [figure for figure in figures.ALL if figure.NAME != "composition"], ids=lambda figure: figure.NAME)
def test_every_figure_renders_a_pdf_and_a_png(figure, tmp_path):
    path = figure.render(dataset(), tmp_path)

    assert path is not None
    assert path.suffix == ".pdf" and path.exists()
    assert path.with_suffix(".png").exists()


@pytest.mark.parametrize("figure", [figure for figure in figures.ALL if figure.NAME != "composition"], ids=lambda figure: figure.NAME)
def test_every_figure_renders_when_points_were_repeated(figure, tmp_path):
    assert figure.render(dataset(repeats=2), tmp_path) is not None


def test_composition_is_skipped_without_histograms(tmp_path):
    assert composition.render(dataset(), tmp_path) is None


def test_composition_groups_jvm_array_descriptors_with_the_fragments():
    histogram = pd.DataFrame(
        {
            "class": ["[J", "[D", "[Ljava.lang.Object;", "org.opendc.simulator.compute.workload.trace.TraceFragment"],
            "count": [1, 1, 1, 1],
            "bytes": [100, 50, 10, 5],
        }
    )

    groups = composition.heap_groups(histogram)

    assert groups["fragment arrays"] == 155
    assert groups["other"] == 10


def test_composition_shares_the_listed_heap_among_the_kinds():
    histogram = pd.DataFrame({"class": ["[J", "[B"], "count": [1, 1], "bytes": [300, 100]})
    stems = [f"{point}-1" for point in base_points(derive(grid_frame()))["point"]]

    table = composition.heap_shares(dataset({stem: histogram for stem in stems}))

    assert table.loc["surf", "fragment arrays"] == 75.0
    assert table.loc["surf", "byte arrays"] == 25.0
    assert table.loc["surf", "export"] == 0.0


def test_export_points_keep_the_interval_and_table_axes_only():
    points = export.export_points(derive(grid_frame(("surf",))))

    assert set(points["hosts"]) == {16}, "a host point grows the simulation as well, so it says nothing about export alone"
    assert set(points["export_interval_s"]) == {60, 300}


def test_export_settings_run_from_no_export_to_the_finest_interval():
    points = export.export_points(derive(grid_frame(("surf",))))

    assert export.ordered_settings(points) == ["no export", "host table, 5 min", "all tables, 5 min", "all tables, 1 min"]


def test_fit_scales_the_heap_prediction_by_the_units_sharing_the_jvm_and_leaves_oom_capped_and_sampled_runs_out():
    table = fit.predicted_and_measured(derive(grid_frame(("surf",)))).set_index("point")

    four_units = table[table.index.str.contains("-p4-default-")].iloc[0]
    capped = table[table.index.str.endswith("-f2.0")].iloc[0]
    assert four_units["predicted_heap"] == 280.0 * 4 and four_units["measured_heap"] == 150.0
    assert math.isnan(capped["measured_heap"]) and capped["measured_seconds"] == 5.0, "a capped heap is a bound, not a measurement"
    assert not table.index.str.endswith("-f1.0").any(), "the run that ran out of memory has no measurement"
    assert not table.index.str.contains("-s0.1-").any(), "a sampled run spins on duplicate tasks and says nothing"
    assert table["finished"].all()


def test_fit_keeps_a_run_killed_at_the_time_limit_as_a_censored_time_without_a_heap():
    frame = derive(pd.DataFrame([row(), row(export_interval_s=60, exit_reason="TIMEOUT", exit_code=137, wall_s=1800.0)]))

    table = fit.predicted_and_measured(frame).set_index("point")
    killed = table[~table["finished"]].iloc[0]

    assert len(table) == 2
    assert killed["measured_seconds"] == 1800.0 and math.isnan(killed["measured_heap"])


def test_components_pairs_each_trace_with_and_without_export_where_a_recording_was_read():
    table = components.component_shares(dataset()).set_index(["trace", "exporting"])

    assert set(table.index) == {("surf", True), ("surf", False), ("borg", True), ("borg", False)}
    assert table.loc[("surf", True), "WRITING"] == 0.2 and table.loc[("surf", False), "COLLECTING"] == 0.05
    assert (table.sum(axis=1) - 1.0).abs().max() < 1e-9


def test_components_skips_a_run_without_a_recording():
    data = dataset()
    data.components.clear()

    assert components.component_shares(data).empty
    assert components.render(data, Path("unused")) is None


def test_throughput_pairs_each_trace_with_and_without_export():
    table = throughput.rates(derive(grid_frame(("surf",)))).set_index("exporting")

    assert table.loc[True, "days_per_s"] == pytest.approx(7.0 / 5.0)
    assert table.loc[False, "days_per_s"] == pytest.approx(7.0 / 3.5)
    assert table.loc[True, "fragments_per_s"] == pytest.approx(2_323_475 / 5.0)


def test_cli_renders_the_other_figures_when_one_fails(tmp_path, monkeypatch, capsys):
    grid_frame().to_csv(tmp_path / "measurements.csv", index=False)
    rendered = []
    broken = SimpleNamespace(NAME="broken", render=lambda data, out: 1 / 0)
    fine = SimpleNamespace(NAME="fine", render=lambda data, out: rendered.append(out) or out / "fine.pdf")
    monkeypatch.setattr(cli, "ALL", (broken, fine))

    status = cli.main([str(tmp_path), str(tmp_path / "figures")])

    assert status == 1
    assert rendered == [tmp_path / "figures"]
    assert "ZeroDivisionError" in capsys.readouterr().err


def test_composition_renders_from_a_histogram(tmp_path):
    histogram = pd.DataFrame(
        {
            "class": ["org.opendc.simulator.compute.workload.trace.TraceFragment", "long[]", "org.opendc.simulator.compute.SimHost", "int[]"],
            "count": [1000, 50, 10, 500],
            "bytes": [48_000, 8_000_000, 20_000, 200_000],
        }
    )
    stems = [f"{point}-1" for point in base_points(derive(grid_frame()))["point"]]

    path = composition.render(dataset({stem: histogram for stem in stems}), tmp_path)

    assert path is not None and path.exists()


def test_headroom_measures_unused_predicted_heap_over_serial_uncapped_one_unit_runs():
    frame = derive(
        pd.DataFrame(
            [
                row(heap_after_gc_peak_mb=210.0),
                row(collector="SERIAL", heap_after_gc_peak_mb=140.0),
                row(collector="G1", heap_after_gc_peak_mb=20.0),
                row(parallelism=4, active_processors=4, heap_after_gc_peak_mb=20.0),
                row(heap_factor=1.0, heap_mb=280, heap_after_gc_peak_mb=20.0),
                row("borg", heap_after_gc_peak_mb=350.0),
            ]
        )
    )

    shares = headroom.unused_share(frame)

    assert shares["surf"] == pytest.approx(37.5), "G1, four units and a capped heap say nothing about the need"
    assert shares["borg"] == pytest.approx(-25.0), "a trace that needed more than predicted goes below zero"


def test_charge_compares_billed_core_seconds_with_cpu_consumed():
    frame = derive(pd.DataFrame([row(wall_s=5.0, cpu_total_s=8.0), row(parallelism=4, active_processors=4, wall_s=6.5, cpu_total_s=20.0)]))

    compared = charge.billed_and_consumed(frame).set_index("parallelism")

    assert compared.loc[1, "billed_s"] == 5.0 and compared.loc[1, "consumed_s"] == 8.0
    assert compared.loc[4, "billed_s"] == 26.0 and compared.loc[4, "consumed_s"] == 20.0


def test_charge_skips_a_run_without_cpu_seconds():
    frame = derive(pd.DataFrame([row(cpu_total_s=math.nan)]))

    assert charge.billed_and_consumed(frame).empty


def test_footprint_pairs_live_heap_with_resident_memory_per_collector():
    frame = derive(
        pd.DataFrame(
            [
                row(),
                row(collector="SERIAL", heap_after_gc_peak_mb=150.0, rss_peak_mb=600.0),
                row(collector="G1", heap_after_gc_peak_mb=160.0, rss_peak_mb=6000.0),
            ]
        )
    )

    table = footprint.resident_and_live(frame).set_index("collector")

    assert list(table.index) == ["SERIAL", "G1"], "the JVM's own choice is not a collector of its own"
    assert table.loc["G1", "resident_mb"] == 6000.0 and table.loc["G1", "live_mb"] == 160.0
    assert table.loc["SERIAL", "resident_mb"] == 600.0
    assert table.loc["SERIAL", "ratio"] == 4.0 and table.loc["G1", "ratio"] == 37.5


def test_topology_keeps_the_host_axis_in_host_order():
    table = topology.time_by_hosts(derive(grid_frame(("surf",))))

    assert list(table["hosts"]) == [16, 64]
    assert list(table["wall_s"]) == [5.0, 7.0]
    assert table["finished"].all()


def test_topology_and_export_keep_a_killed_run_flagged_and_drop_other_failures():
    frame = derive(
        pd.DataFrame(
            [
                row(),
                row(hosts=64, exit_reason="TIMEOUT", exit_code=137, wall_s=1800.0, output_bytes=0),
                row(hosts=256, exit_reason="OOM", exit_code=3, wall_s=2.0, output_bytes=0),
                row(export_interval_s=60, exit_reason="TIMEOUT", exit_code=137, wall_s=1800.0, output_bytes=0),
            ]
        )
    )

    hosts = topology.time_by_hosts(frame)
    settings = export.export_points(frame)

    assert list(hosts["hosts"]) == [16, 64] and list(hosts["finished"]) == [True, False]
    assert set(settings["setting"]) == {"all tables, 5 min", "all tables, 1 min"}
    assert list(settings.set_index("setting").loc[["all tables, 5 min", "all tables, 1 min"], "finished"]) == [True, False]


def test_timeline_names_the_traces_whose_base_run_was_killed():
    frame = derive(pd.DataFrame([row(), row("borg", exit_reason="TIMEOUT", exit_code=137, wall_s=1800.0)]))

    assert timeline.killed_bases(Dataset(frame, {}, {}, {}, {})) == {"borg"}


def test_timeline_drops_the_resident_sample_read_after_the_process_exited():
    series = pd.DataFrame({"t_s": [0.0, 1.0, 2.0, 2.2], "kind": ["rss", "heap_after_gc", "rss", "rss"], "mb": [100.0, 60.0, 400.0, 0.0]})

    cleaned = timeline.without_exit_sample(series)

    assert list(cleaned["mb"]) == [100.0, 60.0, 400.0]
