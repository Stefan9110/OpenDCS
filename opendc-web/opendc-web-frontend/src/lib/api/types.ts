export type ProjectRole = "owner" | "editor" | "viewer"

export interface Project {
    id: string
    name: string
    createdAt: string
    updatedAt: string
    role: ProjectRole
}

export type PlanTier = "free" | "education" | "enterprise"

export type BudgetPeriod = "session" | "week"

export interface BudgetWindow {
    period: BudgetPeriod
    usedSeconds: number
    budgetSeconds: number | "infinity"
    resetsAt: string
}

export interface Invoice {
    id: string
    issuedAt: string
    amountEur: number
    paid: boolean
}

export interface Billing {
    renewsAt: string
    paymentMethod: string
    invoices: Invoice[]
}

export interface Account {
    plan: PlanTier
    projectCount: number
    budgets: BudgetWindow[]
    billing: Billing
    isAdmin: boolean
}
