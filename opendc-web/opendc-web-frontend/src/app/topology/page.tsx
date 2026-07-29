"use client"

import { AppShell } from "@/components/layout/AppShell"
import { TopologyView } from "@/components/topology/TopologyView"
import { Center, Loader } from "@mantine/core"
import { Suspense } from "react"

export default function TopologyPage() {
    return (
        <AppShell>
            <Suspense
                fallback={
                    <Center py="xl">
                        <Loader size="sm" />
                    </Center>
                }
            >
                <TopologyView />
            </Suspense>
        </AppShell>
    )
}
