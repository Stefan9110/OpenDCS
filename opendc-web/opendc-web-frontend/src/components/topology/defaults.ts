import type {
    BatteryPolicy,
    BatterySpec,
    ClusterSpec,
    DataCenterSpec,
    HostSpec,
    TopologySpec,
} from "@/lib/topology/spec"

export const STARTER_HOST: HostSpec = {
    name: "Host",
    count: 8,
    cpu: { coreCount: 16, coreSpeed: "2.6 GHz", count: 2 },
    memory: { size: "64 GiB" },
    cpuPowerModel: { type: "linear", maxPower: "300 Watts", idlePower: "90 Watts" },
}

export function newCluster(name: string, host: HostSpec = STARTER_HOST): ClusterSpec {
    return { name, hosts: [host] }
}

/** A data center with the simulator's own default supply, which is unlimited. */
export function newDataCenter(name: string): DataCenterSpec {
    return { name, clusters: [] }
}

export function newTopology(): TopologySpec {
    return { datacenters: [{ ...newDataCenter("DC 1"), clusters: [newCluster("Cluster 1")] }] }
}

/** The first of "base 1", "base 2", ... that is not taken, so names stay unique after deletions. */
export function nextName(base: string, taken: string[]): string {
    const used = new Set(taken)
    let index = 1
    while (used.has(`${base} ${index}`)) index++
    return `${base} ${index}`
}

export function starterBattery(): BatterySpec {
    return {
        name: "Battery",
        capacity: 100,
        chargingSpeed: 10_000,
        initialCharge: 0,
        policy: starterBatteryPolicy("single"),
    }
}

/**
 * The policies the editor offers. The simulator still reads runningMedian and runningQuartiles from
 * older documents, which the editor shows, but no longer offers them.
 */
export const CREATABLE_BATTERY_POLICIES = ["single", "double", "runningMean", "runningMeanPlus"] as const

export type CreatableBatteryPolicy = (typeof CREATABLE_BATTERY_POLICIES)[number]

export function isCreatableBatteryPolicy(type: string): type is CreatableBatteryPolicy {
    return (CREATABLE_BATTERY_POLICIES as readonly string[]).includes(type)
}

export function starterBatteryPolicy(type: CreatableBatteryPolicy): BatteryPolicy {
    switch (type) {
        case "single":
            return { type, carbonThreshold: 150 }
        case "double":
            return { type, lowerThreshold: 100, upperThreshold: 200 }
        case "runningMean":
            return { type, startingThreshold: 150, windowSize: 24 }
        case "runningMeanPlus":
            return { type, startingThreshold: 150, windowSize: 24 }
    }
}
