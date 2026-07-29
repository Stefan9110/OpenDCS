"use client"

import {
    type RunLabels,
    formatScaled,
    formatSimulatedInstant,
    seriesColor,
    simulatedTimeFormatter,
    timeRows,
} from "@/components/experiment/results/resultsView"
import { type MetricId, type ScenarioResults, metricById } from "@/lib/experiment/results"
import { AreaChart, LineChart } from "@mantine/charts"
import { Center, Text, useComputedColorScheme } from "@mantine/core"

const CHART_HEIGHT = 300

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

    const series = keys.map((name, position) => ({ name, color: seriesColor(position, scheme) }))
    const shared = {
        h: CHART_HEIGHT,
        data: rows,
        dataKey: "t",
        series,
        curveType: "monotone" as const,
        strokeWidth: 2,
        withDots: false,
        connectNulls: false,
        gridAxis: "xy" as const,
        tickLine: "none" as const,
        valueFormatter: (value: number) => formatScaled(value, definition.sample),
        xAxisProps: { tickFormatter: simulatedTimeFormatter(spanMs), minTickGap: 48 },
        tooltipProps: { labelFormatter: (value: number) => formatSimulatedInstant(value, spanMs) },
        withLegend: series.length > 1,
        tooltipAnimationDuration: 120,
    }

    return series.length === 1 ? <AreaChart {...shared} fillOpacity={0.18} withGradient /> : <LineChart {...shared} />
}
