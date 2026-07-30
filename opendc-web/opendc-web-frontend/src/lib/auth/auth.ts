import { ApiError, apiRequest, problemOf } from "@/lib/api/client"
import type { Account, ApiProblem, Billing } from "@/lib/api/types"
import { type UseQueryResult, useQuery } from "@tanstack/react-query"

export type AuthMode = "developer" | "auth0"

export interface AuthSession {
    authMode: AuthMode
    userName: string
    avatarUrl: string
    email?: string
    account: Account
}

/**
 * Developer mode has no sign-in, so a session is always present. Under auth0 the server answers
 * /me with 401 until the visitor signs in, which is what puts the app behind its gate.
 */
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

interface ServerConfig {
    authMode: AuthMode
}

interface UserProfile {
    displayName: string
    email?: string
    plan: Account["plan"]
    isAdmin: boolean
    projectCount: number
    budgets: Account["budgets"]
}

const AVATAR_URL = "/img/avatar.svg"

export function useAuth(): AuthState {
    const config = useQuery({
        queryKey: ["config"],
        queryFn: () => apiRequest<ServerConfig>("api/v1/config"),
        staleTime: Number.POSITIVE_INFINITY,
    })
    const profile = useQuery({
        queryKey: ["me"],
        queryFn: () => apiRequest<UserProfile>("api/v1/me"),
        enabled: config.data !== undefined,
        retry: false,
    })

    // A failure has to be its own state. Reading "no data yet" as loading would leave the whole
    // application on a spinner for good after one unreachable request.
    const failure = config.error ?? profile.error
    if (failure !== null && !isUnauthorized(profile.error)) {
        return { status: "unavailable", problem: problemOf(failure), retry: () => refetch(config, profile) }
    }
    if (isUnauthorized(profile.error)) return { status: "signedOut" }
    if (config.data === undefined || profile.data === undefined) return { status: "loading" }

    return {
        status: "signedIn",
        session: {
            authMode: config.data.authMode,
            userName: profile.data.displayName,
            avatarUrl: AVATAR_URL,
            ...(profile.data.email === undefined ? {} : { email: profile.data.email }),
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
