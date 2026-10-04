import { ApiError, apiUrl } from "@/lib/api/client"
import type { DeploymentConfig } from "@/lib/api/types"
import { useQuery } from "@tanstack/react-query"

/**
 * How this deployment signs people in. Fetched once, without credentials, since it is what decides
 * how credentials are had at all; one static export then serves every deployment.
 */
export function useDeploymentConfig() {
    return useQuery({
        queryKey: ["config"],
        queryFn: async () => {
            const response = await fetch(apiUrl("api/v1/config"))
            if (!response.ok) {
                throw new ApiError({ status: response.status, title: "Cannot reach OpenDC", issues: [] })
            }
            return (await response.json()) as DeploymentConfig
        },
        staleTime: Number.POSITIVE_INFINITY,
        retry: false,
    })
}
