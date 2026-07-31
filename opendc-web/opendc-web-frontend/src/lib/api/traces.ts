import { ApiError, apiRequest } from "@/lib/api/client"
import type {
    CatalogEntry,
    RegisteredTrace,
    Trace,
    TraceKind,
    TraceKindTables,
    TraceShare,
    UploadPart,
} from "@/lib/api/types"
import { config } from "@/lib/config"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

export const traceKeys = {
    all: ["traces"] as const,
    ofKind: (kind: TraceKind) => ["traces", kind] as const,
    kinds: ["traces", "kinds"] as const,
    shares: (traceId: string) => ["traces", traceId, "shares"] as const,
}

export function useTraces(kind?: TraceKind) {
    return useQuery({
        queryKey: kind ? traceKeys.ofKind(kind) : traceKeys.all,
        queryFn: () => apiRequest<Trace[]>(kind ? `api/v1/traces?kind=${kind}` : "api/v1/traces"),
    })
}

/** The traces of [kind] a document may reference, as picker options. */
export function useTraceOptions(kind: TraceKind) {
    return useQuery({
        queryKey: traceKeys.ofKind(kind),
        queryFn: () => apiRequest<Trace[]>(`api/v1/traces?kind=${kind}`),
        select: (traces: Trace[]): CatalogEntry[] =>
            traces.map((trace) => ({ id: trace.slug, label: trace.slug, group: kind, description: trace.description })),
    })
}

export function useTraceKinds() {
    return useQuery({
        queryKey: traceKeys.kinds,
        queryFn: () => apiRequest<TraceKindTables[]>("api/v1/traces/kinds"),
        staleTime: Number.POSITIVE_INFINITY,
    })
}

/** How far an upload has got, for something that runs long enough to look stuck. */
export interface UploadProgress {
    sent: number
    total: number
}

export interface TraceUpload {
    kind: TraceKind
    name: string
    description: string
    files: Record<string, File>
    onProgress?: (progress: UploadProgress) => void
    /** Stops the transfers and takes the half-made trace back out again. */
    signal?: AbortSignal
}

/**
 * How many transfers run at once, counted across every table rather than per table.
 *
 * One connection to object storage settles at a few megabytes a second whatever the link can
 * actually carry, and measuring eight of them gave eight times the throughput with no sign of
 * flattening out. So this is a floor on how fast a large trace can go, not a ceiling: the number is
 * kept modest because every one of these is a live request the browser is holding open.
 */
const CONCURRENT_PARTS = 8

/** One stretch of one table, and the file to cut it from. */
interface Transfer {
    id: string
    part: UploadPart
    file: File
    table: string
    direct: boolean
}

/**
 * Uploads a trace in the three steps the API asks for: claim the name, put each table where it
 * says, then complete.
 *
 * The bytes go wherever the slots point, which against object storage is straight there. Sending
 * them through this application instead would mean holding a whole trace in the browser's request
 * to an API that has no reason to see it.
 */
export function useUploadTrace() {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: async (upload: TraceUpload): Promise<Trace> => {
            // The sizes go up front because they decide the answer: how a file is cut into parts,
            // and so how many connections may carry it, is settled while the targets are handed out.
            const files = Object.entries(upload.files).map(([table, file]) => ({ table, sizeBytes: file.size }))
            const registered = await apiRequest<RegisteredTrace>("api/v1/traces", {
                method: "POST",
                body: { kind: upload.kind, name: upload.name, description: upload.description || undefined, files },
            })

            const transfers = registered.uploads.flatMap((slot): Transfer[] => {
                const file = upload.files[slot.table]
                if (file === undefined) throw missingFile(slot.table)
                return slot.parts.map((part, index) => ({
                    id: `${slot.table}#${index}`,
                    part,
                    file,
                    table: slot.table,
                    direct: slot.direct,
                }))
            })

            const total = transfers.reduce((bytes, { part }) => bytes + part.length, 0)
            const sent = new Map<string, number>()
            const report = (id: string, bytes: number) => {
                sent.set(id, bytes)
                upload.onProgress?.({ sent: [...sent.values()].reduce((a, b) => a + b, 0), total })
            }

            const stop = new AbortController()
            upload.signal?.addEventListener("abort", () => stop.abort(), { once: true })

            try {
                await inParallel(transfers, CONCURRENT_PARTS, async (transfer) => {
                    try {
                        await putPart(transfer, (bytes) => report(transfer.id, bytes), stop.signal)
                    } catch (error) {
                        stop.abort()
                        throw error
                    }
                })
                return await apiRequest<Trace>(`api/v1/traces/${registered.trace.id}/complete`, { method: "POST" })
            } catch (error) {
                await discard(registered.trace.id)
                throw error
            }
        },
        onSuccess: () => queryClient.invalidateQueries({ queryKey: traceKeys.all }),
    })
}

export function useEditTrace() {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: ({ id, name, description }: { id: string; name?: string; description?: string }) =>
            apiRequest<Trace>(`api/v1/traces/${id}`, { method: "PATCH", body: { name, description } }),
        onSuccess: () => queryClient.invalidateQueries({ queryKey: traceKeys.all }),
    })
}

export function useDeleteTrace() {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: (id: string) => apiRequest<void>(`api/v1/traces/${id}`, { method: "DELETE" }),
        onSuccess: () => queryClient.invalidateQueries({ queryKey: traceKeys.all }),
    })
}

export function useShares(traceId: string, enabled: boolean) {
    return useQuery({
        queryKey: traceKeys.shares(traceId),
        queryFn: () => apiRequest<TraceShare[]>(`api/v1/traces/${traceId}/shares`),
        enabled,
    })
}

export function useShareTrace(traceId: string) {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: (handle: string) =>
            apiRequest<TraceShare>(`api/v1/traces/${traceId}/shares`, { method: "POST", body: { handle } }),
        onSuccess: () => queryClient.invalidateQueries({ queryKey: traceKeys.shares(traceId) }),
    })
}

export function useRevokeShare(traceId: string) {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: (handle: string) =>
            apiRequest<void>(`api/v1/traces/${traceId}/shares/${handle}`, { method: "DELETE" }),
        onSuccess: () => queryClient.invalidateQueries({ queryKey: traceKeys.shares(traceId) }),
    })
}

/** Where the browser should send a table, which for a signed target is nowhere near this API. */
export function downloadUrl(traceId: string): string {
    return `${config.apiBaseUrl}/api/v1/traces/${traceId}/content`
}

/**
 * Sends [transfers], at most [limit] of them at once, taking the next one the moment a slot frees
 * rather than in fixed rounds: parts differ in size only at the tail of a file, but they differ a
 * great deal in how long they take.
 */
async function inParallel(
    transfers: Transfer[],
    limit: number,
    send: (transfer: Transfer) => Promise<void>,
): Promise<void> {
    const queue = [...transfers]
    const worker = async () => {
        for (let transfer = queue.shift(); transfer !== undefined; transfer = queue.shift()) {
            await send(transfer)
        }
    }
    await Promise.all(Array.from({ length: Math.min(limit, queue.length) }, worker))
}

/**
 * Sends one stretch of one table, reporting how much of it has gone.
 *
 * XMLHttpRequest rather than fetch, for the one thing fetch cannot do: say how far an upload has
 * got. A trace of a few hundred megabytes takes minutes, and a spinner that never changes is
 * indistinguishable from one that has hung.
 */
function putPart(transfer: Transfer, onSent: (bytes: number) => void, signal: AbortSignal): Promise<void> {
    const { part, file, table, direct } = transfer
    // A signed target carries its own absolute URL and its own authorization; anything else is a
    // path on this API. No content type is set either way, because a signed target only accepts
    // the headers it was signed for.
    const url = direct ? part.url : `${config.apiBaseUrl}/${part.url}`

    return new Promise((resolve, reject) => {
        const failed = (status: number, message: string) =>
            reject(
                new ApiError({
                    status,
                    title: `Could not upload ${table}`,
                    issues: [{ path: table, message }],
                }),
            )

        if (signal.aborted) {
            failed(0, "the upload was cancelled")
            return
        }

        const request = new XMLHttpRequest()
        request.open("PUT", url)
        // Stopping the transfer is the point: a cancelled upload of half a gigabyte should not
        // carry on to storage in the background after the reader has walked away from it.
        signal.addEventListener("abort", () => request.abort(), { once: true })
        request.upload.addEventListener("progress", (event) => onSent(event.loaded))
        request.addEventListener("load", () => {
            if (request.status < 400) {
                onSent(part.length)
                resolve()
            } else {
                failed(request.status, request.statusText || "the upload was refused")
            }
        })
        // A blocked cross-origin request lands here with nothing to report, which is what a bucket
        // missing its CORS policy looks like from inside the browser.
        request.addEventListener("error", () => failed(0, "could not reach storage"))
        request.addEventListener("abort", () => failed(0, "the upload was cancelled"))
        // Only this part of the file. A slice is a view rather than a copy, so cutting a ten
        // gigabyte trace into parts does not put any of it in memory.
        request.send(file.slice(part.offset, part.offset + part.length))
    })
}

/** Removes a registration whose upload failed, without hiding why the upload failed. */
async function discard(traceId: string): Promise<void> {
    try {
        await apiRequest<void>(`api/v1/traces/${traceId}`, { method: "DELETE" })
    } catch {
        // Nothing useful to do: the caller is about to be told what actually went wrong.
    }
}

function missingFile(table: string): ApiError {
    return new ApiError({
        status: 400,
        title: `Choose a file for ${table}`,
        issues: [{ path: table, message: "is required" }],
    })
}
