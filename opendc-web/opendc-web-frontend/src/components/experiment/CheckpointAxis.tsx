"use client"

import { AXIS_HELP } from "@/components/experiment/axisLabels"
import { FieldLabel } from "@/components/topology/inspector/FieldLabel"
import { QuantityInput } from "@/components/topology/inspector/QuantityInput"
import {
    type CheckpointSpec,
    DEFAULT_CHECKPOINT_DURATION,
    DEFAULT_CHECKPOINT_INTERVAL,
    DEFAULT_INTERVAL_SCALING,
    positions,
} from "@/lib/experiment/spec"
import { ActionIcon, Button, Checkbox, Divider, Group, NumberInput, Stack } from "@mantine/core"
import { IconPlus, IconTrash } from "@tabler/icons-react"
import { Fragment } from "react"

// A multiplier below this shrinks the interval tenfold at every checkpoint, and the simulator
// rejects zero outright, so the field stops short of a value that could only be a mistake.
const MIN_SCALING = 0.1

/**
 * The checkpoint axis, whose entries are written out rather than picked from a catalog. Not
 * checkpointing at all is one of the values it can take, and the model spells that as a null entry,
 * so it is a checkbox here rather than a configuration of its own: it has no settings to show.
 */
export function CheckpointAxis({
    entries,
    onChange,
}: Readonly<{ entries: Array<CheckpointSpec | null>; onChange: (next: Array<CheckpointSpec | null>) => void }>) {
    const configured = entries.filter((entry) => entry !== null)
    const includesNone = entries.length > configured.length

    // Null first, so adding or dropping a configuration leaves the other entries where they were
    // and the scenarios they expand to keep their index.
    const write = (none: boolean, rest: CheckpointSpec[]) => onChange([...(none ? [null] : []), ...rest])

    const setField = (position: number, change: Partial<CheckpointSpec>) =>
        write(
            includesNone,
            configured.map((entry, at) => (at === position ? { ...entry, ...change } : entry)),
        )

    return (
        <Stack gap="sm">
            <Checkbox
                label={<FieldLabel label="Include no checkpointing" help={AXIS_HELP.checkpointModels} />}
                size="sm"
                checked={includesNone}
                // Unchecking the last thing in the axis would leave an experiment that expands to no
                // scenarios at all, so it holds until a configuration exists to fall back on.
                disabled={includesNone && configured.length === 0}
                onChange={(event) => write(event.currentTarget.checked, configured)}
            />

            {positions(configured.length).map((position) => (
                <Fragment key={position}>
                    <Divider />
                    <Stack gap="xs">
                        <Group justify="space-between" wrap="nowrap" align="flex-end" gap="xs">
                            <QuantityInput
                                label="Interval"
                                kind="time"
                                help="How much simulated time passes between one checkpoint and the next."
                                value={configured[position]?.interval ?? DEFAULT_CHECKPOINT_INTERVAL}
                                onChange={(interval) => setField(position, { interval })}
                            />
                            <ActionIcon
                                variant="subtle"
                                color="gray"
                                size="lg"
                                aria-label="Remove this configuration"
                                disabled={!includesNone && configured.length === 1}
                                onClick={() =>
                                    write(
                                        includesNone,
                                        configured.filter((_, at) => at !== position),
                                    )
                                }
                            >
                                <IconTrash size={16} />
                            </ActionIcon>
                        </Group>
                        <QuantityInput
                            label="Duration"
                            kind="time"
                            help="How long writing one checkpoint takes, which is time the task spends not running."
                            value={configured[position]?.duration ?? DEFAULT_CHECKPOINT_DURATION}
                            onChange={(duration) => setField(position, { duration })}
                        />
                        <NumberInput
                            label={
                                <FieldLabel
                                    label="Interval scaling"
                                    help="Multiplier applied to the interval after each checkpoint. Above one the gaps grow, below one they shorten."
                                />
                            }
                            size="xs"
                            min={MIN_SCALING}
                            step={0.1}
                            allowNegative={false}
                            hideControls
                            value={configured[position]?.intervalScaling ?? DEFAULT_INTERVAL_SCALING}
                            onChange={(value) =>
                                setField(position, {
                                    intervalScaling: typeof value === "number" ? value : DEFAULT_INTERVAL_SCALING,
                                })
                            }
                        />
                    </Stack>
                </Fragment>
            ))}

            <Button
                size="compact-xs"
                variant="light"
                leftSection={<IconPlus size={14} />}
                onClick={() => write(includesNone, [...configured, {}])}
            >
                Add configuration
            </Button>
        </Stack>
    )
}
