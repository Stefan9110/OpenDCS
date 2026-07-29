import { z } from "zod"

const quantity = z.union([z.number(), z.string()])

export const POWER_MODEL_TYPES = ["constant", "linear", "square", "cubic", "sqrt", "mse", "asymptotic"] as const

export const resourceReferenceSchema = z.discriminatedUnion("type", [
    z.object({ type: z.literal("named"), name: z.string() }).passthrough(),
    z.object({ type: z.literal("uri"), uri: z.string() }).passthrough(),
])

export const distributionPolicySchema = z.discriminatedUnion("type", [
    z.object({ type: z.literal("maxMinFairness") }).passthrough(),
    z.object({ type: z.literal("bestEffort"), updateIntervalMs: z.number().optional() }).passthrough(),
    z.object({ type: z.literal("equalShare") }).passthrough(),
    z.object({ type: z.literal("firstFit") }).passthrough(),
    z.object({ type: z.literal("fixedShare"), shareRatio: z.number().optional() }).passthrough(),
])

export const virtualizationOverheadSchema = z.discriminatedUnion("type", [
    z.object({ type: z.literal("none") }).passthrough(),
    z.object({ type: z.literal("constant"), percentageOverhead: z.number().nullish() }).passthrough(),
    z.object({ type: z.literal("shareBased") }).passthrough(),
])

export const batteryPolicySchema = z.discriminatedUnion("type", [
    z.object({ type: z.literal("single"), carbonThreshold: z.number() }).passthrough(),
    z.object({ type: z.literal("double"), lowerThreshold: z.number(), upperThreshold: z.number() }).passthrough(),
    z.object({ type: z.literal("runningMean"), startingThreshold: z.number(), windowSize: z.number() }).passthrough(),
    z
        .object({ type: z.literal("runningMeanPlus"), startingThreshold: z.number(), windowSize: z.number() })
        .passthrough(),
    z.object({ type: z.literal("runningMedian"), startingThreshold: z.number(), windowSize: z.number() }).passthrough(),
    z
        .object({ type: z.literal("runningQuartiles"), startingThreshold: z.number(), windowSize: z.number() })
        .passthrough(),
])

export const powerSpecSchema = z
    .object({
        type: z.enum(POWER_MODEL_TYPES).optional(),
        maxPower: quantity,
        idlePower: quantity,
        power: quantity.optional(),
        calibrationFactor: z.number().optional(),
        asymUtil: z.number().optional(),
        dvfs: z.boolean().optional(),
    })
    .passthrough()

export const cpuSpecSchema = z
    .object({
        coreCount: z.number(),
        coreSpeed: quantity,
        count: z.number().optional(),
        vendor: z.string().optional(),
        modelName: z.string().optional(),
        architecture: z.string().optional(),
    })
    .passthrough()

export const memorySpecSchema = z
    .object({
        size: quantity,
        speed: quantity.optional(),
        vendor: z.string().optional(),
        modelName: z.string().optional(),
        architecture: z.string().optional(),
    })
    .passthrough()

export const gpuSpecSchema = z
    .object({
        coreCount: z.number(),
        coreSpeed: quantity,
        count: z.number().optional(),
        memory: quantity.optional(),
        memoryBandwidth: quantity.optional(),
        vendor: z.string().optional(),
        modelName: z.string().optional(),
        architecture: z.string().optional(),
        virtualizationOverhead: virtualizationOverheadSchema.optional(),
    })
    .passthrough()

export const hostSpecSchema = z
    .object({
        name: z.string().optional(),
        count: z.number().optional(),
        cpu: cpuSpecSchema,
        memory: memorySpecSchema,
        gpu: gpuSpecSchema.nullish(),
        cpuPowerModel: powerSpecSchema.optional(),
        gpuPowerModel: powerSpecSchema.optional(),
        cpuDistribution: distributionPolicySchema.optional(),
        gpuDistribution: distributionPolicySchema.optional(),
    })
    .passthrough()

export const powerSourceSpecSchema = z
    .object({
        name: z.string().optional(),
        maxPower: quantity.optional(),
        carbon: resourceReferenceSchema.nullish(),
    })
    .passthrough()

export const batterySpecSchema = z
    .object({
        name: z.string().optional(),
        capacity: z.number(),
        chargingSpeed: z.number(),
        initialCharge: z.number().optional(),
        policy: batteryPolicySchema,
        embodiedCarbon: z.number().optional(),
        expectedLifetime: z.number().optional(),
    })
    .passthrough()

export const clusterSpecSchema = z
    .object({
        name: z.string().optional(),
        count: z.number().optional(),
        hosts: z.array(hostSpecSchema),
        powerSource: powerSourceSpecSchema.optional(),
        battery: batterySpecSchema.nullish(),
    })
    .passthrough()

export const topologySpecSchema = z.object({ clusters: z.array(clusterSpecSchema) }).passthrough()

export type ResourceReference = z.infer<typeof resourceReferenceSchema>
export type DistributionPolicy = z.infer<typeof distributionPolicySchema>
export type VirtualizationOverhead = z.infer<typeof virtualizationOverheadSchema>
export type BatteryPolicy = z.infer<typeof batteryPolicySchema>
export type PowerSpec = z.infer<typeof powerSpecSchema>
export type CpuSpec = z.infer<typeof cpuSpecSchema>
export type MemorySpec = z.infer<typeof memorySpecSchema>
export type GpuSpec = z.infer<typeof gpuSpecSchema>
export type HostSpec = z.infer<typeof hostSpecSchema>
export type PowerSourceSpec = z.infer<typeof powerSourceSpecSchema>
export type BatterySpec = z.infer<typeof batterySpecSchema>
export type ClusterSpec = z.infer<typeof clusterSpecSchema>
export type TopologySpec = z.infer<typeof topologySpecSchema>

export const DEFAULT_CLUSTER_NAME = "Cluster"
export const DEFAULT_HOST_NAME = "Host"
export const DEFAULT_POWER_SOURCE_NAME = "PowerSource"
export const DEFAULT_COUNT = 1
export const DEFAULT_POWER_MODEL_TYPE = "linear"

export function clusterCount(cluster: ClusterSpec): number {
    return cluster.count ?? DEFAULT_COUNT
}

export function hostCount(host: HostSpec): number {
    return host.count ?? DEFAULT_COUNT
}

export function cpuCount(cpu: CpuSpec): number {
    return cpu.count ?? DEFAULT_COUNT
}

export function gpuCount(gpu: GpuSpec): number {
    return gpu.count ?? DEFAULT_COUNT
}

export function clusterName(cluster: ClusterSpec): string {
    return cluster.name ?? DEFAULT_CLUSTER_NAME
}

export function hostName(host: HostSpec): string {
    return host.name ?? DEFAULT_HOST_NAME
}
