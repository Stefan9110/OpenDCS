import {
    type ExperimentResults,
    type MetricId,
    type ResultSeries,
    type ScenarioResults,
    metricById,
    reduceSeries,
} from "@/lib/experiment/results"
import { type ExperimentSpec, type ScenarioSpec, experimentRuns } from "@/lib/experiment/spec"
import { isTerminalScenario } from "@/lib/experiment/status"
import { DEFAULT_TRACE_SPAN_DAYS, TRACE_SPAN_DAYS } from "@/lib/sample/dataset"
import {
    type ExecutionSchedule,
    type ScenarioTimeline,
    expandScenarios,
    sampleNoise,
    scenarioTimelines,
} from "@/lib/sample/execution"
import { topologyCapacity } from "@/lib/topology/capacity"

export const EXPORT_INTERVAL_MS = 300_000

// A chart has room for a few hundred points. A month of simulated time exports 8,640 samples per
// series at the default interval and a year exports 105,120, so the reduction belongs here rather
// than in the browser.
export const DEFAULT_BUCKETS = 480

const DAY_MS = 86_400_000
const BUCKET_TAPS = 6
const IDLE_SHARE = 0.35
const FACILITY_OVERHEAD = 1.15
const BATTERY_HOURS = 0.5
const TASK_LAG = 0.12
const TERMINATED_SHARE = 0.035
const OUTAGE_MS = 3 * 3_600_000
const SEED_SPREAD = 0.06
const DEMAND_BASE = 0.45
const DEMAND_SWING = 0.3
const JITTER = 0.035
const SMOOTHING_TAPS = 3

export interface ResultsRequest {
    experimentId: number
    schedule: ExecutionSchedule
    spec: ExperimentSpec
    buckets?: number
}

export function deriveResults(request: ResultsRequest, nowMs: number): ExperimentResults {
    const scenarios = expandScenarios(request.spec)
    const runs = experimentRuns(request.spec)
    const timelines = scenarioTimelines(request.schedule, nowMs)
    const buckets = Math.max(1, request.buckets ?? DEFAULT_BUCKETS)

    // One width across the whole experiment, so scenarios of different lengths still line up on a
    // shared axis and can be overlaid.
    const models = timelines.map((timeline, index) => modelOf(request.schedule.seed, scenarios[index], timeline, index))
    const longest = Math.max(0, ...models.map((model, index) => emittedSteps(timelines[index], model.nativeSteps)))
    const width = bucketStepsFor(longest, buckets)

    const reported = models.map((model, index) => {
        const timeline = timelines[index]
        if (timeline === undefined) throw new Error("a scenario model without a timeline")
        return scenarioResults(model, timeline, index, runs, emittedSteps(timeline, model.nativeSteps), width)
    })

    return {
        experimentId: request.experimentId,
        exportIntervalMs: EXPORT_INTERVAL_MS,
        bucketMs: width * EXPORT_INTERVAL_MS,
        complete: reported.length > 0 && reported.every((scenario) => scenario.complete),
        scenarios: reported,
    }
}

interface ScenarioModel {
    seed: string
    hosts: number
    peakPowerW: number
    batteryJoules: number
    totalTasks: number
    concurrency: number
    faulty: boolean
    outageFrom: number
    packing: number
    nativeSteps: number
}

function modelOf(
    experimentSeed: string,
    scenario: ScenarioSpec | undefined,
    timeline: ScenarioTimeline,
    index: number,
): ScenarioModel {
    const capacity = topologyCapacity(scenario?.topology ?? { clusters: [] })
    const seed = `${experimentSeed}:${index}`
    const stored = scenario?.topology.clusters.some((cluster) => cluster.battery !== undefined) ?? false
    const nativeSteps = Math.round(traceSpanMs(scenario) / EXPORT_INTERVAL_MS)

    return {
        seed,
        hosts: capacity.hosts,
        peakPowerW: capacity.peakPowerW,
        batteryJoules: stored ? capacity.peakPowerW * BATTERY_HOURS * 3600 : 0,
        totalTasks: timeline.plan.totalTasks,
        concurrency: Math.max(1, Math.round(capacity.hosts * 1.5)),
        faulty: timeline.plan.fails,
        outageFrom: sampleNoise(`${seed}:outage`) % Math.max(1, nativeSteps),
        packing: 0.78 + (sampleNoise(`${seed}:packing`) % 45) / 100,
        nativeSteps,
    }
}

function traceSpanMs(scenario: ScenarioSpec | undefined): number {
    const workload = scenario?.workload
    const named =
        workload?.type === "trace" && workload.source.type === "named"
            ? TRACE_SPAN_DAYS[workload.source.name]
            : undefined
    return (named ?? DEFAULT_TRACE_SPAN_DAYS) * DAY_MS
}

interface Sample {
    cpuUtilization: number
    itPowerW: number
    facilityPowerW: number
    energyJ: number
    carbonIntensity: number
    carbonG: number
    tasksActive: number
    tasksPending: number
    tasksCompleted: number
    tasksTerminated: number
    hostsDown: number
    batteryJoules: number
}

function sampleAt(model: ScenarioModel, step: number): Sample {
    const dayPhase = ((step * EXPORT_INTERVAL_MS) % DAY_MS) / DAY_MS
    const runPhase = model.nativeSteps === 0 ? 0 : step / model.nativeSteps
    const demand = DEMAND_BASE + DEMAND_SWING * Math.sin(2 * Math.PI * (dayPhase - 0.25))
    const load = clamp(demand * model.packing + jitter(model, "load", step, JITTER), 0.03, 0.99)
    const itPowerW = model.peakPowerW * (IDLE_SHARE + (1 - IDLE_SHARE) * load)
    const facilityPowerW = itPowerW * FACILITY_OVERHEAD
    const energyJ = facilityPowerW * (EXPORT_INTERVAL_MS / 1000)
    const carbonIntensity = Math.max(
        40,
        260 + 150 * Math.sin(2 * Math.PI * (dayPhase + 0.15)) + jitter(model, "grid", step, 22),
    )

    const submitted = Math.round(model.totalTasks * smoothstep(runPhase))
    const settled = Math.round(model.totalTasks * smoothstep((runPhase - TASK_LAG) / (1 - TASK_LAG)))
    const tasksTerminated = Math.round(settled * (model.faulty ? TERMINATED_SHARE : 0))
    const backlog = submitted - settled
    const tasksActive = Math.min(backlog, Math.max(1, Math.round(model.concurrency * load)))

    return {
        cpuUtilization: load,
        itPowerW,
        facilityPowerW,
        energyJ,
        carbonIntensity,
        carbonG: (energyJ / 3_600_000) * carbonIntensity,
        tasksActive,
        tasksPending: backlog - tasksActive,
        tasksCompleted: settled - tasksTerminated,
        tasksTerminated,
        hostsDown: outageAt(model, step),
        batteryJoules: model.batteryJoules * clamp(0.62 + 0.3 * Math.sin(2 * Math.PI * (dayPhase - 0.55)), 0.05, 1),
    }
}

function outageAt(model: ScenarioModel, step: number): number {
    const width = Math.max(1, Math.round(OUTAGE_MS / EXPORT_INTERVAL_MS))
    if (!model.faulty) return 0
    if (step < model.outageFrom || step >= model.outageFrom + width) return 0
    return Math.max(1, Math.round(model.hosts * 0.06))
}

const PROJECTIONS: Array<{ metric: MetricId; of: (sample: Sample) => number }> = [
    { metric: "host.cpu_utilization", of: (sample) => sample.cpuUtilization },
    { metric: "host.power_draw", of: (sample) => sample.itPowerW },
    { metric: "powerSource.power_draw", of: (sample) => sample.facilityPowerW },
    { metric: "powerSource.energy_usage", of: (sample) => sample.energyJ },
    { metric: "powerSource.carbon_emission", of: (sample) => sample.carbonG },
    { metric: "powerSource.carbon_intensity", of: (sample) => sample.carbonIntensity },
    { metric: "service.tasks_active", of: (sample) => sample.tasksActive },
    { metric: "service.tasks_pending", of: (sample) => sample.tasksPending },
    { metric: "service.tasks_completed", of: (sample) => sample.tasksCompleted },
    { metric: "service.tasks_terminated", of: (sample) => sample.tasksTerminated },
    { metric: "service.hosts_down", of: (sample) => sample.hostsDown },
    { metric: "battery.charge", of: (sample) => sample.batteryJoules },
]

interface Bucket {
    t: number
    steps: number
    taps: Sample[]
}

function scenarioResults(
    model: ScenarioModel,
    timeline: ScenarioTimeline,
    scenarioIndex: number,
    runs: number,
    emitted: number,
    width: number,
): ScenarioResults {
    const sampled = bucketsOf(model, emitted, width)
    const reported = PROJECTIONS.filter(({ metric }) => metric !== "battery.charge" || model.batteryJoules > 0)

    return {
        scenarioIndex,
        seeds: runs,
        complete: isTerminalScenario(timeline.state),
        series: reported.map(({ metric, of }) => seriesOf(model, metric, sampled, of, runs)),
    }
}

// Bucket widths in export intervals: 5 min through 30 days, every one a whole number of hours or
// days. Dividing a span by the point budget instead would land on widths like 18 hours, and a daily
// cycle sampled at 18 hours aliases into a slow beat that looks like real behaviour but is not.
const NICE_BUCKET_STEPS = [1, 2, 3, 6, 12, 24, 36, 72, 144, 288, 576, 864, 2016, 4032, 8640]

const STEPS_PER_DAY = DAY_MS / EXPORT_INTERVAL_MS

export function bucketStepsFor(emitted: number, buckets: number): number {
    const needed = emitted / Math.max(1, buckets)
    // Past the ladder, keep rounding up to whole days: still cycle-aligned, and no span is wide
    // enough to escape the point budget.
    return NICE_BUCKET_STEPS.find((steps) => steps >= needed) ?? Math.ceil(needed / STEPS_PER_DAY) * STEPS_PER_DAY
}

// Each bucket is probed at several points rather than at its midpoint alone, so a bucket a day wide
// reports the day's average instead of whatever the cycle happened to be doing at its centre.
function bucketsOf(model: ScenarioModel, emitted: number, width: number): Bucket[] {
    if (emitted === 0) return []

    return Array.from({ length: Math.ceil(emitted / width) }, (_, bucket) => {
        const from = bucket * width
        const steps = Math.min(width, emitted - from)
        const taps = Math.min(steps, BUCKET_TAPS)
        return {
            t: from * EXPORT_INTERVAL_MS,
            steps,
            taps: Array.from({ length: taps }, (_, tap) => sampleAt(model, from + Math.floor((tap * steps) / taps))),
        }
    })
}

function seriesOf(
    model: ScenarioModel,
    metric: MetricId,
    sampled: Bucket[],
    of: (sample: Sample) => number,
    runs: number,
): ResultSeries {
    const mode = metricById(metric).reduce
    const points = sampled.map((bucket) => {
        const taps = bucket.taps.map((sample, index) => ({ t: index, value: of(sample) }))
        const reduced = reduceSeries(taps, mode === "sum" ? "mean" : mode)
        const value = reduced.status === "ok" ? reduced.value : 0
        return { t: bucket.t, value: mode === "sum" ? value * bucket.steps : value }
    })

    const total = reduceSeries(points, mode)
    const variation = runs > 1 ? SEED_SPREAD * (0.4 + (sampleNoise(`${model.seed}:${metric}`) % 100) / 100) : 0
    return { metric, points, spread: total.status === "ok" ? Math.abs(total.value) * variation : 0 }
}

function emittedSteps(timeline: ScenarioTimeline | undefined, nativeSteps: number): number {
    if (timeline === undefined) return 0
    const reach = timeline.plan.totalTasks === 0 ? 1 : timeline.plan.settledTasks / timeline.plan.totalTasks
    return Math.floor(timeline.fraction * reach * nativeSteps)
}

// Averaging a few consecutive taps keeps the wobble deterministic while giving the series the
// correlated look of a sampled measurement rather than white noise.
function jitter(model: ScenarioModel, channel: string, step: number, amplitude: number): number {
    let total = 0
    for (let tap = 0; tap < SMOOTHING_TAPS; tap++) {
        total += (sampleNoise(`${model.seed}:${channel}:${step - tap}`) % 2001) / 1000 - 1
    }
    return (total / SMOOTHING_TAPS) * amplitude
}

function smoothstep(value: number): number {
    const clamped = clamp(value, 0, 1)
    return clamped * clamped * (3 - 2 * clamped)
}

function clamp(value: number, low: number, high: number): number {
    return Math.min(high, Math.max(low, value))
}
