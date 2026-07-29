import type { CatalogEntry, CatalogName } from "@/lib/api/types"
import {
    BATTERY_POLICY_CATALOG,
    CATALOG_DESCRIPTIONS,
    FAILURE_PREFAB_CATALOG,
    HOST_TEMPLATES,
    type HostTemplate,
    POWER_MODEL_CATALOG,
    POWER_SOURCE_CATALOG,
    SCHEDULER_CATALOG,
    TRACE_CATALOG,
} from "@/lib/sample/dataset"
import { useQuery } from "@tanstack/react-query"

export const catalogKeys = {
    detail: (catalog: CatalogName | "traces" | "power-sources" | "host-templates") => ["catalogs", catalog] as const,
}

const CATALOGS: Record<CatalogName | "traces" | "power-sources", string[]> = {
    schedulers: SCHEDULER_CATALOG,
    "failure-prefabs": FAILURE_PREFAB_CATALOG,
    "power-models": POWER_MODEL_CATALOG,
    "battery-policies": BATTERY_POLICY_CATALOG,
    "export-columns": [],
    traces: TRACE_CATALOG,
    "power-sources": POWER_SOURCE_CATALOG,
}

export function useCatalog(catalog: CatalogName | "traces" | "power-sources") {
    return useQuery({
        queryKey: catalogKeys.detail(catalog),
        queryFn: async (): Promise<CatalogEntry[]> =>
            (CATALOGS[catalog] ?? []).map((id) => ({
                id,
                label: id,
                group: catalog,
                ...(CATALOG_DESCRIPTIONS[id] ? { description: CATALOG_DESCRIPTIONS[id] } : {}),
            })),
        staleTime: Number.POSITIVE_INFINITY,
    })
}

export function useHostTemplates() {
    return useQuery({
        queryKey: catalogKeys.detail("host-templates"),
        queryFn: async (): Promise<HostTemplate[]> => HOST_TEMPLATES,
        staleTime: Number.POSITIVE_INFINITY,
    })
}
