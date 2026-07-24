"use client"

import { useProjectSearch } from "@/lib/projects"
import { CloseButton, TextInput } from "@mantine/core"
import { IconSearch } from "@tabler/icons-react"

export function ProjectSearch() {
    const query = useProjectSearch((state) => state.query)
    const setQuery = useProjectSearch((state) => state.setQuery)
    return (
        <TextInput
            value={query}
            onChange={(event) => setQuery(event.currentTarget.value)}
            placeholder="Search projects"
            aria-label="Search projects"
            size="xs"
            w={240}
            leftSection={<IconSearch size={16} />}
            rightSection={
                query === "" ? null : <CloseButton size="sm" aria-label="Clear search" onClick={() => setQuery("")} />
            }
            rightSectionPointerEvents="all"
        />
    )
}
