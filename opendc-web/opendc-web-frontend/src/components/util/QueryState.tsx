"use client"

import { problemOf } from "@/lib/api/client"
import { Alert, Center, Loader, Stack, Text } from "@mantine/core"
import { IconAlertTriangle } from "@tabler/icons-react"
import type { UseQueryResult } from "@tanstack/react-query"
import type { ReactNode } from "react"

export function QueryState<T>({
    query,
    loadingLabel,
    children,
}: {
    query: UseQueryResult<T>
    loadingLabel: string
    children: (data: T) => ReactNode
}) {
    if (query.isPending) {
        return (
            <Center py="xl">
                <Stack align="center" gap="xs">
                    <Loader size="sm" />
                    <Text size="sm" c="dimmed">
                        {loadingLabel}
                    </Text>
                </Stack>
            </Center>
        )
    }

    if (query.isError) {
        const problem = problemOf(query.error)
        return (
            <Alert color="red" icon={<IconAlertTriangle size={18} />} title={problem.title}>
                {problem.detail ?? "The request could not be completed."}
            </Alert>
        )
    }

    return <>{children(query.data)}</>
}
