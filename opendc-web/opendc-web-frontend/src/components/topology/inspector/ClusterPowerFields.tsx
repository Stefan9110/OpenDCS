"use client"

import { CatalogSelect } from "@/components/topology/inspector/CatalogSelect"
import { FieldLabel, UnitAdornment } from "@/components/topology/inspector/FieldLabel"
import { QuantityInput } from "@/components/topology/inspector/QuantityInput"
import type { BatteryPolicy, BatterySpec, ClusterSpec, PowerSourceSpec } from "@/lib/topology/spec"
import { Group, NumberInput, Stack, Switch } from "@mantine/core"

const DEFAULT_POWER_SOURCE: PowerSourceSpec = { name: "grid", maxPower: "10 kWatts" }

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

    return (
        <Stack gap="xs">
            <Group grow gap="xs" align="flex-start">
                <CatalogSelect
                    label="Power source"
                    catalog="power-sources"
                    value={source.name ?? "grid"}
                    extraOptions={[source.name ?? "grid"]}
                    onChange={(value) => value && onChange({ powerSource: { ...source, name: value } })}
                />
                <QuantityInput
                    label="Supply limit"
                    kind="power"
                    help="Most power this source can deliver to the cluster. The bar on the floor tile fills against this, and turns red if peak draw exceeds it."
                    value={source.maxPower ?? "10 kWatts"}
                    onChange={(maxPower) => onChange({ powerSource: { ...source, maxPower } })}
                />
            </Group>

            <CatalogSelect
                label="Carbon intensity trace"
                catalog="traces"
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
    return (
        <Stack gap="xs">
            <Group grow gap="xs">
                <NumberInput
                    label={<FieldLabel label="Capacity" help="Energy the battery holds when full." />}
                    size="xs"
                    min={0}
                    hideControls
                    rightSection={<UnitAdornment unit="kWh" />}
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
                    rightSection={<UnitAdornment unit="W" />}
                    rightSectionWidth={38}
                    rightSectionPointerEvents="none"
                    value={battery.chargingSpeed}
                    onChange={(value) => onChange({ ...battery, chargingSpeed: numberOf(value) })}
                />
            </Group>
            <CatalogSelect
                label="Policy"
                catalog="battery-policies"
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
            <NumberInput
                label={
                    <FieldLabel
                        label="Carbon threshold"
                        help="Carbon intensity at or above which the battery discharges instead of drawing from the source. Below it, the battery charges."
                    />
                }
                size="xs"
                value={policy.carbonThreshold}
                onChange={(value) => onChange({ ...policy, carbonThreshold: numberOf(value) })}
            />
        )
    }
    if (policy.type === "double") {
        return (
            <Group grow gap="xs">
                <NumberInput
                    label={<FieldLabel label="Lower" help="Below this carbon intensity the battery charges." />}
                    size="xs"
                    value={policy.lowerThreshold}
                    onChange={(value) => onChange({ ...policy, lowerThreshold: numberOf(value) })}
                />
                <NumberInput
                    label={<FieldLabel label="Upper" help="Above this carbon intensity the battery discharges." />}
                    size="xs"
                    value={policy.upperThreshold}
                    onChange={(value) => onChange({ ...policy, upperThreshold: numberOf(value) })}
                />
            </Group>
        )
    }
    return (
        <Group grow gap="xs">
            <NumberInput
                label={
                    <FieldLabel
                        label="Starting threshold"
                        help="Carbon intensity used before enough samples have been seen to compute the running statistic."
                    />
                }
                size="xs"
                value={policy.startingThreshold}
                onChange={(value) => onChange({ ...policy, startingThreshold: numberOf(value) })}
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
