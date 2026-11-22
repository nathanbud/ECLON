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
  bottomName: 'Net surplus', bottom: [100, 50], bottomTotal: 150,
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
})
