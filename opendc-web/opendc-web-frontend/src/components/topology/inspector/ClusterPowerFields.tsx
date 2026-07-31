"use client"

import { CatalogSelect } from "@/components/topology/inspector/CatalogSelect"
import { FieldLabel, UnitAdornment } from "@/components/topology/inspector/FieldLabel"
import { QuantityInput } from "@/components/topology/inspector/QuantityInput"
import { useCatalog } from "@/lib/api/catalogs"
import { useTraceOptions } from "@/lib/api/traces"
import type { BatteryPolicy, BatterySpec, ClusterSpec, PowerSourceSpec } from "@/lib/topology/spec"
import { SCALAR_UNIT } from "@/lib/units"
import { Group, NumberInput, Stack, Switch } from "@mantine/core"

// The source's name is a free-form label the simulator only echoes into the powerSource export, so
// the editor does not ask for one and the model's own default stands.
const DEFAULT_POWER_SOURCE: PowerSourceSpec = { maxPower: "10 kWatts" }

const DEFAULT_BATTERY: BatterySpec = {
    name: "Battery",
    capacity: 100,
    chargingSpeed: 10_000,
    initialCharge: 0,
    policy: { type: "single", carbonThreshold: 150 },
}

export function ClusterPowerFields({
    cluster,
    onChange,
}: {
    cluster: ClusterSpec
    onChange: (next: Partial<ClusterSpec>) => void
}) {
    const source = cluster.powerSource ?? DEFAULT_POWER_SOURCE
    const carbon = source.carbon?.type === "named" ? source.carbon.name : null
    // Only carbon traces: this field used to offer the whole library, so every option it listed
    // was a workload trace the simulator would refuse here.
    const carbonTraces = useTraceOptions("carbon")

    return (
        <Stack gap="xs">
            <QuantityInput
                label="Supply limit"
                kind="power"
                unit="kW"
                help="Most power this source can deliver to the cluster. The bar on the floor tile fills against this, and turns red if peak draw exceeds it."
                value={source.maxPower ?? "10 kWatts"}
                onChange={(maxPower) => onChange({ powerSource: { ...source, maxPower } })}
            />

            <CatalogSelect
                label="Carbon intensity trace"
                entries={carbonTraces}
                clearable
                placeholder="None"
                value={carbon}
                onChange={(value) =>
                    onChange({
                        powerSource: { ...source, carbon: value ? { type: "named", name: value } : undefined },
                    })
                }
            />

            <Switch
                size="xs"
                label="Battery attached"
                checked={cluster.battery !== undefined && cluster.battery !== null}
                onChange={(event) => onChange({ battery: event.currentTarget.checked ? DEFAULT_BATTERY : undefined })}
            />

            {cluster.battery && (
                <BatteryFields battery={cluster.battery} onChange={(battery) => onChange({ battery })} />
            )}
        </Stack>
    )
}

function BatteryFields({
    battery,
    onChange,
}: {
    battery: BatterySpec
    onChange: (next: BatterySpec) => void
}) {
    const policies = useCatalog("battery-policies")

    return (
        <Stack gap="xs">
            <Group grow gap="xs">
                <NumberInput
                    label={<FieldLabel label="Capacity" help="Energy the battery holds when full." />}
                    size="xs"
                    min={0}
                    hideControls
                    rightSection={<UnitAdornment unit={SCALAR_UNIT.energy} />}
                    rightSectionWidth={46}
                    rightSectionPointerEvents="none"
                    value={battery.capacity}
                    onChange={(value) => onChange({ ...battery, capacity: numberOf(value) })}
                />
                <NumberInput
                    label={<FieldLabel label="Charge rate" help="How fast the battery draws power while charging." />}
                    size="xs"
                    min={0}
                    hideControls
                    rightSection={<UnitAdornment unit={SCALAR_UNIT.power} />}
                    rightSectionWidth={38}
                    rightSectionPointerEvents="none"
                    value={battery.chargingSpeed}
                    onChange={(value) => onChange({ ...battery, chargingSpeed: numberOf(value) })}
                />
            </Group>
            <CatalogSelect
                label="Policy"
                entries={policies}
                value={battery.policy.type}
                onChange={(value) => value && onChange({ ...battery, policy: policyOf(value) })}
            />
            <PolicyFields policy={battery.policy} onChange={(policy) => onChange({ ...battery, policy })} />
        </Stack>
    )
}

function PolicyFields({
    policy,
    onChange,
}: {
    policy: BatteryPolicy
    onChange: (next: BatteryPolicy) => void
}) {
    if (policy.type === "single") {
        return (
            <ThresholdInput
                label="Carbon threshold"
                help="Carbon intensity at or above which the battery discharges instead of drawing from the source. Below it, the battery charges."
                value={policy.carbonThreshold}
                onChange={(carbonThreshold) => onChange({ ...policy, carbonThreshold })}
            />
        )
    }
    if (policy.type === "double") {
        return (
            <Group grow gap="xs">
                <ThresholdInput
                    label="Lower"
                    help="Below this carbon intensity the battery charges."
                    value={policy.lowerThreshold}
                    onChange={(lowerThreshold) => onChange({ ...policy, lowerThreshold })}
                />
                <ThresholdInput
                    label="Upper"
                    help="Above this carbon intensity the battery discharges."
                    value={policy.upperThreshold}
                    onChange={(upperThreshold) => onChange({ ...policy, upperThreshold })}
                />
            </Group>
        )
    }
    return (
        <Group grow gap="xs">
            <ThresholdInput
                label="Starting threshold"
                help="Carbon intensity used before enough samples have been seen to compute the running statistic."
                value={policy.startingThreshold}
                onChange={(startingThreshold) => onChange({ ...policy, startingThreshold })}
            />
            <NumberInput
                label={
                    <FieldLabel
                        label="Window size"
                        help="How many recent carbon intensity samples the running statistic is computed over."
                    />
                }
                size="xs"
                min={1}
                value={policy.windowSize}
                onChange={(value) => onChange({ ...policy, windowSize: numberOf(value) })}
            />
        </Group>
    )
}

/** Every battery policy is steered by carbon intensities, which all read in the same unit. */
function ThresholdInput({
    label,
    help,
    value,
    onChange,
}: {
    label: string
    help: string
    value: number
    onChange: (next: number) => void
}) {
    return (
        <NumberInput
            label={<FieldLabel label={label} help={help} />}
            size="xs"
            value={value}
            onChange={(next) => onChange(numberOf(next))}
            rightSection={<UnitAdornment unit={SCALAR_UNIT.carbonIntensity} />}
            rightSectionWidth={70}
            rightSectionPointerEvents="none"
        />
    )
}

function policyOf(type: string): BatteryPolicy {
    if (type === "single") return { type: "single", carbonThreshold: 150 }
    if (type === "double") return { type: "double", lowerThreshold: 100, upperThreshold: 200 }
    if (type === "runningMeanPlus") return { type: "runningMeanPlus", startingThreshold: 150, windowSize: 24 }
    if (type === "runningMedian") return { type: "runningMedian", startingThreshold: 150, windowSize: 24 }
    if (type === "runningQuartiles") return { type: "runningQuartiles", startingThreshold: 150, windowSize: 24 }
    return { type: "runningMean", startingThreshold: 150, windowSize: 24 }
}

function numberOf(value: string | number): number {
    return typeof value === "number" ? value : (Number(value) ?? 0)
}
