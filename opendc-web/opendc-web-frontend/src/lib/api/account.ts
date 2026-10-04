import { apiRequest } from "@/lib/api/client"
import type { AccessToken, MintedToken, UserProfile } from "@/lib/api/types"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

const tokenKey = ["me", "tokens"] as const

export interface ProfileChange {
    handle: string
    displayName: string
}

export function useUpdateProfile() {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: (change: ProfileChange) =>
            apiRequest<UserProfile>("api/v1/me/profile", { method: "PUT", body: change }),
        onSuccess: (profile) => queryClient.setQueryData(["me"], profile),
    })
}

/** Signs the caller out for good. What the server keeps and drops is said in the modal that asks. */
export function useDeactivateAccount() {
    return useMutation({ mutationFn: () => apiRequest<void>("api/v1/me", { method: "DELETE" }) })
}

export function useAccessTokens(enabled: boolean) {
    return useQuery({
        queryKey: tokenKey,
        queryFn: () => apiRequest<AccessToken[]>("api/v1/me/tokens"),
        enabled,
    })
}

export function useMintToken() {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: (name: string) => apiRequest<MintedToken>("api/v1/me/tokens", { method: "POST", body: { name } }),
        onSuccess: () => queryClient.invalidateQueries({ queryKey: tokenKey }),
    })
}

export function useRevokeToken() {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: (id: string) => apiRequest<void>(`api/v1/me/tokens/${id}`, { method: "DELETE" }),
        onSuccess: () => queryClient.invalidateQueries({ queryKey: tokenKey }),
    })
}
