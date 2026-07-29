import { type ExperimentSpec, scenarioCount } from "@/lib/experiment/spec"
import {
    type ExperimentState,
    type ScenarioExecutionState,
    aggregateProgress,
    foldExperimentState,
    isTerminalExperiment,
    progressFraction,
} from "@/lib/experiment/status"
import { NOT_CANCELLED, deriveScenarioStatuses, expandScenarios } from "@/lib/sample/execution"
import type { TopologySpec } from "@/lib/topology/spec"
import { describe, expect, it } from "vitest"

function topology(name: string): TopologySpec {
    return { clusters: [{ name, hosts: [{ cpu: { coreCount: 8, coreSpeed: "3 GHz" }, memory: { size: "64 GiB" } }] }] }
}

function trace(name: string) {
    return { type: "trace", source: { type: "named", name } } as const
}

function spec(overrides: Partial<ExperimentSpec> = {}): ExperimentSpec {
    return { topologies: [topology("a")], workloads: [trace("bitbrains-small")], ...overrides }
}

describe("scenarioCount", () => {
    it("treats an omitted axis as the single SDK default, not as an empty set", () => {
        expect(scenarioCount(spec())).toBe(1)
    })

    it("multiplies every axis together", () => {
        const wide = spec({
            topologies: [topology("a"), topology("b")],
            workloads: [trace("x"), trace("y"), trace("z")],
            allocationPolicies: [
                { type: "prefab", prefabName: "Mem" },
                { type: "prefab", prefabName: "CoreMem" },
            ],
            maxNumFailures: [5, 10],
        })
        expect(scenarioCount(wide)).toBe(2 * 3 * 2 * 2)
    })

    it("collapses to nothing when an axis is explicitly empty", () => {
        expect(scenarioCount(spec({ workloads: [] }))).toBe(0)
        expect(expandScenarios(spec({ workloads: [] }))).toEqual([])
    })

    it("does not multiply by runs, which repeat inside a scenario", () => {
        expect(scenarioCount(spec({ runs: 8 }))).toBe(1)
    })
})

describe("expandScenarios", () => {
    it("varies axes from least to most significant exactly as the backend does", () => {
        const expanded = expandScenarios(
            spec({
                topologies: [topology("t0"), topology("t1")],
                workloads: [trace("w0"), trace("w1")],
                maxNumFailures: [1, 2],
            }),
        )

        const shape = expanded.map((scenario) => [
            scenario.topology.clusters[0]?.name,
            scenario.workload.type === "trace" && scenario.workload.source.type === "named"
                ? scenario.workload.source.name
                : "",
            scenario.maxNumFailures,
        ])

        expect(shape).toEqual([
            ["t0", "w0", 1],
            ["t0", "w0", 2],
            ["t0", "w1", 1],
            ["t0", "w1", 2],
            ["t1", "w0", 1],
            ["t1", "w0", 2],
            ["t1", "w1", 1],
            ["t1", "w1", 2],
        ])
    })

    it("stamps each scenario with its flattened index, which is the work shard identity", () => {
        const expanded = expandScenarios(spec({ maxNumFailures: [1, 2, 3] }))
        expect(expanded.map((scenario) => scenario.id)).toEqual([0, 1, 2])
        expect(expanded.map((scenario) => scenario.name)).toEqual(["0", "1", "2"])
    })

    it("carries runs and seed onto every scenario instead of expanding them", () => {
        const expanded = expandScenarios(spec({ runs: 4, initialSeed: 7, maxNumFailures: [1, 2] }))
        expect(expanded.every((scenario) => scenario.runs === 4 && scenario.initialSeed === 7)).toBe(true)
    })

    it("keeps a null checkpoint axis entry as a real absent checkpoint", () => {
        const expanded = expandScenarios(spec({ checkpointModels: [null, { interval: "2 hours" }] }))
        expect(expanded[0]?.checkpointModel).toBeNull()
        expect(expanded[1]?.checkpointModel).toEqual({ interval: "2 hours" })
    })
})

describe("foldExperimentState", () => {
    const fold = (...states: ScenarioExecutionState[]) => foldExperimentState(states)

    it("reports an experiment with no scenarios as a draft rather than as running", () => {
        expect(fold()).toBe("draft")
    })

    it("stays queued only while nothing has started", () => {
        expect(fold("queued", "queued")).toBe("queued")
        expect(fold("queued", "running")).toBe("running")
        expect(fold("queued", "succeeded")).toBe("running")
    })

    it("reports success only when every scenario succeeded", () => {
        expect(fold("succeeded", "succeeded")).toBe("succeeded")
    })

    it("reports partial when a failed scenario sits beside a successful one", () => {
        expect(fold("succeeded", "failed")).toBe("partial")
        expect(fold("succeeded", "cancelled")).toBe("partial")
    })

    it("does not treat a failure as cancelling its siblings", () => {
        expect(fold("failed", "running")).toBe("running")
    })

    it("reports failure when everything terminal failed, even mixed with cancellations", () => {
        expect(fold("failed", "failed")).toBe("failed")
        expect(fold("failed", "cancelled")).toBe("failed")
    })

    it("reports cancellation only when nothing else happened", () => {
        expect(fold("cancelled", "cancelled")).toBe("cancelled")
    })

    it("marks every settled outcome as terminal", () => {
        const settled: ExperimentState[] = ["succeeded", "partial", "failed", "cancelled"]
        const live: ExperimentState[] = ["draft", "queued", "running"]
        expect(settled.every(isTerminalExperiment)).toBe(true)
        expect(live.some(isTerminalExperiment)).toBe(false)
    })
})

describe("progress", () => {
    it("sums task counts across scenarios", () => {
        expect(
            aggregateProgress([
                { completedTasks: 3, totalTasks: 10 },
                { completedTasks: 4, totalTasks: 10 },
            ]),
        ).toEqual({ completedTasks: 7, totalTasks: 20 })
    })

    it("reports no progress rather than dividing by zero for an empty experiment", () => {
        expect(progressFraction({ completedTasks: 0, totalTasks: 0 })).toBe(0)
        expect(progressFraction(aggregateProgress([]))).toBe(0)
    })

    it("clamps a runner that over reports completed tasks", () => {
        expect(progressFraction({ completedTasks: 25, totalTasks: 10 })).toBe(1)
    })
})

describe("deriveScenarioStatuses", () => {
    const schedule = { seed: "abc", scenarioCount: 12, submittedAtMs: 1_000_000, cancelledAtMs: NOT_CANCELLED }
    const states = (nowMs: number) => deriveScenarioStatuses(schedule, nowMs).map((entry) => entry.state)

    it("is a pure function of the clock, so the same instant always reads the same", () => {
        expect(deriveScenarioStatuses(schedule, 1_100_000)).toEqual(deriveScenarioStatuses(schedule, 1_100_000))
    })

    it("leaves everything queued at the moment of submission", () => {
        expect(states(schedule.submittedAtMs)).toEqual(new Array(12).fill("queued"))
    })

    it("runs only as many scenarios at once as there are slots", () => {
        const running = states(schedule.submittedAtMs + 1_000).filter((state) => state === "running")
        expect(running).toHaveLength(4)
    })

    it("settles every scenario once enough time has passed", () => {
        expect(states(schedule.submittedAtMs + 10_000_000).every((s) => s === "succeeded" || s === "failed")).toBe(true)
    })

    it("produces a mix of outcomes so the viewer has a partial experiment to show", () => {
        const settled = states(schedule.submittedAtMs + 10_000_000)
        expect(foldExperimentState(settled)).toBe("partial")
    })

    it("reports a failed scenario with the exit information that explains it", () => {
        const failed = deriveScenarioStatuses(schedule, schedule.submittedAtMs + 10_000_000).find(
            (entry) => entry.state === "failed",
        )
        expect(failed?.exitInfo?.reason).toBe("oom")
        expect(failed?.completedTasks).toBeLessThan(failed?.totalTasks ?? 0)
    })

    it("counts every task of a succeeded scenario as done, with no rounding shortfall", () => {
        const done = deriveScenarioStatuses(schedule, schedule.submittedAtMs + 10_000_000).filter(
            (entry) => entry.state === "succeeded",
        )
        expect(done.every((entry) => entry.completedTasks === entry.totalTasks)).toBe(true)
    })

    it("treats a clock that runs backwards as nothing having started", () => {
        expect(states(schedule.submittedAtMs - 60_000)).toEqual(new Array(12).fill("queued"))
    })

    it("freezes progress at the moment of cancellation instead of advancing past it", () => {
        const cancelledAtMs = schedule.submittedAtMs + 20_000
        const cancelled = { ...schedule, cancelledAtMs }
        const atCancel = deriveScenarioStatuses(cancelled, cancelledAtMs)
        const longAfter = deriveScenarioStatuses(cancelled, cancelledAtMs + 10_000_000)
        expect(longAfter).toEqual(atCancel)
    })

    it("keeps scenarios that already finished before a cancellation", () => {
        const cancelledAtMs = schedule.submittedAtMs + 10_000_000
        const settled = deriveScenarioStatuses({ ...schedule, cancelledAtMs }, cancelledAtMs)
        expect(settled.some((entry) => entry.state === "succeeded")).toBe(true)
        expect(settled.every((entry) => entry.state !== "cancelled")).toBe(true)
    })

    it("has nothing to report for an experiment with no scenarios", () => {
        expect(deriveScenarioStatuses({ ...schedule, scenarioCount: 0 }, Date.now())).toEqual([])
    })
})
