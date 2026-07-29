import type { Account, BudgetWindow } from "@/lib/api/types"
import { SAMPLE_BILLING, SAMPLE_BUDGETS } from "@/lib/sample/dataset"
import { readWorld } from "@/lib/sample/store"

export function sampleAccount(): Account {
    const now = Date.now()
    const budgets: BudgetWindow[] = SAMPLE_BUDGETS.map((budget) => ({
        period: budget.period,
        usedSeconds: budget.usedSeconds,
        budgetSeconds: budget.budgetSeconds,
        resetsAt: new Date(now - budget.offsetSeconds * 1000).toISOString(),
    }))

    return {
        plan: "education",
        projectCount: readWorld().projects.length,
        budgets,
        billing: SAMPLE_BILLING,
        isAdmin: true,
    }
}
