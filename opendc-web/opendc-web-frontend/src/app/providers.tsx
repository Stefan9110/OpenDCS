"use client"

import { AuthProvider } from "@/components/layout/AuthProvider"
import { ApiError } from "@/lib/api/client"
import { theme } from "@/theme/theme"
import { MantineProvider } from "@mantine/core"
import { ModalsProvider } from "@mantine/modals"
import { Notifications } from "@mantine/notifications"
import { QueryCache, QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { type ReactNode, useState } from "react"

export function Providers({ children }: { children: ReactNode }) {
    const [queryClient] = useState(() => {
        // Any request refused for want of a sign-in means the session ended, so the profile that
        // decides what is shown is asked for again rather than trusted from before.
        const client: QueryClient = new QueryClient({
            defaultOptions: { queries: { refetchOnWindowFocus: false } },
            queryCache: new QueryCache({
                onError: (error, query) => {
                    if (error instanceof ApiError && error.problem.status === 401 && query.queryKey[0] !== "me") {
                        void client.invalidateQueries({ queryKey: ["me"] })
                    }
                },
            }),
        })
        return client
    })
    return (
        <QueryClientProvider client={queryClient}>
            <MantineProvider theme={theme} defaultColorScheme="auto">
                <ModalsProvider>
                    <Notifications />
                    <AuthProvider>{children}</AuthProvider>
                </ModalsProvider>
            </MantineProvider>
        </QueryClientProvider>
    )
}
