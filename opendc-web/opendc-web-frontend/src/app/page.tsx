"use client"

import { AppShell } from "@/components/layout/AppShell"
import { ProjectGrid } from "@/components/projects/ProjectGrid"
import { ProjectsToolbar } from "@/components/projects/ProjectsToolbar"
import { useAuth } from "@/lib/auth/auth"
import { type ProjectFilterValue, filterProjects, isSearching, sampleProjects, useProjectSearch } from "@/lib/projects"
import { Container, Stack, Text, Title } from "@mantine/core"
import { useState } from "react"

function useWelcomeMessage() {
    const { status, userName } = useAuth()
    return status === "authenticated" ? `Welcome, ${userName}!` : "Welcome!"
}

export default function HomePage() {
    const [filter, setFilter] = useState<ProjectFilterValue>("all")
    const query = useProjectSearch((state) => state.query)
    const projects = filterProjects(sampleProjects, filter, query)
    return (
        <AppShell>
            <Container size="lg" py="lg">
                <Stack gap="lg">
                    <Stack gap={4} py={25}>
                        <Title order={1}>{useWelcomeMessage()}</Title>
                        <Text c="dimmed">Find all your personal and shared projects.</Text>
                    </Stack>
                    <ProjectsToolbar filter={filter} onFilterChange={setFilter} searchActive={isSearching(query)} />
                    <ProjectGrid projects={projects} />
                </Stack>
            </Container>
        </AppShell>
    )
}
