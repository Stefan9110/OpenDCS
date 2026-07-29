"use client"

import { ScenarioStateBadge } from "@/components/experiment/ExperimentStateBadge"
import { AXIS_LABELS, axisEntryLabels } from "@/components/experiment/axisLabels"
import { formatPercent } from "@/components/format"
import type { ScenarioStatus } from "@/lib/api/types"
import {
    AXIS_ORDER,
    type AxisKey,
    type ExperimentSpec,
    experimentAxes,
    scenarioCoordinates,
    scenarioCount,
} from "@/lib/experiment/spec"
import { progressFraction } from "@/lib/experiment/status"
import { Group, Paper, Progress, ScrollArea, Table, Text, Tooltip } from "@mantine/core"

const MAX_HEIGHT = 520

export function ScenarioTable({ spec, statuses }: { spec: ExperimentSpec; statuses: ScenarioStatus[] }) {
    const axes = experimentAxes(spec)
    const total = scenarioCount(spec)
    const varying = [...AXIS_ORDER].reverse().filter((key) => axes[key].length > 1)
    const entryLabels = new Map<AxisKey, string[]>(varying.map((key) => [key, axisEntryLabels(axes, key)]))
    const statusAt = new Map(statuses.map((status) => [status.scenarioIndex, status]))
    const started = statuses.length > 0

    if (total === 0) {
        return (
            <Paper withBorder radius="md" p="md">
                <Text size="sm" c="dimmed">
                    This configuration expands to no scenarios. Every axis needs at least one entry.
                </Text>
            </Paper>
        )
    }

    return (
        <Paper withBorder radius="md" p={0}>
            <ScrollArea.Autosize mah={MAX_HEIGHT}>
                <Table stickyHeader highlightOnHover verticalSpacing="xs" horizontalSpacing="md">
                    <Table.Thead>
                        <Table.Tr>
                            <Table.Th w={56}>#</Table.Th>
                            {varying.map((key) => (
                                <Table.Th key={key}>{AXIS_LABELS[key]}</Table.Th>
                            ))}
                            {started && <Table.Th w={120}>State</Table.Th>}
                            {started && <Table.Th w={160}>Progress</Table.Th>}
                        </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody>
                        {scenarioIndices(total).map((scenarioIndex) => {
                            const at = scenarioCoordinates(axes, scenarioIndex)
                            return (
                                <Table.Tr key={`scenario-${scenarioIndex}`}>
                                    <Table.Td>
                                        <Text size="sm" c="dimmed">
                                            {scenarioIndex}
                                        </Text>
                                    </Table.Td>
                                    {varying.map((key) => (
                                        <Table.Td key={key}>
                                            <Text size="sm">{entryLabels.get(key)?.[at[key]] ?? ""}</Text>
                                        </Table.Td>
                                    ))}
                                    {started && <ScenarioOutcome status={statusAt.get(scenarioIndex)} />}
                                </Table.Tr>
                            )
                        })}
                    </Table.Tbody>
                </Table>
            </ScrollArea.Autosize>
        </Paper>
    )
}

// The flattened expansion index is the scenario's identity in the contract, so it is also its key.
function scenarioIndices(total: number): number[] {
    return Array.from({ length: total }, (_, position) => position)
}

function ScenarioOutcome({ status }: { status: ScenarioStatus | undefined }) {
    if (status === undefined) {
        return (
            <>
                <Table.Td>
                    <Text size="sm" c="dimmed">
                        Queued
                    </Text>
                </Table.Td>
                <Table.Td />
            </>
        )
    }

    const fraction = progressFraction(status)
    const exit = status.exitInfo

    return (
        <>
            <Table.Td>
                {exit === undefined ? (
                    <ScenarioStateBadge state={status.state} />
                ) : (
                    <Tooltip label={exit.message ?? `exit ${exit.exitCode}`} withArrow>
                        <span>
                            <ScenarioStateBadge state={status.state} />
                        </span>
                    </Tooltip>
                )}
            </Table.Td>
            <Table.Td>
                <Group gap="xs" wrap="nowrap">
                    <Progress
                        value={fraction * 100}
                        w={90}
                        size="sm"
                        color={status.state === "failed" ? "red" : "opendc"}
                        aria-label={`Scenario ${status.scenarioIndex} progress`}
                    />
                    <Text size="xs" c="dimmed">
                        {formatPercent(fraction)}
                    </Text>
                </Group>
            </Table.Td>
        </>
    )
}
