import {
    type FloorCell,
    type FloorLayout,
    insertCell,
    placeCluster,
    removeCell,
    reserveCell,
} from "@/lib/topology/layout"
import type { ClusterSpec, HostSpec, TopologySpec } from "@/lib/topology/spec"

export interface TopologyPlan {
    topology: TopologySpec
    layout: FloorLayout
}

export function addCluster(plan: TopologyPlan, cluster: ClusterSpec, cell: FloorCell): TopologyPlan {
    const index = plan.topology.clusters.length
    return {
        topology: { ...plan.topology, clusters: [...plan.topology.clusters, cluster] },
        layout: insertCell(plan.layout, index, cell),
    }
}

export function removeCluster(plan: TopologyPlan, index: number): TopologyPlan {
    if (!plan.topology.clusters[index]) return plan
    return {
        topology: { ...plan.topology, clusters: plan.topology.clusters.filter((_, at) => at !== index) },
        layout: removeCell(plan.layout, index),
    }
}

export function removeClusters(plan: TopologyPlan, indices: number[]): TopologyPlan {
    return [...indices].sort(descending).reduce(removeCluster, plan)
}

export function duplicateCluster(plan: TopologyPlan, index: number): TopologyPlan {
    const cluster = plan.topology.clusters[index]
    const origin = plan.layout.cells[index]
    if (!cluster || !origin) return plan
    const reserved = reserveCell(plan.layout, origin)
    const clusters = [...plan.topology.clusters]
    clusters.splice(index + 1, 0, cluster)
    return {
        topology: { ...plan.topology, clusters },
        layout: insertCell(reserved.layout, index + 1, reserved.cell),
    }
}

export function moveCluster(plan: TopologyPlan, index: number, cell: FloorCell): TopologyPlan {
    const layout = placeCluster(plan.layout, index, cell)
    return layout === plan.layout ? plan : { ...plan, layout }
}

export function updateCluster(plan: TopologyPlan, index: number, patch: Partial<ClusterSpec>): TopologyPlan {
    return updateClusters(plan, [index], patch)
}

export function updateClusters(plan: TopologyPlan, indices: number[], patch: Partial<ClusterSpec>): TopologyPlan {
    const targets = new Set(indices)
    return {
        ...plan,
        topology: {
            ...plan.topology,
            clusters: plan.topology.clusters.map((cluster, at) =>
                targets.has(at) ? { ...cluster, ...patch } : cluster,
            ),
        },
    }
}

export function addHost(plan: TopologyPlan, cluster: number, host: HostSpec): TopologyPlan {
    return withHosts(plan, cluster, (hosts) => [...hosts, host])
}

export function removeHost(plan: TopologyPlan, cluster: number, host: number): TopologyPlan {
    return withHosts(plan, cluster, (hosts) => hosts.filter((_, at) => at !== host))
}

export function duplicateHost(plan: TopologyPlan, cluster: number, host: number): TopologyPlan {
    return withHosts(plan, cluster, (hosts) => {
        const source = hosts[host]
        if (!source) return hosts
        const next = [...hosts]
        next.splice(host + 1, 0, source)
        return next
    })
}

export function updateHost(plan: TopologyPlan, cluster: number, host: number, patch: Partial<HostSpec>): TopologyPlan {
    return withHosts(plan, cluster, (hosts) =>
        hosts.map((existing, at) => (at === host ? { ...existing, ...patch } : existing)),
    )
}

function withHosts(plan: TopologyPlan, cluster: number, transform: (hosts: HostSpec[]) => HostSpec[]): TopologyPlan {
    const target = plan.topology.clusters[cluster]
    if (!target) return plan
    const hosts = transform(target.hosts)
    return {
        ...plan,
        topology: {
            ...plan.topology,
            clusters: plan.topology.clusters.map((existing, at) =>
                at === cluster ? { ...existing, hosts } : existing,
            ),
        },
    }
}

function descending(left: number, right: number): number {
    return right - left
}
