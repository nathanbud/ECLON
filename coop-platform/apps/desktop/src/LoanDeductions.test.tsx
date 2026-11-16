import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { LoanDeduction } from './api'
import LoanDeductions from './LoanDeductions'
import { AppThemeProvider } from './theme'

const list = vi.fn()
const options = vi.fn()
const add = vi.fn()
const change = vi.fn()
const remove = vi.fn()
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  getLoanDeductions: (c: number) => list(c),
  getLoanDeductionOptions: (c: number) => options(c),
  addLoanDeduction: (c: number, f: unknown) => add(c, f),
  changeLoanDeduction: (c: number, id: number, f: unknown) => change(c, id, f),
  removeLoanDeduction: (c: number, id: number, r: string) => remove(c, id, r),
}))

const line = (id: number, description: string, order: number, kind: LoanDeduction['kind'], target: string, extra: Partial<LoanDeduction> = {}): LoanDeduction => ({
  id, description, order, kind, classId: null, typeCode: null, event: null, account: null, target, formula: null, hidePrinting: false,
  editableTarget: kind !== 'NET_PROCEEDS' && kind !== 'OTHER', removable: kind !== 'NET_PROCEEDS', ...extra,
})
const lines = [
  line(1, 'Share Capital', 2, 'MEMBER_ACCOUNT', "The member's Paid up Share Capital", { classId: 31, typeCode: 1 }),
  line(2, 'CLIMBS Loan Insurance', 5, 'CHART_ACCOUNT', 'Chart account 2010503 CLIMBS payable', { account: '2010503' }),
  line(3, 'Net proceeds', 99, 'NET_PROCEEDS', 'Net proceeds: what the member receives'),
]

function show() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider><LoanDeductions code={3} /></AppThemeProvider></QueryClientProvider>)
}

describe('Loan type deductions', () => {
  beforeEach(() => {
    for (const f of [list, options, add, change, remove]) f.mockReset()
    list.mockResolvedValue(lines)
    options.mockResolvedValue({ memberTypes: [{ classId: 31, typeCode: 1, name: 'Paid up Share Capital' }, { classId: 22, typeCode: 1, name: 'SD - Regular' }], charges: [{ event: 31, name: 'Service fee' }] })
  })

  it('lists the lines with where each goes, and keeps the net proceeds line', async () => {
    show()
    const table = await screen.findByRole('table', { name: 'Deductions of this loan type' })
    expect(within(table).getByText("The member's Paid up Share Capital")).toBeInTheDocument()
    expect(within(table).getAllByRole('button', { name: 'Remove' })).toHaveLength(2) // not on net proceeds
  })

  it('adds a line to a member account, with a reason', async () => {
    add.mockResolvedValue(lines)
    show()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Add a deduction' }))
    const group = screen.getByRole('group', { name: 'New deduction' })
    await user.type(within(group).getByLabelText(/^Description/), 'Savings build-up')
    expect(within(group).getByLabelText(/^Order/)).toHaveValue('6')
    await user.click(within(group).getByLabelText(/Kind of account/))
    await user.click(await screen.findByRole('option', { name: 'SD - Regular' }))
    await user.type(screen.getByLabelText(/Reason for a change/), 'Members save from each loan')
    await user.click(within(group).getByRole('button', { name: 'Add deduction' }))
    await waitFor(() => expect(add).toHaveBeenCalledWith(3, { description: 'Savings build-up', order: 6, kind: 'MEMBER_ACCOUNT', classId: 22, typeCode: 1, event: null, account: null, hidePrinting: false, reason: 'Members save from each loan' }))
  })

  it('asks for a reason before removing a line', async () => {
    remove.mockResolvedValue(lines.slice(1))
    show()
    const user = userEvent.setup()
    const table = await screen.findByRole('table', { name: 'Deductions of this loan type' })
    await user.click(within(table).getAllByRole('button', { name: 'Remove' })[0])
    expect(await screen.findByText('Give a reason for the change first.')).toBeInTheDocument()
    expect(remove).not.toHaveBeenCalled()
    await user.type(screen.getByLabelText(/Reason for a change/), 'No longer collected')
    await user.click(within(table).getAllByRole('button', { name: 'Remove' })[0])
    await waitFor(() => expect(remove).toHaveBeenCalledWith(3, 1, 'No longer collected'))
  })
})
