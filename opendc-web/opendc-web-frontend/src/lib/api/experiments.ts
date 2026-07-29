import type { Experiment, ExperimentStatus, ExperimentSummary } from "@/lib/api/types"
import { type ExperimentResults, isLiveResults } from "@/lib/experiment/results"
import type { ExperimentSpec } from "@/lib/experiment/spec"
import { isTerminalExperiment } from "@/lib/experiment/status"
import {
    cancelExperiment,
    cloneExperiment,
    createExperimentDraft,
    deleteExperiment,
    getExperiment,
    getExperimentResults,
    getExperimentStatus,
    listExperiments,
    replaceExperimentDraft,
    submitExperiment,
} from "@/lib/sample/resources"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

const LIVE_POLL_MS = 2000

export const experimentKeys = {
    list: (projectId: number) => ["projects", projectId, "experiments"] as const,
    detail: (projectId: number, experimentId: number) => ["projects", projectId, "experiments", experimentId] as const,
    status: (projectId: number, experimentId: number) =>
        ["projects", projectId, "experiments", experimentId, "status"] as const,
    results: (projectId: number, experimentId: number) =>
        ["projects", projectId, "experiments", experimentId, "results"] as const,
}

export interface ExperimentChange {
    name: string
    spec: ExperimentSpec
}

export function useExperiments(projectId: number) {
    return useQuery({
        queryKey: experimentKeys.list(projectId),
        queryFn: async (): Promise<ExperimentSummary[]> => listExperiments(projectId, Date.now()),
        refetchInterval: (query) => (query.state.data?.some(isLive) ? LIVE_POLL_MS : false),
        retry: false,
    })
}

export function useExperiment(projectId: number, experimentId: number) {
    return useQuery({
        queryKey: experimentKeys.detail(projectId, experimentId),
        queryFn: async (): Promise<Experiment> => getExperiment(projectId, experimentId, Date.now()),
        refetchInterval: (query) => (query.state.data && isLive(query.state.data) ? LIVE_POLL_MS : false),
        retry: false,
    })
}

export function useExperimentStatus(projectId: number, experimentId: number) {
    return useQuery({
        queryKey: experimentKeys.status(projectId, experimentId),
        queryFn: async (): Promise<ExperimentStatus> => getExperimentStatus(projectId, experimentId, Date.now()),
        refetchInterval: (query) => (query.state.data && isLive(query.state.data) ? LIVE_POLL_MS : false),
        retry: false,
    })
}

function isLive(entry: { state: ExperimentSummary["state"] }): boolean {
    return entry.state !== "draft" && !isTerminalExperiment(entry.state)
}

export function useExperimentResults(projectId: number, experimentId: number) {
    return useQuery({
        queryKey: experimentKeys.results(projectId, experimentId),
        queryFn: async (): Promise<ExperimentResults> => getExperimentResults(projectId, experimentId, Date.now()),
        refetchInterval: (query) => (query.state.data && isLiveResults(query.state.data) ? LIVE_POLL_MS : false),
        retry: false,
    })
}

export function useCreateExperiment(projectId: number) {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: async (change: ExperimentChange) => createExperimentDraft(projectId, change.name, change.spec),
        onSuccess: () => queryClient.invalidateQueries(),
    })
}

export function useSaveExperimentDraft(projectId: number, experimentId: number) {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: async (change: ExperimentChange) =>
            replaceExperimentDraft(projectId, experimentId, change, Date.now()),
        onSuccess: () => queryClient.invalidateQueries(),
    })
}

export function useSubmitExperiment(projectId: number) {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: async (experimentId: number) => submitExperiment(projectId, experimentId, Date.now()),
        onSuccess: () => queryClient.invalidateQueries(),
    })
}

export function useCancelExperiment(projectId: number) {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: async (experimentId: number) => cancelExperiment(projectId, experimentId, Date.now()),
        onSuccess: () => queryClient.invalidateQueries(),
    })
}

export function useCloneExperiment(projectId: number) {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: async (experimentId: number) => cloneExperiment(projectId, experimentId),
        onSuccess: () => queryClient.invalidateQueries(),
    })
}

export function useDeleteExperiment(projectId: number) {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: async (experimentId: number) => deleteExperiment(projectId, experimentId),
        onSuccess: () => queryClient.invalidateQueries(),
    })
}
