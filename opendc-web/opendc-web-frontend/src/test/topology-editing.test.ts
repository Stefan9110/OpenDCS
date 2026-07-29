import {
    type TopologyPlan,
    addCluster,
    addHost,
    duplicateCluster,
    moveCluster,
    removeCluster,
    removeClusters,
    removeHost,
    updateCluster,
    updateHost,
} from "@/lib/topology/edits"
import { DEFAULT_FLOOR_WIDTH, autoLayout, clusterAt, emptyLayout, reconcile, reserveCell } from "@/lib/topology/layout"
import type { ClusterSpec, HostSpec, TopologySpec } from "@/lib/topology/spec"
import { describe, expect, it } from "vitest"

function host(overrides: Partial<HostSpec> = {}): HostSpec {
    return { cpu: { coreCount: 8, coreSpeed: "3 GHz" }, memory: { size: "64 GiB" }, ...overrides }
}

function cluster(name: string, overrides: Partial<ClusterSpec> = {}): ClusterSpec {
    return { name, hosts: [host()], ...overrides }
}

function planOf(...names: string[]): TopologyPlan {
    const topology: TopologySpec = { clusters: names.map((name) => cluster(name)) }
    return { topology, layout: autoLayout(topology) }
}

function names(plan: TopologyPlan): Array<string | undefined> {
    return plan.topology.clusters.map((entry) => entry.name)
}

describe("cluster edits", () => {
    it("keeps fields the editor does not understand, so documents round-trip", () => {
        const topology = {
            schemaVersion: 7,
            clusters: [{ name: "a", hosts: [host()], coolingProfile: { kind: "immersion" } }],
        } as TopologySpec
        const plan = updateCluster({ topology, layout: autoLayout(topology) }, 0, { name: "renamed" })
        const edited = plan.topology.clusters[0]

        expect(plan.topology.schemaVersion).toBe(7)
        expect(edited?.coolingProfile).toEqual({ kind: "immersion" })
        expect(edited?.name).toBe("renamed")
    })

    it("removes the layout cell along with the cluster so indices stay aligned", () => {
        const plan = removeCluster(planOf("a", "b", "c"), 1)
        expect(names(plan)).toEqual(["a", "c"])
        expect(plan.layout.cells).toHaveLength(2)
        expect(plan.layout.cells[1]).toEqual({ x: 2, y: 0 })
    })

    it("removes several clusters without the earlier deletions shifting the later ones", () => {
        const plan = removeClusters(planOf("a", "b", "c", "d"), [0, 2])
        expect(names(plan)).toEqual(["b", "d"])
        expect(plan.layout.cells).toHaveLength(2)
    })

    it("inserts a duplicate next to its source on a free cell", () => {
        const plan = duplicateCluster(planOf("a", "b"), 0)
        expect(names(plan)).toEqual(["a", "a", "b"])
        expect(new Set(plan.layout.cells.map((cell) => `${cell.x},${cell.y}`)).size).toBe(3)
    })

    it("does not let an edit to a duplicate leak back into its source", () => {
        const duplicated = duplicateCluster(planOf("a"), 0)
        const edited = updateHost(duplicated, 1, 0, { count: 16 })
        expect(edited.topology.clusters[0]?.hosts[0]?.count).toBeUndefined()
        expect(edited.topology.clusters[1]?.hosts[0]?.count).toBe(16)
    })

    it("swaps two clusters when one is dropped onto the other", () => {
        const plan = moveCluster(planOf("a", "b"), 0, { x: 1, y: 0 })
        expect(plan.layout.cells[0]).toEqual({ x: 1, y: 0 })
        expect(plan.layout.cells[1]).toEqual({ x: 0, y: 0 })
    })

    it("ignores a move outside the floor", () => {
        const plan = planOf("a")
        expect(moveCluster(plan, 0, { x: -1, y: 0 })).toBe(plan)
        expect(moveCluster(plan, 0, { x: 0, y: 999 })).toBe(plan)
    })

    it("ignores edits addressed to clusters that do not exist", () => {
        const plan = planOf("a")
        expect(removeCluster(plan, 4)).toBe(plan)
        expect(duplicateCluster(plan, 4)).toBe(plan)
        expect(addHost(plan, 4, host())).toBe(plan)
    })

    it("adds a cluster at the cell the user picked", () => {
        const plan = addCluster(planOf("a"), cluster("b"), { x: 5, y: 3 })
        expect(plan.layout.cells[1]).toEqual({ x: 5, y: 3 })
        expect(names(plan)).toEqual(["a", "b"])
    })
})

describe("host edits", () => {
    it("changes one host group without touching its siblings", () => {
        const base = planOf("a")
        const plan = addHost(base, 0, host({ name: "gpu" }))
        const edited = updateHost(plan, 0, 1, { count: 8 })
        expect(edited.topology.clusters[0]?.hosts[0]?.count).toBeUndefined()
        expect(edited.topology.clusters[0]?.hosts[1]?.count).toBe(8)
    })

    it("can empty a cluster, which validation then rejects", () => {
        const plan = removeHost(planOf("a"), 0, 0)
        expect(plan.topology.clusters[0]?.hosts).toEqual([])
    })
})

describe("floor layout", () => {
    it("is deterministic for the same topology", () => {
        const topology: TopologySpec = { clusters: [cluster("a"), cluster("b"), cluster("c")] }
        expect(autoLayout(topology)).toEqual(autoLayout(topology))
    })

    it("packs clusters row major and wraps at the floor width", () => {
        const clusters = Array.from({ length: DEFAULT_FLOOR_WIDTH + 1 }, (_, index) => cluster(`c${index}`))
        const layout = autoLayout({ clusters })
        expect(layout.cells[DEFAULT_FLOOR_WIDTH - 1]).toEqual({ x: DEFAULT_FLOOR_WIDTH - 1, y: 0 })
        expect(layout.cells[DEFAULT_FLOOR_WIDTH]).toEqual({ x: 0, y: 1 })
    })

    it("gives newly imported clusters a cell without moving the ones already placed", () => {
        const placed = { version: 1, size: { width: 4, height: 4 }, cells: [{ x: 3, y: 3 }] }
        const layout = reconcile(placed, { clusters: [cluster("a"), cluster("b")] })
        expect(layout.cells[0]).toEqual({ x: 3, y: 3 })
        expect(layout.cells[1]).toEqual({ x: 0, y: 0 })
    })

    it("drops cells for clusters that no longer exist", () => {
        const placed = {
            version: 1,
            size: { width: 4, height: 4 },
            cells: [
                { x: 0, y: 0 },
                { x: 1, y: 0 },
                { x: 2, y: 0 },
            ],
        }
        expect(reconcile(placed, { clusters: [cluster("a")] }).cells).toEqual([{ x: 0, y: 0 }])
    })

    it("separates clusters that a corrupted layout stacked on one cell", () => {
        const placed = {
            version: 1,
            size: { width: 4, height: 4 },
            cells: [
                { x: 2, y: 2 },
                { x: 2, y: 2 },
            ],
        }
        const layout = reconcile(placed, { clusters: [cluster("a"), cluster("b")] })
        expect(layout.cells[0]).toEqual({ x: 2, y: 2 })
        expect(layout.cells[1]).not.toEqual({ x: 2, y: 2 })
    })

    it("grows the floor when there are more clusters than cells", () => {
        const placed = { version: 1, size: { width: 2, height: 1 }, cells: [] }
        const layout = reconcile(placed, { clusters: [cluster("a"), cluster("b"), cluster("c")] })
        expect(layout.size.height).toBe(2)
        expect(layout.cells).toHaveLength(3)
    })

    it("grows the floor rather than failing when reserving a cell on a full one", () => {
        const full = {
            version: 1,
            size: { width: 1, height: 1 },
            cells: [{ x: 0, y: 0 }],
        }
        const reserved = reserveCell(full, { x: 0, y: 0 })
        expect(reserved.layout.size.height).toBe(2)
        expect(reserved.cell).toEqual({ x: 0, y: 1 })
    })

    it("reports what occupies a cell", () => {
        const layout = autoLayout({ clusters: [cluster("a"), cluster("b")] })
        expect(clusterAt(layout, { x: 1, y: 0 })).toEqual({ status: "occupied", cluster: 1 })
        expect(clusterAt(layout, { x: 9, y: 9 })).toEqual({ status: "empty" })
    })

    it("starts empty with a usable default floor", () => {
        expect(emptyLayout().cells).toEqual([])
        expect(emptyLayout().size.width).toBe(DEFAULT_FLOOR_WIDTH)
    })
})
