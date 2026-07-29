"use client"

import { MAX_OVERLAID_RUNS, describeRuns, seriesColor } from "@/components/experiment/results/resultsView"
import type { ScenarioResults } from "@/lib/experiment/results"
import type { ExperimentSpec } from "@/lib/experiment/spec"
import { Button, Checkbox, Group, Menu, Stack, Text, useComputedColorScheme } from "@mantine/core"
import { IconChevronDown } from "@tabler/icons-react"

export function RunPicker({
    spec,
    scenarios,
    chosen,
    onChange,
}: {
    spec: ExperimentSpec
    scenarios: ScenarioResults[]
    chosen: number[]
    onChange: (chosen: number[]) => void
}) {
    const scheme = useComputedColorScheme("light")
    const labels = describeRuns(
        spec,
        scenarios.map((scenario) => scenario.scenarioIndex),
    )

    if (scenarios.length <= 1) return undefined

    const toggle = (scenarioIndex: number) => {
        if (chosen.includes(scenarioIndex)) {
            const kept = chosen.filter((entry) => entry !== scenarioIndex)
            if (kept.length > 0) onChange(kept)
            return
        }
        onChange([...chosen, scenarioIndex].slice(-MAX_OVERLAID_RUNS))
    }

    return (
        <Menu position="bottom-start" closeOnItemClick={false} withinPortal>
            <Menu.Target>
                <Button variant="default" rightSection={<IconChevronDown size={14} />}>
                    {chosen.length} of {scenarios.length} scenarios
                </Button>
            </Menu.Target>
            <Menu.Dropdown mah={360} style={{ overflowY: "auto" }}>
                <Menu.Label>Overlay up to {MAX_OVERLAID_RUNS} at a time</Menu.Label>
                {scenarios.map((scenario, row) => {
                    const label = labels[row] ?? { short: `#${scenario.scenarioIndex}`, full: "" }
                    const position = chosen.indexOf(scenario.scenarioIndex)
                    return (
                        <Menu.Item key={scenario.scenarioIndex} onClick={() => toggle(scenario.scenarioIndex)}>
                            <Group gap="sm" wrap="nowrap" align="flex-start">
                                <Checkbox
                                    checked={position >= 0}
                                    readOnly
                                    size="xs"
                                    mt={3}
                                    color={position >= 0 ? seriesColor(position, scheme) : undefined}
                                    aria-label={label.short}
                                />
                                <Stack gap={0}>
                                    <Text size="sm">{label.short}</Text>
                                    <Text size="xs" c="dimmed">
                                        {label.full}
                                    </Text>
                                </Stack>
                            </Group>
                        </Menu.Item>
                    )
                })}
            </Menu.Dropdown>
        </Menu>
    )
}
