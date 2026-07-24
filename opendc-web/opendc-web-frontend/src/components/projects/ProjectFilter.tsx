"use client"

import type { ProjectFilterValue } from "@/lib/projects"
import { SegmentedControl, useComputedColorScheme } from "@mantine/core"

const options = [
    { label: "All projects", value: "all" },
    { label: "My projects", value: "own" },
    { label: "Shared with me", value: "shared" },
]

export function ProjectFilter({
    value,
    onChange,
    disabled,
}: {
    value: ProjectFilterValue
    onChange: (value: ProjectFilterValue) => void
    disabled: boolean
}) {
    const scheme = useComputedColorScheme()
    return (
        <SegmentedControl
            color={scheme === "dark" ? "gray.7" : "gray.4"}
            autoContrast
            value={value}
            onChange={(next) => onChange(next as ProjectFilterValue)}
            data={options}
            disabled={disabled}
            p={0}
        />
    )
}
