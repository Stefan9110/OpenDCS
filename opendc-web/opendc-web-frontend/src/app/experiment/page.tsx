"use client"

import { ExperimentView } from "@/components/experiment/ExperimentView"
import { AppShell } from "@/components/layout/AppShell"
import { Center, Loader } from "@mantine/core"
import { Suspense } from "react"

export default function ExperimentPage() {
    return (
        <AppShell>
            <Suspense
                fallback={
                    <Center py="xl">
                        <Loader size="sm" />
                    </Center>
                }
            >
                <ExperimentView />
            </Suspense>
        </AppShell>
    )
}
