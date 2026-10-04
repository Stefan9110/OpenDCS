"use client"

import { isCreatableBatteryPolicy, starterBattery, starterBatteryPolicy } from "@/components/topology/defaults"
import { BatteryPolicyFields } from "@/components/topology/inspector/BatteryPolicyFields"
import { CatalogSelect } from "@/components/topology/inspector/CatalogSelect"
import { FieldLabel, UnitAdornment } from "@/components/topology/inspector/FieldLabel"
import { useCatalog } from "@/lib/api/catalogs"
import type { BatterySpec } from "@/lib/topology/spec"
import { SCALAR_UNIT } from "@/lib/units"
import { Group, NumberInput, Stack, Switch } from "@mantine/core"

/** A data center's battery, which charges from and discharges into the supply its clusters share. */
export function BatteryFields({
    battery,
    onChange,
}: {
    battery: BatterySpec | null | undefined
    onChange: (battery: BatterySpec | undefined) => void
}) {
    const policies = useCatalog("battery-policies")

    return (
        <Stack gap="xs">
            <Switch
                size="xs"
                label="Battery attached"
                checked={battery !== undefined && battery !== null}
                onChange={(event) => onChange(event.currentTarget.checked ? starterBattery() : undefined)}
            />

            {battery && (
                <>
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
                            onChange={(value) => onChange({ ...battery, capacity: Number(value) || 0 })}
                        />
                        <NumberInput
                            label={
                                <FieldLabel
                                    label="Charge rate"
                                    help="How fast the battery draws power while charging."
                                />
                            }
                            size="xs"
                            min={0}
                            hideControls
                            rightSection={<UnitAdornment unit={SCALAR_UNIT.power} />}
                            rightSectionWidth={38}
                            rightSectionPointerEvents="none"
                            value={battery.chargingSpeed}
                            onChange={(value) => onChange({ ...battery, chargingSpeed: Number(value) || 0 })}
                        />
                    </Group>
                    <CatalogSelect
                        label="Policy"
                        entries={policies}
                        value={battery.policy.type}
                        onChange={(value) =>
                            value &&
                            isCreatableBatteryPolicy(value) &&
                            onChange({ ...battery, policy: starterBatteryPolicy(value) })
                        }
                    />
                    <BatteryPolicyFields
                        policy={battery.policy}
                        onChange={(policy) => onChange({ ...battery, policy })}
                    />
                </>
            )}
        </Stack>
    )
}
