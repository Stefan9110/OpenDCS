export type QuantityKind = "frequency" | "dataSize" | "dataRate" | "power"

/** A measurement as the model stores it: a number in the base unit, or text naming its own unit. */
export type Quantity = number | string

/**
 * Every unit a quantity may be written in, largest first, with how many base units it is worth.
 * This is the one place unit names exist: the type a field accepts and the ladder a value is
 * displayed on both come from here, so a name cannot be offered by one and unknown to the other.
 */
const LADDER = {
    frequency: [
        ["GHz", 1e3],
        ["MHz", 1],
        ["kHz", 1e-3],
        ["Hz", 1e-6],
    ],
    dataSize: [
        ["TiB", 1024 ** 2],
        ["GiB", 1024],
        ["MiB", 1],
        ["KiB", 1 / 1024],
    ],
    dataRate: [
        ["Gibps", 1024 ** 2],
        ["Mibps", 1024],
        ["Kibps", 1],
    ],
    power: [
        ["kW", 1e3],
        ["W", 1],
    ],
} as const satisfies Record<QuantityKind, ReadonlyArray<readonly [string, number]>>

/** A unit a quantity of kind [K] may be written in. */
export type Unit<K extends QuantityKind = QuantityKind> = (typeof LADDER)[K][number][0]

/**
 * What a bare number in the model is counted in. These fields store a plain number rather than a
 * measurement, so the unit is the editor's to state and never travels in the document.
 */
export const SCALAR_UNIT = {
    energy: "kWh",
    power: "W",
    carbonIntensity: "gCO2/kWh",
} as const

/** Anything the editor prints beside a number. */
export type UnitLabel = Unit | (typeof SCALAR_UNIT)[keyof typeof SCALAR_UNIT]

/**
 * The unit a measurement field is written in unless it names another. A field reads and writes one
 * unit only, so it is a plain number with its unit printed beside it and nobody has to know how the
 * model spells its units in order to type a value.
 */
export const DEFAULT_UNIT: { [K in QuantityKind]: Unit<K> } = {
    frequency: "GHz",
    dataSize: "GiB",
    dataRate: "Gibps",
    power: "W",
}

/** The unit a bare number is counted in, which is what the model assumes when text names none. */
const BASE_UNIT: { [K in QuantityKind]: Unit<K> } = {
    frequency: "MHz",
    dataSize: "MiB",
    dataRate: "Kibps",
    power: "W",
}

// Reading what the model wrote /////////////////////////////////////////////////////////////////

export type QuantityValue = { status: "ok"; base: number } | { status: "unspecified" } | { status: "invalid" }

/**
 * Reads a stored measurement into its base unit. This has to accept every spelling the backend
 * accepts, not just the ones the editor writes: values arrive from the SDK's own formatter, which
 * prints "10.000000 KWatts", and from documents people import.
 */
export function parseQuantity(kind: QuantityKind, wire: Quantity): QuantityValue {
    if (typeof wire === "number") {
        return Number.isFinite(wire) ? classify(wire) : { status: "invalid" }
    }
    const parts = NUMBER_AND_SUFFIX.exec(wire)
    if (!parts) return { status: "invalid" }
    const amount = Number(parts[1])
    const suffix = parts[2] ?? ""
    if (!Number.isFinite(amount)) return { status: "invalid" }
    if (suffix === "") return classify(amount)

    const factor = factorOf(kind, suffix)
    if (factor === undefined) return { status: "invalid" }
    return classify(amount * factor)
}

// The model reads any negative value as "this was never specified", so the editor must not show
// one as a measurement of minus something.
function classify(base: number): QuantityValue {
    return base < 0 ? { status: "unspecified" } : { status: "ok", base }
}

const NUMBER_AND_SUFFIX = /^\s*([\d.e-]+)\s*(.*?)\s*$/

const PER_SECOND = /^(.*?)\s*(?:p|per|\/)\s*(?:s|sec|Sec|second|Second)\s*$/

const BIT_UNITS = ["b", "bit", "bits", "Bit", "Bits"]
const BYTE_UNITS = ["B", "byte", "bytes", "Byte", "Bytes"]

// Longest first, so "Kibi" is tried before "Ki" before "K". Every prefix is matched in either
// case; only the unit itself is case-sensitive, where "B" is bytes and "b" is bits.
const SIZE_PREFIX_SCALES = [
    ["Kibi", 1024],
    ["Mebi", 1024 ** 2],
    ["Gibi", 1024 ** 3],
    ["Tebi", 1024 ** 4],
    ["Kilo", 1e3],
    ["Mega", 1e6],
    ["Giga", 1e9],
    ["Tera", 1e12],
    ["Ki", 1024],
    ["Mi", 1024 ** 2],
    ["Gi", 1024 ** 3],
    ["Ti", 1024 ** 4],
    ["K", 1e3],
    ["M", 1e6],
    ["G", 1e9],
    ["T", 1e12],
] as const

const SIZE_PREFIXES: ReadonlyArray<readonly [string, number]> = [
    ...SIZE_PREFIX_SCALES.flatMap(([name, scale]) => [[name, scale] as const, [name.toLowerCase(), scale] as const]),
    ["", 1],
]

// Hertz and watts are matched case-insensitively, so these need only one spelling each.
const HERTZ_PREFIXES: ReadonlyArray<readonly [string, number]> = [
    ["kilo", 1e3],
    ["mega", 1e6],
    ["giga", 1e9],
    ["k", 1e3],
    ["m", 1e6],
    ["g", 1e9],
    ["", 1],
]

const WATT_PREFIXES: ReadonlyArray<readonly [string, number]> = [
    ["kilo", 1e3],
    ["k", 1e3],
    ["", 1],
]

/** Base units per one of [suffix], or undefined when the backend would reject it too. */
function factorOf(kind: QuantityKind, suffix: string): number | undefined {
    switch (kind) {
        case "frequency":
            return scaledUnit(suffix.toLowerCase(), ["hz", "hertz"], HERTZ_PREFIXES, 1e-6)
        case "power":
            return scaledUnit(suffix.toLowerCase(), ["w", "watts"], WATT_PREFIXES, 1)
        case "dataSize":
            return dataFactor(suffix, 1 / 1024 ** 2)
        case "dataRate": {
            const rate = PER_SECOND.exec(suffix)
            return rate === null ? undefined : dataFactor(rate[1] ?? "", 8 / 1024)
        }
    }
}

function scaledUnit(
    suffix: string,
    units: string[],
    prefixes: ReadonlyArray<readonly [string, number]>,
    unitInBase: number,
): number | undefined {
    for (const unit of units) {
        if (!suffix.endsWith(unit)) continue
        const prefix = suffix.slice(0, suffix.length - unit.length)
        const scale = prefixes.find(([name]) => name === prefix)
        if (scale) return scale[1] * unitInBase
    }
    return undefined
}

function dataFactor(suffix: string, baseUnitsPerByte: number): number | undefined {
    for (const [prefix, scale] of SIZE_PREFIXES) {
        if (!suffix.startsWith(prefix)) continue
        const unit = suffix.slice(prefix.length)
        if (BYTE_UNITS.includes(unit)) return scale * baseUnitsPerByte
        if (BIT_UNITS.includes(unit)) return (scale / 8) * baseUnitsPerByte
    }
    return undefined
}

// Showing a value //////////////////////////////////////////////////////////////////////////////

/** Renders [base] on the largest unit that keeps the number short, for read-only display. */
export function formatQuantity(kind: QuantityKind, base: number): string {
    if (!Number.isFinite(base)) return `0 ${BASE_UNIT[kind]}`
    const unit = naturalUnit(kind, base)
    return `${round(base / unitScale(kind, unit))} ${unit}`
}

/** Counts [base] in [unit], for a field the reader is about to edit. */
export function amountIn<K extends QuantityKind>(kind: K, base: number, unit: Unit<K>): number {
    return trim(base / unitScale(kind, unit))
}

function naturalUnit<K extends QuantityKind>(kind: K, base: number): Unit<K> {
    const ladder = ladderOf(kind)
    const step = ladder.find(([, scale]) => Math.abs(base) >= scale)
    if (step !== undefined) return step[0]
    // Smaller than every step, zero included: show it on the smallest rather than as a long run of
    // leading zeroes on the largest.
    const smallest = ladder[ladder.length - 1]
    return smallest === undefined ? BASE_UNIT[kind] : smallest[0]
}

function unitScale<K extends QuantityKind>(kind: K, unit: Unit<K>): number {
    return ladderOf(kind).find(([name]) => name === unit)?.[1] ?? 1
}

function ladderOf<K extends QuantityKind>(kind: K): ReadonlyArray<readonly [Unit<K>, number]> {
    return LADDER[kind]
}

// Two decimals is all a read-only summary needs.
function round(value: number): string {
    return String(Math.round(value * 100) / 100)
}

// Dividing by a scale turns an exact 2600 MHz into 2.5999999999999996 GHz. Twelve significant
// digits is far more than any field here carries, so this drops the artefact and nothing else.
function trim(value: number): number {
    return Number(value.toPrecision(12))
}
