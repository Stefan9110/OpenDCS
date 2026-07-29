"use client"

import { formatCount, formatMemory, formatPower } from "@/components/format"
import { ShortcutGuide } from "@/components/topology/ShortcutGuide"
import { ClusterInspector } from "@/components/topology/inspector/ClusterInspector"
import type { Selection } from "@/components/topology/selection"
import type { ValidationIssue } from "@/lib/api/types"
import { topologyCapacity, topologyPowerBudget } from "@/lib/topology/capacity"
import type { TopologyPlan } from "@/lib/topology/edits"
import { updateClusters } from "@/lib/topology/edits"
import { clusterName } from "@/lib/topology/spec"
import { Alert, Divider, Group, NumberInput, ScrollArea, Stack, Text } from "@mantine/core"
import { IconAlertTriangle } from "@tabler/icons-react"

export interface InspectorProps {
    plan: TopologyPlan
    selection: Selection
    issues: ValidationIssue[]
    onSelectHost: (cluster: number, host: number) => void
    apply: (change: (plan: TopologyPlan) => TopologyPlan) => void
}

export function TopologyInspector(props: InspectorProps) {
    const { plan, selection, issues } = props

    if (selection.kind === "clusters" && selection.indices.length > 1) {
        return <BulkInspector plan={plan} indices={selection.indices} apply={props.apply} />
    }

    const index =
        selection.kind === "clusters" ? selection.indices[0] : selection.kind === "host" ? selection.cluster : undefined
    const cluster = index === undefined ? undefined : plan.topology.clusters[index]

    if (cluster === undefined || index === undefined) return <TopologyOverview plan={plan} issues={issues} />

    return (
        <ClusterInspector
            cluster={cluster}
            index={index}
            selectedHost={selection.kind === "host" ? selection.host : -1}
            issues={issues}
            onSelectHost={props.onSelectHost}
            apply={props.apply}
        />
    )
}

function TopologyOverview({ plan, issues }: { plan: TopologyPlan; issues: ValidationIssue[] }) {
    const capacity = topologyCapacity(plan.topology)
    const budget = topologyPowerBudget(plan.topology)
    const overBudget = budget.status === "limited" && capacity.peakPowerW > budget.watts

    return (
        <ScrollArea h="100%" p="md">
            <Stack gap="sm">
                <Text fw={600}>Topology</Text>
                <Stack gap={4}>
                    <Stat label="Clusters" value={formatCount(capacity.clusters)} />
                    <Stat label="Hosts" value={formatCount(capacity.hosts)} />
                    <Stat label="Cores" value={formatCount(capacity.cores)} />
                    {capacity.gpus > 0 && <Stat label="GPUs" value={formatCount(capacity.gpus)} />}
                    <Stat label="Memory" value={formatMemory(capacity.memoryMiB)} />
                    <Stat label="Peak draw" value={formatPower(capacity.peakPowerW)} warn={overBudget} />
                    {budget.status === "limited" && <Stat label="Supply limit" value={formatPower(budget.watts)} />}
                </Stack>

                {issues.length > 0 && (
                    <Alert color="red" icon={<IconAlertTriangle size={16} />} title={`${issues.length} problems`}>
                        <Stack gap={2}>
                            {issues.slice(0, 8).map((issue) => (
                                <Text key={`${issue.path}-${issue.message}`} size="xs">
                                    {issue.path} {issue.message}
                                </Text>
                            ))}
                        </Stack>
                    </Alert>
                )}

                <Divider />
                <ShortcutGuide />
            </Stack>
        </ScrollArea>
    )
}

function Stat({ label, value, warn = false }: { label: string; value: string; warn?: boolean }) {
    return (
        <Group justify="space-between">
            <Text size="sm" c="dimmed">
                {label}
            </Text>
            <Text size="sm" fw={500} c={warn ? "red" : undefined}>
                {value}
            </Text>
        </Group>
    )
}

function BulkInspector({
    plan,
    indices,
    apply,
}: {
    plan: TopologyPlan
    indices: number[]
    apply: (change: (plan: TopologyPlan) => TopologyPlan) => void
}) {
    const names = indices
        .map((index) => plan.topology.clusters[index])
        .filter((cluster) => cluster !== undefined)
        .map((cluster) => clusterName(cluster))

    return (
        <ScrollArea h="100%" p="md">
            <Stack gap="sm">
                <Text fw={600}>{indices.length} clusters selected</Text>
                <Text size="xs" c="dimmed" lineClamp={2}>
                    {names.join(", ")}
                </Text>
                <Divider label="Applies to all" />
                <NumberInput
                    label="Copies"
                    size="xs"
                    min={1}
                    placeholder="Set for every selected cluster"
                    onChange={(value) =>
                        typeof value === "number" &&
                        value >= 1 &&
                        apply((current) => updateClusters(current, indices, { count: value }))
                    }
                />
                <Divider />
                <ShortcutGuide />
            </Stack>
        </ScrollArea>
    )
}
