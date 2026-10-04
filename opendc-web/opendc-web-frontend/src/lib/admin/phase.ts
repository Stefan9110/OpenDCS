import type { CarriedOutcome, ExecutionPhase } from "@/lib/api/admin"
import type { ExitReason } from "@/lib/api/types"

export const EXIT_REASON_LABELS: Record<ExitReason, string> = {
    ok: "Exited cleanly",
    simulationError: "Simulation error",
    invalidSpec: "Invalid document",
    oom: "Out of memory",
    timeout: "Ran out of time",
    walltime: "Hit the platform's wall time",
    cancelled: "Cancelled",
    rejected: "Refused by the platform",
    unknown: "Lost by the platform",
}

export interface PhaseBadge {
    label: string
    color: string
}

/** How an execution's phase reads in a list: a running one past twice its estimate stands out. */
export function phaseBadge(phase: ExecutionPhase): PhaseBadge {
    switch (phase.type) {
        case "queued":
            return { label: "Queued", color: "blue" }
        case "submitted":
            return { label: "Submitted", color: "cyan" }
        case "running":
            return phase.straggler ? { label: "Overdue", color: "orange" } : { label: "Running", color: "opendc" }
        case "ended":
            return endedBadge(phase.state)
    }
}

function endedBadge(state: string): PhaseBadge {
    if (state === "succeeded") return { label: "Succeeded", color: "green" }
    if (state === "failed") return { label: "Failed", color: "red" }
    return { label: "Cancelled", color: "gray" }
}

export function outcomeLabel(outcome: CarriedOutcome): string {
    switch (outcome.type) {
        case "carried":
            return "Carried"
        case "succeeded":
            return "Succeeded"
        case "failed":
            return EXIT_REASON_LABELS[outcome.reason]
        case "cancelled":
            return "Cancelled"
    }
}

/** When the execution last changed phase, which is the one time a list of them is sorted by in a reader's head. */
export function phaseSince(phase: ExecutionPhase, createdAt: string): string {
    switch (phase.type) {
        case "queued":
            return createdAt
        case "submitted":
            return phase.submittedAt
        case "running":
            return phase.startedAt
        case "ended":
            return phase.settledAt
    }
}

/** Scenario indices as a compact range list: 0-3, 7, 9-10. */
export function scenarioRanges(indices: readonly number[]): string {
    const sorted = [...new Set(indices)].sort((left, right) => left - right)
    const ranges: string[] = []
    let start = sorted[0]
    let previous = start
    for (const index of sorted.slice(1)) {
        if (previous !== undefined && index === previous + 1) {
            previous = index
            continue
        }
        if (start !== undefined) ranges.push(rangeOf(start, previous ?? start))
        start = index
        previous = index
    }
    if (start !== undefined) ranges.push(rangeOf(start, previous ?? start))
    return ranges.join(", ")
}

function rangeOf(start: number, end: number): string {
    return start === end ? String(start) : `${start}-${end}`
}
