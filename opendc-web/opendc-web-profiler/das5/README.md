# Running the profiler on DAS-5

Everything goes under one scratch directory on the head node,
`/var/scratch/$DAS_USER/opendc-profiler/`:

| Path | Content | Where it comes from |
|---|---|---|
| `opendc-profiler/` | the unpacked bundle: `bin/opendc-profiler` and its `lib/`, the launcher under `launcher/`, this directory under `das5/`, the public grid under `grids/`, the plotting wheel and its pinned requirements under `python/` | `./gradlew :opendc-web:opendc-web-profiler:das5Bundle` writes `build/distributions/opendc-profiler-das5.tar`, about 120 MB, with no trace and no credential in it |
| `traces/<name>/{tasks,fragments}.parquet` | the proprietary traces the grid names, under the same `traces/` prefix as on the laptop | the laptop's `traces/`, about 330 MB for the five the grid names |
| `grid.json` | the grid, naming traces by paths relative to the scratch directory | the laptop's `traces/profiler-grid.json`, listed with `plan` before upload |
| `out/` | `rows/` (one file per run), `measurements.csv` (their merge), `timeline/`, `cpu/`, `components/`, `histogram/`, `logs/` | produced by the jobs |

The commands, run from the repository root on the laptop. Add the jump-host options you normally
use to reach `fs0`.

```
./gradlew :opendc-web:opendc-web-profiler:das5Bundle
DAS=$DAS_USER@fs0.das5.cs.vu.nl
ssh $DAS mkdir -p /var/scratch/$DAS_USER/opendc-profiler/out
scp opendc-web/opendc-web-profiler/build/distributions/opendc-profiler-das5.tar $DAS:/var/scratch/$DAS_USER/opendc-profiler/
rsync -aR traces/solvinity/bitbrains-full traces/borg_day traces/surf traces/borg_week traces/marconi $DAS:/var/scratch/$DAS_USER/opendc-profiler/
scp traces/profiler-grid.json $DAS:/var/scratch/$DAS_USER/opendc-profiler/grid.json
```

## What is executed

On the head node, only unpacking and listing:

```
cd /var/scratch/$DAS_USER/opendc-profiler
tar xf opendc-profiler-das5.tar
module load java/jdk-21
opendc-profiler/bin/opendc-profiler plan --grid grid.json --traces .
```

`plan` reads parquet footers and prints every point with the heap it will get and what the
shipped model predicts. It runs no simulation. The grid as written lists 70 points.

On compute nodes, through SLURM, one array task per point and four at a time, keeping each
run's recording and counting objects after collections:

```
export PROFILER_HOME=/var/scratch/$DAS_USER/opendc-profiler/opendc-profiler
export GRID=/var/scratch/$DAS_USER/opendc-profiler/grid.json
export TRACES=/var/scratch/$DAS_USER/opendc-profiler
export OUT=/var/scratch/$DAS_USER/opendc-profiler/out
KEEP_RECORDINGS=1 HISTOGRAM=1 sbatch --time=00:40:00 --array=1-70%4 $PROFILER_HOME/das5/profile.sbatch
```

Each task reserves one node for at most 40 minutes and runs three JVMs in turn: `plan`, to find
its point; `measure`, the profiler; and under it exactly one launcher. DAS-5 accepts 40 minutes
outside working hours (20:00 to 08:00) only; its daytime ceiling is 15 minutes, in which case
the grid's `pointTimeoutMinutes` has to come down to 12, since a job SLURM cancels writes no row.

```
java -Xmx<heap>m -XX:+ExitOnOutOfMemoryError [-XX:+UseSerialGC|-XX:+UseG1GC] -XX:ActiveProcessorCount=<P> \
  -XX:FlightRecorderOptions:repository=<work>/<point>/jfr \
  -XX:StartFlightRecording:filename=<work>/<point>/recording.jfr,settings=profile,dumponexit=true \
  -cp "launcher/lib/*" org.opendc.web.launcher.MainKt
```

with `MANIFEST_URL` pointing at a manifest under `<work>/<point>/`. `<work>` is node-local:
`opendc-profiler-<job id>` under `WORK` if set, else under `/local/$USER`, else under
`/tmp/$USER`, so the launcher's parquet output and its recording never touch the shared file
system while the clock runs. A node where none of those can be created ends the task with its
hostname in the output, to be excluded with `sbatch --exclude=<node>`. The script removes the
directory when the task ends, and the profiler removes the point's directory as soon as its row
is written, even after a kill. The launcher stages the trace's two parquet files into that
directory as symbolic links, so the traces are read in place from scratch. The profiler itself
only reads `/proc` of the launcher and the recording it leaves. No network is used.

A point that runs past `pointTimeoutMinutes` (30 in the grid) is killed and recorded as
`TIMEOUT`, which is itself a finding: the shipped model predicts over five hours for
`borg_week` at 500 hosts. Each run writes its row as a file of its own under `out/rows/`, so
four tasks never write the same file, and `out/measurements.csv` is rebuilt from those files
whenever a task ends. A task whose point has its row already exits within a minute, having run
nothing, so the same `sbatch` line is resubmitted as it stands until `plan` and `rows/` agree;
the rows, recordings and point files already there are kept:

```
squeue -u $DAS_USER
ls out/rows | wc -l
```

## Sizing the grid for a DAS-5 node

A `defq` node has 16 cores and 64 GB. The grid sets `measureHeapMb` to 48000 so a measurement
point never runs out of heap on its own, and `pointTimeoutMinutes` to 30 so one point fits a
40-minute night reservation with time to spare for the JVMs' startup and for reading the
recording afterwards. A node with a broken local disk fails its task at once with an
`Input/output error` on `mkdir`; resubmit with that node excluded.

## Bringing the results back

```
rsync -a --exclude logs/ $DAS:/var/scratch/$DAS_USER/opendc-profiler/out/ opendc-web/opendc-web-profiler/build/profiler/
./gradlew :opendc-web:opendc-web-profiler:plots
```

No figure reads `logs/`; a point's log is for finding out why it failed or was killed, and is
read on the head node when that happens.

Plotting on the laptop is the default path: the measurements are small text files and the
laptop has `uv`. The bundle also carries the plotting wheel for a head node with Python 3.12 or
newer and access to PyPI:

```
python3 -m venv ~/opendc-plots
~/opendc-plots/bin/pip install -r opendc-profiler/python/requirements.txt opendc-profiler/python/*.whl
~/opendc-plots/bin/opendc-profiler-plots out out/figures
```

The CSV names the traces; only aggregates and figures leave the laptop afterwards.
