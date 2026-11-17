#!/bin/bash
#SBATCH --job-name=opendc-profiler
#SBATCH --nodes=1
#SBATCH --exclusive
#SBATCH --time=00:40:00
#SBATCH --output=opendc-profiler-%j.out
#
# As an array job, task N measures the N-th point `plan` lists, so tasks never race for a point
# and the array can be resubmitted: a point that has its row already is skipped. Without an array
# index, the job measures the next MAX_POINTS (default 1) points that have none.
#
# The job's time limit must stay above the grid's pointTimeoutMinutes (30) with room for the
# three JVMs' startup and for reading the recording, since a job SLURM cancels writes no row.
# DAS-5 accepts 40 minutes outside working hours only; in daytime pass --time=00:15:00 and a grid
# whose pointTimeoutMinutes is 12.
#
# Environment: PROFILER_HOME (the unpacked bundle), GRID (the grid file), TRACES (the directory
# grid paths are relative to), OUT (where rows land). Optional: WORK, a node-local base directory
# for the launcher to run in (tried before /local/$USER and /tmp/$USER; the task makes its own
# directory under it and removes it at the end); MAX_POINTS; HISTOGRAM=1 to count objects after
# every collection; KEEP_RECORDINGS=1 to copy each run's recording to $OUT/recordings.
set -euo pipefail

module load java/jdk-21
cd "$PROFILER_HOME"

work=""
for base in ${WORK:+"$WORK"} "/local/$USER" "/tmp/$USER"; do
  candidate="$base/opendc-profiler-${SLURM_JOB_ID:-$$}"
  if mkdir -p "$candidate" 2>/dev/null; then
    work="$candidate"
    break
  fi
done
if [[ -z "$work" ]]; then
  echo "no writable node-local directory on $(hostname); exclude it with sbatch --exclude=$(hostname)"
  exit 1
fi
echo "work directory: $work on $(hostname)"
trap 'rm -rf "$work"' EXIT

selection=(--max-points "${MAX_POINTS:-1}")
if [[ -n "${SLURM_ARRAY_TASK_ID:-}" ]]; then
  point=$(bin/opendc-profiler plan --grid "$GRID" --traces "$TRACES" | awk '/ heap /{print $1}' | sed -n "${SLURM_ARRAY_TASK_ID}p")
  if [[ -z "$point" ]]; then
    echo "the grid has no point ${SLURM_ARRAY_TASK_ID}"
    exit 0
  fi
  selection=(--only "$point")
fi

bin/opendc-profiler measure \
  --grid "$GRID" \
  --traces "$TRACES" \
  --launcher-lib "$PROFILER_HOME/launcher/lib" \
  --out "$OUT" \
  --work "$work" \
  "${selection[@]}" \
  ${HISTOGRAM:+--histogram} \
  ${KEEP_RECORDINGS:+--keep-recordings}
