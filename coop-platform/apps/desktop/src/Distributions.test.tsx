import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import React from 'react'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Distribution, DistributionDetail, DistributionSettings } from './api'
import { DistributionList, DistributionSettingsView } from './Distributions'
import { AppThemeProvider } from './theme'

const list = vi.fn()
const detail = vi.fn()
const submit = vi.fn()
const approve = vi.fn()
const report = vi.fn()
const settings = vi.fn()
const savePolicy = vi.fn()
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  getDistributions: () => list(),
  getDistribution: () => detail(),
  submitDistribution: (id: number) => submit(id),
  approveDistribution: (id: number, note: string | null) => approve(id, note),
  getDistributionReport: (id: number, k: string) => report(id, k),
  getDistributionSettings: () => settings(),
  saveDistributionPolicy: (p: unknown, r: string) => savePolicy(p, r),
}))

const batch: Distribution = {
  id: 7, branch: 1, year: 2026, cutoff: '2027-02-21', method: 'RATES', status: 'DRAFT', netSurplus: null, available: null, iscAllocation: null, prAllocation: null, approvalRef: null,
  dividendRate: 8, patronageRate: 8.23, mutualBenefit: 0, hospitalization: 0, overrides: 0, totalAverageShare: 100_000_000, totalPatronage: 50_000_000, members: 25_000,
  totalIsc: 8_000_000, totalPr: 4_115_000, total: 12_115_000, held: 7, excluded: 0, policy: 'Qualified patronage: Interest Paid (23).', preparedBy: 'ana', preparedAt: '2027-03-01 10:00',
  submittedBy: null, submittedAt: null, decidedBy: null, decidedAt: null, decisionNote: null, supersededBy: null, encodedBy: null, encodedAt: null, voucherYear: null, voucherFirst: null, voucherLast: null,
}
const detailOf = (b: Distribution, rights: Partial<DistributionDetail['rights']>): DistributionDetail => ({
  batch: b, funds: [], patronage: [{ event: 23, name: 'Interest Paid', amount: 50_000_000, members: 11_000 }], credited: { SAVINGS: 12_115_000 },
  leftOut: [{ clientId: 99, name: 'Cruz, Ana', total: 120.5, reason: 'the member has no active savings or share capital account to credit' }], controls: ['The credits to members add up to the total, 12,115,000.00.'],
  rights: { maySubmit: false, mayApprove: false, mayReject: false, mayEncode: false, note: 'Prepared.', ...rights },
})

function show(ui: React.ReactElement) {
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AppThemeProvider>{ui}</AppThemeProvider></QueryClientProvider>)
}

function Opened() {
  const [id, setId] = React.useState<number | null>(null)
  return <DistributionList openId={id} onOpen={setId} />
}

describe('Distributions', () => {
  beforeEach(() => { list.mockReset(); detail.mockReset(); submit.mockReset(); approve.mockReset(); report.mockReset(); settings.mockReset(); savePolicy.mockReset() })

  it('lists the years and opens one, where the preparer submits it for review', async () => {
    list.mockResolvedValue([batch, { ...batch, id: 6, status: 'SUPERSEDED' }])
    detail.mockResolvedValue(detailOf(batch, { maySubmit: true, mayReject: true }))
    submit.mockResolvedValue(detailOf({ ...batch, status: 'SUBMITTED' }, {}))
    show(<Opened />)
    expect(await screen.findByText('Replaced')).toBeInTheDocument()
    await userEvent.click(screen.getAllByText(/2026 · 12,115,000.00 to 25,000 members/)[0])
    expect(await screen.findByText('Distribution of 2026 · cut-off 2027-02-21')).toBeInTheDocument()
    expect(screen.getByText(/Interest Paid \(11,000 members\)/)).toBeInTheDocument()
    expect(screen.getByRole('table', { name: 'Members left out' })).toHaveTextContent('Cruz, Ana')
    expect(screen.queryByRole('button', { name: 'Approve' })).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: 'Submit for review' }))
    await waitFor(() => expect(submit).toHaveBeenCalledWith(7))
  })

  it('lets another person approve, after saying what the figures are', async () => {
    list.mockResolvedValue([{ ...batch, status: 'SUBMITTED' }])
    detail.mockResolvedValue(detailOf({ ...batch, status: 'SUBMITTED' }, { mayApprove: true, mayReject: true }))
    approve.mockResolvedValue(detailOf({ ...batch, status: 'APPROVED' }, {}))
    show(<Opened />)
    await userEvent.click(await screen.findByText(/2026 · 12,115,000.00/))
    await userEvent.type(await screen.findByLabelText('Note (optional)'), 'per board resolution')
    await userEvent.click(screen.getByRole('button', { name: 'Approve' }))
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('25,000 members receive 12,115,000.00')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Approve' }))
    await waitFor(() => expect(approve).toHaveBeenCalledWith(7, 'per board resolution'))
  })

  it('opens a report as a printable table', async () => {
    list.mockResolvedValue([batch])
    detail.mockResolvedValue(detailOf(batch, {}))
    report.mockResolvedValue({ title: 'Patronage refund summary', subtitle: 'Financial year 2026', sections: [{ heading: 'Qualified patronage by kind', columns: ['Loan event', 'Members', 'Amount'], rows: [['Interest Paid (23)', '11000', '50,000,000.00']], notes: ['Paid amounts only.'] }] })
    show(<Opened />)
    await userEvent.click(await screen.findByText(/2026 · 12,115,000.00/))
    await userEvent.click(await screen.findByRole('button', { name: 'Patronage refund' }))
    await waitFor(() => expect(report).toHaveBeenCalledWith(7, 'pr'))
    const table = await screen.findByRole('table', { name: 'Qualified patronage by kind' })
    expect(table).toHaveTextContent('50,000,000.00')
    expect(screen.getByRole('button', { name: 'Print' })).toBeInTheDocument()
  })
})

describe('Dividend settings', () => {
  const s: DistributionSettings = {
    policy: { patronageEvents: [23, 24, 31], eligibleStatuses: null, iscTypes: null, prTypes: null, minAverageShare: 0, updatedBy: 'setup', updatedAt: '2026-11-16 09:00' },
    funds: [], events: [{ id: 23, name: 'Interest Paid' }, { id: 24, name: 'Interest Earned' }, { id: 31, name: 'Service Fee' }, { id: 36, name: 'Collection Fee' }],
    statuses: [{ id: 1, name: 'Active' }, { id: 3, name: 'Closed' }], types: [{ id: 1, name: 'Regular Individual' }, { id: 2, name: 'Associate' }], words: '', mayEdit: true,
  }
  beforeEach(() => { settings.mockReset(); savePolicy.mockReset() })

  it('chooses what counts and who earns a refund, and saves with a reason', async () => {
    settings.mockResolvedValue(s)
    savePolicy.mockResolvedValue(s)
    show(<DistributionSettingsView />)
    await userEvent.click(await screen.findByRole('checkbox', { name: 'Collection Fee (36)' }))
    const refund = screen.getByText('Who earns a patronage refund, by member type').parentElement!
    await userEvent.click(within(refund).getByRole('switch', { name: 'Every member type' }))
    await userEvent.click(within(refund).getByRole('checkbox', { name: 'Associate (2)' }))
    await userEvent.click(screen.getByRole('button', { name: 'Save these settings' }))
    await userEvent.type(await screen.findByLabelText('Reason for the change'), 'bylaws section 12')
    await userEvent.click(screen.getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(savePolicy).toHaveBeenCalledWith({ patronageEvents: [23, 24, 31, 36], eligibleStatuses: null, iscTypes: null, prTypes: [1], minAverageShare: 0 }, 'bylaws section 12'))
  })

  it('shows the settings read only to someone who is not an administrator', async () => {
    settings.mockResolvedValue({ ...s, mayEdit: false })
    show(<DistributionSettingsView />)
    expect(await screen.findByRole('checkbox', { name: 'Interest Paid (23)' })).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Save these settings' })).toBeNull()
    expect(screen.getByText(/Only an administrator can change them/)).toBeInTheDocument()
  })
})
