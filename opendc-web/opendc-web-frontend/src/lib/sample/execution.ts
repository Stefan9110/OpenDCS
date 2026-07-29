import type { ExitInfo, ScenarioStatus } from "@/lib/api/types"
import {
    type ExperimentSpec,
    type ScenarioSpec,
    experimentAxes,
    experimentInitialSeed,
    experimentRuns,
    scenarioCoordinates,
    scenarioCount,
} from "@/lib/experiment/spec"
import type { ScenarioExecutionState } from "@/lib/experiment/status"

const CONCURRENT_SLOTS = 4
const MIN_DURATION_MS = 18_000
const DURATION_SPREAD_MS = 52_000
const MIN_TASKS = 1_200
const TASK_SPREAD = 9_400
const FAILURE_MODULUS = 17

export function expandScenarios(spec: ExperimentSpec): ScenarioSpec[] {
    const axes = experimentAxes(spec)
    const runs = experimentRuns(spec)
    const initialSeed = experimentInitialSeed(spec)

    return Array.from({ length: scenarioCount(spec) }, (_, index) => {
        const at = scenarioCoordinates(axes, index)
        return {
            topology: pick(axes.topologies, at.topologies),
            workload: pick(axes.workloads, at.workloads),
            allocationPolicy: pick(axes.allocationPolicies, at.allocationPolicies),
            exportModel: pick(axes.exportModels, at.exportModels),
            failureModel: pick(axes.failureModels, at.failureModels),
            checkpointModel: pick(axes.checkpointModels, at.checkpointModels),
            maxNumFailures: pick(axes.maxNumFailures, at.maxNumFailures),
            runs,
            initialSeed,
            id: index,
            name: String(index),
        }
    })
}

function pick<T>(values: readonly T[], index: number): T {
    const value = values[index]
    if (value === undefined) throw new Error("cannot expand an experiment with an empty axis")
    return value
}

export interface ExecutionSchedule {
    seed: string
    scenarioCount: number
    submittedAtMs: number
    cancelledAtMs: number
}

export const NOT_CANCELLED = Number.POSITIVE_INFINITY

export interface ScenarioPlan {
    totalTasks: number
    settledTasks: number
    startMs: number
    durationMs: number
    fails: boolean
}

export function scenarioPlans(schedule: ExecutionSchedule): ScenarioPlan[] {
    const slots = new Array<number>(CONCURRENT_SLOTS).fill(schedule.submittedAtMs)
    const plans: ScenarioPlan[] = []

    for (let index = 0; index < schedule.scenarioCount; index++) {
        const noise = sampleNoise(`${schedule.seed}:${index}`)
        const totalTasks = MIN_TASKS + (Math.floor(noise / 7) % TASK_SPREAD)
        const fails = noise % FAILURE_MODULUS === 0
        const reached = fails ? 0.2 + (Math.floor(noise / 13) % 60) / 100 : 1
        const durationMs = Math.round((MIN_DURATION_MS + (noise % DURATION_SPREAD_MS)) * reached)
        const slot = earliestSlot(slots)
        const startMs = slots[slot] ?? schedule.submittedAtMs
        slots[slot] = startMs + durationMs
        plans.push({ totalTasks, settledTasks: Math.floor(totalTasks * reached), startMs, durationMs, fails })
    }

    return plans
}

export interface ScenarioTimeline {
    plan: ScenarioPlan
    state: ScenarioExecutionState
    fraction: number
}

export function scenarioTimelines(schedule: ExecutionSchedule, nowMs: number): ScenarioTimeline[] {
    const clock = Math.min(nowMs, schedule.cancelledAtMs)
    const cancelled = schedule.cancelledAtMs !== NOT_CANCELLED

    return scenarioPlans(schedule).map((plan) => {
        const elapsed = clock - plan.startMs
        return {
            plan,
            state: scenarioStateOf(plan, elapsed, cancelled),
            fraction: Math.min(1, Math.max(0, elapsed / plan.durationMs)),
        }
    })
}

export function deriveScenarioStatuses(schedule: ExecutionSchedule, nowMs: number): ScenarioStatus[] {
    return scenarioTimelines(schedule, nowMs).map((timeline, scenarioIndex) => ({
        scenarioIndex,
        state: timeline.state,
        completedTasks: Math.floor(timeline.plan.settledTasks * timeline.fraction),
        totalTasks: timeline.plan.totalTasks,
        attempt: 1,
        ...exitOf(timeline.state),
    }))
}

function scenarioStateOf(plan: ScenarioPlan, elapsed: number, cancelled: boolean): ScenarioExecutionState {
    if (elapsed >= plan.durationMs) return plan.fails ? "failed" : "succeeded"
    if (cancelled) return "cancelled"
    return elapsed > 0 ? "running" : "queued"
}

function exitOf(state: ScenarioExecutionState): { exitInfo?: ExitInfo } {
    if (state === "failed")
        return { exitInfo: { exitCode: 137, reason: "oom", message: "Simulation ran out of memory" } }
    if (state === "cancelled") return { exitInfo: { exitCode: 130, reason: "cancelled" } }
    return {}
}

function earliestSlot(slots: number[]): number {
    let best = 0
    for (let index = 1; index < slots.length; index++) {
        if ((slots[index] ?? 0) < (slots[best] ?? 0)) best = index
    }
    return best
}

export function sampleNoise(value: string): number {
    let result = 2166136261
    for (let index = 0; index < value.length; index++) {
        result ^= value.codePointAt(index) ?? 0
        result = Math.imul(result, 16777619)
    }
    return Math.abs(result)
}
