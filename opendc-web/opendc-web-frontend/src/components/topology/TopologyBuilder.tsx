"use client"

import { BuilderPanes } from "@/components/topology/BuilderPanes"
import { BuilderToolbar } from "@/components/topology/BuilderToolbar"
import { useResizableWidth } from "@/components/topology/ResizeHandle"
import { TopologyTree } from "@/components/topology/TopologyTree"
import type { ZoomCommand } from "@/components/topology/canvas/FloorStage"
import { newCluster } from "@/components/topology/defaults"
import { TopologyInspector } from "@/components/topology/inspector/TopologyInspector"
import {
    WHOLE_TOPOLOGY,
    afterRemoval,
    extendToCluster,
    selectCluster,
    selectHost,
    selectedClusters,
} from "@/components/topology/selection"
import { useTopologyEditor } from "@/components/topology/useTopologyEditor"
import { EntityBreadcrumbs } from "@/components/util/EntityBreadcrumbs"
import { openNamePrompt } from "@/components/util/NamePrompt"
import { useProject } from "@/lib/api/projects"
import type { TopologyTemplate } from "@/lib/api/types"
import { addCluster, duplicateCluster, moveCluster, removeClusters } from "@/lib/topology/edits"
import type { FloorCell, FloorLayout } from "@/lib/topology/layout"
import { ActionIcon, Box, Center, Loader, Stack } from "@mantine/core"
import { useDisclosure, useHotkeys, useMediaQuery } from "@mantine/hooks"
import { IconPencil } from "@tabler/icons-react"
import dynamic from "next/dynamic"
import { useState } from "react"

const FloorStage = dynamic(() => import("@/components/topology/canvas/FloorStage").then((m) => m.FloorStage), {
    ssr: false,
    loading: () => (
        <Center h="100%">
            <Loader size="sm" />
        </Center>
    ),
})

export function TopologyBuilder({
    projectId,
    template,
    layout,
}: {
    projectId: number
    template: TopologyTemplate
    layout: FloorLayout
}) {
    const editor = useTopologyEditor(projectId, template, layout)
    const [zoom, setZoom] = useState<ZoomCommand>({ action: "fit", nonce: 0 })
    const inspector = useResizableWidth(360, 300, 720)
    const compact = useMediaQuery("(max-width: 75em)", false, { getInitialValueInEffect: true })
    const [treeOpened, tree] = useDisclosure(false)
    const [inspectorOpened, inspectorDrawer] = useDisclosure(false)
    const { plan, selection } = editor

    const createAt = (cell: FloorCell) => {
        const index = plan.topology.clusters.length
        editor.apply((current) => addCluster(current, newCluster(index + 1), cell))
        editor.select(selectCluster(index))
    }

    const duplicateSelected = () => {
        const chosen = selectedClusters(selection)
        const first = chosen[0]
        if (first === undefined) return
        editor.apply((current) => duplicateCluster(current, first))
        editor.select(selectCluster(first + 1))
    }

    const deleteSelected = () => {
        const chosen = selectedClusters(selection)
        if (chosen.length === 0) return
        editor.apply((current) => removeClusters(current, chosen))
        editor.select(afterRemoval(selection, chosen))
    }

    useHotkeys([
        ["mod+z", editor.undo],
        ["mod+shift+Z", editor.redo],
        ["mod+s", editor.saveNow],
        ["mod+d", duplicateSelected],
        ["Delete", deleteSelected],
        ["Backspace", deleteSelected],
        ["Escape", () => editor.select(WHOLE_TOPOLOGY)],
    ])

    return (
        <Stack gap="xs">
            <TopologyBreadcrumbs
                projectId={projectId}
                name={editor.name}
                onRename={() =>
                    openNamePrompt({
                        title: "Rename topology",
                        label: "Topology name",
                        initial: editor.name,
                        confirmLabel: "Rename",
                        onSubmit: editor.rename,
                    })
                }
            />
            <BuilderPanes
                compact={compact}
                resizer={inspector}
                drawers={{
                    treeOpened,
                    inspectorOpened,
                    closeTree: tree.close,
                    closeInspector: inspectorDrawer.close,
                }}
                tree={
                    <TopologyTree
                        plan={plan}
                        selection={selection}
                        issues={editor.issues}
                        onSelectCluster={(index) => editor.select(selectCluster(index))}
                        onSelectHost={(cluster, host) => editor.select(selectHost(cluster, host))}
                    />
                }
                canvas={
                    <>
                        <BuilderToolbar
                            saveState={editor.saveState}
                            canUndo={editor.canUndo}
                            canRedo={editor.canRedo}
                            onUndo={editor.undo}
                            onRedo={editor.redo}
                            onZoom={(action) => setZoom((current) => ({ action, nonce: current.nonce + 1 }))}
                            onExport={() => downloadTopology(editor.name, plan.topology)}
                            {...(compact ? { onOpenTree: tree.open, onOpenInspector: inspectorDrawer.open } : {})}
                        />
                        <Box flex={1} mih={0}>
                            <FloorStage
                                plan={plan}
                                selection={selection}
                                issues={editor.issues}
                                zoom={zoom}
                                onCreate={createAt}
                                onSelect={(index, additive) =>
                                    editor.select(additive ? extendToCluster(selection, index) : selectCluster(index))
                                }
                                onClearSelection={() => editor.select(WHOLE_TOPOLOGY)}
                                onMove={(index, cell) => editor.apply((current) => moveCluster(current, index, cell))}
                                onOpen={(index) => {
                                    editor.select(selectHost(index, 0))
                                    if (compact) inspectorDrawer.open()
                                }}
                            />
                        </Box>
                    </>
                }
                inspector={
                    <TopologyInspector
                        plan={plan}
                        selection={selection}
                        issues={editor.issues}
                        onSelectHost={(cluster, host) => editor.select(selectHost(cluster, host))}
                        apply={editor.apply}
                    />
                }
            />
        </Stack>
    )
}

function TopologyBreadcrumbs({
    projectId,
    name,
    onRename,
}: {
    projectId: number
    name: string
    onRename: () => void
}) {
    const project = useProject(projectId)

    return (
        <EntityBreadcrumbs
            crumbs={[
                { kind: "projects", label: "Projects", href: "/" },
                { kind: "project", label: project.data?.name ?? "Project", href: `/project?id=${projectId}` },
                { kind: "topology", label: name },
            ]}
            trailing={
                <ActionIcon variant="subtle" color="gray" size="sm" aria-label="Rename topology" onClick={onRename}>
                    <IconPencil size={14} />
                </ActionIcon>
            }
        />
    )
}

function downloadTopology(name: string, topology: unknown): void {
    const blob = new Blob([JSON.stringify(topology, null, 4)], { type: "application/json" })
    const url = URL.createObjectURL(blob)
    const link = document.createElement("a")
    link.href = url
    link.download = `${name.replaceAll(/\s+/g, "-").toLowerCase()}.json`
    link.click()
    URL.revokeObjectURL(url)
}
