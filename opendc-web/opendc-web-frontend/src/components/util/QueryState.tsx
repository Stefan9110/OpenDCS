"use client"

import { problemOf } from "@/lib/api/client"
import { Anchor, Group, Text } from "@mantine/core"
import type { UseQueryResult } from "@tanstack/react-query"
import type { ReactNode } from "react"

/**
 * Renders the loading and failure states of one query so callers only handle loaded data. Wrap the
 * part of the page that needs the data, not the controls around it: a failed list must not take a
 * section's create button down with it.
 *
 * While the data is on its way the caller's [ghost] stands in for it. It is required, and it comes
 * from the caller because only the caller knows the shape being waited on: an outline that keeps the
 * page's height and columns means nothing jumps when the data lands, and the reader can already see
 * what kind of thing is arriving. Spinners are for actions somebody just triggered, where the wait
 * itself is the whole message.
 *
 * Data already on screen survives a failed refetch. A poll that fails once, or a server that
 * restarts under a dev session, must not blank out what the reader was looking at; it says so above
 * the content and keeps showing it. Only a query that has never succeeded has nothing to show, and
 * then the failure is an inline hint rather than a panel. A page that cannot render at all belongs
 * in a MessagePage instead.
 */
export function QueryState<T>({
    query,
    ghost,
    children,
}: {
    query: UseQueryResult<T>
    ghost: ReactNode
    children: (data: T) => ReactNode
}) {
    if (query.data !== undefined) {
        return (
            <>
                {query.isError && <Failure query={query} label="Could not refresh" />}
                {children(query.data)}
            </>
        )
    }
    if (query.isPending) return ghost

    return <Failure query={query} label={problemOf(query.error).title} />
}

function Failure({ query, label }: { query: UseQueryResult<unknown>; label: string }) {
    return (
        <Group gap="xs" py="xs">
            <Text size="sm" c="red.6">
                {label}
            </Text>
            <Anchor size="sm" component="button" type="button" onClick={() => query.refetch()}>
                {query.isFetching ? "Retrying" : "Try again"}
            </Anchor>
        </Group>
    )
}
