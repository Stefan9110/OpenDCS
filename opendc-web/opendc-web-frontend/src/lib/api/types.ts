import type { ExperimentSpec } from "@/lib/experiment/spec"
import type { ExperimentState, ProgressReport, ScenarioExecutionState } from "@/lib/experiment/status"
import type { FloorLayout } from "@/lib/topology/layout"
import type { TopologySpec } from "@/lib/topology/spec"

export interface ValidationIssue {
    path: string
    message: string
}

export interface ValidationProblem {
    status: number
    title: string
    detail?: string
    issues: ValidationIssue[]
}

export type ProjectRole = "owner" | "editor" | "viewer"

export interface Project {
    id: number
    name: string
    role: ProjectRole
    createdAt: string
    updatedAt: string
}

export type ExitReason =
    | "ok"
    | "simulationError"
    | "invalidSpec"
    | "oom"
    | "timeout"
    | "walltime"
    | "cancelled"
    | "unknown"

export interface ExitInfo {
    exitCode: number
    reason: ExitReason
    message?: string
}

export interface CostEstimate {
    scenarioCount: number
    estimatedSimulationSeconds: number
    estimatedBudgetSeconds: number
}

export interface Experiment {
    id: number
    projectId: number
    number: number
    name: string
    state: ExperimentState
    spec: ExperimentSpec
    specHash: string
    estimate: CostEstimate
    createdAt: string
    updatedAt: string
    submittedAt?: string
}

export interface ExperimentSummary {
    id: number
    number: number
    name: string
    state: ExperimentState
    scenarioCount: number
    progress: ProgressReport
    createdAt: string
    submittedAt?: string
}

export interface ScenarioStatus {
    scenarioIndex: number
    state: ScenarioExecutionState
    completedTasks: number
    totalTasks: number
    attempt: number
    exitInfo?: ExitInfo
}

export interface ExperimentStatus {
    id: number
    number: number
    name: string
    state: ExperimentState
    completedTasks: number
    totalTasks: number
    scenarioCount: number
    scenarios: ScenarioStatus[]
}

export interface TopologyTemplate {
    id: number
    projectId: number
    number: number
    name: string
    topology: TopologySpec
    topologyHash: string
    createdAt: string
    updatedAt: string
}

export interface Layout {
    topologyHash: string
    layout: FloorLayout
    generated: boolean
    version: number
}

export type CatalogName = "schedulers" | "failure-prefabs" | "power-models" | "battery-policies" | "export-columns"

export interface CatalogEntry {
    id: string
    label: string
    group: string
    description?: string
}

export interface UserAccounting {
    periodEnd: string
    simulationTime: number
    simulationTimeBudget: number
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
