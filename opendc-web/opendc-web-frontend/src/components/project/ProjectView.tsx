"use client"

import { ExperimentSection } from "@/components/project/ExperimentSection"
import { TopologySection } from "@/components/project/TopologySection"
import { EntityBreadcrumbs } from "@/components/util/EntityBreadcrumbs"
import { openNamePrompt } from "@/components/util/NamePrompt"
import { QueryState } from "@/components/util/QueryState"
import { notifyProblem } from "@/components/util/feedback"
import { numericParam } from "@/components/util/params"
import { useExperiments } from "@/lib/api/experiments"
import { useProject, useRenameProject } from "@/lib/api/projects"
import { useTopologies } from "@/lib/api/topologies"
import { ActionIcon, Alert, Badge, Container, Group, Stack } from "@mantine/core"
import { IconAlertTriangle, IconPencil } from "@tabler/icons-react"
import { useSearchParams } from "next/navigation"

export function ProjectView() {
    const params = useSearchParams()
    const id = numericParam(params, "id")

    if (id.status !== "ok") {
        return (
            <Container size="lg" py="xl">
                <Alert color="red" icon={<IconAlertTriangle size={18} />} title="No project selected">
                    {id.status === "missing"
                        ? "This link is missing a project id."
                        : `"${id.raw}" is not a valid project id.`}
                </Alert>
            </Container>
        )
    }

    return <LoadedProject projectId={id.value} />
}

function LoadedProject({ projectId }: { projectId: number }) {
    const project = useProject(projectId)
    const templates = useTopologies(projectId)
    const experiments = useExperiments(projectId)
    const rename = useRenameProject(projectId)

    return (
        <Container size="lg" py="md">
            <Stack gap="md">
                <QueryState query={project} loadingLabel="Loading project">
                    {(loaded) => (
                        <EntityBreadcrumbs
                            crumbs={[
                                { kind: "projects", label: "Projects", href: "/" },
                                { kind: "project", label: loaded.name },
                            ]}
                            trailing={
                                <Group gap={6} wrap="nowrap">
                                    <Badge variant="light" color="opendc" tt="capitalize" size="sm">
                                        {loaded.role}
                                    </Badge>
                                    {loaded.role === "owner" && (
                                        <ActionIcon
                                            variant="subtle"
                                            color="gray"
                                            size="sm"
                                            aria-label="Rename project"
                                            onClick={() =>
                                                openNamePrompt({
                                                    title: "Rename project",
                                                    label: "Project name",
                                                    initial: loaded.name,
                                                    confirmLabel: "Rename",
                                                    onSubmit: (name) => rename.mutate(name, { onError: notifyProblem }),
                                                })
                                            }
                                        >
                                            <IconPencil size={14} />
                                        </ActionIcon>
                                    )}
                                </Group>
                            }
                        />
                    )}
                </QueryState>

                <QueryState query={templates} loadingLabel="Loading topologies">
                    {(loaded) => <TopologySection projectId={projectId} templates={loaded} />}
                </QueryState>

                <QueryState query={experiments} loadingLabel="Loading experiments">
                    {(loaded) => (
                        <ExperimentSection
                            projectId={projectId}
                            experiments={loaded}
                            templates={templates.data ?? []}
                        />
                    )}
                </QueryState>
            </Stack>
        </Container>
    )
}
