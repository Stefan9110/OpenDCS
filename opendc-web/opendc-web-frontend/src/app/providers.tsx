"use client"

import { theme } from "@/theme/theme"
import { MantineProvider } from "@mantine/core"
import { ModalsProvider } from "@mantine/modals"
import { Notifications } from "@mantine/notifications"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { type ReactNode, useState } from "react"

export function Providers({ children }: { children: ReactNode }) {
    const [queryClient] = useState(
        () => new QueryClient({ defaultOptions: { queries: { refetchOnWindowFocus: false } } }),
    )
    return (
        <QueryClientProvider client={queryClient}>
            <MantineProvider theme={theme} defaultColorScheme="auto">
                <ModalsProvider>
                    <Notifications />
                    {children}
                </ModalsProvider>
            </MantineProvider>
        </QueryClientProvider>
    )
}
