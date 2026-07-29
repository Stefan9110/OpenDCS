import { documentHash } from "@/lib/api/client"
import type {
    Experiment,
    ExperimentStatus,
    ExperimentSummary,
    Layout,
    Project,
    ScenarioStatus,
    TopologyTemplate,
} from "@/lib/api/types"
import type { ExperimentResults } from "@/lib/experiment/results"
import { type ExperimentSpec, scenarioCount } from "@/lib/experiment/spec"
import {
    type ExperimentState,
    aggregateProgress,
    foldExperimentState,
    isTerminalExperiment,
} from "@/lib/experiment/status"
import { type ExecutionSchedule, NOT_CANCELLED, deriveScenarioStatuses } from "@/lib/sample/execution"
import { DEFAULT_BUCKETS, EXPORT_INTERVAL_MS, deriveResults } from "@/lib/sample/results"
import {
    type SampleExperiment,
    type World,
    conflict,
    layoutKey,
    notFound,
    readWorld,
    writeWorld,
} from "@/lib/sample/store"
import { type FloorLayout, autoLayout, reconcile } from "@/lib/topology/layout"
import type { TopologySpec } from "@/lib/topology/spec"

export function listProjects(): Project[] {
    return [...readWorld().projects].sort((left, right) => right.updatedAt.localeCompare(left.updatedAt))
}

export function getProject(projectId: number): Project {
    const project = readWorld().projects.find((entry) => entry.id === projectId)
    if (!project) throw notFound("Project")
    return project
}

export function createProject(name: string): Project {
    const world = readWorld()
    const now = new Date().toISOString()
    const project: Project = { id: world.nextId, name, role: "owner", createdAt: now, updatedAt: now }
    writeWorld({ ...world, nextId: world.nextId + 1, projects: [...world.projects, project] })
    return project
}

export function renameProject(projectId: number, name: string): Project {
    const world = readWorld()
    const project = getProject(projectId)
    const updated: Project = { ...project, name, updatedAt: new Date().toISOString() }
    writeWorld({ ...world, projects: world.projects.map((entry) => (entry.id === projectId ? updated : entry)) })
    return updated
}

export function deleteProject(projectId: number): void {
    const world = readWorld()
    getProject(projectId)
    writeWorld({
        ...world,
        projects: world.projects.filter((entry) => entry.id !== projectId),
        topologies: world.topologies.filter((entry) => entry.projectId !== projectId),
        experiments: world.experiments.filter((entry) => entry.projectId !== projectId),
    })
}

export function listTopologies(projectId: number): TopologyTemplate[] {
    getProject(projectId)
    return readWorld()
        .topologies.filter((entry) => entry.projectId === projectId)
        .sort((left, right) => left.number - right.number)
}

export function getTopology(projectId: number, templateId: number): TopologyTemplate {
    const template = readWorld().topologies.find((entry) => entry.id === templateId && entry.projectId === projectId)
    if (!template) throw notFound("Topology")
    return template
}

export function createTopology(projectId: number, name: string, topology: TopologySpec): TopologyTemplate {
    const world = readWorld()
    getProject(projectId)
    const now = new Date().toISOString()
    const template: TopologyTemplate = {
        id: world.nextId,
        projectId,
        number: listTopologies(projectId).length + 1,
        name,
        topology,
        topologyHash: documentHash(topology),
        createdAt: now,
        updatedAt: now,
    }
    writeWorld({ ...world, nextId: world.nextId + 1, topologies: [...world.topologies, template] })
    return template
}

export function replaceTopology(
    projectId: number,
    templateId: number,
    change: { name: string; topology: TopologySpec },
): TopologyTemplate {
    const world = readWorld()
    const template = getTopology(projectId, templateId)
    const topologyHash = documentHash(change.topology)
    const updated: TopologyTemplate = {
        ...template,
        name: change.name,
        topology: change.topology,
        topologyHash,
        updatedAt: new Date().toISOString(),
    }

    const layouts = { ...world.layouts }
    const previous = layouts[layoutKey(projectId, template.topologyHash)]
    if (previous && template.topologyHash !== topologyHash) {
        delete layouts[layoutKey(projectId, template.topologyHash)]
        layouts[layoutKey(projectId, topologyHash)] = {
            topologyHash,
            layout: reconcile(previous.layout, change.topology),
            generated: false,
            version: previous.version + 1,
        }
    }

    writeWorld({
        ...world,
        layouts,
        topologies: world.topologies.map((entry) => (entry.id === templateId ? updated : entry)),
    })
    return updated
}

export function deleteTopology(projectId: number, templateId: number): void {
    const world = readWorld()
    const template = getTopology(projectId, templateId)
    const layouts = { ...world.layouts }
    delete layouts[layoutKey(projectId, template.topologyHash)]
    writeWorld({ ...world, layouts, topologies: world.topologies.filter((entry) => entry.id !== templateId) })
}

export function getLayout(projectId: number, topologyHash: string): Layout {
    const world = readWorld()
    const stored = world.layouts[layoutKey(projectId, topologyHash)]
    if (stored) return stored

    const template = world.topologies.find(
        (entry) => entry.projectId === projectId && entry.topologyHash === topologyHash,
    )
    if (!template) throw notFound("Layout")
    const generated: Layout = {
        topologyHash,
        layout: autoLayout(template.topology),
        generated: true,
        version: 1,
    }
    writeWorld({ ...world, layouts: { ...world.layouts, [layoutKey(projectId, topologyHash)]: generated } })
    return generated
}

export function putLayout(projectId: number, topologyHash: string, layout: FloorLayout): Layout {
    const world = readWorld()
    const key = layoutKey(projectId, topologyHash)
    const previous = world.layouts[key]
    const saved: Layout = { topologyHash, layout, generated: false, version: (previous?.version ?? 0) + 1 }
    writeWorld({ ...world, layouts: { ...world.layouts, [key]: saved } })
    return saved
}

export function listExperiments(projectId: number, nowMs: number): ExperimentSummary[] {
    getProject(projectId)
    return readWorld()
        .experiments.filter((entry) => entry.projectId === projectId)
        .sort((left, right) => left.number - right.number)
        .map((entry) => {
            const scenarios = scenarioStatuses(entry, nowMs)
            return {
                id: entry.id,
                number: entry.number,
                name: entry.name,
                state: experimentState(entry, nowMs),
                scenarioCount: scenarioCount(entry.spec),
                progress: aggregateProgress(scenarios),
                createdAt: entry.createdAt,
                ...(entry.submittedAt ? { submittedAt: entry.submittedAt } : {}),
            }
        })
}

export function getExperiment(projectId: number, experimentId: number, nowMs: number): Experiment {
    const record = findExperiment(projectId, experimentId)
    return {
        id: record.id,
        projectId: record.projectId,
        number: record.number,
        name: record.name,
        state: experimentState(record, nowMs),
        spec: record.spec,
        specHash: record.specHash,
        estimate: estimateOf(record.spec),
        createdAt: record.createdAt,
        updatedAt: record.updatedAt,
        ...(record.submittedAt ? { submittedAt: record.submittedAt } : {}),
    }
}

export function getExperimentStatus(projectId: number, experimentId: number, nowMs: number): ExperimentStatus {
    const record = findExperiment(projectId, experimentId)
    const scenarios = scenarioStatuses(record, nowMs)
    const progress = aggregateProgress(scenarios)
    return {
        id: record.id,
        number: record.number,
        name: record.name,
        state: experimentState(record, nowMs),
        completedTasks: progress.completedTasks,
        totalTasks: progress.totalTasks,
        scenarioCount: scenarioCount(record.spec),
        scenarios,
    }
}

export function createExperimentDraft(projectId: number, name: string, spec: ExperimentSpec): Experiment {
    const world = readWorld()
    getProject(projectId)
    const now = new Date().toISOString()
    const record: SampleExperiment = {
        id: world.nextId,
        projectId,
        number: world.experiments.filter((entry) => entry.projectId === projectId).length + 1,
        name,
        spec,
        specHash: documentHash(spec),
        createdAt: now,
        updatedAt: now,
    }
    writeWorld({ ...world, nextId: world.nextId + 1, experiments: [...world.experiments, record] })
    return getExperiment(projectId, record.id, Date.parse(now))
}

export function replaceExperimentDraft(
    projectId: number,
    experimentId: number,
    change: { name: string; spec: ExperimentSpec },
    nowMs: number,
): Experiment {
    const record = findExperiment(projectId, experimentId)
    if (record.submittedAt) throw conflict("A submitted experiment can no longer be edited")
    save(readWorld(), {
        ...record,
        name: change.name,
        spec: change.spec,
        specHash: documentHash(change.spec),
        updatedAt: new Date().toISOString(),
    })
    return getExperiment(projectId, experimentId, nowMs)
}

export function submitExperiment(projectId: number, experimentId: number, nowMs: number): Experiment {
    const record = findExperiment(projectId, experimentId)
    if (record.submittedAt) throw conflict("This experiment has already been submitted")
    if (scenarioCount(record.spec) === 0) throw conflict("This experiment expands to no scenarios")
    save(readWorld(), { ...record, submittedAt: new Date(nowMs).toISOString() })
    return getExperiment(projectId, experimentId, nowMs)
}

export function cancelExperiment(projectId: number, experimentId: number, nowMs: number): Experiment {
    const record = findExperiment(projectId, experimentId)
    const state = experimentState(record, nowMs)
    if (state === "draft") throw conflict("A draft experiment is not running")
    if (isTerminalExperiment(state)) throw conflict("This experiment has already finished")
    save(readWorld(), { ...record, cancelledAt: new Date(nowMs).toISOString() })
    return getExperiment(projectId, experimentId, nowMs)
}

export function cloneExperiment(projectId: number, experimentId: number, name?: string): Experiment {
    const record = findExperiment(projectId, experimentId)
    return createExperimentDraft(projectId, name ?? `${record.name} (copy)`, record.spec)
}

export function deleteExperiment(projectId: number, experimentId: number): void {
    const world = readWorld()
    findExperiment(projectId, experimentId)
    writeWorld({ ...world, experiments: world.experiments.filter((entry) => entry.id !== experimentId) })
}

function findExperiment(projectId: number, experimentId: number): SampleExperiment {
    const record = readWorld().experiments.find((entry) => entry.id === experimentId && entry.projectId === projectId)
    if (!record) throw notFound("Experiment")
    return record
}

function save(world: World, record: SampleExperiment): void {
    writeWorld({
        ...world,
        experiments: world.experiments.map((entry) => (entry.id === record.id ? record : entry)),
    })
}

export function getExperimentResults(
    projectId: number,
    experimentId: number,
    nowMs: number,
    buckets = DEFAULT_BUCKETS,
): ExperimentResults {
    const record = findExperiment(projectId, experimentId)
    const submittedAt = record.submittedAt
    if (submittedAt === undefined) {
        return {
            experimentId,
            exportIntervalMs: EXPORT_INTERVAL_MS,
            bucketMs: EXPORT_INTERVAL_MS,
            complete: false,
            scenarios: [],
        }
    }
    return deriveResults({ experimentId, schedule: scheduleOf(record, submittedAt), spec: record.spec, buckets }, nowMs)
}

function scheduleOf(record: SampleExperiment, submittedAt: string): ExecutionSchedule {
    return {
        seed: record.specHash,
        scenarioCount: scenarioCount(record.spec),
        submittedAtMs: Date.parse(submittedAt),
        cancelledAtMs: record.cancelledAt ? Date.parse(record.cancelledAt) : NOT_CANCELLED,
    }
}

function scenarioStatuses(record: SampleExperiment, nowMs: number): ScenarioStatus[] {
    const submittedAt = record.submittedAt
    if (submittedAt === undefined) return []
    return deriveScenarioStatuses(scheduleOf(record, submittedAt), nowMs)
}

function experimentState(record: SampleExperiment, nowMs: number): ExperimentState {
    if (record.submittedAt === undefined) return "draft"
    return foldExperimentState(scenarioStatuses(record, nowMs).map((entry) => entry.state))
}

function estimateOf(spec: ExperimentSpec): {
    scenarioCount: number
    estimatedSimulationSeconds: number
    estimatedBudgetSeconds: number
} {
    const scenarios = scenarioCount(spec)
    const seconds = scenarios * 45
    return { scenarioCount: scenarios, estimatedSimulationSeconds: seconds, estimatedBudgetSeconds: seconds }
}
