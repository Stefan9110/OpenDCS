"use client"

import {formatAmount, formatBillingDate} from "@/lib/account"
import type {Billing, Invoice, PlanTier} from "@/lib/api/types"
import {notifyComingSoon} from "@/lib/notify"
import {Badge, Button, Group, Modal, Paper, Stack, Table, Text} from "@mantine/core"

export function BillingModal(
    {
        plan,
        billing,
        opened,
        onClose,
    }: Readonly<{ plan: PlanTier; billing: Billing; opened: boolean; onClose: () => void }>
) {
    return (
        <Modal opened={opened} onClose={onClose} title="Billing details" size="md" centered>
            <Stack gap="lg">
                <Paper withBorder radius="md" p="md">
                    <Stack gap={2}>
                        <Text fw={600} tt="capitalize">{plan} plan</Text>
                        <Text size="sm" c="dimmed">
                            Your subscription auto-renews on {formatBillingDate(billing.renewsAt)}
                        </Text>
                    </Stack>
                </Paper>
                <Group justify="space-between">
                    <Text size="sm" c="dimmed">
                        Payment method
                    </Text>
                    <Text size="sm">{billing.paymentMethod}</Text>
                </Group>
                <InvoiceTable invoices={billing.invoices}/>
                <Group justify="flex-end" gap="sm">
                    <Button variant="default" onClick={() => notifyComingSoon("Managing your payment method")}>
                        Payment method
                    </Button>
                    <Button onClick={() => notifyComingSoon("Changing your plan")}>Change plan</Button>
                </Group>
            </Stack>
        </Modal>
    )
}

function InvoiceTable({invoices}: Readonly<{ invoices: Invoice[] }>) {
    return (
        <Stack gap="xs">
            <Text size="xs" fw={700} tt="uppercase" c="dimmed">
                Recent invoices
            </Text>
            <Table verticalSpacing="xs" horizontalSpacing={0}>
                <Table.Tbody>
                    {invoices.map((invoice) => (
                        <Table.Tr key={invoice.id}>
                            <Table.Td>{formatBillingDate(invoice.issuedAt)}</Table.Td>
                            <Table.Td>{formatAmount(invoice.amountEur)}</Table.Td>
                            <Table.Td ta="right">
                                <Badge variant="light" color={invoice.paid ? "green" : "yellow"} size="sm">
                                    {invoice.paid ? "Paid" : "Due"}
                                </Badge>
                            </Table.Td>
                        </Table.Tr>
                    ))}
                </Table.Tbody>
            </Table>
        </Stack>
    )
}
