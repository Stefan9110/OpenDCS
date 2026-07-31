import { ApiError, apiRequest, problemOf } from "@/lib/api/client"
import type { Account, ApiProblem, Billing } from "@/lib/api/types"
import { type UseQueryResult, useQuery } from "@tanstack/react-query"

export interface AuthSession {
    userName: string
    avatarUrl: string
    email?: string
    handle: string
    account: Account
}

export type AuthState =
    | { status: "loading" }
    | { status: "unavailable"; problem: ApiProblem; retry: () => void }
    | { status: "signedOut" }
    | { status: "signedIn"; session: AuthSession }

function isUnauthorized(error: unknown): boolean {
    return error instanceof ApiError && error.problem.status === 401
}

function refetch(...queries: UseQueryResult[]): void {
    for (const query of queries) {
        void query.refetch()
    }
}

interface UserProfile {
    displayName: string
    handle: string
    plan: Account["plan"]
    isAdmin: boolean
    projectCount: number
    budgets: Account["budgets"]
}

const AVATAR_URL = "/img/avatar.svg"

export function useAuth(): AuthState {
    const profile = useQuery({
        queryKey: ["me"],
        queryFn: () => apiRequest<UserProfile>("api/v1/me"),
        enabled: true,
        retry: false,
    })

    // A failure has to be its own state. Reading "no data yet" as loading would leave the whole
    // application on a spinner for good after one unreachable request.
    const failure = profile.error
    if (failure !== null && !isUnauthorized(profile.error))
        return { status: "unavailable", problem: problemOf(failure), retry: () => refetch(profile) }

    if (isUnauthorized(profile.error)) return { status: "signedOut" }
    if (profile.data === undefined) return { status: "loading" }

    return {
        status: "signedIn",
        session: {
            userName: profile.data.displayName,
            avatarUrl: AVATAR_URL,
            handle: profile.data.handle,
            account: {
                plan: profile.data.plan,
                isAdmin: profile.data.isAdmin,
                projectCount: profile.data.projectCount,
                budgets: profile.data.budgets,
            },
        },
    }
}

export function useBilling(enabled: boolean) {
    return useQuery({
        queryKey: ["me", "billing"],
        queryFn: () => apiRequest<Billing>("api/v1/me/billing"),
        enabled,
    })
}
