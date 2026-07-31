"use client"

import type { UnitLabel } from "@/lib/units"
import { Group, Text, Tooltip } from "@mantine/core"
import { IconInfoCircle } from "@tabler/icons-react"
import type { ReactNode } from "react"

/**
 * A field's label, with the explanation behind an icon beside it.
 *
 * The text is left to inherit whatever the surrounding label is styled as, so a labelled field sits
 * at the same size as an unlabelled one next to it. Naming a size here would be right for the
 * inspector, where every input is extra small, and visibly wrong in a dialog where they are not.
 */
export function FieldLabel({ label, help }: { label: string; help?: string }): ReactNode {
    if (!help) return label
    return (
        <Group gap={4} wrap="nowrap" component="span" display="inline-flex">
            {label}
            <Tooltip label={help} multiline w={260} withArrow position="top-start" openDelay={150}>
                <IconInfoCircle size={13} style={{ opacity: 0.6, flexShrink: 0 }} />
            </Tooltip>
        </Group>
    )
}

export function UnitAdornment({ unit }: { unit: UnitLabel }) {
    return (
        <Text size="xs" c="dimmed" pr={8}>
            {unit}
        </Text>
    )
}
