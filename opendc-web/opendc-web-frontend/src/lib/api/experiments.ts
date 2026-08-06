import { apiRequest } from "@/lib/api/client"
import type {
    Experiment,
    ExperimentPreview,
    ExperimentStatus,
    ExperimentSummary,
    Id,
    ScenarioStatus,
} from "@/lib/api/types"
import { type ExperimentResults, isLiveResults } from "@/lib/experiment/results"
import type { ExperimentSpec } from "@/lib/experiment/spec"
import { isTerminalExperiment } from "@/lib/experiment/status"
import { type QueryClient, useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

const LIVE_POLL_MS = 2000

// An experiment is addressed by its own id alone, so its cache entries are keyed that way too. The
// project only scopes the list it appears in.
export const experimentKeys = {
    list: (projectId: Id) => ["projects", projectId, "experiments"] as const,
    detail: (experimentId: Id) => ["experiments", experimentId] as const,
    status: (experimentId: Id) => ["experiments", experimentId, "status"] as const,
    results: (experimentId: Id) => ["experiments", experimentId, "results"] as const,
}

export interface ExperimentChange {
    name: string
    spec: ExperimentSpec
}

export function useExperiments(projectId: Id) {
    return useQuery({
        queryKey: experimentKeys.list(projectId),
        queryFn: () => apiRequest<ExperimentSummary[]>(`api/v1/experiments?project=${projectId}`),
        refetchInterval: (query) => (query.state.data?.some(isLive) ? LIVE_POLL_MS : false),
        retry: false,
    })
}

export function useExperiment(experimentId: Id) {
    return useQuery({
        queryKey: experimentKeys.detail(experimentId),
        queryFn: () => apiRequest<Experiment>(`api/v1/experiments/${experimentId}`),
        refetchInterval: (query) => (query.state.data && isLive(query.state.data) ? LIVE_POLL_MS : false),
        retry: false,
    })
}

export function useExperimentStatus(experimentId: Id) {
    return useQuery({
        queryKey: experimentKeys.status(experimentId),
        queryFn: () => apiRequest<ExperimentStatus>(`api/v1/experiments/${experimentId}/status`),
        refetchInterval: (query) => (query.state.data && isLive(query.state.data) ? LIVE_POLL_MS : false),
        retry: false,
    })
}

function isLive(entry: { state: ExperimentSummary["state"] }): boolean {
    return entry.state !== "draft" && !isTerminalExperiment(entry.state)
}

export function useExperimentResults(experimentId: Id) {
    return useQuery({
        queryKey: experimentKeys.results(experimentId),
        queryFn: () => apiRequest<ExperimentResults>(`api/v1/experiments/${experimentId}/results`),
        refetchInterval: (query) => (query.state.data && isLiveResults(query.state.data) ? LIVE_POLL_MS : false),
        retry: false,
    })
}

export function useCreateExperiment(projectId: Id) {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: (change: ExperimentChange) =>
            apiRequest<Experiment>("api/v1/experiments", {
                method: "POST",
                body: { projectId, name: change.name, spec: change.spec },
            }),
        onSuccess: () => queryClient.invalidateQueries({ queryKey: experimentKeys.list(projectId) }),
    })
}

export function useSaveExperimentDraft(experimentId: Id) {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: (change: ExperimentChange) =>
            apiRequest<Experiment>(`api/v1/experiments/${experimentId}`, { method: "PUT", body: change }),
        onSuccess: (experiment) => {
            queryClient.setQueryData(experimentKeys.detail(experimentId), experiment)
            queryClient.invalidateQueries({ queryKey: experimentKeys.list(experiment.projectId) })
        },
    })
}

export function usePreviewExperiment() {
    return useMutation({
        mutationFn: (spec: ExperimentSpec) =>
            apiRequest<ExperimentPreview>("api/v1/experiments/preview", { method: "POST", body: { spec } }),
    })
}

export function useSubmitExperiment(experimentId: Id) {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: () => apiRequest<Experiment>(`api/v1/experiments/${experimentId}/submit`, { method: "POST" }),
        onSuccess: (experiment) => applyExperiment(queryClient, experiment),
    })
}

export function useCancelExperiment(experimentId: Id) {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: () => apiRequest<Experiment>(`api/v1/experiments/${experimentId}/cancel`, { method: "POST" }),
        onSuccess: (experiment) => applyExperiment(queryClient, experiment),
    })
}

export function useRetryScenario(experimentId: Id) {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: (scenarioIndex: number) =>
            apiRequest<ScenarioStatus>(`api/v1/experiments/${experimentId}/scenarios/${scenarioIndex}/retry`, {
                method: "POST",
            }),
        // The detail key is a prefix of the status and results keys, so one invalidation reaches
        // the panel that has to start polling again.
        onSuccess: () => queryClient.invalidateQueries({ queryKey: experimentKeys.detail(experimentId) }),
    })
}

/**
 * Takes the experiment a write returned, and drops what that write invalidated.
 *
 * Status and results have to go: their cached copies still describe a draft, and a query whose data
 * says draft does not poll, so leaving them would show a submitted experiment as one that never
 * started.
 */
function applyExperiment(queryClient: QueryClient, experiment: Experiment): void {
    queryClient.setQueryData(experimentKeys.detail(experiment.id), experiment)
    queryClient.invalidateQueries({ queryKey: experimentKeys.status(experiment.id) })
    queryClient.invalidateQueries({ queryKey: experimentKeys.results(experiment.id) })
    queryClient.invalidateQueries({ queryKey: experimentKeys.list(experiment.projectId) })
}

export function useCloneExperiment(experimentId: Id) {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: () => apiRequest<Experiment>(`api/v1/experiments/${experimentId}/clone`, { method: "POST" }),
        onSuccess: (draft) => queryClient.invalidateQueries({ queryKey: experimentKeys.list(draft.projectId) }),
    })
}

export function useDeleteExperiment(projectId: Id) {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: (experimentId: Id) => apiRequest<void>(`api/v1/experiments/${experimentId}`, { method: "DELETE" }),
        // Drop the deleted experiment's queries instead of invalidating them. Invalidating refetches
        // an experiment that no longer exists, and its 404 reaches the still-mounted page before the
        // caller's redirect does. The detail key is a prefix of the status and results keys, so one
        // removal covers all three.
        onSuccess: (_result, experimentId) => {
            queryClient.removeQueries({ queryKey: experimentKeys.detail(experimentId) })
            queryClient.invalidateQueries({ queryKey: experimentKeys.list(projectId) })
        },
    })
}
