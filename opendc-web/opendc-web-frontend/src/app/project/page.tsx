"use client"

import { AppShell } from "@/components/layout/AppShell"
import { ProjectView } from "@/components/project/ProjectView"
import { Center, Loader } from "@mantine/core"
import { Suspense } from "react"

export default function ProjectPage() {
    return (
        <AppShell>
            <Suspense
                fallback={
                    <Center py="xl">
                        <Loader size="sm" />
                    </Center>
                }
            >
                <ProjectView />
            </Suspense>
        </AppShell>
    )
}
