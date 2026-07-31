"use client"

import { FieldLabel } from "@/components/topology/inspector/FieldLabel"
import type { CatalogEntry } from "@/lib/api/types"
import { Select, Text, Tooltip } from "@mantine/core"
import type { UseQueryResult } from "@tanstack/react-query"

/**
 * A picker over whatever the caller fetched. The options are passed in rather than named here
 * because they no longer all come from one place: option lists are reflected from the simulator,
 * while traces are a library that belongs to somebody and is scoped to a kind.
 */
export function CatalogSelect({
    label,
    entries,
    value,
    onChange,
    clearable = false,
    placeholder,
}: {
    label: string
    entries: UseQueryResult<CatalogEntry[]>
    value: string | null
    onChange: (value: string | null) => void
    clearable?: boolean
    placeholder?: string
}) {
    const known = entries.data ?? []
    const descriptions = new Map(known.map((entry) => [entry.id, entry.description]))
    const options = known.map((entry) => entry.id)
    const selected = value === null ? undefined : descriptions.get(value)

    return (
        <Select
            label={<FieldLabel label={label} help={selected} />}
            size="xs"
            data={options}
            value={value}
            clearable={clearable}
            placeholder={placeholder}
            allowDeselect={clearable}
            onChange={onChange}
            renderOption={({ option }) => {
                const description = descriptions.get(option.value)
                if (!description) return <Text size="xs">{option.label}</Text>
                return (
                    <Tooltip label={description} position="right" withArrow multiline w={260} openDelay={200}>
                        <Text size="xs" w="100%">
                            {option.label}
                        </Text>
                    </Tooltip>
                )
            }}
        />
    )
}
