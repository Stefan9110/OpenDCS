# opendc-web-profiler

## What it measures

One grid point is one launcher JVM, started the way the local dispatcher starts it, over one trace
with one topology size, export interval, table set, sample fraction, collector, parallelism and
heap. Around each trace's base point every setting is varied alone. Per run the profiler records:

- how the JVM ended (`OK`, `UNITS_FAILED`, `OOM`, `TIMEOUT`), its wall time and each unit's own
  seconds;
- CPU seconds by thread name, grouped into simulation (the runner's fork-join workers), export
  writers, GC, JIT and other, and resident memory over time, sampled from `/proc` while it runs;
- heap after every collection, GC pauses, the share of Java samples spent loading, simulating,
  collecting telemetry and writing it, and optionally bytes by class, from a Flight Recorder
  recording;
- the launcher's own peak report, the bytes it published, and what the shipped estimator would
  have predicted for the same run.

Each run writes its row as a file of its own under `rows/`, whole or not at all, so jobs on
several nodes never share a file; `measurements.csv` is their merge, rebuilt whenever `measure`
ends. The series behind each row land in `timeline/`, `cpu/`, `components/` and `histogram/`. The
launcher's output streams into its workspace while it runs; `logs/` keeps the last 500 lines of
a finished run and all of one that failed or was killed. A rerun skips every run that has a row
file.

## Commands

```
./gradlew :opendc-web:opendc-web-profiler:installDist
build/install/opendc-profiler/bin/opendc-profiler plan --grid grids/public.json --traces <repo root>
build/install/opendc-profiler/bin/opendc-profiler measure --grid <grid> --traces <root> --launcher-lib <lib> --out <dir> [--work <dir>] [--max-points N] [--histogram] [--keep-recordings] [--only id,...]
build/install/opendc-profiler/bin/opendc-profiler inspect <trace dir>...
build/install/opendc-profiler/bin/opendc-profiler recordings <out>/recordings --out <out>
```

`plan` reads parquet footers and lists every point with the heap it will get; it runs nothing.
`recordings` re-reads the recordings a `--keep-recordings` run kept and rewrites `components/` and
`histogram/` from them, for when the reading has been corrected since the run.
`--work` is where a point's launcher runs while it is measured: node-local storage on a cluster,
so that timings do not pay for a shared file system. Gradle's `profile` task measures the public
grid on this machine into `build/profiler`.

A grid is JSON: traces with their host counts, plus the axes and their defaults
(`src/main/resources/grids/public.json` is the committed example). A grid naming proprietary
traces lives under the repository's gitignored `traces/`.

## Figures

The plotting package is a uv project rooted in this directory: sources in
`src/main/python/opendc_profiler_plots`, tests in `src/test/python`. Gradle drives it:

| Task | Does |
|---|---|
| `uvSync` | `uv sync --frozen` |
| `pythonTest` | `uv run pytest` |
| `plots` | renders `build/profiler` into `build/profiler/figures`, PDF and PNG |
| `uvBuild`, `uvExport` | the wheel and pinned requirements under `build/python` |
| `das5Bundle` | one tarball with the distribution, the launcher and the wheel |

Each figure is one module with a named transform that computes exactly what is drawn:

| Figure | Shows | Transform |
|---|---|---|
| `cores` | cores a unit keeps busy, stacked by thread group, against the one core granted | `cores_by_group` |
| `components` | share of Java samples loading, simulating, collecting telemetry and writing tables, with export and with no tables | `component_shares` |
| `fit` | measured against predicted, heap and time, every run of a whole trace, with the diagonal and a 2x band; killed runs as hollow triangles at the time limit | `predicted_and_measured` |
| `headroom` | share of the predicted heap left unused by the live data, per trace, the median over serial uncapped runs | `unused_share` |
| `sampling` | heap and wall time at a sample fraction relative to the whole trace | `relative_to_full` |
| `export` | wall time and output size per export setting, from no export to every table every minute; killed runs as hollow bars at the time limit | `export_points`, `ordered_settings` |
| `charge` | billed core-seconds, wall time times units, against CPU consumed, at one and four units | `billed_and_consumed` |
| `throughput` | simulated days and fragments per wall-clock second, with and without export | `rates` |
| `topology` | wall time at each host count the same workload ran on, bars grouped by trace; killed runs as hollow bars at the time limit | `time_by_hosts` |
| `footprint` | resident memory over live heap under each collector, at one unit | `resident_and_live` |
| `timeline` | resident memory and heap after GC over one run per trace, a killed base run named as such | `base_timelines`, `killed_bases`, `without_exit_sample` |
| `composition` | each kind of object's share of the live heap, one panel per trace, bars coloured green to red as a kind takes over | `heap_groups`, `heap_shares` |

`cores`, `components`, `throughput`, `charge` and `footprint` read a trace's finished base run, so
a trace whose base run was killed at the time limit is absent from them; `fit`, `export` and
`topology` draw such a run as a hollow mark at the limit, and `headroom`, `composition` and
`timeline` use whatever runs and recordings the trace has.

Axes step evenly: linear with regular steps, or logarithmic with one label per decade where the
values span two decades or where ratios are the point. The histogram behind `composition` is
taken by the JVM after its last full collection and lists the classes above one percent of the
heap at that moment; the shares are of what it listed.

## DAS-5

`das5/README.md` states what is uploaded where and what runs; `das5/profile.sbatch` is the job.

