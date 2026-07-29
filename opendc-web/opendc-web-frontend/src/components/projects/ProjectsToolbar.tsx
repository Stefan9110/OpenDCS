"use client"

import { ProjectSearch } from "@/components/projects/ProjectSearch"
import type { ProjectFilterValue } from "@/components/projects/projectList"
import { useCreateProjectPrompt } from "@/components/projects/useCreateProjectPrompt"
import { useCreateProject } from "@/lib/api/projects"
import { Button, Group } from "@mantine/core"
import { IconPlus } from "@tabler/icons-react"
import { ProjectFilter } from "./ProjectFilter"

export function ProjectsToolbar({
    filter,
    onFilterChange,
    searchActive,
}: {
    filter: ProjectFilterValue
    onFilterChange: (value: ProjectFilterValue) => void
    searchActive: boolean
}) {
    const create = useCreateProject()
    const promptCreate = useCreateProjectPrompt(create)

    return (
        <Group justify="space-between">
            <Group gap="md">
                <ProjectFilter value={filter} onChange={onFilterChange} disabled={searchActive} />
                <ProjectSearch />
            </Group>
            <Button leftSection={<IconPlus size={18} />} onClick={promptCreate} loading={create.isPending}>
                Create project
            </Button>
        </Group>
    )
}
