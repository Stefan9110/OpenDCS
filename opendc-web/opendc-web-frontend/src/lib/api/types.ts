import type { ExperimentSpec } from "@/lib/experiment/spec"
import type { ExperimentState, ProgressReport, ScenarioExecutionState } from "@/lib/experiment/status"
import type { FloorLayout } from "@/lib/topology/layout"
import type { HostSpec, TopologySpec } from "@/lib/topology/spec"

export interface DocumentIssue {
    path: string
    message: string
}

export interface ApiProblem {
    status: number
    title: string
    detail?: string
    issues: DocumentIssue[]
}

export type ProjectRole = "owner" | "editor" | "viewer"

// Identifiers are opaque strings issued by the server (uuids). Nothing on the client parses,
// orders or does arithmetic on them; they are only ever passed back.
export type Id = string

export interface Project {
    id: Id
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

export interface ExperimentPreview {
    scenarioCount: number
    estimate: CostEstimate
    issues: DocumentIssue[]
}

export interface Experiment {
    id: Id
    projectId: Id
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
    id: Id
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
    id: Id
    name: string
    state: ExperimentState
    completedTasks: number
    totalTasks: number
    scenarioCount: number
    scenarios: ScenarioStatus[]
}

export interface TopologyTemplate {
    id: Id
    projectId: Id
    name: string
    topology: TopologySpec
    topologyHash: string
    layout?: FloorLayout
    createdAt: string
    updatedAt: string
}

export type CatalogName = "schedulers" | "failure-prefabs" | "power-models" | "battery-policies" | "export-columns"

export interface CatalogEntry {
    id: string
    label: string
    group: string
    description?: string
}

export interface HostTemplate {
    id: string
    label: string
    group: string
    host: HostSpec
}

export type PlanTier = "free" | "education" | "enterprise"

export type BudgetPeriod = "session" | "week"

// Unlimited is a deliberate grant, held by developer mode and by accounts raised by hand, rather
// than the absence of a limit.
export type SimulationCap = { type: "limited"; seconds: number } | { type: "unlimited" }

export interface BudgetWindow {
    period: BudgetPeriod
    usedSeconds: number
    reservedSeconds: number
    cap: SimulationCap
    resetsAt: string
}

export interface Invoice {
    id: string
    issuedAt: string
    amountEur: number
    paid: boolean
}

// Renewal and payment method only exist once a billing provider is wired up; until then the server
// sends neither rather than sending placeholders.
export interface Billing {
    renewsAt?: string
    paymentMethod?: string
    invoices: Invoice[]
}

export interface Account {
    plan: PlanTier
    projectCount: number
    budgets: BudgetWindow[]
    isAdmin: boolean
}
