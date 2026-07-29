export type QuantityKind = "frequency" | "dataSize" | "dataRate" | "power"

export type Quantity = number | string

export type QuantityValue =
    | { status: "ok"; base: number }
    | { status: "unspecified" }
    | { status: "invalid"; input: string }

export const BASE_UNIT: Record<QuantityKind, string> = {
    frequency: "MHz",
    dataSize: "MiB",
    dataRate: "Kibps",
    power: "W",
}

const NUMBER_AND_SUFFIX = /^\s*([\d.e-]+)\s*(.*?)\s*$/

const SIZE_PREFIXES: ReadonlyArray<readonly [string, number]> = [
    ["Kibi", 1024],
    ["kibi", 1024],
    ["Mebi", 1024 ** 2],
    ["mebi", 1024 ** 2],
    ["Gibi", 1024 ** 3],
    ["gibi", 1024 ** 3],
    ["Tebi", 1024 ** 4],
    ["tebi", 1024 ** 4],
    ["Kilo", 1e3],
    ["kilo", 1e3],
    ["Mega", 1e6],
    ["mega", 1e6],
    ["Giga", 1e9],
    ["giga", 1e9],
    ["Tera", 1e12],
    ["tera", 1e12],
    ["Ki", 1024],
    ["ki", 1024],
    ["Mi", 1024 ** 2],
    ["mi", 1024 ** 2],
    ["Gi", 1024 ** 3],
    ["gi", 1024 ** 3],
    ["Ti", 1024 ** 4],
    ["ti", 1024 ** 4],
    ["K", 1e3],
    ["k", 1e3],
    ["M", 1e6],
    ["m", 1e6],
    ["G", 1e9],
    ["g", 1e9],
    ["T", 1e12],
    ["t", 1e12],
    ["", 1],
]

const BIT_UNITS = ["b", "bit", "bits", "Bit", "Bits"]
const BYTE_UNITS = ["B", "byte", "bytes", "Byte", "Bytes"]

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

const PER_SECOND = /^(.*?)\s*(?:p|per|\/)\s*(?:s|sec|Sec|second|Second)\s*$/

export function parseQuantity(kind: QuantityKind, wire: Quantity): QuantityValue {
    if (typeof wire === "number") {
        return Number.isFinite(wire) ? classify(wire) : { status: "invalid", input: String(wire) }
    }
    const parts = NUMBER_AND_SUFFIX.exec(wire)
    if (!parts) return { status: "invalid", input: wire }
    const amount = Number(parts[1])
    const suffix = parts[2] ?? ""
    if (!Number.isFinite(amount)) return { status: "invalid", input: wire }
    if (suffix === "") return classify(amount)

    const factor = factorOf(kind, suffix)
    if (factor === undefined) return { status: "invalid", input: wire }
    return classify(amount * factor)
}

function classify(base: number): QuantityValue {
    return base < 0 ? { status: "unspecified" } : { status: "ok", base }
}

function factorOf(kind: QuantityKind, suffix: string): number | undefined {
    if (kind === "frequency") return scaledUnit(suffix.toLowerCase(), ["hz", "hertz"], HERTZ_PREFIXES, 1e-6)
    if (kind === "power") return scaledUnit(suffix.toLowerCase(), ["w", "watts"], WATT_PREFIXES, 1)
    if (kind === "dataSize") return dataFactor(suffix, 1 / 1024 ** 2)
    const rate = PER_SECOND.exec(suffix)
    return rate ? dataFactor(rate[1] ?? "", 8 / 1024) : undefined
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

const FREQUENCY_LADDER: ReadonlyArray<readonly [string, number]> = [
    ["GHz", 1e3],
    ["MHz", 1],
    ["kHz", 1e-3],
    ["Hz", 1e-6],
]

const POWER_LADDER: ReadonlyArray<readonly [string, number]> = [
    ["kW", 1e3],
    ["W", 1],
]

const SIZE_LADDER: ReadonlyArray<readonly [string, number]> = [
    ["TiB", 1024 ** 2],
    ["GiB", 1024],
    ["MiB", 1],
    ["KiB", 1 / 1024],
]

const RATE_LADDER: ReadonlyArray<readonly [string, number]> = [
    ["Gibps", 1024 ** 2],
    ["Mibps", 1024],
    ["Kibps", 1],
]

const LADDER: Record<QuantityKind, ReadonlyArray<readonly [string, number]>> = {
    frequency: FREQUENCY_LADDER,
    dataSize: SIZE_LADDER,
    dataRate: RATE_LADDER,
    power: POWER_LADDER,
}

export function formatQuantity(kind: QuantityKind, base: number): string {
    if (!Number.isFinite(base)) return `0 ${BASE_UNIT[kind]}`
    const ladder = LADDER[kind]
    const magnitude = Math.abs(base)
    const step = ladder.find(([, size]) => magnitude >= size) ?? ladder.at(-1)
    if (!step) return `${round(base)} ${BASE_UNIT[kind]}`
    return `${round(base / step[1])} ${step[0]}`
}

function round(value: number): string {
    const rounded = Math.round(value * 100) / 100
    return String(rounded)
}
