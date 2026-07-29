import { ApiError, documentHash } from "@/lib/api/client"
import type { Layout, Project, TopologyTemplate } from "@/lib/api/types"
import type { ExperimentSpec } from "@/lib/experiment/spec"
import { SEED_EXPERIMENTS, SEED_PROJECTS, SEED_TOPOLOGIES } from "@/lib/sample/dataset"
import { autoLayout } from "@/lib/topology/layout"

const STORAGE_KEY = "opendc.sample"
const WORLD_VERSION = 1

export interface SampleExperiment {
    id: number
    projectId: number
    number: number
    name: string
    spec: ExperimentSpec
    specHash: string
    createdAt: string
    updatedAt: string
    submittedAt?: string
    cancelledAt?: string
}

export interface World {
    version: number
    projects: Project[]
    topologies: TopologyTemplate[]
    layouts: Record<string, Layout>
    experiments: SampleExperiment[]
    nextId: number
}

let cached: World | undefined

export function readWorld(): World {
    if (!cached) cached = load()
    return cached
}

export function writeWorld(next: World): World {
    cached = next
    storage()?.setItem(STORAGE_KEY, JSON.stringify(next))
    return next
}

export function resetWorld(): World {
    return writeWorld(seedWorld(new Date()))
}

function load(): World {
    const raw = storage()?.getItem(STORAGE_KEY)
    if (!raw) return writeWorld(seedWorld(new Date()))
    try {
        const parsed = JSON.parse(raw) as World
        if (parsed.version === WORLD_VERSION) return parsed
    } catch {
        // a corrupted or outdated world is replaced rather than repaired
    }
    return writeWorld(seedWorld(new Date()))
}

function storage(): Storage | undefined {
    if (typeof globalThis.localStorage === "undefined") return undefined
    try {
        return globalThis.localStorage
    } catch {
        return undefined
    }
}

export function notFound(what: string): ApiError {
    return new ApiError({ status: 404, title: `${what} not found`, issues: [] })
}

export function conflict(title: string): ApiError {
    return new ApiError({ status: 409, title, issues: [] })
}

export function layoutKey(projectId: number, topologyHash: string): string {
    return `${projectId}:${topologyHash}`
}

function seedWorld(now: Date): World {
    const iso = (offsetSeconds: number) => new Date(now.getTime() - offsetSeconds * 1000).toISOString()
    let nextId = 1
    const take = () => nextId++

    const projects: Project[] = SEED_PROJECTS.map((name, index) => ({
        id: take(),
        name,
        role: index === 1 ? "editor" : index === 2 ? "viewer" : "owner",
        createdAt: iso(86_400 * (30 - index)),
        updatedAt: iso(3_600 * (index + 1)),
    }))

    const topologies: TopologyTemplate[] = SEED_TOPOLOGIES.map((seed, index) => {
        const project = projects[seed.projectIndex]
        if (!project) throw new Error("seed topology refers to a missing project")
        return {
            id: take(),
            projectId: project.id,
            number: numberWithin(SEED_TOPOLOGIES, seed.projectIndex, index),
            name: seed.name,
            topology: seed.topology,
            topologyHash: documentHash(seed.topology),
            createdAt: iso(86_400 * 7),
            updatedAt: iso(3_600 * (index + 2)),
        }
    })

    const layouts: Record<string, Layout> = {}
    for (const template of topologies) {
        layouts[layoutKey(template.projectId, template.topologyHash)] = {
            topologyHash: template.topologyHash,
            layout: autoLayout(template.topology),
            generated: true,
            version: 1,
        }
    }

    const experiments: SampleExperiment[] = SEED_EXPERIMENTS.map((seed, index) => {
        const project = projects[seed.projectIndex]
        if (!project) throw new Error("seed experiment refers to a missing project")
        const chosen = topologies.filter(
            (template) => template.projectId === project.id && seed.topologyNames.includes(template.name),
        )
        const spec: ExperimentSpec = { ...seed.spec, topologies: chosen.map((template) => template.topology) }
        return {
            id: take(),
            projectId: project.id,
            number: numberWithin(SEED_EXPERIMENTS, seed.projectIndex, index),
            name: seed.name,
            spec,
            specHash: documentHash(spec),
            createdAt: iso(86_400),
            updatedAt: iso(3_600),
            ...(seed.submittedSecondsAgo >= 0 ? { submittedAt: iso(seed.submittedSecondsAgo) } : {}),
        }
    })

    return { version: WORLD_VERSION, projects, topologies, layouts, experiments, nextId }
}

function numberWithin(seeds: Array<{ projectIndex: number }>, projectIndex: number, index: number): number {
    return seeds.slice(0, index + 1).filter((seed) => seed.projectIndex === projectIndex).length
}
