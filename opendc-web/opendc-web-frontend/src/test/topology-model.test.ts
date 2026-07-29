import {
    clusterPowerBudget,
    clusterPowerHeadroom,
    isOverBudget,
    topologyCapacity,
    topologyPowerBudget,
} from "@/lib/topology/capacity"
import type { ClusterSpec, HostSpec, TopologySpec } from "@/lib/topology/spec"
import { issuesUnder, validateTopology } from "@/lib/topology/validation"
import { describe, expect, it } from "vitest"

function host(overrides: Partial<HostSpec> = {}): HostSpec {
    return {
        cpu: { coreCount: 32, coreSpeed: "3.2 GHz" },
        memory: { size: "128 GiB" },
        cpuPowerModel: { maxPower: "400 Watts", idlePower: "120 Watts" },
        ...overrides,
    }
}

function cluster(overrides: Partial<ClusterSpec> = {}): ClusterSpec {
    return { name: "compute", hosts: [host()], ...overrides }
}

function topology(clusters: ClusterSpec[]): TopologySpec {
    return { clusters }
}

function paths(spec: TopologySpec): string[] {
    return validateTopology(spec).map((issue) => issue.path)
}

describe("validateTopology", () => {
    it("accepts a well formed topology", () => {
        expect(validateTopology(topology([cluster()]))).toEqual([])
    })

    it("rejects a topology with no clusters", () => {
        expect(validateTopology(topology([]))).toEqual([{ path: "clusters", message: "must not be empty" }])
    })

    it("rejects a cluster with no hosts", () => {
        expect(validateTopology(topology([cluster({ hosts: [] })]))).toContainEqual({
            path: "clusters[0].hosts",
            message: "must not be empty",
        })
    })

    it("reports indexed paths that match the backend error envelope", () => {
        const spec = topology([cluster(), cluster({ hosts: [host(), host({ cpu: { coreCount: 0, coreSpeed: 1 } })] })])
        expect(paths(spec)).toContain("clusters[1].hosts[1].cpu.coreCount")
    })

    it("rejects non positive counts", () => {
        expect(paths(topology([cluster({ hosts: [host({ count: 0 })] })]))).toContain("clusters[0].hosts[0].count")
        expect(
            paths(topology([cluster({ hosts: [host({ cpu: { coreCount: 4, coreSpeed: 1, count: 0 } })] })])),
        ).toContain("clusters[0].hosts[0].cpu.count")
    })

    it("rejects a power model whose peak is below its idle draw", () => {
        const spec = topology([
            cluster({ hosts: [host({ cpuPowerModel: { maxPower: "100 W", idlePower: "200 W" } })] }),
        ])
        expect(validateTopology(spec)).toContainEqual({
            path: "clusters[0].hosts[0].cpuPowerModel.maxPower",
            message: "must be >= idlePower",
        })
    })

    it("only validates the gpu power model when the host actually has a gpu", () => {
        const broken = { maxPower: "10 W", idlePower: "90 W" }
        const withoutGpu = topology([cluster({ hosts: [host({ gpuPowerModel: broken })] })])
        expect(validateTopology(withoutGpu)).toEqual([])

        const withGpu = topology([
            cluster({ hosts: [host({ gpu: { coreCount: 6912, coreSpeed: "1.4 GHz" }, gpuPowerModel: broken })] }),
        ])
        expect(paths(withGpu)).toContain("clusters[0].hosts[0].gpuPowerModel.maxPower")
    })

    it("rejects quantities the backend cannot deserialize", () => {
        const spec = topology([cluster({ hosts: [host({ cpu: { coreCount: 4, coreSpeed: "3.2 parsecs" } })] })])
        expect(validateTopology(spec)).toContainEqual({
            path: "clusters[0].hosts[0].cpu.coreSpeed",
            message: "must be a valid frequency",
        })
    })

    it("rejects a negative measurement where the backend has no unset sentinel", () => {
        const spec = topology([cluster({ hosts: [host({ memory: { size: -1 } })] })])
        expect(validateTopology(spec)).toContainEqual({
            path: "clusters[0].hosts[0].memory.size",
            message: "must not be negative",
        })
    })
})

describe("issuesUnder", () => {
    it("does not confuse a prefix with a longer index sharing its digits", () => {
        const issues = [
            { path: "clusters[1].hosts", message: "a" },
            { path: "clusters[10].hosts", message: "b" },
            { path: "clusters[1]", message: "c" },
        ]
        expect(issuesUnder(issues, "clusters[1]").map((issue) => issue.message)).toEqual(["a", "c"])
    })
})

describe("topologyCapacity", () => {
    it("multiplies cluster count, host count and cpu count together", () => {
        const spec = topology([
            cluster({
                count: 3,
                hosts: [host({ count: 4, cpu: { coreCount: 32, coreSpeed: "3 GHz", count: 2 } })],
            }),
        ])
        const capacity = topologyCapacity(spec)
        expect(capacity.hosts).toBe(12)
        expect(capacity.cores).toBe(3 * 4 * 2 * 32)
    })

    it("sums memory across host groups in MiB", () => {
        const spec = topology([cluster({ hosts: [host({ count: 2 }), host({ memory: { size: "256 GiB" } })] })])
        expect(topologyCapacity(spec).memoryMiB).toBe(2 * 131_072 + 262_144)
    })

    it("counts gpus only when the host declares one", () => {
        const spec = topology([
            cluster({
                hosts: [host(), host({ count: 2, gpu: { coreCount: 6912, coreSpeed: "1.4 GHz", count: 4 } })],
            }),
        ])
        expect(topologyCapacity(spec).gpus).toBe(8)
    })

    it("excludes the gpu power model from peak draw when the host has no gpu", () => {
        const gpuPowerModel = { maxPower: "400 W", idlePower: "100 W" }
        const withoutGpu = topology([cluster({ hosts: [host({ gpuPowerModel })] })])
        expect(topologyCapacity(withoutGpu).peakPowerW).toBe(400)

        const withGpu = topology([cluster({ hosts: [host({ gpu: { coreCount: 1, coreSpeed: 1 }, gpuPowerModel })] })])
        expect(topologyCapacity(withGpu).peakPowerW).toBe(800)
    })
})

describe("power budget", () => {
    it("treats an absent power source as unlimited rather than as zero", () => {
        expect(clusterPowerBudget(cluster())).toEqual({ status: "unlimited" })
        expect(isOverBudget(clusterPowerHeadroom(cluster()))).toBe(false)
    })

    it("scales a declared budget by the cluster count", () => {
        const spec = cluster({ count: 2, powerSource: { name: "grid", maxPower: "50 kWatts" } })
        expect(clusterPowerBudget(spec)).toEqual({ status: "limited", watts: 100_000 })
    })

    it("flags a cluster drawing more than its power source can supply", () => {
        const spec = cluster({
            hosts: [host({ count: 10, cpuPowerModel: { maxPower: "400 W", idlePower: "100 W" } })],
            powerSource: { maxPower: "1 kW" },
        })
        expect(isOverBudget(clusterPowerHeadroom(spec))).toBe(true)
    })

    it("reports the whole topology as unlimited when any single cluster is", () => {
        const spec = topology([cluster({ powerSource: { maxPower: "50 kW" } }), cluster()])
        expect(topologyPowerBudget(spec)).toEqual({ status: "unlimited" })
    })
})
