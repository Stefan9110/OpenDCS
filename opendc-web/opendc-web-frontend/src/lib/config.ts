export type AuthMode = "sample" | "anonymous"

export interface AppConfig {
    apiBaseUrl: string
    basePath: string
    authMode: AuthMode
}

export const config: AppConfig = {
    apiBaseUrl: process.env.NEXT_PUBLIC_API_BASE_URL ?? "",
    basePath: process.env.NEXT_BASE_PATH ? `/${process.env.NEXT_BASE_PATH}` : "",
    authMode: process.env.NEXT_PUBLIC_AUTH_MODE === "anonymous" ? "anonymous" : "sample",
}
