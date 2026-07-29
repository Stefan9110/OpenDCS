"use client"

import { FieldLabel } from "@/components/topology/inspector/FieldLabel"
import { useCatalog } from "@/lib/api/catalogs"
import type { CatalogName } from "@/lib/api/types"
import { Select, Text, Tooltip } from "@mantine/core"

export type CatalogKind = CatalogName | "traces" | "power-sources"

export function CatalogSelect({
    label,
    catalog,
    value,
    onChange,
    clearable = false,
    placeholder,
    extraOptions = [],
}: {
    label: string
    catalog: CatalogKind
    value: string | null
    onChange: (value: string | null) => void
    clearable?: boolean
    placeholder?: string
    extraOptions?: string[]
}) {
    const entries = useCatalog(catalog)
    const known = entries.data ?? []
    const descriptions = new Map(known.map((entry) => [entry.id, entry.description]))
    const options = [...new Set([...known.map((entry) => entry.id), ...extraOptions])]
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
