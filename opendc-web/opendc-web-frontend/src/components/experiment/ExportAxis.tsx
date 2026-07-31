"use client"

import { AXIS_HELP } from "@/components/experiment/axisLabels"
import { FieldLabel, UnitAdornment } from "@/components/topology/inspector/FieldLabel"
import { DEFAULT_EXPORT_INTERVAL, type ExportSpec } from "@/lib/experiment/spec"
import { amountIn, parseQuantity } from "@/lib/units"
import { TagsInput } from "@mantine/core"

// Every entry is counted in minutes, so the box says so once rather than each entry carrying a unit
// of its own. A value stored in another unit is converted rather than refused: the document may
// have been written by hand or by the SDK, neither of which owes this field its unit.
const UNIT = "min"

// Wide enough for the unit to sit clear of the last entry.
const UNIT_WIDTH = 44

/**
 * The snapshot intervals. Only the interval is edited here: an entry the document already carries
 * keeps its column selection and file list, which are not this field's to drop.
 */
export function ExportAxis({
    entries,
    onChange,
}: Readonly<{ entries: ExportSpec[]; onChange: (next: ExportSpec[]) => void }>) {
    return (
        <TagsInput
            label={<FieldLabel label="Export interval" help={AXIS_HELP.exportModels} />}
            size="sm"
            value={entries.map(minutesOf)}
            onChange={(tags) => onChange(rebuild(tags, entries))}
            splitChars={[",", " "]}
            placeholder={entries.length === 0 ? "Add an interval" : undefined}
            rightSection={<UnitAdornment unit={UNIT} />}
            rightSectionWidth={UNIT_WIDTH}
            rightSectionPointerEvents="none"
        />
    )
}

function minutesOf(entry: ExportSpec): string {
    const wire = entry.exportInterval ?? DEFAULT_EXPORT_INTERVAL
    const parsed = parseQuantity("time", wire)
    // Anything unreadable is shown as it was stored. Hiding it would take the entry with it on the
    // next edit, and the scenarios it expands to along with it.
    return parsed.status === "ok" ? String(amountIn("time", parsed.base, UNIT)) : String(wire)
}

// A tag that still reads as an entry the document holds keeps that entry whole. Tags are matched in
// the order they appear, so two entries written at the same interval stay two entries.
function rebuild(tags: string[], entries: ExportSpec[]): ExportSpec[] {
    const remaining = [...entries]

    return tags.flatMap((tag) => {
        const at = remaining.findIndex((entry) => minutesOf(entry) === tag)
        if (at !== -1) return remaining.splice(at, 1)
        const minutes = Number(tag)
        return Number.isFinite(minutes) && minutes > 0 ? [{ exportInterval: `${minutes} ${UNIT}` }] : []
    })
}
