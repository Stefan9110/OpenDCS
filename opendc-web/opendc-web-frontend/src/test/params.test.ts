import { numericParam } from "@/components/util/params"
import { describe, expect, it } from "vitest"

const params = (query: string) => new URLSearchParams(query)

describe("numericParam", () => {
    it("reads a well formed id", () => {
        expect(numericParam(params("id=42"), "id")).toEqual({ status: "ok", value: 42 })
    })

    it("distinguishes a missing link parameter from a broken one", () => {
        expect(numericParam(params(""), "id").status).toBe("missing")
        expect(numericParam(params("id="), "id").status).toBe("missing")
        expect(numericParam(params("id=%20%20"), "id").status).toBe("missing")
        expect(numericParam(params("id=abc"), "id")).toEqual({ status: "invalid", raw: "abc" })
    })

    it("rejects ids that would silently truncate or flip sign", () => {
        expect(numericParam(params("id=-3"), "id").status).toBe("invalid")
        expect(numericParam(params("id=1.5"), "id").status).toBe("invalid")
        expect(numericParam(params("id=3px"), "id").status).toBe("invalid")
        expect(numericParam(params("id=1e3"), "id").status).toBe("invalid")
    })

    it("tolerates surrounding whitespace from hand-edited links", () => {
        expect(numericParam(params("id=%207%20"), "id")).toEqual({ status: "ok", value: 7 })
    })
})
