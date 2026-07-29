import type { ExperimentSpec } from "@/lib/experiment/spec"
import type { ClusterSpec, HostSpec, TopologySpec } from "@/lib/topology/spec"

export interface HostTemplate {
    id: string
    label: string
    group: string
    host: HostSpec
}

const EPYC: HostSpec = {
    name: "Compute node",
    cpu: { coreCount: 32, coreSpeed: "3.2 GHz", count: 2, vendor: "AMD", modelName: "EPYC 7502", architecture: "Zen2" },
    memory: { size: "256 GiB", speed: "3.2 GHz", vendor: "Samsung", modelName: "DDR4-3200" },
    cpuPowerModel: { type: "linear", maxPower: "400 Watts", idlePower: "120 Watts" },
}

const XEON: HostSpec = {
    name: "General purpose node",
    cpu: {
        coreCount: 20,
        coreSpeed: "2.5 GHz",
        count: 2,
        vendor: "Intel",
        modelName: "Xeon Gold 6248",
        architecture: "Cascade Lake",
    },
    memory: { size: "192 GiB", speed: "2.933 GHz" },
    cpuPowerModel: { type: "square", maxPower: "300 Watts", idlePower: "90 Watts" },
}

const MEMORY_NODE: HostSpec = {
    name: "Memory optimised node",
    cpu: { coreCount: 24, coreSpeed: "2.9 GHz", count: 2, vendor: "Intel", modelName: "Xeon Gold 6248R" },
    memory: { size: "1 TiB", speed: "2.933 GHz" },
    cpuPowerModel: { type: "linear", maxPower: "350 Watts", idlePower: "140 Watts" },
}

const GPU_NODE: HostSpec = {
    name: "GPU node",
    cpu: { coreCount: 64, coreSpeed: "2.6 GHz", count: 2, vendor: "AMD", modelName: "EPYC 7742" },
    memory: { size: "512 GiB" },
    gpu: {
        coreCount: 6912,
        coreSpeed: "1.4 GHz",
        count: 4,
        memory: "40 GiB",
        memoryBandwidth: "1600 GBps",
        vendor: "NVIDIA",
        modelName: "A100",
        architecture: "Ampere",
        virtualizationOverhead: { type: "constant", percentageOverhead: 5 },
    },
    cpuPowerModel: { type: "square", maxPower: "500 Watts", idlePower: "150 Watts" },
    gpuPowerModel: { type: "cubic", maxPower: "400 Watts", idlePower: "100 Watts" },
}

const EDGE_NODE: HostSpec = {
    name: "Edge node",
    cpu: { coreCount: 8, coreSpeed: "3.7 GHz", vendor: "Intel", modelName: "Xeon E-2288G" },
    memory: { size: "32 GiB" },
    cpuPowerModel: { type: "linear", maxPower: "95 Watts", idlePower: "25 Watts" },
}

export const HOST_TEMPLATES: HostTemplate[] = [
    { id: "compute-epyc", label: "Compute (2x EPYC 7502, 256 GiB)", group: "Compute", host: EPYC },
    { id: "compute-xeon", label: "General purpose (2x Xeon 6248, 192 GiB)", group: "Compute", host: XEON },
    { id: "memory-node", label: "Memory optimised (1 TiB)", group: "Compute", host: MEMORY_NODE },
    { id: "gpu-a100", label: "GPU (4x A100 40 GiB)", group: "Accelerated", host: GPU_NODE },
    { id: "edge-node", label: "Edge (8 core, 32 GiB)", group: "Edge", host: EDGE_NODE },
]

function cluster(name: string, hosts: HostSpec[], maxPower: string, extra: Partial<ClusterSpec> = {}): ClusterSpec {
    return { name, hosts, powerSource: { name: "grid", maxPower }, ...extra }
}

function sized(host: HostSpec, count: number, name?: string): HostSpec {
    return name === undefined ? { ...host, count } : { ...host, count, name }
}

const PRODUCTION: TopologySpec = {
    clusters: [
        cluster("web-tier", [sized(XEON, 16)], "12 kWatts"),
        cluster("batch-tier", [sized(EPYC, 32)], "24 kWatts"),
        cluster("database-tier", [sized(MEMORY_NODE, 8)], "8 kWatts"),
    ],
}

const GPU_EXPANSION: TopologySpec = {
    clusters: [
        cluster("training", [sized(GPU_NODE, 8)], "16 kWatts", {
            battery: {
                name: "training-battery",
                capacity: 200,
                chargingSpeed: 25_000,
                initialCharge: 50,
                policy: { type: "runningMean", startingThreshold: 150, windowSize: 24 },
                embodiedCarbon: 4500,
                expectedLifetime: 10,
            },
        }),
        cluster("inference", [sized(EPYC, 24)], "18 kWatts"),
    ],
}

const CARBON_PILOT: TopologySpec = {
    clusters: [
        cluster("nl-zone", [sized(EPYC, 12), sized(GPU_NODE, 2)], "20 kWatts", {
            powerSource: {
                name: "nl-grid",
                maxPower: "20 kWatts",
                carbon: { type: "named", name: "carbon_traces/NL_2021-2024.parquet" },
            },
        }),
    ],
}

const EDGE_SANDBOX: TopologySpec = {
    clusters: [cluster("edge-site", [sized(EDGE_NODE, 4)], "1 kWatts")],
}

export interface SeedTopology {
    projectIndex: number
    name: string
    topology: TopologySpec
}

export const SEED_PROJECTS = [
    "Datacenter Capacity Study",
    "Carbon-Aware Scheduling",
    "GPU Cluster Sizing",
    "Failure Injection Sandbox",
] as const

export const SEED_TOPOLOGIES: SeedTopology[] = [
    { projectIndex: 0, name: "Production baseline", topology: PRODUCTION },
    { projectIndex: 0, name: "Edge sandbox", topology: EDGE_SANDBOX },
    { projectIndex: 1, name: "Carbon-aware pilot", topology: CARBON_PILOT },
    { projectIndex: 2, name: "GPU expansion", topology: GPU_EXPANSION },
    { projectIndex: 3, name: "Edge sandbox", topology: EDGE_SANDBOX },
]

function workload(name: string) {
    return { type: "trace", source: { type: "named", name } } as const
}

export interface SeedExperiment {
    projectIndex: number
    name: string
    topologyNames: string[]
    submittedSecondsAgo: number
    spec: {
        workloads: ExperimentSpec["workloads"]
        allocationPolicies?: ExperimentSpec["allocationPolicies"]
        failureModels?: ExperimentSpec["failureModels"]
        maxNumFailures?: number[]
        runs?: number
        initialSeed?: number
    }
}

export const SEED_EXPERIMENTS: SeedExperiment[] = [
    {
        projectIndex: 0,
        name: "Baseline vs scaled batch tier",
        topologyNames: ["Production baseline", "Edge sandbox"],
        submittedSecondsAgo: 40,
        spec: {
            workloads: [workload("bitbrains-small"), workload("azure-2019")],
            allocationPolicies: [
                { type: "prefab", prefabName: "Mem" },
                { type: "prefab", prefabName: "CoreMem" },
                { type: "prefab", prefabName: "ActiveServers" },
            ],
            runs: 2,
            initialSeed: 0,
        },
    },
    {
        projectIndex: 0,
        name: "Failure sensitivity sweep",
        topologyNames: ["Production baseline"],
        submittedSecondsAgo: 5_400,
        spec: {
            workloads: [workload("bitbrains-small")],
            failureModels: [{ type: "none" }, { type: "prefab", prefabName: "G5k06Exp" }],
            maxNumFailures: [5, 10, 20],
            runs: 4,
        },
    },
    {
        projectIndex: 0,
        name: "Scheduler comparison draft",
        topologyNames: ["Production baseline"],
        submittedSecondsAgo: -1,
        spec: {
            workloads: [workload("bitbrains-small")],
            allocationPolicies: [
                { type: "prefab", prefabName: "Mem" },
                { type: "prefab", prefabName: "Random" },
            ],
        },
    },
    {
        projectIndex: 2,
        name: "A100 capacity planning",
        topologyNames: ["GPU expansion"],
        submittedSecondsAgo: 900,
        spec: { workloads: [workload("azure-2019")], runs: 2 },
    },
]

export const SCHEDULER_CATALOG = [
    "Mem",
    "MemInv",
    "CoreMem",
    "CoreMemInv",
    "ActiveServers",
    "ActiveServersInv",
    "ProvisionedCores",
    "ProvisionedCoresInv",
    "Random",
    "TaskNumMemorizing",
    "Timeshift",
    "ProvisionedCpuGpuCores",
    "ProvisionedCpuGpuCoresInv",
    "GpuTaskMemorizing",
]

export const FAILURE_PREFAB_CATALOG = [
    "G5k06Exp",
    "G5k06Wbl",
    "Lanl05Exp",
    "Lanl05Wbl",
    "Ldns04Exp",
    "Microsoft99Exp",
    "Nd07cpuExp",
    "Overnet03Exp",
    "Pl05Exp",
    "Skype06Exp",
    "Websites02Exp",
]

export const POWER_MODEL_CATALOG = ["constant", "linear", "square", "cubic", "sqrt", "mse", "asymptotic"]

export const BATTERY_POLICY_CATALOG = [
    "single",
    "double",
    "runningMean",
    "runningMeanPlus",
    "runningMedian",
    "runningQuartiles",
]

export const POWER_SOURCE_CATALOG = ["grid", "solar", "wind", "hydro", "diesel", "battery"]

export const TRACE_CATALOG = ["bitbrains-small", "bitbrains-large", "azure-2019", "solvinity-short"]

// How much simulated time each trace covers. Real workload traces run for weeks to a year, which is
// what makes result series long enough to need bucketing before they reach a chart.
export const TRACE_SPAN_DAYS: Record<string, number> = {
    "bitbrains-small": 30,
    "bitbrains-large": 180,
    "azure-2019": 365,
    "solvinity-short": 7,
}

export const DEFAULT_TRACE_SPAN_DAYS = 30

export const SAMPLE_BUDGETS = [
    { period: "session" as const, usedSeconds: 3480, budgetSeconds: "infinity" as const, offsetSeconds: -8040 },
    { period: "week" as const, usedSeconds: 24480, budgetSeconds: 36000, offsetSeconds: -259200 },
]

export const SAMPLE_BILLING = {
    renewsAt: "2026-08-21T00:00:00Z",
    paymentMethod: "Visa ending in 4242",
    invoices: [
        { id: "2026-07", issuedAt: "2026-07-21T00:00:00Z", amountEur: 49, paid: true },
        { id: "2026-06", issuedAt: "2026-06-21T00:00:00Z", amountEur: 49, paid: true },
        { id: "2026-05", issuedAt: "2026-05-21T00:00:00Z", amountEur: 29, paid: true },
    ],
}

const TRACE_NOTES: Record<string, string> = {
    "bitbrains-small": "Distributed datacenter workload from Bitbrains, the usual starting point.",
    "bitbrains-large": "The larger of the two Bitbrains datacenters, with far more concurrent tasks.",
    "azure-2019": "Azure virtual machine workload, long enough to show seasonal behaviour.",
    "solvinity-short": "Business-critical Solvinity workload, short enough to iterate on quickly.",
}

// Ranking descriptions follow the scheduler prefabs in ComputeSchedulers.kt: a filter scheduler
// places each task on the fitting host with the highest weight, and the Inv variants negate it.
export const CATALOG_DESCRIPTIONS: Record<string, string> = {
    Mem: "Places each task on the host with the most free memory, spreading load across the fleet.",
    MemInv: "Places each task on the fitting host with the least free memory, packing hosts tightly.",
    CoreMem: "Ranks free memory per core, so a wide host is not favoured on total capacity alone.",
    CoreMemInv: "Reverses CoreMem, filling the host with the least free memory per core first.",
    ActiveServers: "Prefers the host running the fewest tasks, keeping per-host contention low.",
    ActiveServersInv: "Prefers the host already running the most tasks, so fewer servers stay active.",
    ProvisionedCores: "Prefers the host with the fewest vCPUs provisioned, spreading CPU commitments.",
    ProvisionedCoresInv: "Prefers the host with the most vCPUs provisioned, consolidating commitments.",
    ProvisionedCpuGpuCores: "Ranks free CPU and GPU capacity together, for fleets with accelerators.",
    ProvisionedCpuGpuCoresInv: "Reverses the CPU and GPU ranking, consolidating onto busy accelerated hosts.",
    Random: "Picks uniformly at random among the hosts that fit. The baseline every policy should beat.",
    TaskNumMemorizing: "Keeps hosts ordered by task count so placement stays cheap on a large fleet.",
    GpuTaskMemorizing: "Memorizing placement that tracks GPU occupancy as well as task count.",
    Timeshift: "Defers deferrable tasks to a better moment, judged over a week of carbon history.",

    G5k06Exp: "Grid'5000 2006 availability trace, fitted to an exponential distribution.",
    G5k06Wbl: "Grid'5000 2006 availability trace, fitted to a Weibull distribution.",
    Lanl05Exp: "Los Alamos National Laboratory 2005 availability trace, fitted to an exponential distribution.",
    Lanl05Wbl: "Los Alamos National Laboratory 2005 availability trace, fitted to a Weibull distribution.",
    Ldns04Exp: "DNS server availability trace from 2004, fitted to an exponential distribution.",
    Microsoft99Exp: "Microsoft 1999 desktop grid availability trace, fitted to an exponential distribution.",
    Nd07cpuExp: "Notre Dame 2007 CPU availability trace, fitted to an exponential distribution.",
    Overnet03Exp: "Overnet 2003 peer-to-peer availability trace, fitted to an exponential distribution.",
    Pl05Exp: "PlanetLab 2005 node availability trace, fitted to an exponential distribution.",
    Skype06Exp: "Skype 2006 supernode availability trace, fitted to an exponential distribution.",
    Websites02Exp: "Web server availability trace from 2002, fitted to an exponential distribution.",

    single: "Discharges while carbon intensity is at or above the threshold, charges below it.",
    double: "Discharges above the upper threshold, charges below the lower one, idles in between.",
    runningMean: "Compares carbon intensity against the mean of the last N samples.",
    runningMeanPlus: "Like running mean, but keeps charging until full to avoid rapid switching.",
    runningMedian: "Compares carbon intensity against the median of the last N samples.",
    runningQuartiles: "Compares carbon intensity against the quartiles of the last N samples.",

    constant: "Draws a fixed amount of power whatever the load.",
    linear: "Power rises in a straight line from idle to peak as utilisation grows.",
    square: "Power rises with the square of utilisation, so load costs more at the top end.",
    cubic: "Power rises with the cube of utilisation, punishing high load hardest.",
    sqrt: "Power rises with the square root of utilisation, so early load costs most.",
    mse: "Interpolated curve tuned by the calibration factor.",
    asymptotic: "Approaches peak power gradually, shaped by the asymptotic utilisation point.",

    grid: "Public electricity grid, the default supply.",
    solar: "On-site photovoltaic generation.",
    wind: "On-site or contracted wind generation.",
    hydro: "Hydroelectric supply.",
    diesel: "Backup diesel generation.",
    battery: "Supplied from stored energy.",

    ...Object.fromEntries(
        Object.entries(TRACE_NOTES).map(([name, note]) => [
            name,
            `${note} Covers ${TRACE_SPAN_DAYS[name] ?? DEFAULT_TRACE_SPAN_DAYS} simulated days.`,
        ]),
    ),
}
