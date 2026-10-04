import { apiRequest, apiUrl } from "@/lib/api/client"
import type { DownloadLink } from "@/lib/api/types"

/**
 * Starts a download the browser streams straight to disk. The server signs a short-lived link for
 * it first, because a plain link cannot carry an access token, and fetching the file into the tab
 * instead would hold all of it in memory. A link refused because there is nothing to download
 * rejects here, before the browser goes anywhere.
 */
export async function startDownload(linkPath: string): Promise<void> {
    const link = await apiRequest<DownloadLink>(linkPath, { method: "POST" })
    window.location.assign(apiUrl(link.url))
}
