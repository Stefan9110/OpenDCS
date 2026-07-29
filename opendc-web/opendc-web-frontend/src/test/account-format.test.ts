import { budgetColor, budgetPercent, formatBudgetUsage, formatResetsAt } from "@/components/user/accountFormat"
import type { BudgetWindow } from "@/lib/api/types"
import { describe, expect, it } from "vitest"

function window(overrides: Partial<BudgetWindow> = {}): BudgetWindow {
    return {
        period: "week",
        usedSeconds: 1800,
        budgetSeconds: 3600,
        resetsAt: "2026-07-25T18:30:00Z",
        ...overrides,
    }
}

describe("budgetPercent", () => {
    it("reports an unlimited budget as unconsumed rather than as full", () => {
        expect(budgetPercent(window({ budgetSeconds: "infinity", usedSeconds: 999_999 }))).toBe(0)
    })

    it("treats a zero budget as fully consumed instead of dividing by zero", () => {
        expect(budgetPercent(window({ budgetSeconds: 0, usedSeconds: 0 }))).toBe(100)
    })

    it("clamps a budget that has been overspent", () => {
        expect(budgetPercent(window({ budgetSeconds: 100, usedSeconds: 500 }))).toBe(100)
    })

    it("rounds to the nearest whole percent", () => {
        expect(budgetPercent(window({ budgetSeconds: 3600, usedSeconds: 1800 }))).toBe(50)
    })
})

describe("budgetColor", () => {
    it("escalates exactly at the warning and danger thresholds", () => {
        expect(budgetColor(74)).toBe("opendc")
        expect(budgetColor(75)).toBe("yellow")
        expect(budgetColor(89)).toBe("yellow")
        expect(budgetColor(90)).toBe("red")
    })
})

describe("formatBudgetUsage", () => {
    it("names an unlimited allowance in words rather than as a symbol", () => {
        expect(formatBudgetUsage(window({ budgetSeconds: "infinity", usedSeconds: 3480 }))).toBe(
            "58 / unlimited simulation min",
        )
    })

    it("rounds partial minutes up so consumed time is never understated", () => {
        expect(formatBudgetUsage(window({ usedSeconds: 61, budgetSeconds: 119 }))).toBe("2 / 2 simulation min")
    })
})

describe("formatResetsAt", () => {
    it("omits the weekday for a session budget but keeps it for a longer period", () => {
        expect(formatResetsAt(window({ period: "session" }))).toMatch(/^Resets at \d{2}:\d{2}$/)
        expect(formatResetsAt(window({ period: "week" }))).toMatch(/^Resets \w{3} \d{2}:\d{2}$/)
    })
})
