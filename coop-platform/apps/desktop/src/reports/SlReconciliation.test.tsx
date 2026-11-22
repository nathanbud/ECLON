import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { SlReconciliation as Result } from '../api'
import { AppThemeProvider } from '../theme'
import SlReconciliation from './SlReconciliation'

const fetchIt = vi.fn<(b: number, d: string, fresh: boolean) => Promise<Result>>()
vi.mock('../api', async (orig) => ({ ...(await orig<typeof import('../api')>()), getSlReconciliation: (b: number, d: string, f: boolean) => fetchIt(b, d, f), getBranches: async () => [{ code: 1, name: 'Main Office' }] }))
vi.mock('../session', () => ({ useMe: () => ({ username: 'acct', name: 'Accountant', roles: ['ACCOUNTANT'], canEdit: false, canApprove: false, admin: false, mustChangePassword: false, branchCode: 1 }) }))

const result: Result = {
  branch: 1, asOf: '2026-11-20', totalDifference: 1110895150, unreconciled: 1, ranAt: '2026-11-20T21:00:00', ranBy: 'acct', kept: false,
  groups: [
    { title: 'Savings Deposit: SD - Regular', credit: true, members: 3146747749.71, ledger: 2035852599.71, difference: -1110895150, reconciled: false,
      types: [{ slc: 22, slt: 1, name: 'SD - Regular', total: 3146747749.71 }], accounts: [{ account: '2010101', name: 'Savings Deposit', balance: 2035852599.71 }] },
    { title: 'Loans Receivable: Salary Loan', credit: false, members: 816814826.49, ledger: 816814826.49, difference: 0, reconciled: true,
      types: [{ slc: 12, slt: 21, name: 'Salary Loan', total: 816814826.49 }], accounts: [{ account: '10110129', name: 'Salary loan - current', balance: 816814826.49 }] },
  ],
}

function show() {
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AppThemeProvider><SlReconciliation /></AppThemeProvider></QueryClientProvider>)
}

// Each test sets its own answer (no mockReset before each; see SchemaStatusNotice.test.tsx).
describe('SL reconciliation', () => {
  it('runs for the branch and date, and puts the groups that do not reconcile first, opened', async () => {
    fetchIt.mockResolvedValue(result)
    show()
    await userEvent.click(screen.getByRole('button', { name: 'Run' }))
    expect(fetchIt).toHaveBeenCalledWith(1, expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/), false)
    expect(await screen.findByText('1 of 2 groups does not reconcile')).toBeInTheDocument()
    const table = screen.getByRole('table', { name: 'SL reconciliation' })
    expect(within(table).getByText('Savings Deposit: SD - Regular')).toBeInTheDocument()
    expect(within(table).getByText('-1,110,895,150.00')).toBeInTheDocument()
    // the group that differs is open, showing its type and control account; the one that reconciles is closed
    expect(screen.getByRole('table', { name: 'Details of Savings Deposit: SD - Regular' })).toHaveTextContent('Control account · 2010101 Savings Deposit')
    expect(screen.queryByRole('table', { name: 'Details of Loans Receivable: Salary Loan' })).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: 'Show the details of Loans Receivable: Salary Loan' }))
    expect(await screen.findByRole('table', { name: 'Details of Loans Receivable: Salary Loan' })).toHaveTextContent('Salary loan - current')
  })

  it('says so when everything reconciles, and Run again asks for a fresh run', async () => {
    fetchIt.mockResolvedValue({ ...result, unreconciled: 0, totalDifference: 0, groups: [result.groups[1]] })
    show()
    await userEvent.click(screen.getByRole('button', { name: 'Run' }))
    expect(await screen.findByText('The group reconciles')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Run again' }))
    expect(fetchIt).toHaveBeenLastCalledWith(1, expect.any(String), true)
  })

  it('shows the server\'s reason when it refuses', async () => {
    fetchIt.mockImplementation(() => Promise.reject(new Error('Only administrators and accountants can run the SL reconciliation.')))
    show()
    await userEvent.click(screen.getByRole('button', { name: 'Run' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Only administrators and accountants')
  })
})
