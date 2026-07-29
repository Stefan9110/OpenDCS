import {
    type ClusterSpec,
    type HostSpec,
    type TopologySpec,
    clusterCount,
    cpuCount,
    gpuCount,
    hostCount,
} from "@/lib/topology/spec"
import { type Quantity, parseQuantity } from "@/lib/units"

export interface Capacity {
    clusters: number
    hosts: number
    cores: number
    gpus: number
    memoryMiB: number
    peakPowerW: number
}

export type PowerBudget = { status: "unlimited" } | { status: "limited"; watts: number }

export interface PowerHeadroom {
    usedW: number
    budget: PowerBudget
}

const EMPTY: Capacity = { clusters: 0, hosts: 0, cores: 0, gpus: 0, memoryMiB: 0, peakPowerW: 0 }

export function topologyCapacity(topology: TopologySpec): Capacity {
    return topology.clusters.map(clusterCapacity).reduce(addCapacity, EMPTY)
}

export function clusterCapacity(cluster: ClusterSpec): Capacity {
    const instances = clusterCount(cluster)
    const perInstance = cluster.hosts.map(hostCapacity).reduce(addCapacity, EMPTY)
    return {
        clusters: instances,
        hosts: perInstance.hosts * instances,
        cores: perInstance.cores * instances,
        gpus: perInstance.gpus * instances,
        memoryMiB: perInstance.memoryMiB * instances,
        peakPowerW: perInstance.peakPowerW * instances,
    }
}

export function hostCapacity(host: HostSpec): Capacity {
    const instances = hostCount(host)
    const gpus = host.gpu ? gpuCount(host.gpu) : 0
    const cpuPower = watts(host.cpuPowerModel?.maxPower)
    const gpuPower = host.gpu ? watts(host.gpuPowerModel?.maxPower) : 0
    return {
        clusters: 0,
        hosts: instances,
        cores: host.cpu.coreCount * cpuCount(host.cpu) * instances,
        gpus: gpus * instances,
        memoryMiB: measure("dataSize", host.memory.size) * instances,
        peakPowerW: (cpuPower + gpuPower) * instances,
    }
}

export function clusterPowerBudget(cluster: ClusterSpec): PowerBudget {
    const declared = cluster.powerSource?.maxPower
    if (declared === undefined) return { status: "unlimited" }
    return { status: "limited", watts: measure("power", declared) * clusterCount(cluster) }
}

export function topologyPowerBudget(topology: TopologySpec): PowerBudget {
    let total = 0
    for (const cluster of topology.clusters) {
        const budget = clusterPowerBudget(cluster)
        if (budget.status === "unlimited") return { status: "unlimited" }
        total += budget.watts
    }
    return { status: "limited", watts: total }
}

export function clusterPowerHeadroom(cluster: ClusterSpec): PowerHeadroom {
    return { usedW: clusterCapacity(cluster).peakPowerW, budget: clusterPowerBudget(cluster) }
}

export function isOverBudget(headroom: PowerHeadroom): boolean {
    return headroom.budget.status === "limited" && headroom.usedW > headroom.budget.watts
}

function addCapacity(left: Capacity, right: Capacity): Capacity {
    return {
        clusters: left.clusters + right.clusters,
        hosts: left.hosts + right.hosts,
        cores: left.cores + right.cores,
        gpus: left.gpus + right.gpus,
        memoryMiB: left.memoryMiB + right.memoryMiB,
        peakPowerW: left.peakPowerW + right.peakPowerW,
    }
}

function watts(value: Quantity | undefined): number {
    return value === undefined ? 0 : measure("power", value)
}

function measure(kind: "power" | "dataSize", value: Quantity): number {
    const parsed = parseQuantity(kind, value)
    return parsed.status === "ok" ? parsed.base : 0
}
