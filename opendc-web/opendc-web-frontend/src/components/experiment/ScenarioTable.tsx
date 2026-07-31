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
    positions,
    scenarioCoordinates,
    scenarioCount,
} from "@/lib/experiment/spec"
import { progressFraction } from "@/lib/experiment/status"
import { Group, Paper, Progress, Stack, Table, Text, Tooltip } from "@mantine/core"

const MAX_HEIGHT = 520
const INDEX_WIDTH = 56
const STATE_WIDTH = 110
const PROGRESS_WIDTH = 150

// What one axis column needs before its entries start reading as fragments. Past that the table
// scrolls sideways rather than squeezing every column, which is what turned a state into "Queue...".
const AXIS_MIN_WIDTH = 150

/**
 * The columns that hold still while the axes scroll under them. They need a background of their
 * own, or the scrolling cells show through, and a layer above the ordinary cells. Mantine puts the
 * sticky header at 3, so the pinned header cells go above that and the pinned body cells below: the
 * header must cover the body, and both must cover whatever scrolls past.
 */
const PINNED = { bg: "var(--mantine-color-body)", style: { zIndex: 2 } } as const
const PINNED_HEADER = { style: { zIndex: 5 } } as const

/**
 * Where each pinned column sits, shared by its header and its cells so the two cannot drift apart.
 *
 * A phone has no room for all three: pinning 316px of them would leave an axis column a sliver to
 * scroll in. The bar is the part that goes, since it says nothing the percentage beside it does not,
 * and the state column takes the edge it leaves behind.
 */
const INDEX_COLUMN = { w: INDEX_WIDTH, pos: "sticky", left: 0 } as const
const STATE_COLUMN = { w: STATE_WIDTH, pos: "sticky", right: { base: 0, sm: PROGRESS_WIDTH } } as const
const PROGRESS_COLUMN = { w: PROGRESS_WIDTH, pos: "sticky", right: 0, visibleFrom: "sm" } as const

export function ScenarioTable({ spec, statuses }: { spec: ExperimentSpec; statuses: ScenarioStatus[] }) {
    const axes = experimentAxes(spec)
    const total = scenarioCount(spec)
    const varying = [...AXIS_ORDER].reverse().filter((key) => axes[key].length > 1)
    const entryLabels = new Map<AxisKey, string[]>(varying.map((key) => [key, axisEntryLabels(axes, key)]))
    const statusAt = new Map(statuses.map((status) => [status.scenarioIndex, status]))
    const started = statuses.length > 0

    // What the columns need to all be readable. Narrower than this the table scrolls, and the
    // pinned columns are what it scrolls under.
    const minWidth = INDEX_WIDTH + varying.length * AXIS_MIN_WIDTH + (started ? STATE_WIDTH + PROGRESS_WIDTH : 0)

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
            <Table.ScrollContainer type="native" minWidth={minWidth} maxHeight={MAX_HEIGHT}>
                <Table stickyHeader highlightOnHover verticalSpacing="xs" horizontalSpacing="md">
                    <Table.Thead>
                        <Table.Tr>
                            <Table.Th {...INDEX_COLUMN} {...PINNED_HEADER}>
                                #
                            </Table.Th>
                            {varying.map((key) => (
                                <Table.Th key={key} miw={AXIS_MIN_WIDTH}>
                                    {AXIS_LABELS[key]}
                                </Table.Th>
                            ))}
                            {started && (
                                <>
                                    <Table.Th {...STATE_COLUMN} {...PINNED_HEADER}>
                                        State
                                    </Table.Th>
                                    <Table.Th {...PROGRESS_COLUMN} {...PINNED_HEADER}>
                                        Progress
                                    </Table.Th>
                                </>
                            )}
                        </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody>
                        {positions(total).map((scenarioIndex) => {
                            const at = scenarioCoordinates(axes, scenarioIndex)
                            return (
                                <Table.Tr key={`scenario-${scenarioIndex}`}>
                                    <Table.Td {...INDEX_COLUMN} {...PINNED}>
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
            </Table.ScrollContainer>
        </Paper>
    )
}

function ScenarioOutcome({ status }: { status: ScenarioStatus | undefined }) {
    if (status === undefined) {
        return (
            <>
                <Table.Td {...STATE_COLUMN} {...PINNED}>
                    <Text size="sm" c="dimmed">
                        Queued
                    </Text>
                </Table.Td>
                <Table.Td {...PROGRESS_COLUMN} {...PINNED} />
            </>
        )
    }

    const fraction = progressFraction(status)
    const exit = status.exitInfo

    return (
        <>
            <Table.Td {...STATE_COLUMN} {...PINNED}>
                <Stack gap={2} align="flex-start">
                    {exit === undefined ? (
                        <ScenarioStateBadge state={status.state} />
                    ) : (
                        <Tooltip label={exit.message ?? `exit ${exit.exitCode}`} withArrow>
                            <span>
                                <ScenarioStateBadge state={status.state} />
                            </span>
                        </Tooltip>
                    )}
                    {/* The reading the bar carries, for the width where the bar has no column. */}
                    <Text size="xs" c="dimmed" hiddenFrom="sm">
                        {formatPercent(fraction)}
                    </Text>
                </Stack>
            </Table.Td>
            <Table.Td {...PROGRESS_COLUMN} {...PINNED}>
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
