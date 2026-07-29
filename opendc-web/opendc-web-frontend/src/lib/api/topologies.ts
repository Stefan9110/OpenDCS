import type { Layout, TopologyTemplate } from "@/lib/api/types"
import {
    createTopology,
    deleteTopology,
    getLayout,
    getTopology,
    listTopologies,
    putLayout,
    replaceTopology,
} from "@/lib/sample/resources"
import type { FloorLayout } from "@/lib/topology/layout"
import type { TopologySpec } from "@/lib/topology/spec"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

export const topologyKeys = {
    list: (projectId: number) => ["projects", projectId, "topologies"] as const,
    detail: (projectId: number, templateId: number) => ["projects", projectId, "topologies", templateId] as const,
    layout: (projectId: number, topologyHash: string) => ["projects", projectId, "layouts", topologyHash] as const,
}

export interface TopologyChange {
    name: string
    topology: TopologySpec
}

export function useTopologies(projectId: number) {
    return useQuery({
        queryKey: topologyKeys.list(projectId),
        queryFn: async (): Promise<TopologyTemplate[]> => listTopologies(projectId),
        retry: false,
    })
}

export function useTopology(projectId: number, templateId: number) {
    return useQuery({
        queryKey: topologyKeys.detail(projectId, templateId),
        queryFn: async (): Promise<TopologyTemplate> => getTopology(projectId, templateId),
        retry: false,
    })
}

export function useLayout(projectId: number, topologyHash: string) {
    return useQuery({
        queryKey: topologyKeys.layout(projectId, topologyHash),
        queryFn: async (): Promise<Layout> => getLayout(projectId, topologyHash),
        enabled: topologyHash !== "",
        retry: false,
    })
}

export function useCreateTopology(projectId: number) {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: async (change: TopologyChange) => createTopology(projectId, change.name, change.topology),
        onSuccess: () => queryClient.invalidateQueries(),
    })
}

export function useDeleteTopology(projectId: number) {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: async (templateId: number) => deleteTopology(projectId, templateId),
        onSuccess: () => queryClient.invalidateQueries(),
    })
}

export interface TopologySave extends TopologyChange {
    templateId: number
    layout: FloorLayout
}

export function useSaveTopology(projectId: number) {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: async (save: TopologySave) => {
            const template = replaceTopology(projectId, save.templateId, {
                name: save.name,
                topology: save.topology,
            })
            return { template, layout: putLayout(projectId, template.topologyHash, save.layout) }
        },
        // Saving changes the content hash, so the layout query key changes with it. Seed both caches
        // instead of invalidating, or the editor would unmount while the new key loads.
        onSuccess: ({ template, layout }) => {
            queryClient.setQueryData(topologyKeys.detail(projectId, template.id), template)
            queryClient.setQueryData(topologyKeys.layout(projectId, template.topologyHash), layout)
            queryClient.invalidateQueries({ queryKey: topologyKeys.list(projectId) })
        },
    })
}
