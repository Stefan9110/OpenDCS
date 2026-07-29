"use client"

import { TopologyBuilder } from "@/components/topology/TopologyBuilder"
import { QueryState } from "@/components/util/QueryState"
import { numericParam } from "@/components/util/params"
import { useLayout, useTopology } from "@/lib/api/topologies"
import type { TopologyTemplate } from "@/lib/api/types"
import type { FloorLayout } from "@/lib/topology/layout"
import { Alert, Box, Container } from "@mantine/core"
import { IconAlertTriangle } from "@tabler/icons-react"
import { useSearchParams } from "next/navigation"
import { useRef } from "react"

export function TopologyView() {
    const params = useSearchParams()
    const projectId = numericParam(params, "project")
    const topologyId = numericParam(params, "topology")

    if (projectId.status !== "ok" || topologyId.status !== "ok") {
        return (
            <Container size="lg" py="xl">
                <Alert color="red" icon={<IconAlertTriangle size={18} />} title="No topology selected">
                    This link needs both a project and a topology id.
                </Alert>
            </Container>
        )
    }

    return <LoadedTopology projectId={projectId.value} templateId={topologyId.value} />
}

function LoadedTopology({ projectId, templateId }: { projectId: number; templateId: number }) {
    const template = useTopology(projectId, templateId)

    return (
        <Box px="md" py="sm">
            <QueryState query={template} loadingLabel="Loading topology">
                {(loaded) => <LoadedLayout key={loaded.id} projectId={projectId} template={loaded} />}
            </QueryState>
        </Box>
    )
}

// The builder owns the document once it is open, so it keeps the layout it started from. Re-gating on
// the query would tear the editor down every time a save moved the layout to a new content hash.
function LoadedLayout({ projectId, template }: { projectId: number; template: TopologyTemplate }) {
    const layout = useLayout(projectId, template.topologyHash)
    const opened = useRef<FloorLayout | undefined>(undefined)
    if (!opened.current && layout.data) opened.current = layout.data.layout
    const started = opened.current

    if (!started) {
        return (
            <QueryState query={layout} loadingLabel="Loading floor plan">
                {() => undefined}
            </QueryState>
        )
    }

    return <TopologyBuilder projectId={projectId} template={template} layout={started} />
}
