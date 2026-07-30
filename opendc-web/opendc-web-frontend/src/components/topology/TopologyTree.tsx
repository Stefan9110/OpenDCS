"use client"

import { type Selection, isClusterSelected } from "@/components/topology/selection"
import type { DocumentIssue } from "@/lib/api/types"
import type { TopologyPlan } from "@/lib/topology/edits"
import { clusterCount, clusterName, hostCount, hostName } from "@/lib/topology/spec"
import { issuesUnder } from "@/lib/topology/validation"
import { Group, ScrollArea, Text, Tree, type TreeNodeData, useTree } from "@mantine/core"
import { IconAlertTriangle, IconChevronDown, IconChevronRight, IconServer, IconStack2 } from "@tabler/icons-react"
import { useMemo } from "react"

export function TopologyTree({
    plan,
    selection,
    issues,
    onSelectCluster,
    onSelectHost,
}: {
    plan: TopologyPlan
    selection: Selection
    issues: DocumentIssue[]
    onSelectCluster: (index: number) => void
    onSelectHost: (cluster: number, host: number) => void
}) {
    const tree = useTree()

    const data: TreeNodeData[] = useMemo(
        () =>
            plan.topology.clusters.map((cluster, index) => ({
                value: `cluster:${index}`,
                label: `${clusterName(cluster)}${clusterCount(cluster) > 1 ? ` x${clusterCount(cluster)}` : ""}`,
                children: cluster.hosts.map((host, hostIndex) => ({
                    value: `host:${index}:${hostIndex}`,
                    label: `${hostName(host)} x${hostCount(host)}`,
                })),
            })),
        [plan.topology.clusters],
    )

    if (data.length === 0) {
        return (
            <Text size="sm" c="dimmed" p="md">
                No clusters yet. Click an empty tile on the floor to add one.
            </Text>
        )
    }

    return (
        <ScrollArea h="100%" p="xs">
            <Tree
                data={data}
                tree={tree}
                levelOffset={16}
                renderNode={({ node, expanded, hasChildren, elementProps }) => {
                    const parts = node.value.split(":")
                    const clusterIndex = Number(parts[1])
                    const isCluster = parts[0] === "cluster"
                    const broken =
                        issuesUnder(
                            issues,
                            isCluster
                                ? `clusters[${clusterIndex}]`
                                : `clusters[${clusterIndex}].hosts[${Number(parts[2])}]`,
                        ).length > 0
                    const active = isCluster
                        ? isClusterSelected(selection, clusterIndex) && selection.kind === "clusters"
                        : selection.kind === "host" &&
                          selection.cluster === clusterIndex &&
                          selection.host === Number(parts[2])

                    return (
                        <Group
                            {...elementProps}
                            gap={6}
                            wrap="nowrap"
                            py={3}
                            onClick={(event) => {
                                elementProps.onClick(event)
                                if (isCluster) onSelectCluster(clusterIndex)
                                else onSelectHost(clusterIndex, Number(parts[2]))
                            }}
                        >
                            {hasChildren ? (
                                expanded ? (
                                    <IconChevronDown size={14} />
                                ) : (
                                    <IconChevronRight size={14} />
                                )
                            ) : (
                                <IconServer size={14} />
                            )}
                            {isCluster && <IconStack2 size={14} />}
                            <Text size="sm" fw={active ? 600 : 400} lineClamp={1}>
                                {node.label}
                            </Text>
                            {broken && <IconAlertTriangle size={13} color="var(--mantine-color-red-6)" />}
                        </Group>
                    )
                }}
            />
        </ScrollArea>
    )
}
