export type Selection =
    | { kind: "topology" }
    | { kind: "clusters"; indices: number[] }
    | { kind: "host"; cluster: number; host: number }

export const WHOLE_TOPOLOGY: Selection = { kind: "topology" }

export function selectCluster(index: number): Selection {
    return { kind: "clusters", indices: [index] }
}

export function selectHost(cluster: number, host: number): Selection {
    return { kind: "host", cluster, host }
}

/**
 * Opens a host group, or closes it if it is the one already open. Without the second half there is
 * no way back out of a group short of Escape, which drops the cluster as well and throws the
 * inspector back to the whole topology.
 */
export function toggleHost(selection: Selection, cluster: number, host: number): Selection {
    const open = selection.kind === "host" && selection.cluster === cluster && selection.host === host
    return open ? selectCluster(cluster) : selectHost(cluster, host)
}

export function selectedClusters(selection: Selection): number[] {
    if (selection.kind === "clusters") return selection.indices
    if (selection.kind === "host") return [selection.cluster]
    return []
}

export function isClusterSelected(selection: Selection, index: number): boolean {
    return selectedClusters(selection).includes(index)
}

export function extendToCluster(selection: Selection, index: number): Selection {
    const current = selectedClusters(selection)
    const next = current.includes(index) ? current.filter((entry) => entry !== index) : [...current, index]
    return next.length === 0 ? WHOLE_TOPOLOGY : { kind: "clusters", indices: next.sort(ascending) }
}

export function afterRemoval(selection: Selection, removed: number[]): Selection {
    const gone = new Set(removed)
    if (selection.kind === "host") {
        if (gone.has(selection.cluster)) return WHOLE_TOPOLOGY
        return { kind: "host", cluster: shift(selection.cluster, removed), host: selection.host }
    }
    if (selection.kind !== "clusters") return selection
    const kept = selection.indices.filter((index) => !gone.has(index)).map((index) => shift(index, removed))
    return kept.length === 0 ? WHOLE_TOPOLOGY : { kind: "clusters", indices: kept }
}

export function clampToClusterCount(selection: Selection, clusters: number): Selection {
    if (selection.kind === "host") return selection.cluster < clusters ? selection : WHOLE_TOPOLOGY
    if (selection.kind !== "clusters") return selection
    const kept = selection.indices.filter((index) => index < clusters)
    return kept.length === 0 ? WHOLE_TOPOLOGY : { kind: "clusters", indices: kept }
}

function shift(index: number, removed: number[]): number {
    return index - removed.filter((entry) => entry < index).length
}

function ascending(left: number, right: number): number {
    return left - right
}
