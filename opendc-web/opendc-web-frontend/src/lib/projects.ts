import type { Project, ProjectRole } from "@/lib/api/types"
import { type Icon, IconEye, IconHome, IconPencil } from "@tabler/icons-react"
import dayjs from "dayjs"
import isToday from "dayjs/plugin/isToday"
import isYesterday from "dayjs/plugin/isYesterday"
import { create } from "zustand"

dayjs.extend(isToday)
dayjs.extend(isYesterday)

export type ProjectFilterValue = "all" | "own" | "shared"


export function formatUpdatedAt(value: string): string {
    const date = dayjs(value)
    if (date.isToday()) {
        return `Today, ${date.format("HH:mm")}`
    }
    if (date.isYesterday()) {
        return `Yesterday, ${date.format("HH:mm")}`
    }
    return date.format("MMM D, YYYY HH:mm")
}

interface ProjectSearchState {
    query: string
    setQuery: (query: string) => void
}

export const useProjectSearch = create<ProjectSearchState>((set) => ({
    query: "",
    setQuery: (query) => set({ query }),
}))

export function isSearching(query: string): boolean {
    return query.trim() !== ""
}

export function filterProjects(projects: Project[], filter: ProjectFilterValue, search: string): Project[] {
    const query = search.trim().toLowerCase()
    if (query !== "") {
        return projects.filter((project) => project.name.toLowerCase().includes(query))
    }
    if (filter === "own") {
        return projects.filter((project) => project.role === "owner")
    }
    if (filter === "shared") {
        return projects.filter((project) => project.role !== "owner")
    }
    return projects
}

export const sampleProjects: Project[] = [
    {
        id: "1",
        name: "Datacenter Capacity Study",
        role: "owner",
        createdAt: "2026-06-01T10:00:00Z",
        updatedAt: "2026-07-20T14:30:00Z",
    },
    {
        id: "2",
        name: "Carbon-Aware Scheduling",
        role: "editor",
        createdAt: "2026-05-12T09:00:00Z",
        updatedAt: "2026-07-24T08:15:00Z",
    },
    {
        id: "3",
        name: "GPU Cluster Sizing",
        role: "viewer",
        createdAt: "2026-04-03T11:00:00Z",
        updatedAt: "2026-07-10T16:45:00Z",
    },
    {
        id: "4",
        name: "Failure Injection Sandbox",
        role: "owner",
        createdAt: "2026-03-22T13:00:00Z",
        updatedAt: "2026-06-30T12:00:00Z",
    },
]
