"use client"

import { AXIS_LABELS, axisEntryLabels } from "@/components/experiment/axisLabels"
import { formatCount, formatSimulationBudget } from "@/components/format"
import type { CostEstimate } from "@/lib/api/types"
import { AXIS_ORDER, type ExperimentSpec, experimentAxes, experimentRuns, scenarioCount } from "@/lib/experiment/spec"
import { Divider, Group, Paper, Stack, Text } from "@mantine/core"

export function AxisSummary({ spec, estimate }: { spec: ExperimentSpec; estimate: CostEstimate }) {
    const axes = experimentAxes(spec)

    return (
        <Paper withBorder radius="md" p="md">
            <Stack gap="sm">
                {[...AXIS_ORDER].reverse().map((key) => (
                    <AxisRow key={key} label={AXIS_LABELS[key]} entries={axisEntryLabels(axes, key)} />
                ))}

                <Divider my={4} />

                <AxisRow label="Runs per scenario" entries={[formatCount(experimentRuns(spec))]} />
                <AxisRow label="Scenarios" entries={[formatCount(scenarioCount(spec))]} />
                <AxisRow
                    label="Simulation budget"
                    entries={[formatSimulationBudget(estimate.estimatedBudgetSeconds)]}
                />
            </Stack>
        </Paper>
    )
}

function AxisRow({ label, entries }: { label: string; entries: string[] }) {
    return (
        <Group align="flex-start" justify="space-between" wrap="nowrap" gap="lg">
            <Text size="sm" c="dimmed" style={{ flexShrink: 0 }}>
                {label}
            </Text>
            <Stack gap={2} align="flex-end">
                {entries.map((entry) => (
                    <Text key={entry} size="sm" ta="right">
                        {entry}
                    </Text>
                ))}
            </Stack>
        </Group>
    )
}
