import { DEFAULT_UNIT, type QuantityKind, amountIn, formatQuantity, parseQuantity } from "@/lib/units"
import { describe, expect, it } from "vitest"

const KINDS: QuantityKind[] = ["frequency", "dataSize", "dataRate", "power"]

function base(kind: QuantityKind, wire: string | number): number {
    const parsed = parseQuantity(kind, wire)
    if (parsed.status !== "ok") throw new Error(`expected ${wire} to parse, got ${parsed.status}`)
    return parsed.base
}

describe("parseQuantity", () => {
    it("reads a bare number in the implicit base unit the SDK assumes", () => {
        expect(base("frequency", 3200)).toBe(3200)
        expect(base("dataSize", 128)).toBe(128)
        expect(base("power", 400)).toBe(400)
        expect(base("dataRate", 1600)).toBe(1600)
    })

    it("treats data-size case as significant, because the backend does", () => {
        expect(base("dataSize", "1 GiB")).toBe(1024)
        expect(base("dataSize", "1 gib")).toBe(1024 / 8)
        expect(base("dataSize", "1 GB")).toBe(1e9 / 1024 ** 2)
        expect(base("dataSize", "1 Gb")).toBe(1e9 / 8 / 1024 ** 2)
    })

    it("ignores case for frequency and power, because the backend does", () => {
        expect(base("frequency", "3.2 ghz")).toBe(base("frequency", "3.2 GHz"))
        expect(base("frequency", "2megahertz")).toBe(2)
        expect(base("power", "50 KWATTS")).toBe(base("power", "50 kW"))
    })

    it("rejects units the backend does not accept, rather than guessing", () => {
        expect(parseQuantity("power", "5 MW").status).toBe("invalid")
        expect(parseQuantity("frequency", "1 THz").status).toBe("invalid")
        expect(parseQuantity("power", "1 watt").status).toBe("invalid")
        expect(parseQuantity("dataSize", "1 PiB").status).toBe("invalid")
    })

    it("rejects malformed input", () => {
        expect(parseQuantity("frequency", "1.2.3 GHz").status).toBe("invalid")
        expect(parseQuantity("frequency", "fast").status).toBe("invalid")
        expect(parseQuantity("frequency", "").status).toBe("invalid")
        expect(parseQuantity("dataSize", "GiB").status).toBe("invalid")
        expect(parseQuantity("power", Number.NaN).status).toBe("invalid")
    })

    it("matches the backend's numeric grammar, which has no exponent sign", () => {
        expect(base("frequency", "1e3 MHz")).toBe(1000)
        expect(parseQuantity("frequency", "1e+3 MHz").status).toBe("invalid")
        expect(parseQuantity("frequency", "1E3 MHz").status).toBe("invalid")
    })

    it("reports the -1 sentinel as unspecified instead of a real measurement", () => {
        expect(parseQuantity("frequency", -1).status).toBe("unspecified")
        expect(parseQuantity("dataSize", -1).status).toBe("unspecified")
        expect(parseQuantity("dataRate", -1).status).toBe("unspecified")
        expect(parseQuantity("dataSize", "-1 GiB").status).toBe("unspecified")
    })

    it("tolerates the whitespace the backend tolerates", () => {
        expect(base("frequency", "  10    GHz   ")).toBe(10_000)
        expect(base("dataSize", "128GiB")).toBe(131_072)
    })

    it("understands every per-second spelling for data rates", () => {
        expect(base("dataRate", "1 Kibps")).toBe(1)
        expect(base("dataRate", "1 Kib/s")).toBe(1)
        expect(base("dataRate", "1 Kib per second")).toBe(1)
        expect(base("dataRate", "1600 GBps")).toBe((1600 * 1e9 * 8) / 1024)
    })

    it("rejects a data size where a data rate is required", () => {
        expect(parseQuantity("dataRate", "1 GiB").status).toBe("invalid")
    })
})

describe("formatQuantity", () => {
    it("picks the largest unit that keeps the number readable", () => {
        expect(formatQuantity("frequency", 3200)).toBe("3.2 GHz")
        expect(formatQuantity("frequency", 800)).toBe("800 MHz")
        expect(formatQuantity("dataSize", 131_072)).toBe("128 GiB")
        expect(formatQuantity("dataSize", 512)).toBe("512 MiB")
        expect(formatQuantity("power", 50_000)).toBe("50 kW")
        expect(formatQuantity("power", 400)).toBe("400 W")
    })

    it("falls back to the smallest unit for zero rather than dividing by a large step", () => {
        expect(formatQuantity("power", 0)).toBe("0 W")
        expect(formatQuantity("dataSize", 0)).toBe("0 KiB")
    })

    it("round-trips back through the parser", () => {
        for (const [kind, value] of [
            ["frequency", 3200],
            ["dataSize", 131_072],
            ["power", 50_000],
            ["dataRate", 1024],
        ] as Array<[QuantityKind, number]>) {
            expect(base(kind, formatQuantity(kind, value))).toBe(value)
        }
    })
})

describe("amountIn", () => {
    it("counts a value in the unit its field is written in", () => {
        expect(amountIn("frequency", 2600, "GHz")).toBe(2.6)
        expect(amountIn("frequency", 2600, "MHz")).toBe(2600)
        expect(amountIn("dataSize", 131_072, "GiB")).toBe(128)
        expect(amountIn("power", 10_000, "kW")).toBe(10)
    })

    // 2600 / 1000 is 2.5999999999999996 in floating point, and the SDK's own "%f" formatter writes
    // "10.000000 KWatts". Neither may reach the box the reader types into.
    it("shows a clean number for values the backend wrote", () => {
        expect(amountIn("power", base("power", "10.000000 KWatts"), "kW")).toBe(10)
        expect(amountIn("frequency", base("frequency", "2.6 GHz"), "GHz")).toBe(2.6)
        expect(amountIn("dataSize", base("dataSize", "1.5 GiB"), "GiB")).toBe(1.5)
    })

    // Anything written and read back in the same unit must survive the trip, or editing one field
    // would quietly rewrite a neighbouring value every time the inspector opened.
    it("round-trips whatever the editor writes", () => {
        for (const kind of KINDS) {
            const unit = DEFAULT_UNIT[kind]
            expect(amountIn(kind, base(kind, `12.5 ${unit}`), unit), `${unit} for ${kind}`).toBe(12.5)
        }
    })
})
