"use client"

import { BackTo, MessagePage } from "@/components/layout/MessagePage"
import { ExperimentSection } from "@/components/project/ExperimentSection"
import { TopologySection } from "@/components/project/TopologySection"
import { EntityBreadcrumbs } from "@/components/util/EntityBreadcrumbs"
import { LineGhost, TableGhost } from "@/components/util/Ghost"
import { openNamePrompt } from "@/components/util/NamePrompt"
import { QueryState } from "@/components/util/QueryState"
import { notifyProblem } from "@/components/util/feedback"
import { idParam } from "@/components/util/params"
import { problemOf } from "@/lib/api/client"
import { useExperiments } from "@/lib/api/experiments"
import { useProject, useRenameProject } from "@/lib/api/projects"
import { useTopologies } from "@/lib/api/topologies"
import type { Id } from "@/lib/api/types"
import { ActionIcon, Badge, Container, Group, Stack } from "@mantine/core"
import { IconPencil } from "@tabler/icons-react"
import { useSearchParams } from "next/navigation"

export function ProjectView() {
    const params = useSearchParams()
    const id = idParam(params, "id")

    if (id.status !== "ok") {
        return (
            <MessagePage title="No project here" message="This link is missing a project id.">
                <BackTo href="/" label="Back to projects" />
            </MessagePage>
        )
    }

    return <LoadedProject projectId={id.value} />
}

function LoadedProject({ projectId }: { projectId: Id }) {
    const project = useProject(projectId)
    const templates = useTopologies(projectId)
    const experiments = useExperiments(projectId)
    const rename = useRenameProject(projectId)

    // Without the project there is no page to annotate, so this is a dead end rather than an error
    // sitting where its contents would be. Only a first load counts: a failed refetch keeps showing
    // what is already on screen.
    if (project.isError && project.data === undefined) {
        return (
            <MessagePage title="Project not found" message={problemOf(project.error).title}>
                <BackTo href="/" label="Back to projects" />
            </MessagePage>
        )
    }

    return (
        <Container size="lg" py="md">
            <Stack gap="md">
                <QueryState query={project} ghost={<LineGhost width={260} />}>
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

                <QueryState query={templates} ghost={<TableGhost columns={8} />}>
                    {(loaded) => <TopologySection projectId={projectId} templates={loaded} />}
                </QueryState>

                <QueryState query={experiments} ghost={<TableGhost columns={6} />}>
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
