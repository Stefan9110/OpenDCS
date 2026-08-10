"use client"

import { ColorSwatch, Group, Text, UnstyledButton } from "@mantine/core"

export interface LegendEntry {
    name: string
    color: string
}

/**
 * Names every line on the chart, and isolates one while it is pointed at.
 *
 * A sweep may put any number of runs on one chart, at which point telling them apart by colour alone
 * stops working however good the palette is. Pointing at a name is the way back out: everything else
 * leaves the chart for as long as the pointer rests there, so a single run can be read in place
 * without anything being clicked or any selection being lost.
 *
 * Focus isolates as well as hover, so the same reading is available from the keyboard.
 */
export function SeriesLegend({
    entries,
    isolated,
    onIsolate,
}: {
    entries: LegendEntry[]
    isolated: string | undefined
    onIsolate: (name: string | undefined) => void
}) {
    return (
        <Group gap="md" wrap="wrap" justify="center" onMouseLeave={() => onIsolate(undefined)}>
            {entries.map((entry) => (
                <UnstyledButton
                    key={entry.name}
                    onMouseEnter={() => onIsolate(entry.name)}
                    onFocus={() => onIsolate(entry.name)}
                    onBlur={() => onIsolate(undefined)}
                    aria-label={`Show only ${entry.name}`}
                >
                    <Group gap={6} wrap="nowrap">
                        <ColorSwatch color={entry.color} size={10} withShadow={false} />
                        <Text size="xs" c={isolated === undefined || isolated === entry.name ? undefined : "dimmed"}>
                            {entry.name}
                        </Text>
                    </Group>
                </UnstyledButton>
            ))}
        </Group>
    )
}
