import { formatCount } from "@/components/format"
import type {
    AllocationPolicySpec,
    AxisKey,
    CheckpointSpec,
    ExperimentAxes,
    ExportSpec,
    FailureModelSpec,
    WorkloadSpec,
} from "@/lib/experiment/spec"
import { topologyCapacity } from "@/lib/topology/capacity"
import type { ResourceReference, TopologySpec } from "@/lib/topology/spec"

export const AXIS_LABELS: Record<AxisKey, string> = {
    topologies: "Topology",
    workloads: "Workload",
    allocationPolicies: "Scheduler",
    failureModels: "Failure model",
    maxNumFailures: "Failure budget",
    checkpointModels: "Checkpointing",
    exportModels: "Export",
}

export function axisEntryLabels(axes: ExperimentAxes, key: AxisKey): string[] {
    switch (key) {
        case "topologies":
            return axes.topologies.map(topologyLabel)
        case "workloads":
            return axes.workloads.map(workloadLabel)
        case "allocationPolicies":
            return axes.allocationPolicies.map(schedulerLabel)
        case "failureModels":
            return axes.failureModels.map(failureLabel)
        case "maxNumFailures":
            return axes.maxNumFailures.map((value) => `${formatCount(value)} failures`)
        case "checkpointModels":
            return axes.checkpointModels.map(checkpointLabel)
        case "exportModels":
            return axes.exportModels.map(exportLabel)
    }
}

export function topologyLabel(topology: TopologySpec): string {
    const capacity = topologyCapacity(topology)
    const first = topology.clusters[0]
    const name = topology.clusters.length === 1 && first?.name ? first.name : `${topology.clusters.length} clusters`
    return `${name} (${formatCount(capacity.hosts)} hosts)`
}

export function workloadLabel(workload: WorkloadSpec): string {
    if (workload.type === "inline") return `${formatCount(workload.tasks.length)} inline tasks`
    const fraction = workload.sampleFraction
    const name = referenceLabel(workload.source)
    return fraction === undefined || fraction >= 1 ? name : `${name} at ${Math.round(fraction * 100)}%`
}

export function schedulerLabel(policy: AllocationPolicySpec): string {
    if (policy.type === "prefab") return policy.prefabName ?? "default"
    return policy.type
}

export function failureLabel(model: FailureModelSpec): string {
    if (model.type === "none") return "No failures"
    if (model.type === "prefab") return model.prefabName
    if (model.type === "traceBased") return referenceLabel(model.source)
    return "Custom"
}

export function checkpointLabel(checkpoint: CheckpointSpec | null): string {
    if (checkpoint === null) return "No checkpoints"
    return checkpoint.interval === undefined ? "Default interval" : `Every ${checkpoint.interval}`
}

export function exportLabel(model: ExportSpec): string {
    return model.exportInterval === undefined ? "Default interval" : `Every ${model.exportInterval}`
}

function referenceLabel(reference: ResourceReference): string {
    return reference.type === "named" ? reference.name : reference.uri
}
