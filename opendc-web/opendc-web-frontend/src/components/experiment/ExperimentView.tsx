"use client"

import { ExperimentActions } from "@/components/experiment/ExperimentActions"
import { ExperimentOverview } from "@/components/experiment/ExperimentOverview"
import { ExperimentStateBadge } from "@/components/experiment/ExperimentStateBadge"
import { ResultsPanel } from "@/components/experiment/results/ResultsPanel"
import { EntityBreadcrumbs } from "@/components/util/EntityBreadcrumbs"
import { QueryState } from "@/components/util/QueryState"
import { numericParam } from "@/components/util/params"
import { useExperiment } from "@/lib/api/experiments"
import { useProject } from "@/lib/api/projects"
import type { Experiment } from "@/lib/api/types"
import { Alert, Container, Group, Stack, Tabs } from "@mantine/core"
import { IconAlertTriangle, IconChartLine, IconClipboardList } from "@tabler/icons-react"
import { useRouter, useSearchParams } from "next/navigation"

const TABS = ["overview", "results"] as const

type TabName = (typeof TABS)[number]

export function ExperimentView() {
    const params = useSearchParams()
    const projectId = numericParam(params, "project")
    const experimentId = numericParam(params, "experiment")

    if (projectId.status !== "ok" || experimentId.status !== "ok") {
        return (
            <Container size="xl" py="xl">
                <Alert color="red" icon={<IconAlertTriangle size={18} />} title="No experiment selected">
                    This link needs both a project and an experiment id.
                </Alert>
            </Container>
        )
    }

    return (
        <LoadedExperiment
            projectId={projectId.value}
            experimentId={experimentId.value}
            tab={tabOf(params.get("tab"))}
        />
    )
}

function tabOf(raw: string | null): TabName {
    return TABS.find((name) => name === raw) ?? "overview"
}

function LoadedExperiment({
    projectId,
    experimentId,
    tab,
}: {
    projectId: number
    experimentId: number
    tab: TabName
}) {
    const experiment = useExperiment(projectId, experimentId)
    const router = useRouter()

    const openTab = (name: string | null) =>
        router.replace(`/experiment?project=${projectId}&experiment=${experimentId}&tab=${tabOf(name)}`, {
            scroll: false,
        })

    return (
        <Container size="xl" py="md">
            <QueryState query={experiment} loadingLabel="Loading experiment">
                {(loaded) => (
                    <Stack gap="md">
                        <Group justify="space-between" wrap="wrap" gap="xs">
                            <ExperimentBreadcrumbs projectId={projectId} experiment={loaded} />
                            <ExperimentActions projectId={projectId} experiment={loaded} />
                        </Group>

                        <Tabs value={tab} onChange={openTab} keepMounted={false}>
                            <Tabs.List mb="md">
                                <Tabs.Tab value="overview" leftSection={<IconClipboardList size={16} />}>
                                    Overview
                                </Tabs.Tab>
                                <Tabs.Tab value="results" leftSection={<IconChartLine size={16} />}>
                                    Results
                                </Tabs.Tab>
                            </Tabs.List>

                            <Tabs.Panel value="overview">
                                <ExperimentOverview projectId={projectId} experiment={loaded} />
                            </Tabs.Panel>
                            <Tabs.Panel value="results">
                                <ResultsPanel projectId={projectId} experiment={loaded} />
                            </Tabs.Panel>
                        </Tabs>
                    </Stack>
                )}
            </QueryState>
        </Container>
    )
}

function ExperimentBreadcrumbs({ projectId, experiment }: { projectId: number; experiment: Experiment }) {
    const project = useProject(projectId)

    return (
        <EntityBreadcrumbs
            crumbs={[
                { kind: "projects", label: "Projects", href: "/" },
                { kind: "project", label: project.data?.name ?? "Project", href: `/project?id=${projectId}` },
                { kind: "experiment", label: experiment.name },
            ]}
            trailing={<ExperimentStateBadge state={experiment.state} />}
        />
    )
}
