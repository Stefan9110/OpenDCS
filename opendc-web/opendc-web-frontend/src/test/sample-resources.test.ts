import { ApiError } from "@/lib/api/client"
import type { ExperimentSpec } from "@/lib/experiment/spec"
import {
    cancelExperiment,
    createExperimentDraft,
    createProject,
    createTopology,
    deleteProject,
    deleteTopology,
    getExperiment,
    getLayout,
    getProject,
    getTopology,
    listExperiments,
    listProjects,
    listTopologies,
    replaceExperimentDraft,
    replaceTopology,
    submitExperiment,
} from "@/lib/sample/resources"
import { resetWorld } from "@/lib/sample/store"
import type { TopologySpec } from "@/lib/topology/spec"
import { beforeEach, describe, expect, it } from "vitest"

const HOUR_MS = 3_600_000

function topology(name: string): TopologySpec {
    return { clusters: [{ name, hosts: [{ cpu: { coreCount: 8, coreSpeed: "3 GHz" }, memory: { size: "64 GiB" } }] }] }
}

function spec(overrides: Partial<ExperimentSpec> = {}): ExperimentSpec {
    return {
        topologies: [topology("a")],
        workloads: [{ type: "trace", source: { type: "named", name: "bitbrains-small" } }],
        ...overrides,
    }
}

function statusOf(error: unknown): number {
    if (!(error instanceof ApiError)) throw new Error(`expected an ApiError, got ${String(error)}`)
    return error.problem.status
}

beforeEach(() => {
    resetWorld()
})

describe("projects", () => {
    it("rejects a lookup of a project that does not exist with a 404 envelope", () => {
        expect(() => getProject(9999)).toThrowError(ApiError)
        try {
            getProject(9999)
        } catch (error) {
            expect(statusOf(error)).toBe(404)
        }
    })

    it("takes its topologies and experiments down with it when deleted", () => {
        const project = listProjects().find((entry) => listTopologies(entry.id).length > 0)
        if (!project) throw new Error("expected a seeded project with topologies")
        deleteProject(project.id)

        expect(listProjects().some((entry) => entry.id === project.id)).toBe(false)
        expect(() => listTopologies(project.id)).toThrowError(ApiError)
    })

    it("gives a newly created project no topologies rather than inheriting any", () => {
        const created = createProject("Fresh")
        expect(listTopologies(created.id)).toEqual([])
        expect(listExperiments(created.id, Date.now())).toEqual([])
    })
})

describe("topologies", () => {
    it("generates a layout on demand for a topology that has never been opened", () => {
        const project = createProject("Layout probe")
        const template = createTopology(project.id, "Fresh", topology("only"))
        const layout = getLayout(project.id, template.topologyHash)

        expect(layout.generated).toBe(true)
        expect(layout.layout.cells).toHaveLength(1)
    })

    it("moves the layout to the new content hash when the topology changes", () => {
        const project = createProject("Rehash")
        const template = createTopology(project.id, "Fresh", topology("only"))
        const before = template.topologyHash
        getLayout(project.id, before)

        const changed = {
            clusters: [
                ...template.topology.clusters,
                { name: "second", hosts: template.topology.clusters[0]?.hosts ?? [] },
            ],
        }
        const updated = replaceTopology(project.id, template.id, { name: "Fresh", topology: changed })

        expect(updated.topologyHash).not.toBe(before)
        const layout = getLayout(project.id, updated.topologyHash)
        expect(layout.layout.cells).toHaveLength(2)
        expect(layout.generated).toBe(false)
    })

    it("hashes by content, so a rename alone does not re-key the layout", () => {
        const project = createProject("Rename")
        const template = createTopology(project.id, "Before", topology("only"))
        const updated = replaceTopology(project.id, template.id, { name: "After", topology: template.topology })

        expect(updated.name).toBe("After")
        expect(updated.topologyHash).toBe(template.topologyHash)
    })

    it("leaves a submitted experiment intact when the topology it was built from is deleted", () => {
        const project = createProject("Copy semantics")
        const template = createTopology(project.id, "Source", topology("only"))
        const draft = createExperimentDraft(project.id, "Run", spec({ topologies: [template.topology] }))
        submitExperiment(project.id, draft.id, Date.now())

        deleteTopology(project.id, template.id)

        const experiment = getExperiment(project.id, draft.id, Date.now())
        expect(experiment.spec.topologies).toHaveLength(1)
        expect(() => getTopology(project.id, template.id)).toThrowError(ApiError)
    })
})

describe("experiment lifecycle", () => {
    it("starts life as a draft with no scenarios running", () => {
        const project = createProject("Lifecycle")
        const draft = createExperimentDraft(project.id, "Draft", spec())
        expect(draft.state).toBe("draft")
    })

    it("refuses to submit an experiment that expands to no scenarios", () => {
        const project = createProject("Lifecycle")
        const draft = createExperimentDraft(project.id, "Empty", spec({ workloads: [] }))
        try {
            submitExperiment(project.id, draft.id, Date.now())
            throw new Error("expected a conflict")
        } catch (error) {
            expect(statusOf(error)).toBe(409)
        }
    })

    it("refuses to edit an experiment once it has been submitted", () => {
        const project = createProject("Lifecycle")
        const draft = createExperimentDraft(project.id, "Run", spec())
        const now = Date.now()
        submitExperiment(project.id, draft.id, now)

        try {
            replaceExperimentDraft(project.id, draft.id, { name: "Changed", spec: spec() }, now)
            throw new Error("expected a conflict")
        } catch (error) {
            expect(statusOf(error)).toBe(409)
        }
    })

    it("refuses to submit the same experiment twice", () => {
        const project = createProject("Lifecycle")
        const draft = createExperimentDraft(project.id, "Run", spec())
        const now = Date.now()
        submitExperiment(project.id, draft.id, now)

        try {
            submitExperiment(project.id, draft.id, now)
            throw new Error("expected a conflict")
        } catch (error) {
            expect(statusOf(error)).toBe(409)
        }
    })

    it("refuses to cancel something that never started", () => {
        const project = createProject("Lifecycle")
        const draft = createExperimentDraft(project.id, "Draft", spec())
        try {
            cancelExperiment(project.id, draft.id, Date.now())
            throw new Error("expected a conflict")
        } catch (error) {
            expect(statusOf(error)).toBe(409)
        }
    })

    it("refuses to cancel an experiment that has already settled", () => {
        const project = createProject("Lifecycle")
        const draft = createExperimentDraft(project.id, "Run", spec())
        const now = Date.now()
        submitExperiment(project.id, draft.id, now)

        try {
            cancelExperiment(project.id, draft.id, now + HOUR_MS)
            throw new Error("expected a conflict")
        } catch (error) {
            expect(statusOf(error)).toBe(409)
        }
    })

    it("advances from queued to settled purely because time passed", () => {
        const project = createProject("Clock")
        const draft = createExperimentDraft(project.id, "Run", spec({ maxNumFailures: [1, 2, 3, 4, 5, 6] }))
        const now = Date.now()
        submitExperiment(project.id, draft.id, now)

        expect(getExperiment(project.id, draft.id, now).state).toBe("queued")
        expect(getExperiment(project.id, draft.id, now + 1000).state).toBe("running")
        const settled = getExperiment(project.id, draft.id, now + HOUR_MS).state
        expect(["succeeded", "partial", "failed"]).toContain(settled)
    })

    it("keeps a cancelled experiment cancelled no matter how much later it is read", () => {
        const project = createProject("Clock")
        const draft = createExperimentDraft(project.id, "Run", spec({ maxNumFailures: [1, 2, 3, 4, 5, 6] }))
        const now = Date.now()
        submitExperiment(project.id, draft.id, now)
        cancelExperiment(project.id, draft.id, now + 1000)

        const state = getExperiment(project.id, draft.id, now + HOUR_MS).state
        expect(["cancelled", "partial"]).toContain(state)
    })
})
