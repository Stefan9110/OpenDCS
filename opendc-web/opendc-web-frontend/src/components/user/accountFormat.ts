import type { BudgetWindow } from "@/lib/api/types"
import dayjs from "dayjs"

const euro = new Intl.NumberFormat("en-IE", { style: "currency", currency: "EUR" })

export function budgetPercent({ usedSeconds, budgetSeconds }: BudgetWindow): number {
    if (budgetSeconds === "infinity") return 0
    if (budgetSeconds <= 0) return 100
    return Math.min(100, Math.round((usedSeconds / budgetSeconds) * 100))
}

export function budgetColor(percent: number): string {
    if (percent >= 90) return "red"
    if (percent >= 75) return "yellow"
    return "opendc"
}

export function formatBudgetUsage({ usedSeconds, budgetSeconds }: BudgetWindow): string {
    const allowance = budgetSeconds === "infinity" ? "unlimited" : String(simulationMinutes(budgetSeconds))
    return `${simulationMinutes(usedSeconds)} / ${allowance} simulation min`
}

function simulationMinutes(seconds: number): number {
    return Math.ceil(seconds / 60)
}

export function formatResetsAt({ period, resetsAt }: BudgetWindow): string {
    const pattern = period === "session" ? "[at] HH:mm" : "ddd HH:mm"
    return `Resets ${dayjs(resetsAt).format(pattern)}`
}

export function formatBillingDate(date: string): string {
    return dayjs(date).format("MMM D, YYYY")
}

export function formatAmount(amountEur: number): string {
    return euro.format(amountEur)
}
