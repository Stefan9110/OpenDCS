"use client"

import { CatalogSelect } from "@/components/topology/inspector/CatalogSelect"
import { QuantityInput } from "@/components/topology/inspector/QuantityInput"
import { useCatalog } from "@/lib/api/catalogs"
import type { PowerSpec } from "@/lib/topology/spec"
import { Group, Stack } from "@mantine/core"

export const DEFAULT_POWER_MODEL: PowerSpec = { type: "linear", maxPower: "400 Watts", idlePower: "120 Watts" }

export function PowerModelFields({
    model,
    onChange,
}: {
    model: PowerSpec
    onChange: (next: PowerSpec) => void
}) {
    const powerModels = useCatalog("power-models")

    return (
        <Stack gap="xs">
            <CatalogSelect
                label="Power model"
                entries={powerModels}
                value={model.type ?? "linear"}
                onChange={(value) => value && onChange({ ...model, type: value as PowerSpec["type"] })}
            />
            <Group grow gap="xs" align="flex-start">
                <QuantityInput
                    label="Peak"
                    kind="power"
                    value={model.maxPower}
                    onChange={(maxPower) => onChange({ ...model, maxPower })}
                />
                <QuantityInput
                    label="Idle"
                    kind="power"
                    value={model.idlePower}
                    onChange={(idlePower) => onChange({ ...model, idlePower })}
                />
            </Group>
        </Stack>
    )
}
