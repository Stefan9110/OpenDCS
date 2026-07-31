import type { DocumentIssue } from "@/lib/api/types"
import type { ClusterSpec, CpuSpec, HostSpec, PowerSpec, TopologySpec } from "@/lib/topology/spec"
import { hostCount } from "@/lib/topology/spec"
import { type Quantity, type QuantityKind, parseQuantity } from "@/lib/units"

export function validateTopology(topology: TopologySpec): DocumentIssue[] {
    const issues: DocumentIssue[] = []
    if (topology.clusters.length === 0) issues.push({ path: "clusters", message: "must not be empty" })
    topology.clusters.forEach((cluster, index) => issues.push(...validateCluster(cluster, `clusters[${index}]`)))
    return issues
}

export function validateCluster(cluster: ClusterSpec, path: string): DocumentIssue[] {
    const issues: DocumentIssue[] = []
    if (cluster.hosts.length === 0) issues.push({ path: `${path}.hosts`, message: "must not be empty" })
    cluster.hosts.forEach((host, index) => issues.push(...validateHost(host, `${path}.hosts[${index}]`)))
    if (cluster.powerSource?.maxPower !== undefined) {
        issues.push(...measurement("power", cluster.powerSource.maxPower, `${path}.powerSource.maxPower`))
    }
    return issues
}

export function validateHost(host: HostSpec, path: string): DocumentIssue[] {
    const issues: DocumentIssue[] = []
    if (hostCount(host) <= 0) issues.push({ path: `${path}.count`, message: "must be > 0" })
    issues.push(...validateCpu(host.cpu, `${path}.cpu`))
    issues.push(...measurement("dataSize", host.memory.size, `${path}.memory.size`))
    if (host.cpuPowerModel) issues.push(...validatePowerSpec(host.cpuPowerModel, `${path}.cpuPowerModel`))
    if (host.gpu && host.gpuPowerModel) {
        issues.push(...validatePowerSpec(host.gpuPowerModel, `${path}.gpuPowerModel`))
    }
    return issues
}

function validateCpu(cpu: CpuSpec, path: string): DocumentIssue[] {
    const issues: DocumentIssue[] = []
    if (cpu.coreCount <= 0) issues.push({ path: `${path}.coreCount`, message: "must be > 0" })
    if ((cpu.count ?? 1) <= 0) issues.push({ path: `${path}.count`, message: "must be > 0" })
    issues.push(...measurement("frequency", cpu.coreSpeed, `${path}.coreSpeed`))
    return issues
}

function validatePowerSpec(power: PowerSpec, path: string): DocumentIssue[] {
    const issues: DocumentIssue[] = []
    const max = parseQuantity("power", power.maxPower)
    const idle = parseQuantity("power", power.idlePower)
    issues.push(...measurement("power", power.maxPower, `${path}.maxPower`))
    issues.push(...measurement("power", power.idlePower, `${path}.idlePower`))
    if (max.status === "ok" && idle.status === "ok" && max.base < idle.base) {
        issues.push({ path: `${path}.maxPower`, message: "must be >= idlePower" })
    }
    if (power.calibrationFactor !== undefined && power.calibrationFactor <= 0) {
        issues.push({ path: `${path}.calibrationFactor`, message: "must be > 0" })
    }
    return issues
}

const QUANTITY_LABEL: Record<QuantityKind, string> = {
    frequency: "frequency",
    dataSize: "data size",
    dataRate: "data rate",
    power: "power",
    time: "duration",
}

function measurement(kind: QuantityKind, value: Quantity, path: string): DocumentIssue[] {
    const parsed = parseQuantity(kind, value)
    if (parsed.status === "invalid") return [{ path, message: `must be a valid ${QUANTITY_LABEL[kind]}` }]
    if (parsed.status === "unspecified") return [{ path, message: "must not be negative" }]
    return []
}

export function issuesUnder(issues: DocumentIssue[], prefix: string): DocumentIssue[] {
    return issues.filter((issue) => issue.path === prefix || issue.path.startsWith(`${prefix}.`))
}
