"use client"

import { type LegendEntry, SeriesLegend } from "@/components/experiment/results/SeriesLegend"
import {
    type RunLabels,
    formatScaled,
    formatSimulatedInstant,
    seriesColor,
    timeAxis,
    timeRows,
} from "@/components/experiment/results/resultsView"
import { type MetricId, type ScenarioResults, metricById } from "@/lib/experiment/results"
import { LineChart } from "@mantine/charts"
import { Center, Stack, Text, useComputedColorScheme } from "@mantine/core"
import { useState } from "react"

const CHART_HEIGHT = 300

/** A line the reader has looked away from. It keeps its place on the axis without being drawn. */
const HIDDEN = "transparent"

export function MetricChart({
    runs,
    labels,
    metric,
    spanMs,
}: {
    runs: ScenarioResults[]
    labels: RunLabels[]
    metric: MetricId
    spanMs: number
}) {
    const scheme = useComputedColorScheme("light")
    const [isolated, setIsolated] = useState<string | undefined>(undefined)
    const definition = metricById(metric)
    const keys = labels.map((label) => label.short)
    const rows = timeRows(runs, metric, keys)

    if (rows.length === 0) {
        return (
            <Center h={CHART_HEIGHT}>
                <Text size="sm" c="dimmed">
                    No samples yet. The first ones arrive once a scenario starts simulating.
                </Text>
            </Center>
        )
    }

    // A run that reported one instant has no span to divide up; the axis still needs a width.
    const span = Math.max(spanMs, 1)
    const axis = timeAxis(span)
    const entries: LegendEntry[] = keys.map((name, position) => ({ name, color: seriesColor(position, scheme) }))
    // Isolating by colour rather than by hiding the line keeps every run in the axis, so the scale
    // stays where it was and only the drawing changes as the pointer moves along the legend.
    const series = entries.map((entry) => ({
        name: entry.name,
        color: isolated === undefined || isolated === entry.name ? entry.color : HIDDEN,
    }))

    return (
        <Stack gap="xs">
            <LineChart
                h={CHART_HEIGHT}
                data={rows}
                dataKey="t"
                series={series}
                curveType="monotone"
                strokeWidth={2}
                withDots={false}
                connectNulls={false}
                gridAxis="xy"
                tickLine="none"
                // Mantine's own legend dims the runs a reader is not pointing at rather than taking
                // them off the chart, which a sweep of a dozen runs is not helped by.
                withLegend={false}
                valueFormatter={(value: number) => formatScaled(value, definition.sample)}
                // A time axis has to be read as time: left as categories, the points sit an equal
                // distance apart whatever the gap between them, and the marks fall wherever a point
                // happens to be rather than on a round number of weeks.
                xAxisProps={{
                    type: "number",
                    domain: [0, span],
                    ticks: axis.ticks,
                    tickFormatter: axis.format,
                    minTickGap: 48,
                }}
                tooltipProps={{ labelFormatter: (value: number) => formatSimulatedInstant(value, spanMs) }}
                tooltipAnimationDuration={120}
            />
            {entries.length > 1 && <SeriesLegend entries={entries} isolated={isolated} onIsolate={setIsolated} />}
        </Stack>
    )
}
