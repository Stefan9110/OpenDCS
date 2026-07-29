"use client"

import { formatMemory, formatPower } from "@/components/format"
import { TILE_INSET, TILE_SIZE, cellOrigin } from "@/components/topology/canvas/geometry"
import type { CanvasPalette } from "@/components/topology/canvas/palette"
import type { HardwareIcons } from "@/components/topology/canvas/useHardwareIcons"
import { clusterCapacity, clusterPowerHeadroom, isOverBudget } from "@/lib/topology/capacity"
import type { FloorCell } from "@/lib/topology/layout"
import { type ClusterSpec, clusterCount, clusterName } from "@/lib/topology/spec"
import type { KonvaEventObject } from "konva/lib/Node"
import { Group, Image as KonvaImage, Line, Rect, Text } from "react-konva"

const BODY = TILE_SIZE - TILE_INSET * 2
const PAD = 10
const ICON = 12
const GUTTER = ICON + 7
const TITLE_Y = 10
const DIVIDER_Y = 28
const ROWS_TOP = 34
const ROW_HEIGHT = 15
const FOOTER_HEIGHT = 20
const METER_HEIGHT = 6
const PERCENT_WIDTH = 30

export interface ClusterTileProps {
    cluster: ClusterSpec
    index: number
    cell: FloorCell
    selected: boolean
    invalid: boolean
    palette: CanvasPalette
    icons: HardwareIcons
    onSelect: (index: number, additive: boolean) => void
    onOpen: (index: number) => void
    onMove: (index: number, cell: FloorCell) => void
}

export function ClusterTile(props: ClusterTileProps) {
    const { cluster, index, cell, selected, invalid, palette, icons } = props
    const origin = cellOrigin(cell)
    const capacity = clusterCapacity(cluster)
    const headroom = clusterPowerHeadroom(cluster)
    const overBudget = isOverBudget(headroom)
    const repeats = clusterCount(cluster)
    const budget = headroom.budget

    const limited = budget.status === "limited" && budget.watts > 0
    const usage = limited && budget.status === "limited" ? Math.min(1, headroom.usedW / budget.watts) : 0
    const powerLabel = limited ? `${Math.round(usage * 100)}%` : formatPower(capacity.peakPowerW)

    const rows = [
        { icon: icons.space, label: `${capacity.hosts} hosts` },
        { icon: icons.cpu, label: `${capacity.cores} cores` },
        { icon: icons.memory, label: formatMemory(capacity.memoryMiB) },
        ...(capacity.gpus > 0 ? [{ icon: icons.gpu, label: `${capacity.gpus} GPUs` }] : []),
    ]

    const handleDragEnd = (event: KonvaEventObject<DragEvent>) => {
        const node = event.target
        const dropped = {
            x: Math.round((node.x() - TILE_INSET) / TILE_SIZE),
            y: Math.round((node.y() - TILE_INSET) / TILE_SIZE),
        }
        node.position({ x: origin.x + TILE_INSET, y: origin.y + TILE_INSET })
        props.onMove(index, dropped)
    }

    return (
        <Group
            x={origin.x + TILE_INSET}
            y={origin.y + TILE_INSET}
            draggable
            onDragEnd={handleDragEnd}
            onClick={(event) => props.onSelect(index, event.evt.shiftKey)}
            onTap={() => props.onSelect(index, false)}
            onDblClick={() => props.onOpen(index)}
        >
            <Rect
                width={BODY}
                height={BODY}
                cornerRadius={10}
                fill={selected ? palette.clusterSelected : palette.cluster}
                stroke={invalid ? palette.invalidBorder : selected ? palette.borderSelected : palette.border}
                strokeWidth={selected || invalid ? 1.5 : 1}
                shadowColor="#0b1220"
                shadowOpacity={selected ? 0.16 : 0.06}
                shadowBlur={selected ? 12 : 5}
                shadowOffsetY={2}
            />

            <Text
                x={PAD}
                y={TITLE_Y}
                width={BODY - PAD * 2 - (repeats > 1 ? 24 : 0)}
                text={clusterName(cluster)}
                fontSize={12}
                fontStyle="600"
                fill={palette.title}
                wrap="none"
                ellipsis
            />
            {repeats > 1 && (
                <Text
                    x={BODY - PAD - 24}
                    y={TITLE_Y + 1}
                    width={24}
                    align="right"
                    text={`x${repeats}`}
                    fontSize={10}
                    fill={palette.accent}
                />
            )}

            <Line points={[PAD, DIVIDER_Y, BODY - PAD, DIVIDER_Y]} stroke={palette.divider} strokeWidth={1} />

            {rows.map((row, position) => (
                <IconRow
                    key={row.label}
                    y={ROWS_TOP + position * ROW_HEIGHT}
                    icon={row.icon}
                    label={row.label}
                    palette={palette}
                />
            ))}

            <Group y={BODY - FOOTER_HEIGHT}>
                {icons.energy && <KonvaImage image={icons.energy} x={PAD} y={0} width={ICON} height={ICON} />}
                <Text
                    x={PAD + GUTTER}
                    y={1}
                    width={limited ? PERCENT_WIDTH : BODY - PAD * 2 - GUTTER}
                    text={powerLabel}
                    fontSize={10}
                    fill={overBudget ? palette.meterOver : palette.muted}
                    wrap="none"
                    ellipsis
                />
                {limited && (
                    <>
                        <Rect
                            x={PAD + GUTTER + PERCENT_WIDTH}
                            y={3}
                            width={BODY - PAD * 2 - GUTTER - PERCENT_WIDTH}
                            height={METER_HEIGHT}
                            cornerRadius={3}
                            fill={palette.meterTrack}
                        />
                        <Rect
                            x={PAD + GUTTER + PERCENT_WIDTH}
                            y={3}
                            width={(BODY - PAD * 2 - GUTTER - PERCENT_WIDTH) * usage}
                            height={METER_HEIGHT}
                            cornerRadius={3}
                            fill={overBudget ? palette.meterOver : palette.energyFill}
                        />
                    </>
                )}
            </Group>
        </Group>
    )
}

function IconRow({
    y,
    icon,
    label,
    palette,
}: {
    y: number
    icon: HTMLImageElement | undefined
    label: string
    palette: CanvasPalette
}) {
    return (
        <Group y={y}>
            {icon && <KonvaImage image={icon} x={PAD} y={0} width={ICON} height={ICON} opacity={0.7} />}
            <Text
                x={PAD + GUTTER}
                y={1}
                width={BODY - PAD * 2 - GUTTER}
                text={label}
                fontSize={11}
                fill={palette.label}
                wrap="none"
                ellipsis
            />
        </Group>
    )
}
