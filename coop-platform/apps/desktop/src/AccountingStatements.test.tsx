import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AccountingPage from './AccountingPage'
import type { ConsolidatingStatement, Me, OperationsStatement, UnitChoice } from './api'
import { AppThemeProvider } from './theme'

vi.mock('./session', () => ({ useMe: () => ({ username: 'acct', name: 'Accountant', roles: ['ROLE_ACCOUNTANT'], admin: false, branchCode: 1 }) as unknown as Me }))
vi.mock('./TrialBalancePage', () => ({ default: () => null }))
vi.mock('./ui/BranchSelect', () => ({ BranchSelect: () => null }))

const ops = vi.fn()
const cons = vi.fn()
const comp = vi.fn()
const setClass = vi.fn()
const units: UnitChoice[] = [
  { id: 15, name: 'CONSOLIDATED MAIN', parent: null, kind: 'CONSOLIDATION', kindName: 'Consolidation', kindChosen: true, disabled: false, children: 2 },
  { id: 2, name: 'MAIN OFFICE', parent: 15, kind: 'MAIN_OFFICE', kindName: 'Main office', kindChosen: true, disabled: false, children: 0 },
  { id: 3, name: 'BAUKO SATELLITE OFFICE', parent: 15, kind: 'SATELLITE_OFFICE', kindName: 'Satellite office', kindChosen: false, disabled: false, children: 0 },
]
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  getStatementUnits: async () => units,
  getOperations: (...a: unknown[]) => ops(...a),
  getFinancialCondition: async () => ({ branchCode: 1, asOf: '2025-03-31', unit: null, sections: [], totalAssets: 0, totalLiabilitiesAndEquity: 0, balanced: true }),
  getConsolidating: (...a: unknown[]) => cons(...a),
  getComparative: (...a: unknown[]) => comp(...a),
  getBalanceStatus: async () => ({ state: 'idle', builtYears: [2024, 2025], builtYearsByUnit: [] }),
  getChangesInEquity: async () => ({
    branchCode: 1, from: '2026-01-01', to: '2026-03-31', unit: null, beginning: 1000, increases: 300, decreases: 50, other: 0, ending: 1250,
    rows: [
      { group: 'SHARE CAPITAL', accountCode: '3010101', description: 'Paid-up share capital', beginning: 1000, increases: 200, decreases: 50, other: 0, ending: 1150 },
      { group: 'Current year surplus', accountCode: '', description: 'Net surplus not yet closed into equity', beginning: 0, increases: 100, decreases: 0, other: 0, ending: 100 },
    ],
  }),
  getCashFlow: async () => ({
    branchCode: 1, from: '2026-01-01', to: '2026-03-31', unit: null, netSurplus: 100, netChange: 700, cashBeginning: 500, cashEnding: 1200, unexplained: 0,
    activities: [
      { category: 'OPERATING', name: 'Operating activities', total: -300, lines: [{ accountCode: '', description: 'Net surplus for the period', amount: 100 }, { accountCode: '12001', description: 'Loans receivable', amount: -400 }] },
      { category: 'INVESTING', name: 'Investing activities', total: 0, lines: [] },
      { category: 'FINANCING', name: 'Financing activities', total: 1000, lines: [{ accountCode: '3010101', description: 'Paid-up share capital', amount: 1000 }] },
    ],
  }),
  getCashFlowClasses: async () => [
    { code: '201', name: 'DEPOSIT LIABILITIES', type: 2, parent: '2', summary: true, category: 'OPERATING', chosen: false, inheritedFrom: null },
  ],
  setCashFlowClass: (...a: unknown[]) => setClass(...a),
}))

const statement = (unit: OperationsStatement['unit']): OperationsStatement => ({
  branchCode: 1, from: '2025-01-01', to: '2025-03-31', unit, netSurplus: 150,
  sections: [{ name: 'REVENUES', total: 150, groups: [{ name: 'INCOME', total: 150, lines: [{ accountCode: '4010', description: 'Interest', amount: 150 }] }] }],
})
const columns: ConsolidatingStatement = {
  branchCode: 1, statement: 'operations', asOf: null, from: '2025-01-01', to: '2025-03-31',
  unit: { id: 15, name: 'CONSOLIDATED MAIN', kind: 'CONSOLIDATION', kindName: 'Consolidation', ownEntries: false },
  columns: [
    { id: 2, name: 'MAIN OFFICE', kind: 'MAIN_OFFICE', kindName: 'Main office', ownEntries: false },
    { id: 3, name: 'BAUKO SATELLITE OFFICE', kind: 'SATELLITE_OFFICE', kindName: 'Satellite office', ownEntries: false },
  ],
  sections: [{ name: 'REVENUES', totals: [100, 50], total: 150, groups: [{ name: 'INCOME', totals: [100, 50], total: 150, lines: [{ accountCode: '4010', description: 'Interest', amounts: [100, 50], total: 150 }] }] }],
  bottomName: 'Net surplus', bottom: [100, 50], bottomTotal: 150, totalName: 'Consolidated',
}

describe('Statements by unit', () => {
  beforeEach(() => {
    ops.mockReset().mockImplementation(async (_b: number, _f: string, _t: string, d?: number | null) =>
      statement(d == null ? null : { id: 15, name: 'CONSOLIDATED MAIN', kind: 'CONSOLIDATION', kindName: 'Consolidation', units: 3 }))
    cons.mockReset().mockResolvedValue(columns)
  })

  it('makes a statement for a consolidation, then shows each unit side by side with the consolidated total', async () => {
    render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AppThemeProvider><AccountingPage initialTab="Statements" /></AppThemeProvider></QueryClientProvider>)
    const user = userEvent.setup()
    await user.click(await screen.findByLabelText(/^Statement$/))
    await user.click(await screen.findByRole('option', { name: 'Statement of Operations' }))
    await user.click(screen.getByLabelText(/^Unit$/))
    const listbox = await screen.findByRole('listbox')
    expect(within(listbox).getByText('Satellite office (suggested)')).toBeInTheDocument()
    await user.click(within(listbox).getByText('CONSOLIDATED MAIN'))
    expect(await screen.findByText('CONSOLIDATED MAIN, consolidation of 3 units')).toBeInTheDocument()
    expect(ops).toHaveBeenLastCalledWith(1, expect.any(String), expect.any(String), 15)

    await user.click(screen.getByLabelText('Show each unit side by side'))
    const table = await screen.findByRole('table', { name: 'Consolidating statement' })
    expect(within(table).getByText('BAUKO SATELLITE OFFICE')).toBeInTheDocument()
    expect(within(table).getByText('Consolidated')).toBeInTheDocument()
    expect(cons).toHaveBeenCalledWith(1, 15, expect.objectContaining({ from: expect.any(String), to: expect.any(String) }))
    const net = within(table).getByText('Net surplus').closest('tr')!
    expect(net).toHaveTextContent('100.00')
    expect(net).toHaveTextContent('150.00')
  })

  it('compares this period with the prior one, with the change in the last column', async () => {
    comp.mockResolvedValue({ ...columns, unit: { id: 0, name: 'Whole branch', kind: 'BRANCH', kindName: 'Every unit', ownEntries: false },
      columns: [{ id: 0, name: '2025-01-01 to 2025-03-31', kind: '', kindName: 'This period', ownEntries: false }, { id: 1, name: '2024-01-01 to 2024-03-31', kind: '', kindName: 'Prior period', ownEntries: false }],
      bottom: [150, 100], bottomTotal: 50, totalName: 'Change' })
    render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AppThemeProvider><AccountingPage initialTab="Statements" /></AppThemeProvider></QueryClientProvider>)
    const user = userEvent.setup()
    await user.click(await screen.findByLabelText(/^Statement$/))
    await user.click(await screen.findByRole('option', { name: 'Statement of Operations' }))
    await user.click(screen.getByLabelText('Compare with the same period last year'))
    const table = await screen.findByRole('table', { name: 'Consolidating statement' })
    expect(within(table).getByText('Change')).toBeInTheDocument()
    expect(within(table).getByText('Prior period')).toBeInTheDocument()
    expect(within(table).getByText('Net surplus').closest('tr')).toHaveTextContent('50.00')
    expect(comp).toHaveBeenCalledWith(1, null, expect.objectContaining({ from: expect.any(String), to: expect.any(String) }))
  })

  it('shows the Statement of Changes in Equity and the Statement of Cash Flows, and changes a cash flow class with a reason', async () => {
    setClass.mockResolvedValue([{ code: '201', name: 'DEPOSIT LIABILITIES', type: 2, parent: '2', summary: true, category: 'FINANCING', chosen: true, inheritedFrom: null }])
    render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AppThemeProvider><AccountingPage initialTab="Statements" /></AppThemeProvider></QueryClientProvider>)
    const user = userEvent.setup()
    await user.click(await screen.findByLabelText(/^Statement$/))
    await user.click(await screen.findByRole('option', { name: 'Statement of Changes in Equity' }))
    const eq = await screen.findByRole('table', { name: 'Statement of Changes in Equity' })
    expect(within(eq).getByText('Total equity').closest('tr')).toHaveTextContent('1,250.00')
    await user.click(screen.getByLabelText(/^Statement$/))
    await user.click(await screen.findByRole('option', { name: 'Statement of Cash Flows' }))
    const cf = await screen.findByRole('table', { name: 'Statement of Cash Flows' })
    expect(within(cf).getByText('Net change in cash and cash equivalents').closest('tr')).toHaveTextContent('700.00')
    expect(within(cf).queryByText(/Not explained/)).toBeNull()
    await user.click(screen.getByRole('button', { name: 'Cash flow classes' }))
    const dialog = await screen.findByRole('dialog')
    await within(dialog).findByText('201 DEPOSIT LIABILITIES')
    await user.click(within(dialog).getByRole('combobox'))
    await user.click(await screen.findByRole('option', { name: 'Financing' }))
    const reason = await screen.findByRole('dialog', { name: /201 DEPOSIT LIABILITIES/ })
    await user.type(within(reason).getByLabelText(/^Reason/), 'Borrowed funds')
    await user.click(within(reason).getByRole('button', { name: 'Save' }))
    expect(setClass).toHaveBeenCalledWith(1, '201', 'FINANCING', 'Borrowed funds')
  })
})
