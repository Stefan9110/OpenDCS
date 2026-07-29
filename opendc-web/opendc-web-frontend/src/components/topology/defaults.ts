import type { ClusterSpec, HostSpec, TopologySpec } from "@/lib/topology/spec"

export const STARTER_HOST: HostSpec = {
    name: "Host",
    count: 8,
    cpu: { coreCount: 16, coreSpeed: "2.6 GHz", count: 2 },
    memory: { size: "64 GiB" },
    cpuPowerModel: { type: "linear", maxPower: "300 Watts", idlePower: "90 Watts" },
}

export function newCluster(index: number, host: HostSpec = STARTER_HOST): ClusterSpec {
    return {
        name: `Cluster ${index}`,
        hosts: [host],
        powerSource: { name: "grid", maxPower: "10 kWatts" },
    }
}

export function newTopology(): TopologySpec {
    return { clusters: [newCluster(1)] }
}
