import type { ValidationProblem } from "@/lib/api/types"
import { config } from "@/lib/config"
import type { ZodError } from "zod"

export type HttpMethod = "GET" | "POST" | "PUT" | "PATCH" | "DELETE"

export interface RequestOptions {
    method?: HttpMethod
    body?: unknown
    token?: string
}

export class ApiError extends Error {
    readonly problem: ValidationProblem

    constructor(problem: ValidationProblem) {
        super(problem.title)
        this.name = "ApiError"
        this.problem = problem
    }
}

export function problemOf(error: unknown): ValidationProblem {
    if (error instanceof ApiError) return error.problem
    const title = error instanceof Error ? error.message : "Something went wrong"
    return { status: 0, title, issues: [] }
}

// The content hash the API stamps on topology and experiment documents. Callers use it to tell
// whether two documents are the same revision without comparing them field by field.
export function documentHash(value: unknown): string {
    return fnv1a(stableStringify(value)).toString(16).padStart(8, "0")
}

function stableStringify(value: unknown): string {
    if (value === null || typeof value !== "object") return JSON.stringify(value) ?? "null"
    if (Array.isArray(value)) return `[${value.map(stableStringify).join(",")}]`
    const entries = Object.entries(value as Record<string, unknown>)
        .filter(([, entry]) => entry !== undefined)
        .sort(([left], [right]) => left.localeCompare(right))
    return `{${entries.map(([name, entry]) => `${JSON.stringify(name)}:${stableStringify(entry)}`).join(",")}}`
}

function fnv1a(value: string): number {
    let result = 2166136261
    for (let index = 0; index < value.length; index++) {
        result ^= value.codePointAt(index) ?? 0
        result = Math.imul(result, 16777619)
    }
    return result >>> 0
}

export function problemFromZod(error: ZodError, title: string): ValidationProblem {
    return {
        status: 400,
        title,
        issues: error.issues.map((issue) => ({ path: pathOf(issue.path), message: issue.message })),
    }
}

function pathOf(segments: Array<string | number>): string {
    return segments.reduce<string>(
        (path, segment) =>
            typeof segment === "number" ? `${path}[${segment}]` : path === "" ? segment : `${path}.${segment}`,
        "",
    )
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
    const { method = "GET", body, token } = options
    const headers: Record<string, string> = { "Content-Type": "application/json" }
    if (token) {
        headers.Authorization = `Bearer ${token}`
    }
    const response = await fetch(`${config.apiBaseUrl}/${path}`, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
    })
    if (!response.ok) {
        throw new ApiError(await problemFrom(response))
    }
    if (response.status === 204) {
        return undefined as T
    }
    return (await response.json()) as T
}

async function problemFrom(response: Response): Promise<ValidationProblem> {
    const fallback: ValidationProblem = {
        status: response.status,
        title: `Request failed with status ${response.status}`,
        issues: [],
    }
    try {
        const payload = (await response.json()) as Partial<ValidationProblem>
        if (typeof payload?.title !== "string") return fallback
        return {
            status: payload.status ?? response.status,
            title: payload.title,
            detail: payload.detail,
            issues: Array.isArray(payload.issues) ? payload.issues : [],
        }
    } catch {
        return fallback
    }
}
