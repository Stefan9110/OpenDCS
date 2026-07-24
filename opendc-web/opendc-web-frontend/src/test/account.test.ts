import {
    budgetColor,
    budgetPercent,
    formatBudgetUsage,
    formatMemberSince,
    formatResetsAt,
    planLabel,
} from "@/lib/account"
import type { BudgetPeriod, BudgetWindow } from "@/lib/api/types"
import { describe, expect, it } from "vitest"

function budget(usedSeconds: number, budgetSeconds: number, period: BudgetPeriod = "session"): BudgetWindow {
    return { period, usedSeconds, budgetSeconds, resetsAt: "2026-07-24T14:20:00" }
}

describe("budget percentage", () => {
    it("rounds the consumed share of the allowance", () => {
        expect(budgetPercent(budget(3480, 7200))).toBe(48)
    })

    it("caps overspend at 100", () => {
        expect(budgetPercent(budget(9000, 7200))).toBe(100)
    })

    it("treats an empty allowance as fully consumed", () => {
        expect(budgetPercent(budget(0, 0))).toBe(100)
    })
})

describe("budget colour", () => {
    it("escalates at 75 and 90 percent", () => {
        expect(budgetColor(74)).toBe("opendc")
        expect(budgetColor(75)).toBe("yellow")
        expect(budgetColor(89)).toBe("yellow")
        expect(budgetColor(90)).toBe("red")
    })
})

describe("budget usage formatting", () => {
    it("reports simulation minutes for the session window", () => {
        expect(formatBudgetUsage(budget(3480, 7200))).toBe("58 / 120 min")
    })

    it("stays in simulation minutes for the much larger weekly window", () => {
        expect(formatBudgetUsage(budget(24480, 36000, "week"))).toBe("408 / 600 min")
    })

    it("rounds a part-consumed minute up so spending never reads as zero", () => {
        expect(formatBudgetUsage(budget(1, 7200))).toBe("1 / 120 min")
    })
})

describe("account labels", () => {
    it("shows a clock time for the session window", () => {
        expect(formatResetsAt(budget(0, 7200))).toBe("Resets at 14:20")
    })

    it("shows a weekday for the weekly window", () => {
        expect(formatResetsAt({ ...budget(0, 36000, "week"), resetsAt: "2026-07-27T09:00:00" })).toBe(
            "Resets Mon 09:00",
        )
    })

    it("names the plan tier", () => {
        expect(planLabel("research")).toBe("Research")
    })

    it("shortens the membership date to a month", () => {
        expect(formatMemberSince("2026-03-14T09:00:00")).toBe("Since Mar 2026")
    })
})
