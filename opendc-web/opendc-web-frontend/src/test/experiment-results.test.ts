import {
    RESULT_METRICS,
    type ResultPoint,
    type ScenarioResults,
    alignOnTimestamp,
    bucketPoints,
    isLiveResults,
    reduceMetric,
    reduceSeries,
    reportedMetrics,
    seriesOf,
} from "@/lib/experiment/results"
import { type ExperimentSpec, scenarioCount } from "@/lib/experiment/spec"
import { NOT_CANCELLED, deriveScenarioStatuses } from "@/lib/sample/execution"
import { DEFAULT_BUCKETS, EXPORT_INTERVAL_MS, bucketStepsFor, deriveResults } from "@/lib/sample/results"
import type { ClusterSpec, TopologySpec } from "@/lib/topology/spec"
import { describe, expect, it } from "vitest"

const SUBMITTED_AT = 1_000_000

function points(...values: number[]): ResultPoint[] {
    return values.map((value, index) => ({ t: index * EXPORT_INTERVAL_MS, value }))
}

function cluster(name: string, hosts: number, extra: Partial<ClusterSpec> = {}): ClusterSpec {
    return {
        name,
        hosts: [
            {
                count: hosts,
                cpu: { coreCount: 16, coreSpeed: "3 GHz" },
                memory: { size: "128 GiB" },
                cpuPowerModel: { type: "linear", maxPower: "400 Watts", idlePower: "120 Watts" },
            },
        ],
        powerSource: { name: "grid", maxPower: "50 kWatts" },
        ...extra,
    }
}

const BATTERY: ClusterSpec["battery"] = {
    name: "store",
    capacity: 200,
    chargingSpeed: 25_000,
    initialCharge: 50,
    policy: { type: "single", carbonThreshold: 150 },
    embodiedCarbon: 4500,
    expectedLifetime: 10,
}

function trace(name: string) {
    return { type: "trace", source: { type: "named", name } } as const
}

function spec(topologies: TopologySpec[], overrides: Partial<ExperimentSpec> = {}): ExperimentSpec {
    return { topologies, workloads: [trace("bitbrains-small")], ...overrides }
}

function spanOf(points: ResultPoint[]): number {
    return points.at(-1)?.t ?? 0
}

function results(
    experimentSpec: ExperimentSpec,
    nowMs: number,
    cancelledAtMs = NOT_CANCELLED,
    seed = "seed",
    buckets = DEFAULT_BUCKETS,
) {
    return deriveResults(
        {
            experimentId: 1,
            schedule: {
                seed,
                scenarioCount: scenarioCount(experimentSpec),
                submittedAtMs: SUBMITTED_AT,
                cancelledAtMs,
            },
            spec: experimentSpec,
            buckets,
        },
        nowMs,
    )
}

function only(experimentSpec: ExperimentSpec, nowMs: number): ScenarioResults {
    const scenario = results(experimentSpec, nowMs).scenarios[0]
    if (!scenario) throw new Error("the experiment reported no scenario")
    return scenario
}

const SMALL = spec([{ clusters: [cluster("a", 4)] }])
const LARGE = spec([{ clusters: [cluster("a", 64)] }])
const SETTLED = SUBMITTED_AT + 10_000_000

describe("the metric catalog", () => {
    it("identifies every metric by the export table and column it is read from", () => {
        for (const metric of RESULT_METRICS) {
            expect(metric.id).toBe(`${metric.table}.${metric.column}`)
        }
    })

    it("has no duplicate identities, so a chart can never read the wrong column", () => {
        expect(new Set(RESULT_METRICS.map((metric) => metric.id)).size).toBe(RESULT_METRICS.length)
    })

    it("reduces energy and carbon by summing intervals, not by averaging them", () => {
        const summed = RESULT_METRICS.filter((metric) => metric.reduce === "sum").map((metric) => metric.id)
        expect(summed).toContain("powerSource.energy_usage")
        expect(summed).toContain("powerSource.carbon_emission")
    })
})

describe("reduceSeries", () => {
    it("reports an empty series as having no value rather than as zero", () => {
        expect(reduceSeries([], "mean")).toEqual({ status: "empty" })
        expect(reduceSeries([], "sum")).toEqual({ status: "empty" })
        expect(reduceSeries([], "max")).toEqual({ status: "empty" })
        expect(reduceSeries([], "last")).toEqual({ status: "empty" })
    })

    it("distinguishes a genuine zero from missing data", () => {
        expect(reduceSeries(points(0, 0), "mean")).toEqual({ status: "ok", value: 0 })
    })

    it("takes the final sample for cumulative counters instead of the largest", () => {
        expect(reduceSeries(points(5, 9, 7), "last")).toEqual({ status: "ok", value: 7 })
        expect(reduceSeries(points(5, 9, 7), "max")).toEqual({ status: "ok", value: 9 })
    })

    it("averages over the samples present, not over the full simulated span", () => {
        expect(reduceSeries(points(2, 4), "mean")).toEqual({ status: "ok", value: 3 })
    })

    it("applies the reduction the metric declares", () => {
        const scenario = {
            scenarioIndex: 0,
            seeds: 1,
            complete: true,
            series: [
                { metric: "powerSource.energy_usage" as const, points: points(10, 10, 10), spread: 0 },
                { metric: "host.cpu_utilization" as const, points: points(0.2, 0.8), spread: 0 },
            ],
        }
        expect(reduceMetric(scenario, "powerSource.energy_usage")).toEqual({ status: "ok", value: 30 })
        expect(reduceMetric(scenario, "host.cpu_utilization")).toEqual({ status: "ok", value: 0.5 })
    })

    it("treats a metric the run never reported as empty instead of failing", () => {
        const scenario = { scenarioIndex: 0, seeds: 1, complete: true, series: [] }
        expect(seriesOf(scenario, "battery.charge").points).toEqual([])
        expect(reduceMetric(scenario, "battery.charge")).toEqual({ status: "empty" })
    })
})

describe("bucketStepsFor", () => {
    const DAY = 288

    it("does not bucket at all while a run still fits in the point budget", () => {
        expect(bucketStepsFor(DAY, 480)).toBe(1)
    })

    it("snaps a long run onto a whole-day bucket rather than an 18 hour one", () => {
        expect(bucketStepsFor(365 * DAY, 480)).toBe(DAY)
    })

    it("keeps the daily cycle visible on a month long run", () => {
        const steps = bucketStepsFor(30 * DAY, 480)
        expect(steps).toBeLessThan(DAY)
        expect(DAY % steps).toBe(0)
    })

    it("only ever picks a width that divides or multiplies a day, so cycles cannot alias", () => {
        for (const span of [DAY, 7 * DAY, 30 * DAY, 180 * DAY, 365 * DAY, 3000 * DAY]) {
            const steps = bucketStepsFor(span, 480)
            expect(steps <= DAY ? DAY % steps === 0 : steps % DAY === 0).toBe(true)
        }
    })

    it("stays inside the point budget even for a run far longer than any real trace", () => {
        const span = 20_000 * DAY
        expect(Math.ceil(span / bucketStepsFor(span, 480))).toBeLessThanOrEqual(480)
    })
})

describe("bucketPoints", () => {
    it("leaves a series alone when it already fits", () => {
        expect(bucketPoints(points(1, 2, 3), 10, "mean")).toEqual(points(1, 2, 3))
    })

    it("preserves an additive total instead of averaging it away", () => {
        const bucketed = bucketPoints(points(1, 1, 1, 1, 1, 1), 3, "sum")
        expect(bucketed.map((point) => point.value)).toEqual([2, 2, 2])
        expect(reduceSeries(bucketed, "sum")).toEqual({ status: "ok", value: 6 })
    })

    it("carries a cumulative counter forward rather than averaging inside a bucket", () => {
        expect(bucketPoints(points(1, 2, 3, 4), 2, "last").map((point) => point.value)).toEqual([2, 4])
    })

    it("keeps a spike visible instead of smoothing it into the mean", () => {
        expect(bucketPoints(points(0, 90, 0, 0), 2, "max").map((point) => point.value)).toEqual([90, 0])
    })

    it("stamps each bucket with the time it starts at, so the axis stays monotone", () => {
        const bucketed = bucketPoints(points(1, 2, 3, 4, 5, 6), 3, "mean")
        expect(bucketed.map((point) => point.t)).toEqual([0, 2 * EXPORT_INTERVAL_MS, 4 * EXPORT_INTERVAL_MS])
    })

    it("swallows no samples when the count does not divide evenly", () => {
        const bucketed = bucketPoints(points(1, 1, 1, 1, 1, 1, 1), 3, "sum")
        expect(reduceSeries(bucketed, "sum")).toEqual({ status: "ok", value: 7 })
    })

    it("has nothing to draw when asked for no buckets at all", () => {
        expect(bucketPoints(points(1, 2, 3), 0, "mean")).toEqual([])
    })
})

describe("alignOnTimestamp", () => {
    it("keeps samples from a shorter run instead of truncating every run to it", () => {
        const rows = alignOnTimestamp([points(1, 2, 3), points(4)])
        expect(rows).toHaveLength(3)
        expect(rows[0]?.values).toEqual({ 0: 1, 1: 4 })
        expect(rows[2]?.values).toEqual({ 0: 3 })
    })

    it("orders rows by simulated time even when the series arrive out of order", () => {
        const rows = alignOnTimestamp([[{ t: 600, value: 1 }], [{ t: 0, value: 2 }]])
        expect(rows.map((row) => row.t)).toEqual([0, 600])
    })

    it("has nothing to plot when no run has reported yet", () => {
        expect(alignOnTimestamp([])).toEqual([])
        expect(alignOnTimestamp([[], []])).toEqual([])
    })
})

describe("live results", () => {
    it("does not poll a draft that has no scenarios to report on", () => {
        expect(
            isLiveResults({ experimentId: 1, exportIntervalMs: 1, bucketMs: 1, complete: false, scenarios: [] }),
        ).toBe(false)
    })

    it("stops polling once every scenario has settled", () => {
        const finished = results(SMALL, SUBMITTED_AT + 10_000_000)
        expect(finished.complete).toBe(true)
        expect(isLiveResults(finished)).toBe(false)
    })

    it("keeps polling while a scenario is still producing samples", () => {
        const midway = results(SMALL, SUBMITTED_AT + 5_000)
        expect(isLiveResults(midway)).toBe(true)
    })
})

describe("simulated results", () => {
    it("reports nothing for a scenario that has not started", () => {
        const queued = results(SMALL, SUBMITTED_AT)
        expect(queued.scenarios[0]?.series.every((series) => series.points.length === 0)).toBe(true)
    })

    it("covers more simulated time the longer a run has been going", () => {
        const early = seriesOf(only(SMALL, SUBMITTED_AT + 6_000), "powerSource.power_draw").points
        const later = seriesOf(only(SMALL, SUBMITTED_AT + 12_000), "powerSource.power_draw").points

        expect(spanOf(later)).toBeGreaterThan(spanOf(early))
    })

    it("never reports more points than the caller asked for, whatever the trace length", () => {
        const year = spec([{ clusters: [cluster("a", 4)] }], { workloads: [trace("azure-2019")] })
        const settled = results(year, SETTLED, NOT_CANCELLED, "seed", 120)

        expect(settled.scenarios[0]?.series.every((series) => series.points.length <= 120)).toBe(true)
        expect(settled.bucketMs).toBeGreaterThan(EXPORT_INTERVAL_MS)
    })

    it("reports timestamps that only move forward and start at the beginning of the run", () => {
        const series = seriesOf(only(SMALL, SETTLED), "powerSource.power_draw").points
        expect(series[0]?.t).toBe(0)
        expect(series.every((point, index) => index === 0 || point.t > (series[index - 1]?.t ?? 0))).toBe(true)
    })

    it("keeps the energy total intact no matter how coarsely it is bucketed", () => {
        const fine = reduceMetric(only(SMALL, SETTLED), "powerSource.energy_usage")
        const coarse = results(SMALL, SETTLED, NOT_CANCELLED, "seed", 40).scenarios[0]
        const reduced = coarse === undefined ? undefined : reduceMetric(coarse, "powerSource.energy_usage")

        expect(fine.status).toBe("ok")
        expect(reduced?.status).toBe("ok")
        if (fine.status !== "ok" || reduced?.status !== "ok") return
        expect(Math.abs(reduced.value - fine.value) / fine.value).toBeLessThan(0.05)
    })

    it("draws more power for a larger topology, so the numbers follow the design", () => {
        const small = reduceMetric(only(SMALL, SETTLED), "powerSource.power_draw")
        const large = reduceMetric(only(LARGE, SETTLED), "powerSource.power_draw")

        expect(small.status === "ok" && large.status === "ok" && large.value > small.value * 4).toBe(true)
    })

    it("reports battery charge only for a topology that actually stores energy", () => {
        const stored = spec([{ clusters: [cluster("a", 4, { battery: BATTERY })] }])

        expect(reportedMetrics(results(stored, SETTLED)).map((metric) => metric.id)).toContain("battery.charge")
        expect(reportedMetrics(results(SMALL, SETTLED)).map((metric) => metric.id)).not.toContain("battery.charge")
    })

    it("cuts a failed run short instead of covering the whole trace", () => {
        const wide = spec([{ clusters: [cluster("a", 4)] }], {
            maxNumFailures: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12],
        })
        const settled = results(wide, SUBMITTED_AT + 100_000_000, NOT_CANCELLED, "abc").scenarios
        const spans = new Map(
            settled.map((scenario) => [
                scenario.scenarioIndex,
                spanOf(seriesOf(scenario, "host.cpu_utilization").points),
            ]),
        )
        const whole = Math.max(...spans.values())
        const statuses = deriveScenarioStatuses(
            { seed: "abc", scenarioCount: settled.length, submittedAtMs: SUBMITTED_AT, cancelledAtMs: NOT_CANCELLED },
            SUBMITTED_AT + 100_000_000,
        )
        const failed = statuses.filter((status) => status.state === "failed")
        const succeeded = statuses.filter((status) => status.state === "succeeded")

        expect(failed.length).toBeGreaterThan(0)
        expect(succeeded.every((status) => spans.get(status.scenarioIndex) === whole)).toBe(true)
        expect(failed.every((status) => (spans.get(status.scenarioIndex) ?? whole) < whole)).toBe(true)
    })

    it("never reports a negative queue depth", () => {
        const queued = seriesOf(only(SMALL, SETTLED), "service.tasks_pending").points
        expect(queued.every((point) => point.value >= 0)).toBe(true)
    })

    it("never walks a cumulative task counter backwards", () => {
        const completed = seriesOf(only(SMALL, SETTLED), "service.tasks_completed").points.map((point) => point.value)
        expect(completed.every((value, index) => index === 0 || value >= (completed[index - 1] ?? 0))).toBe(true)
    })

    it("freezes at the moment of cancellation rather than filling in the rest of the run", () => {
        const cancelledAtMs = SUBMITTED_AT + 8_000
        const atCancel = results(SMALL, cancelledAtMs, cancelledAtMs)
        const longAfter = results(SMALL, cancelledAtMs + 10_000_000, cancelledAtMs)
        expect(longAfter).toEqual(atCancel)
    })

    it("has no seed spread to report when the scenario runs only once", () => {
        const single = only(SMALL, SETTLED)
        const repeated = only(spec([{ clusters: [cluster("a", 4)] }], { runs: 4 }), SETTLED)

        expect(single.seeds).toBe(1)
        expect(seriesOf(single, "powerSource.energy_usage").spread).toBe(0)
        expect(repeated.seeds).toBe(4)
        expect(seriesOf(repeated, "powerSource.energy_usage").spread).toBeGreaterThan(0)
    })

    it("has nothing to report for an experiment that expands to no scenarios", () => {
        const empty = results(spec([{ clusters: [cluster("a", 4)] }], { workloads: [] }), SETTLED)
        expect(empty.scenarios).toEqual([])
        expect(empty.complete).toBe(false)
    })
})
