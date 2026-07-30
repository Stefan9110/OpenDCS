import { apiRequest } from "@/lib/api/client"
import type { CatalogEntry, CatalogName, HostTemplate } from "@/lib/api/types"
import { useQuery } from "@tanstack/react-query"

export const catalogKeys = {
    detail: (catalog: CatalogName | "traces" | "host-templates") => ["catalogs", catalog] as const,
}

interface TraceSummary {
    slug: string
}

export function useCatalog(catalog: CatalogName | "traces") {
    return useQuery({
        queryKey: catalogKeys.detail(catalog),
        queryFn: async (): Promise<CatalogEntry[]> => {
            if (catalog === "traces") {
                const traces = await apiRequest<TraceSummary[]>("api/v1/traces")
                return traces.map((trace) => ({ id: trace.slug, label: trace.slug, group: "traces" }))
            }
            return apiRequest<CatalogEntry[]>(`api/v1/catalogs/${catalog}`)
        },
        staleTime: Number.POSITIVE_INFINITY,
    })
}

export function useHostTemplates() {
    return useQuery({
        queryKey: catalogKeys.detail("host-templates"),
        queryFn: () => apiRequest<HostTemplate[]>("api/v1/catalogs/host-templates"),
        staleTime: Number.POSITIVE_INFINITY,
    })
}
