import { ApiError, apiRequest, problemOf } from "@/lib/api/client"
import type { Account, ApiProblem, Billing, Handle, UserProfile } from "@/lib/api/types"
import { useQuery } from "@tanstack/react-query"

export interface AuthSession {
    userName: string
    avatarUrl: string
    handle: Handle
    account: Account
}

export type AuthState =
    | { status: "loading" }
    | { status: "unavailable"; problem: ApiProblem }
    | { status: "signedOut" }
    | { status: "deactivated" }
    | { status: "needsHandle"; profile: UserProfile }
    | { status: "signedIn"; session: AuthSession }

const AVATAR_URL = "/img/avatar.svg"

/**
 * Where the person stands, read off their profile request. A refusal of /me itself can only mean a
 * deactivated account, which has to be told apart from one that is signed out; anything else that
 * goes wrong is its own state, or the whole app would sit on a spinner after one failed request.
 */
export function authStateOf(profile: UserProfile | undefined, error: unknown): AuthState {
    if (error !== null && error !== undefined) {
        const status = error instanceof ApiError ? error.problem.status : 0
        if (status === 401) return { status: "signedOut" }
        if (status === 403) return { status: "deactivated" }
        return { status: "unavailable", problem: problemOf(error) }
    }
    if (profile === undefined) return { status: "loading" }
    switch (profile.handle.type) {
        case "provisional":
            return { status: "needsHandle", profile }
        case "chosen":
            return {
                status: "signedIn",
                session: {
                    userName: profile.displayName,
                    avatarUrl: AVATAR_URL,
                    handle: profile.handle,
                    account: {
                        plan: profile.plan,
                        isAdmin: profile.isAdmin,
                        projectCount: profile.projectCount,
                        budgets: profile.budgets,
                    },
                },
            }
    }
}

export function useAuth(): AuthState & { retry: () => void } {
    const profile = useQuery({
        queryKey: ["me"],
        queryFn: () => apiRequest<UserProfile>("api/v1/me"),
        retry: false,
    })
    return { ...authStateOf(profile.data, profile.error), retry: () => void profile.refetch() }
}

export function useBilling(enabled: boolean) {
    return useQuery({
        queryKey: ["me", "billing"],
        queryFn: () => apiRequest<Billing>("api/v1/me/billing"),
        enabled,
    })
}
