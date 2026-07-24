"use client"

import { ProjectSearch } from "@/components/projects/ProjectSearch"
import { notifyComingSoon } from "@/lib/notify"
import type { ProjectFilterValue } from "@/lib/projects"
import { Button, Divider, Group } from "@mantine/core"
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
    return (
        <Group justify="space-between">
            <Group gap="md">
                <ProjectFilter value={filter} onChange={onFilterChange} disabled={searchActive} />
                <ProjectSearch />
            </Group>
            <Button leftSection={<IconPlus size={18} />} onClick={() => notifyComingSoon("Creating a project")}>
                Create project
            </Button>
        </Group>
    )
}
