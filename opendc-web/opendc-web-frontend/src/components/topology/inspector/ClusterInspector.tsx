"use client"

import { ClusterPowerFields } from "@/components/topology/inspector/ClusterPowerFields"
import { FieldLabel } from "@/components/topology/inspector/FieldLabel"
import { HostGroupList } from "@/components/topology/inspector/HostGroupList"
import { HostInspector } from "@/components/topology/inspector/HostInspector"
import type { ValidationIssue } from "@/lib/api/types"
import { clusterCapacity } from "@/lib/topology/capacity"
import type { TopologyPlan } from "@/lib/topology/edits"
import { addHost, duplicateHost, removeHost, updateCluster, updateHost } from "@/lib/topology/edits"
import { type ClusterSpec, type HostSpec, clusterCount, clusterName } from "@/lib/topology/spec"
import { issuesUnder } from "@/lib/topology/validation"
import { Alert, Badge, Box, Group, NumberInput, ScrollArea, Stack, Tabs, Text, TextInput } from "@mantine/core"
import { IconAlertTriangle } from "@tabler/icons-react"

export function ClusterInspector({
    cluster,
    index,
    selectedHost,
    issues,
    onSelectHost,
    apply,
}: {
    cluster: ClusterSpec
    index: number
    selectedHost: number
    issues: ValidationIssue[]
    onSelectHost: (cluster: number, host: number) => void
    apply: (change: (plan: TopologyPlan) => TopologyPlan) => void
}) {
    const capacity = clusterCapacity(cluster)
    const clusterIssues = issuesUnder(issues, `clusters[${index}]`)
    const host = cluster.hosts[selectedHost]
    const patch = (change: Partial<ClusterSpec>) => apply((current) => updateCluster(current, index, change))

    return (
        <Stack h="100%" gap={0}>
            <Box px="md" pt="md" pb="xs">
                <Group justify="space-between" wrap="nowrap">
                    <Text fw={600} lineClamp={1}>
                        {clusterName(cluster)}
                    </Text>
                    <Badge variant="light" color="gray" size="sm">
                        {capacity.hosts} hosts
                    </Badge>
                </Group>
            </Box>

            <Tabs defaultValue="cluster" style={{ display: "flex", flexDirection: "column", minHeight: 0, flex: 1 }}>
                <Tabs.List px="sm">
                    <Tabs.Tab value="cluster">Cluster</Tabs.Tab>
                    <Tabs.Tab value="hosts">Hosts ({cluster.hosts.length})</Tabs.Tab>
                </Tabs.List>

                <ScrollArea style={{ flex: 1, minHeight: 0 }}>
                    {clusterIssues.length > 0 && (
                        <Alert color="red" icon={<IconAlertTriangle size={16} />} m="sm" p="xs" radius="sm">
                            <Stack gap={2}>
                                {clusterIssues.map((issue) => (
                                    <Text key={`${issue.path}-${issue.message}`} size="xs">
                                        {issue.path.replace(`clusters[${index}].`, "")} {issue.message}
                                    </Text>
                                ))}
                            </Stack>
                        </Alert>
                    )}

                    <Tabs.Panel value="cluster" p="md">
                        <Stack gap="sm">
                            <TextInput
                                label="Name"
                                size="xs"
                                value={clusterName(cluster)}
                                onChange={(event) => patch({ name: event.currentTarget.value })}
                            />
                            <NumberInput
                                label={
                                    <FieldLabel
                                        label="Copies"
                                        help="Simulates this whole cluster this many times, hosts included."
                                    />
                                }
                                size="xs"
                                min={1}
                                value={clusterCount(cluster)}
                                onChange={(value) => patch({ count: typeof value === "number" ? value : 1 })}
                            />
                            <ClusterPowerFields cluster={cluster} onChange={patch} />
                        </Stack>
                    </Tabs.Panel>

                    <Tabs.Panel value="hosts" p="md">
                        <Stack gap="sm">
                            <HostGroupList
                                cluster={cluster}
                                selectedHost={selectedHost}
                                onSelectHost={(chosen) => onSelectHost(index, chosen)}
                                onAddHost={(added: HostSpec) => apply((current) => addHost(current, index, added))}
                                onRemoveHost={(chosen) => apply((current) => removeHost(current, index, chosen))}
                                onDuplicateHost={(chosen) => apply((current) => duplicateHost(current, index, chosen))}
                                onCountChange={(chosen, count) =>
                                    apply((current) => updateHost(current, index, chosen, { count }))
                                }
                            />
                            {host && (
                                <HostInspector
                                    host={host}
                                    onChange={(next) =>
                                        apply((current) => updateHost(current, index, selectedHost, next))
                                    }
                                />
                            )}
                        </Stack>
                    </Tabs.Panel>
                </ScrollArea>
            </Tabs>
        </Stack>
    )
}
