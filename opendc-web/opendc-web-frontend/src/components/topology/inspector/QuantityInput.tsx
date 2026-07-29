"use client"

import { FieldLabel } from "@/components/topology/inspector/FieldLabel"
import { BASE_UNIT, type Quantity, type QuantityKind, parseQuantity } from "@/lib/units"
import { Text, TextInput } from "@mantine/core"

const KIND_NAMES: Record<QuantityKind, string> = {
    frequency: "frequency",
    dataSize: "size",
    dataRate: "data rate",
    power: "power",
}

const EXAMPLES: Record<QuantityKind, string> = {
    frequency: "3.2 GHz",
    dataSize: "128 GiB",
    dataRate: "1600 GBps",
    power: "400 Watts",
}

const BARE_NUMBER = /^\s*[\d.e-]+\s*$/

export function QuantityInput({
    label,
    kind,
    value,
    onChange,
    allowUnset = false,
    help,
}: {
    label: string
    kind: QuantityKind
    value: Quantity
    onChange: (next: string) => void
    allowUnset?: boolean
    help?: string
}) {
    const parsed = parseQuantity(kind, value)
    const unset = parsed.status === "unspecified"
    const invalid = parsed.status === "invalid" || (unset && !allowUnset)
    const text = unset && allowUnset ? "" : String(value)
    const unitless = parsed.status === "ok" && BARE_NUMBER.test(text)

    return (
        <TextInput
            label={<FieldLabel label={label} help={help} />}
            size="xs"
            value={text}
            placeholder={EXAMPLES[kind]}
            onChange={(event) => {
                const next = event.currentTarget.value
                onChange(allowUnset && next.trim() === "" ? "-1" : next)
            }}
            onBlur={() => {
                if (unitless) onChange(`${text.trim()} ${BASE_UNIT[kind]}`)
            }}
            error={invalid ? `Enter a ${KIND_NAMES[kind]} like ${EXAMPLES[kind]}` : undefined}
            rightSection={
                unitless ? (
                    <Text size="xs" c="dimmed" pr={6}>
                        {BASE_UNIT[kind]}
                    </Text>
                ) : undefined
            }
            rightSectionWidth={unitless ? 44 : undefined}
            rightSectionPointerEvents="none"
        />
    )
}
