"use client"

import type { Project } from "@/lib/api/types"
import { notifyComingSoon } from "@/lib/notify"
import { Card, Center, SimpleGrid, Stack, Text, UnstyledButton } from "@mantine/core"
import { IconPlus } from "@tabler/icons-react"
import { ProjectCard } from "./ProjectCard"

export function ProjectGrid({ projects }: { projects: Project[] }) {
    return (
        <Stack gap="md">
            {projects.length === 0 && (
                <Text c="dimmed" size="sm">
                    No projects match the current filters or you don't have any projects.
                </Text>
            )}
            <SimpleGrid cols={{ base: 1, sm: 2, md: 3 }} spacing="md">
                {projects.map((project) => (
                    <ProjectCard key={project.id} project={project} />
                ))}
                <CreateProjectCard />
            </SimpleGrid>
        </Stack>
    )
}

function CreateProjectCard() {
    return (
        <UnstyledButton onClick={() => notifyComingSoon("Creating a project")}>
            <Card withBorder radius="md" padding="md" h="100%" style={{ borderStyle: "dashed" }}>
                <Center mih={96}>
                    <Stack align="center" gap={4}>
                        <IconPlus size={22} />
                        <Text size="sm" c="dimmed">
                            Create project
                        </Text>
                    </Stack>
                </Center>
            </Card>
        </UnstyledButton>
    )
}
