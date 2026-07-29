import type { TopologySpec } from "@/lib/topology/spec"
import { z } from "zod"

export const LAYOUT_VERSION = 1

export const DEFAULT_FLOOR_WIDTH = 20
export const DEFAULT_FLOOR_HEIGHT = 14

export const floorCellSchema = z.object({ x: z.number().int().min(0), y: z.number().int().min(0) })

export const floorLayoutSchema = z.object({
    version: z.number().int(),
    size: z.object({ width: z.number().int().positive(), height: z.number().int().positive() }),
    cells: z.array(floorCellSchema),
})

export type FloorCell = z.infer<typeof floorCellSchema>
export type FloorLayout = z.infer<typeof floorLayoutSchema>

export type CellOccupant = { status: "empty" } | { status: "occupied"; cluster: number }

export function emptyLayout(): FloorLayout {
    return {
        version: LAYOUT_VERSION,
        size: { width: DEFAULT_FLOOR_WIDTH, height: DEFAULT_FLOOR_HEIGHT },
        cells: [],
    }
}

export function autoLayout(topology: TopologySpec): FloorLayout {
    return reconcile(emptyLayout(), topology)
}

export function reconcile(layout: FloorLayout, topology: TopologySpec): FloorLayout {
    const size = grownTo(layout.size, topology.clusters.length)
    const taken = new Set<string>()
    const kept: Array<FloorCell | undefined> = []

    for (let index = 0; index < topology.clusters.length; index++) {
        const cell = layout.cells[index]
        if (cell && withinBounds(cell, size) && !taken.has(key(cell))) {
            taken.add(key(cell))
            kept.push(cell)
        } else {
            kept.push(undefined)
        }
    }

    let cursor = 0
    return {
        version: LAYOUT_VERSION,
        size,
        cells: kept.map((cell) => {
            if (cell) return cell
            const next = scanFree(size, taken, cursor)
            cursor = next.cursor + 1
            taken.add(key(next.cell))
            return next.cell
        }),
    }
}

export function clusterAt(layout: FloorLayout, cell: FloorCell): CellOccupant {
    const index = layout.cells.findIndex((candidate) => candidate.x === cell.x && candidate.y === cell.y)
    return index === -1 ? { status: "empty" } : { status: "occupied", cluster: index }
}

export function placeCluster(layout: FloorLayout, cluster: number, cell: FloorCell): FloorLayout {
    const current = layout.cells[cluster]
    if (!current || !withinBounds(cell, layout.size)) return layout
    const occupant = clusterAt(layout, cell)
    const cells = layout.cells.map((existing, index) => {
        if (index === cluster) return cell
        if (occupant.status === "occupied" && index === occupant.cluster) return current
        return existing
    })
    return { ...layout, cells }
}

export function reserveCell(layout: FloorLayout, near: FloorCell): { layout: FloorLayout; cell: FloorCell } {
    const taken = new Set(layout.cells.map(key))
    const start = near.y * layout.size.width + near.x + 1
    const scan = scanFree(layout.size, taken, Math.max(0, start))
    if (withinBounds(scan.cell, layout.size)) return { layout, cell: scan.cell }
    const size = { width: layout.size.width, height: layout.size.height + 1 }
    return { layout: { ...layout, size }, cell: scanFree(size, taken, 0).cell }
}

export function removeCell(layout: FloorLayout, cluster: number): FloorLayout {
    return { ...layout, cells: layout.cells.filter((_, index) => index !== cluster) }
}

export function insertCell(layout: FloorLayout, cluster: number, cell: FloorCell): FloorLayout {
    const cells = [...layout.cells]
    cells.splice(cluster, 0, cell)
    return { ...layout, cells }
}

function scanFree(
    size: { width: number; height: number },
    taken: Set<string>,
    from: number,
): { cell: FloorCell; cursor: number } {
    const total = size.width * size.height
    for (let cursor = from; cursor < total; cursor++) {
        const cell = { x: cursor % size.width, y: Math.floor(cursor / size.width) }
        if (!taken.has(key(cell))) return { cell, cursor }
    }
    for (let cursor = 0; cursor < from && cursor < total; cursor++) {
        const cell = { x: cursor % size.width, y: Math.floor(cursor / size.width) }
        if (!taken.has(key(cell))) return { cell, cursor }
    }
    return { cell: { x: 0, y: size.height }, cursor: total }
}

function grownTo(size: { width: number; height: number }, clusters: number): { width: number; height: number } {
    const width = Math.max(size.width, 1)
    const needed = Math.ceil(clusters / width)
    return { width, height: Math.max(size.height, needed) }
}

function withinBounds(cell: FloorCell, size: { width: number; height: number }): boolean {
    return cell.x >= 0 && cell.y >= 0 && cell.x < size.width && cell.y < size.height
}

function key(cell: FloorCell): string {
    return `${cell.x},${cell.y}`
}
