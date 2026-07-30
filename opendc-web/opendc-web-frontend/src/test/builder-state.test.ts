import { canRedo, canUndo, initialHistory, record, redo, replace, undo } from "@/components/topology/history"
import {
    WHOLE_TOPOLOGY,
    afterRemoval,
    clampToClusterCount,
    extendToCluster,
    isClusterSelected,
    selectCluster,
    selectHost,
    selectedClusters,
    toggleHost,
} from "@/components/topology/selection"
import { describe, expect, it } from "vitest"

describe("selection", () => {
    it("treats a selected host as also selecting its cluster", () => {
        expect(selectedClusters(selectHost(2, 1))).toEqual([2])
        expect(isClusterSelected(selectHost(2, 1), 2)).toBe(true)
    })

    // Reopening the group that is already open has to close it, or the inspector traps the reader
    // in a host with no way back to the cluster except Escape, which drops the cluster too.
    it("closes the open host group when it is clicked again, keeping its cluster selected", () => {
        expect(toggleHost(selectHost(2, 1), 2, 1)).toEqual(selectCluster(2))
        expect(toggleHost(selectCluster(2), 2, 1)).toEqual(selectHost(2, 1))
    })

    it("moves between host groups rather than closing when a different one is clicked", () => {
        expect(toggleHost(selectHost(2, 1), 2, 0)).toEqual(selectHost(2, 0))
        expect(toggleHost(selectHost(2, 1), 3, 1)).toEqual(selectHost(3, 1))
    })

    it("adds and removes clusters when shift clicking, falling back to the whole topology", () => {
        const one = selectCluster(1)
        const two = extendToCluster(one, 3)
        expect(selectedClusters(two)).toEqual([1, 3])
        expect(selectedClusters(extendToCluster(two, 1))).toEqual([3])
        expect(extendToCluster(selectCluster(1), 1)).toEqual(WHOLE_TOPOLOGY)
    })

    it("keeps multi-selection ordered so bulk edits are deterministic", () => {
        const selection = extendToCluster(extendToCluster(selectCluster(5), 2), 9)
        expect(selectedClusters(selection)).toEqual([2, 5, 9])
    })

    it("shifts surviving indices down after a deletion instead of pointing at the wrong cluster", () => {
        const selection = extendToCluster(selectCluster(1), 4)
        expect(selectedClusters(afterRemoval(selection, [0, 2]))).toEqual([0, 2])
    })

    it("clears the selection when the selected cluster is the one deleted", () => {
        expect(afterRemoval(selectCluster(2), [2])).toEqual(WHOLE_TOPOLOGY)
        expect(afterRemoval(selectHost(2, 0), [2])).toEqual(WHOLE_TOPOLOGY)
    })

    it("follows a host selection when earlier clusters are deleted", () => {
        expect(afterRemoval(selectHost(3, 1), [0])).toEqual({ kind: "host", cluster: 2, host: 1 })
    })

    it("drops selections that fall outside the topology after an undo or import", () => {
        expect(clampToClusterCount(selectCluster(7), 3)).toEqual(WHOLE_TOPOLOGY)
        expect(clampToClusterCount(selectHost(7, 0), 3)).toEqual(WHOLE_TOPOLOGY)
        expect(selectedClusters(clampToClusterCount(extendToCluster(selectCluster(1), 8), 3))).toEqual([1])
    })
})

describe("history", () => {
    const start = initialHistory("a")

    it("has nothing to undo or redo when it starts", () => {
        expect(canUndo(start)).toBe(false)
        expect(canRedo(start)).toBe(false)
        expect(undo(start)).toBe(start)
        expect(redo(start)).toBe(start)
    })

    it("walks backwards and forwards through recorded edits", () => {
        const edited = record(record(start, "b"), "c")
        const back = undo(edited)
        expect(back.present).toBe("b")
        expect(redo(back).present).toBe("c")
        expect(undo(back).present).toBe("a")
    })

    it("does not record an edit that changed nothing", () => {
        expect(record(start, "a")).toBe(start)
        expect(canUndo(record(start, "a"))).toBe(false)
    })

    it("discards the redo branch once a new edit is made after undoing", () => {
        const edited = record(record(start, "b"), "c")
        const branched = record(undo(edited), "d")
        expect(canRedo(branched)).toBe(false)
        expect(branched.present).toBe("d")
        expect(undo(branched).present).toBe("b")
    })

    it("replaces the present without creating an undo step, for continuous drags", () => {
        const dragging = replace(record(start, "b"), "b-moved")
        expect(dragging.present).toBe("b-moved")
        expect(undo(dragging).present).toBe("a")
    })

    it("forgets the oldest edits rather than growing without bound", () => {
        let history = start
        for (let step = 0; step < 200; step++) history = record(history, `step-${step}`)
        expect(history.past.length).toBeLessThanOrEqual(50)
        expect(history.present).toBe("step-199")
    })
})
