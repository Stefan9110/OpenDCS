"use client"

import { Group, Text, Tooltip } from "@mantine/core"
import { IconInfoCircle } from "@tabler/icons-react"
import type { ReactNode } from "react"

export function FieldLabel({ label, help }: { label: string; help?: string }): ReactNode {
    if (!help) return label
    return (
        <Group gap={4} wrap="nowrap">
            <Text size="xs" fw={500}>
                {label}
            </Text>
            <Tooltip label={help} multiline w={260} withArrow position="top-start" openDelay={150}>
                <IconInfoCircle size={13} style={{ opacity: 0.6, flexShrink: 0 }} />
            </Tooltip>
        </Group>
    )
}

export function UnitAdornment({ unit }: { unit: string }) {
    return (
        <Text size="xs" c="dimmed" pr={8}>
            {unit}
        </Text>
    )
}
