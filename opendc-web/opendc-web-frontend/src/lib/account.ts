import type {Account, BudgetWindow} from "@/lib/api/types"
import {sampleProjects} from "@/lib/projects"
import dayjs from "dayjs"

const euro = new Intl.NumberFormat("en-IE", {style: "currency", currency: "EUR"})


export function budgetPercent({usedSeconds, budgetSeconds}: BudgetWindow): number {
    if (budgetSeconds === "infinity") return 0
    if (budgetSeconds <= 0) return 100
    return Math.min(100, Math.round((usedSeconds / budgetSeconds) * 100))
}

export function budgetColor(percent: number): string {
    if (percent >= 90) return "red"
    if (percent >= 75) return "yellow"
    return "opendc"
}

export function formatBudgetUsage({usedSeconds, budgetSeconds}: BudgetWindow): string {
    let remainingSeconds: string
    if (budgetSeconds === "infinity")
        remainingSeconds = "∞"
    else
        remainingSeconds = simulationMinutes(budgetSeconds).toString()

    return `${simulationMinutes(usedSeconds)} / ${remainingSeconds} simulation min`
}

function simulationMinutes(seconds: number): number {
    return Math.ceil(seconds / 60)
}

export function formatResetsAt({period, resetsAt}: BudgetWindow): string {
    const pattern = period === "session" ? "[at] HH:mm" : "ddd HH:mm"
    return `Resets ${dayjs(resetsAt).format(pattern)}`
}

export function formatBillingDate(date: string): string {
    return dayjs(date).format("MMM D, YYYY")
}

export function formatAmount(amountEur: number): string {
    return euro.format(amountEur)
}

export const sampleAccount: Account = {
    plan: "education",
    projectCount: sampleProjects.length,
    budgets: [
        {
            period: "session",
            usedSeconds: 3480,
            budgetSeconds: "infinity",
            resetsAt: dayjs().add(2, "hour").add(14, "minute").toISOString(),
        },
        {
            period: "week",
            usedSeconds: 24480,
            budgetSeconds: 36000,
            resetsAt: dayjs().add(3, "day").toISOString(),
        },
    ],
    billing: {
        renewsAt: "2026-08-21T00:00:00Z",
        paymentMethod: "Visa ending in 4242",
        invoices: [
            {id: "2026-07", issuedAt: "2026-07-21T00:00:00Z", amountEur: 49, paid: true},
            {id: "2026-06", issuedAt: "2026-06-21T00:00:00Z", amountEur: 49, paid: true},
            {id: "2026-05", issuedAt: "2026-05-21T00:00:00Z", amountEur: 29, paid: true},
        ],
    },
    isAdmin: true
}
