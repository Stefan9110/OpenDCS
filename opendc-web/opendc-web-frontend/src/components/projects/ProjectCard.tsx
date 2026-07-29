"use client"

import { formatUpdatedAt } from "@/components/format"
import { notifyComingSoon, notifyProblem } from "@/components/util/feedback"
import { useDeleteProject } from "@/lib/api/projects"
import type { Project, ProjectRole } from "@/lib/api/types"
import { logoGradient } from "@/theme/theme"
import { ActionIcon, Badge, Card, Group, Menu, Text } from "@mantine/core"
import { modals } from "@mantine/modals"
import {
    type Icon,
    IconDots,
    IconEye,
    IconFolder,
    IconHome,
    IconPencil,
    IconShare,
    IconTrash,
} from "@tabler/icons-react"
import Link from "next/link"
import classes from "./ProjectCard.module.css"

const roleBadges: Record<ProjectRole, Icon> = {
    owner: IconHome,
    editor: IconPencil,
    viewer: IconEye,
}

export function ProjectCard({ project }: Readonly<{ project: Project }>) {
    const RoleIcon = roleBadges[project.role]
    return (
        <Card withBorder radius="md" padding="md" className={classes.card} __vars={{ "--logo-gradient": logoGradient }}>
            <Group justify="space-between" mb="xs">
                <IconFolder size={22} />
                <Group gap="xs">
                    <Badge variant="light" color="opendc" leftSection={<RoleIcon size={14} />}>
                        {project.role}
                    </Badge>
                    <ProjectActions project={project} />
                </Group>
            </Group>
            <Link href={`/project?id=${project.id}`} style={{ color: "inherit", textDecoration: "none" }}>
                <Text fw={600} lineClamp={1}>
                    {project.name}
                </Text>
                <Text size="sm" c="dimmed" mt={4}>
                    Last modified {formatUpdatedAt(project.updatedAt)}
                </Text>
            </Link>
        </Card>
    )
}

function ProjectActions({ project }: Readonly<{ project: Project }>) {
    const remove = useDeleteProject()

    const confirmDelete = () =>
        modals.openConfirmModal({
            title: `Delete ${project.name}?`,
            children: <Text size="sm">Its topologies and experiments go with it. This cannot be undone.</Text>,
            labels: { confirm: "Delete project", cancel: "Keep it" },
            confirmProps: { color: "red" },
            onConfirm: () => remove.mutate(project.id, { onError: notifyProblem }),
        })

    return (
        <Menu position="bottom-end">
            <Menu.Target>
                <ActionIcon variant="subtle" color="gray" aria-label="Project actions">
                    <IconDots size={18} />
                </ActionIcon>
            </Menu.Target>
            <Menu.Dropdown>
                {project.role === "owner" && (
                    <Menu.Item
                        leftSection={<IconShare size={16} />}
                        onClick={() => notifyComingSoon("Sharing a project")}
                    >
                        Share
                    </Menu.Item>
                )}
                <Menu.Item
                    color="red"
                    leftSection={<IconTrash size={16} />}
                    disabled={project.role !== "owner"}
                    onClick={confirmDelete}
                >
                    Delete
                </Menu.Item>
            </Menu.Dropdown>
        </Menu>
    )
}
