"use client"

import {
    type History,
    canRedo as canRedoHistory,
    canUndo as canUndoHistory,
    initialHistory,
    record,
    redo as redoHistory,
    replace,
    undo as undoHistory,
} from "@/components/topology/history"
import { type Selection, WHOLE_TOPOLOGY, clampToClusterCount } from "@/components/topology/selection"
import { notifyProblem } from "@/components/util/feedback"
import { useSaveTopology } from "@/lib/api/topologies"
import type { TopologyTemplate, ValidationIssue } from "@/lib/api/types"
import type { TopologyPlan } from "@/lib/topology/edits"
import type { FloorLayout } from "@/lib/topology/layout"
import { reconcile } from "@/lib/topology/layout"
import { validateTopology } from "@/lib/topology/validation"
import { useCallback, useEffect, useMemo, useState } from "react"

const AUTOSAVE_DELAY_MS = 1200

export type SaveState = "saved" | "pending" | "saving"

export interface TopologyEditor {
    name: string
    plan: TopologyPlan
    selection: Selection
    issues: ValidationIssue[]
    saveState: SaveState
    canUndo: boolean
    canRedo: boolean
    select: (selection: Selection) => void
    rename: (name: string) => void
    apply: (change: (plan: TopologyPlan) => TopologyPlan) => void
    applyTransient: (change: (plan: TopologyPlan) => TopologyPlan) => void
    undo: () => void
    redo: () => void
    saveNow: () => void
}

export function useTopologyEditor(projectId: number, template: TopologyTemplate, layout: FloorLayout): TopologyEditor {
    const save = useSaveTopology(projectId)
    const [history, setHistory] = useState<History<TopologyPlan>>(() =>
        initialHistory({ topology: template.topology, layout: reconcile(layout, template.topology) }),
    )
    const [name, setName] = useState(template.name)
    const [saved, setSaved] = useState<TopologyPlan>(() => history.present)
    const [selection, setSelection] = useState<Selection>(WHOLE_TOPOLOGY)

    const plan = history.present
    const issues = useMemo(() => validateTopology(plan.topology), [plan.topology])
    const dirty = plan !== saved || name !== template.name

    const flush = useCallback(() => {
        if (!dirty || save.isPending) return
        setSaved(plan)
        save.mutate(
            { templateId: template.id, name, topology: plan.topology, layout: plan.layout },
            { onError: notifyProblem },
        )
    }, [dirty, name, plan, save, template.id])

    useEffect(() => {
        if (!dirty) return
        const timer = setTimeout(flush, AUTOSAVE_DELAY_MS)
        return () => clearTimeout(timer)
    }, [dirty, flush])

    const changeSelection = useCallback((next: Selection) => setSelection(next), [])

    const apply = useCallback((change: (plan: TopologyPlan) => TopologyPlan) => {
        setHistory((current) => record(current, change(current.present)))
    }, [])

    const applyTransient = useCallback((change: (plan: TopologyPlan) => TopologyPlan) => {
        setHistory((current) => replace(current, change(current.present)))
    }, [])

    const step = useCallback((move: (history: History<TopologyPlan>) => History<TopologyPlan>) => {
        setHistory((current) => {
            const next = move(current)
            setSelection((chosen) => clampToClusterCount(chosen, next.present.topology.clusters.length))
            return next
        })
    }, [])

    return {
        name,
        plan,
        selection,
        issues,
        saveState: save.isPending ? "saving" : dirty ? "pending" : "saved",
        canUndo: canUndoHistory(history),
        canRedo: canRedoHistory(history),
        select: changeSelection,
        rename: setName,
        apply,
        applyTransient,
        undo: () => step(undoHistory),
        redo: () => step(redoHistory),
        saveNow: flush,
    }
}
