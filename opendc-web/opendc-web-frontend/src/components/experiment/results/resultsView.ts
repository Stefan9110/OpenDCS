import { AXIS_LABELS, axisEntryLabels } from "@/components/experiment/axisLabels"
import {
    type ExperimentResults,
    type MetricId,
    type MetricScale,
    type ScenarioResults,
    alignOnTimestamp,
    bucketPoints,
    metricById,
    reduceMetric,
    reportedMetrics,
    seriesOf,
} from "@/lib/experiment/results"
import {
    AXIS_ORDER,
    type AxisKey,
    type ExperimentSpec,
    experimentAxes,
    scenarioCoordinates,
} from "@/lib/experiment/spec"

// Validated against the light (#ffffff) and dark (#242424) chart surfaces: every slot clears the
// lightness, chroma, colour-vision and normal-vision gates. Overlaid lines cross wherever they like,
// so every pair has to hold apart rather than only neighbouring ones, and three is the cap that
// ordering supports -- so the chart overlays at most three runs at a time.
const SERIES_COLORS: Record<ColorScheme, string[]> = {
    light: ["#2a78d6", "#eb6834", "#1baf7a"],
    dark: ["#3987e5", "#d95926", "#199e70"],
}

export type ColorScheme = "light" | "dark"

export const MAX_OVERLAID_RUNS = SERIES_COLORS.light.length

export function seriesColor(position: number, scheme: ColorScheme): string {
    const palette = SERIES_COLORS[scheme]
    return palette[position % palette.length] ?? palette[0] ?? "gray"
}

export function magnitudeColor(scheme: ColorScheme): string {
    return seriesColor(0, scheme)
}

export interface RunLabels {
    short: string
    full: string
}

const SHORT_LABEL_AXES = 2

// A run's short name has to tell it apart from the runs it is shown beside, so the axes it names are
// the ones that actually differ across that set. Naming a sweep-wide axis that happens to be constant
// within the set would label three lines identically.
export function describeRuns(spec: ExperimentSpec, scenarioIndices: number[]): RunLabels[] {
    const axes = experimentAxes(spec)
    const varying = [...AXIS_ORDER].reverse().filter((key) => axes[key].length > 1)
    const coordinates = scenarioIndices.map((index) => scenarioCoordinates(axes, index))
    const distinguishing = varying.filter((key) => new Set(coordinates.map((at) => at[key])).size > 1)

    return scenarioIndices.map((scenarioIndex, position) => {
        const at = coordinates[position]
        const entryAt = (key: AxisKey) => (at === undefined ? "" : (axisEntryLabels(axes, key)[at[key]] ?? ""))
        const naming = distinguishing.slice(0, SHORT_LABEL_AXES).map(entryAt).join(" · ")

        return {
            short: naming === "" ? `#${scenarioIndex}` : `#${scenarioIndex} ${naming}`,
            full:
                varying.length === 0
                    ? "The only scenario in this experiment"
                    : varying.map((key) => `${AXIS_LABELS[key]}: ${entryAt(key)}`).join(" · "),
        }
    })
}

export function metricOptions(results: ExperimentResults): Array<{ value: string; label: string }> {
    return reportedMetrics(results).map((metric) => ({ value: metric.id, label: metric.label }))
}

// The server already reduces a series to a chart-sized bucket count; this is the client's own
// guard so a longer trace can never put more points on screen than there are pixels for them.
const MAX_CHART_POINTS = 600

export function timeRows(
    scenarios: ScenarioResults[],
    metric: MetricId,
    keys: string[],
): Array<Record<string, number>> {
    const definition = metricById(metric)
    const scale = definition.sample.scale
    const capped = scenarios.map((scenario) =>
        bucketPoints(seriesOf(scenario, metric).points, MAX_CHART_POINTS, definition.reduce),
    )
    return alignOnTimestamp(capped).map((row) => {
        const shaped: Record<string, number> = { t: row.t }
        for (const [position, key] of keys.entries()) {
            const value = row.values[position]
            if (value !== undefined) shaped[key] = value * scale
        }
        return shaped
    })
}

export interface ComparisonRow {
    run: string
    value: number
    spread: number
}

export function comparisonRows(scenarios: ScenarioResults[], metric: MetricId): ComparisonRow[] {
    const scale = metricById(metric).total.scale
    return scenarios.flatMap((scenario) => {
        const reduced = reduceMetric(scenario, metric)
        if (reduced.status === "empty") return []
        return [
            {
                run: `#${scenario.scenarioIndex}`,
                value: reduced.value * scale,
                spread: seriesOf(scenario, metric).spread * scale,
            },
        ]
    })
}

const numbers = new Intl.NumberFormat("en-GB", { maximumFractionDigits: 2 })

export function formatScaled(value: number, scale: MetricScale): string {
    const shown = new Intl.NumberFormat("en-GB", {
        minimumFractionDigits: scale.decimals,
        maximumFractionDigits: scale.decimals,
    }).format(value)
    return scale.unit === "" ? shown : `${shown} ${scale.unit}`
}

export function formatReduced(value: number, metric: MetricId): string {
    const scale = metricById(metric).total
    return formatScaled(value * scale.scale, scale)
}

const MINUTE_MS = 60_000
const HOUR_MS = 3_600_000
const DAY_MS = 86_400_000
const WEEK_MS = 7 * DAY_MS

// Spans a reader recognises. A tick lands on an exact multiple of one of these, which is what lets
// every label be a whole number of its own unit.
const TICK_STEPS = [
    MINUTE_MS,
    5 * MINUTE_MS,
    15 * MINUTE_MS,
    30 * MINUTE_MS,
    HOUR_MS,
    2 * HOUR_MS,
    3 * HOUR_MS,
    6 * HOUR_MS,
    12 * HOUR_MS,
    DAY_MS,
    2 * DAY_MS,
    3 * DAY_MS,
    WEEK_MS,
    2 * WEEK_MS,
    4 * WEEK_MS,
    13 * WEEK_MS,
    26 * WEEK_MS,
    52 * WEEK_MS,
]

const TARGET_TICKS = 8

export interface TimeAxis {
    ticks: number[]
    format: (milliseconds: number) => string
}

/**
 * Where the time axis is marked, and how those marks read.
 *
 * The two are chosen together because they have to agree. Marks left where they fall and then
 * labelled in whole weeks put the same week on the axis twice and leave the one between them off it,
 * which reads as a chart that has lost track of its own time. Here a step is picked from spans a
 * reader recognises, the marks land on exact multiples of it, and the unit is the one the step is a
 * whole number of -- so no two labels can collide and none can be skipped.
 */
export function timeAxis(spanMs: number): TimeAxis {
    const widest = TICK_STEPS[TICK_STEPS.length - 1] ?? DAY_MS
    const step = TICK_STEPS.find((candidate) => spanMs / candidate <= TARGET_TICKS) ?? widest
    const ticks: number[] = []
    for (let at = 0; at <= spanMs; at += step) {
        ticks.push(at)
    }
    return { ticks, format: labelEvery(step) }
}

function labelEvery(step: number): (milliseconds: number) => string {
    if (step >= WEEK_MS) return (milliseconds) => `${milliseconds / WEEK_MS}w`
    if (step >= DAY_MS) return (milliseconds) => `${milliseconds / DAY_MS}d`
    if (step >= HOUR_MS) return (milliseconds) => `${milliseconds / HOUR_MS}h`
    return (milliseconds) => `${milliseconds / MINUTE_MS}m`
}

function clockOf(milliseconds: number): string {
    const hours = Math.floor(milliseconds / HOUR_MS)
    const minutes = Math.floor((milliseconds % HOUR_MS) / 60_000)
    return `${String(hours).padStart(2, "0")}:${String(minutes).padStart(2, "0")}`
}

// The tooltip header has to say where in the run the reader is standing. A bare "12:00" is
// ambiguous on a trace that covers a year, so the day is named once the run is longer than one.
export function formatSimulatedInstant(milliseconds: number, spanMs: number): string {
    const clock = clockOf(milliseconds % DAY_MS)
    return spanMs < DAY_MS ? clock : `Day ${Math.floor(milliseconds / DAY_MS) + 1}, ${clock}`
}

export function formatSimulatedDuration(milliseconds: number): string {
    if (milliseconds >= DAY_MS) return plural(Math.round(milliseconds / DAY_MS), "day")
    if (milliseconds >= HOUR_MS) return plural(Math.round(milliseconds / HOUR_MS), "hour")
    return plural(Math.max(1, Math.round(milliseconds / 60_000)), "minute")
}

function plural(count: number, unit: string): string {
    return `${count} ${unit}${count === 1 ? "" : "s"}`
}

// "every 1 day" reads badly where "every day" does not.
export function formatInterval(milliseconds: number): string {
    const spelled = formatSimulatedDuration(milliseconds)
    return spelled.startsWith("1 ") ? spelled.slice(2) : spelled
}

export function formatPlain(value: number): string {
    return numbers.format(value)
}

export function resultsCsv(results: ExperimentResults): string {
    const metrics = reportedMetrics(results)
    const header = ["scenario", "simulated_ms", ...metrics.map((metric) => metric.column)]
    const lines = [header.join(",")]

    for (const scenario of results.scenarios) {
        const stamps = alignOnTimestamp(metrics.map((metric) => seriesOf(scenario, metric.id).points))
        for (const row of stamps) {
            const cells = metrics.map((_, position) => row.values[position]?.toString() ?? "")
            lines.push([scenario.scenarioIndex, row.t, ...cells].join(","))
        }
    }

    return lines.join("\n")
}
