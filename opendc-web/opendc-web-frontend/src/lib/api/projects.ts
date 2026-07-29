import type { Project } from "@/lib/api/types"
import { createProject, deleteProject, getProject, listProjects, renameProject } from "@/lib/sample/resources"
import { resetWorld } from "@/lib/sample/store"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

export const projectKeys = {
    all: ["projects"] as const,
    detail: (projectId: number) => ["projects", projectId] as const,
}

export function useProjects() {
    return useQuery({ queryKey: projectKeys.all, queryFn: async (): Promise<Project[]> => listProjects() })
}

export function useProject(projectId: number) {
    return useQuery({
        queryKey: projectKeys.detail(projectId),
        queryFn: async (): Promise<Project> => getProject(projectId),
        retry: false,
    })
}

export function useCreateProject() {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: async (name: string) => createProject(name),
        onSuccess: () => queryClient.invalidateQueries({ queryKey: projectKeys.all }),
    })
}

export function useRenameProject(projectId: number) {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: async (name: string) => renameProject(projectId, name),
        onSuccess: () => queryClient.invalidateQueries(),
    })
}

export function useDeleteProject() {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: async (projectId: number) => deleteProject(projectId),
        onSuccess: () => queryClient.invalidateQueries(),
    })
}

export function useResetSampleData() {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: async () => resetWorld(),
        onSuccess: () => queryClient.invalidateQueries(),
    })
}
