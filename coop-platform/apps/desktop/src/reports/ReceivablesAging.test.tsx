import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AgingLine, AgingOptions, AgingRequest, AgingResult } from '../api/receivables'
import { AppThemeProvider } from '../theme'
import { forgetRequest } from './aging'
import ReceivablesAging from './ReceivablesAging'

const run = vi.fn()
const download = vi.fn()
vi.mock('../api/receivables', async (orig) => ({
  ...(await orig<typeof import('../api/receivables')>()),
  getAgingOptions: async (): Promise<AgingOptions> => ({
    cutOff: '2026-04-24',
    classes: [{ code: 12, name: 'Loans Receivable', types: [{ code: 1, name: 'Regular' }] }, { code: 13, name: 'Accts Receivable', types: [] }, { code: 25, name: 'Invest Deposit Plan', types: [] }],
    groupings: [{ key: 'slclass', label: 'SL class' }, { key: 'sltype', label: 'SL type' }, { key: 'collector', label: 'Collector' }, { key: 'dept', label: 'Member department' }],
    sorts: [{ key: 'name', label: 'Member name (A to Z)' }, { key: 'balance', label: 'Outstanding (largest first)' }],
    buckets: ['Current', '1-30 days', '31-60 days', '61-90 days', '91-180 days', '181-365 days', 'Over 365 days'],
  }),
  runAging: (r: AgingRequest) => run(r),
  downloadAging: (r: AgingRequest, kind: string) => download(r, kind),
}))

const l = (kind: AgingLine['kind'], level: number, label: string, outstanding: number, buckets: number[], extra: Partial<AgingLine> = {}): AgingLine => ({
  kind, level, label, detail: kind === 'account' ? 'Regular · 7' : 'SL class', client: kind === 'account' ? 42 : null, slClass: null, slType: null, ref: null, accounts: 1,
  outstanding, buckets, pastDue: 0, atRisk: 0, riskPct: 0, days: kind === 'account' ? 0 : null, released: null, maturity: null, lastTrans: null,
  interestDue: 0, interestAhead: 0, penaltyDue: 0, totalDue: outstanding, ...extra,
})

const RESULT: AgingResult = {
  cutOff: '2026-04-24', basis: 'PAR', parDays: 30, buckets: ['Current', '1-30 days', '31-60 days', '61-90 days', '91-180 days', '181-365 days', 'Over 365 days'], groupBy: ['slclass'],
  lines: [
    l('group', 0, 'Loans Receivable', 3000, [1000, 0, 2000, 0, 0, 0, 0], { accounts: 2, atRisk: 2000, riskPct: 66.67, pastDue: 500 }),
    l('account', 1, 'Abad, Carl', 2000, [0, 0, 2000, 0, 0, 0, 0], { days: 45, atRisk: 2000, pastDue: 500 }),
    l('account', 1, 'Cruz, Ana', 1000, [1000, 0, 0, 0, 0, 0, 0]),
    l('total', 0, 'All receivables', 3000, [1000, 0, 2000, 0, 0, 0, 0], { accounts: 2, atRisk: 2000, riskPct: 66.67, pastDue: 500 }),
  ],
  note: 'Principal outstanding.', ranAt: '2026-04-24T10:00:00', ranBy: 'ana', accrual: false, penalty: false,
}

function show() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider><ReceivablesAging /></AppThemeProvider></QueryClientProvider>)
}

describe('Aging of receivables', () => {
  beforeEach(() => {
    forgetRequest()
    run.mockReset().mockResolvedValue(RESULT)
    download.mockReset().mockResolvedValue('aging-of-receivables-2026-04-24.xlsx')
  })

  it('sends the chosen basis, classes and groupings, then shows the summary and the groups', async () => {
    show()
    await userEvent.click(await screen.findByRole('radio', { name: 'Maturity @' }))
    await userEvent.click(screen.getByRole('button', { name: 'Accts Receivable' }))
    await userEvent.click(screen.getByRole('tab', { name: /Groupings/ }))
    await userEvent.click(screen.getByLabelText('Remove SL type'))
    await userEvent.click(screen.getByRole('button', { name: 'Collector' }))
    await userEvent.click(screen.getByRole('button', { name: 'Run report' }))
    expect(run).toHaveBeenCalledTimes(1)
    const sent = run.mock.calls[0][0] as AgingRequest
    expect(sent.basis).toBe('MATURITY')
    expect(sent.classes).toEqual([12])
    expect(sent.groupBy).toEqual(['slclass', 'collector'])
    expect(sent.cutOff).toBe('2026-04-24')

    expect(await screen.findByText('Outstanding principal')).toBeInTheDocument()
    expect(screen.getAllByText('66.67%').length).toBeGreaterThan(0)
    const table = screen.getByRole('table', { name: 'Aging of receivables' })
    expect(within(table).getByText('Abad, Carl')).toBeInTheDocument()
    await userEvent.click(within(table).getByRole('button', { name: 'Close Loans Receivable' }))
    expect(within(table).queryByText('Abad, Carl')).not.toBeInTheDocument()
    expect(within(table).getByText('All receivables')).toBeInTheDocument()
  })

  it('the accrued interest tab sends its choices and adds its columns and totals', async () => {
    run.mockReset().mockResolvedValue({
      ...RESULT, accrual: true, penalty: true,
      lines: RESULT.lines.map((x) => (x.kind === 'account' && x.label === 'Abad, Carl' ? { ...x, interestDue: 123.45, penaltyDue: 10, totalDue: 2133.45 }
        : x.kind === 'account' ? x : { ...x, interestDue: 123.45, interestAhead: 5, penaltyDue: 10, totalDue: 3133.45 })),
    })
    show()
    await userEvent.click(await screen.findByRole('tab', { name: /Accrued interest/ }))
    const penalty = screen.getByRole('switch', { name: 'Include penalty (estimate)' })
    expect(penalty).toBeDisabled()
    await userEvent.click(screen.getByRole('switch', { name: 'Work out accrued interest' }))
    await userEvent.click(penalty)
    await userEvent.click(screen.getByRole('switch', { name: 'Only accounts that owe interest or penalty' }))
    await userEvent.click(screen.getByRole('button', { name: 'Run report' }))
    const sent = run.mock.calls[0][0] as AgingRequest
    expect([sent.accruedInterest, sent.penalty, sent.owingOnly]).toEqual([true, true, true])

    expect(await screen.findByText('Accrued interest due')).toBeInTheDocument()
    expect(screen.getByText('Penalty due (estimate)')).toBeInTheDocument()
    expect(screen.getByText('1 accounts owe interest')).toBeInTheDocument()
    const table = screen.getByRole('table', { name: 'Aging of receivables' })
    expect(within(table).getByRole('columnheader', { name: 'Interest due' })).toBeInTheDocument()
    expect(within(table).getByRole('columnheader', { name: 'Total owed' })).toBeInTheDocument()
    expect(within(table).getAllByText('3,133.45').length).toBe(2)
  })

  it('makes the Excel and PDF files for the choices of the run on screen, and says why one failed', async () => {
    show()
    await userEvent.click(await screen.findByRole('radio', { name: 'Maturity @' }))
    await userEvent.click(screen.getByRole('button', { name: 'Run report' }))
    await screen.findByText('Outstanding principal')
    // a choice changed after the run does not change what the files hold
    await userEvent.click(screen.getByRole('radio', { name: 'PAR @' }))
    await userEvent.click(screen.getByRole('button', { name: 'Excel' }))
    expect(download).toHaveBeenCalledTimes(1)
    expect((download.mock.calls[0][0] as AgingRequest).basis).toBe('MATURITY')
    expect(download.mock.calls[0][1]).toBe('xlsx')
    download.mockReset().mockRejectedValue(new Error('Cannot reach the server.'))
    await userEvent.click(screen.getByRole('button', { name: 'PDF' }))
    expect(download.mock.calls[0][1]).toBe('pdf')
    expect(await screen.findByText('Cannot reach the server.')).toBeInTheDocument()
  })

  it('allows no more than three groupings', async () => {
    show()
    await userEvent.click(await screen.findByRole('tab', { name: /Groupings/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Collector' }))
    expect(screen.getByText(/Three groupings chosen/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Member department' })).toHaveAttribute('aria-disabled', 'true')
  })

  it('shows why a run failed', async () => {
    run.mockReset().mockRejectedValue(new Error('Only administrators and accountants can run the aging of receivables.'))
    show()
    await userEvent.click(await screen.findByRole('button', { name: 'Run report' }))
    expect(await screen.findByText(/Only administrators and accountants/)).toBeInTheDocument()
  })
})
