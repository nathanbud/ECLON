import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import BudgetTab from './BudgetTab'
import type { BudgetLine } from './api'
import { AppThemeProvider } from './theme'

const save = vi.fn()
const lines: BudgetLine[] = [
  { accountCode: '40101', name: 'Interest income on loans', type: 4, group: 'INTEREST INCOME', budget: 1200000, lastYearActual: 1100000 },
  { accountCode: '50101', name: 'Salaries', type: 5, group: 'PERSONNEL', budget: 0, lastYearActual: 400000 },
]
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  getBudgets: async () => lines,
  saveBudgets: (...a: unknown[]) => save(...a),
  getBudgetVariance: async () => ({
    branchCode: 1, year: 2026, from: '2026-01-01', to: '2026-03-31', months: 3, revenueBudget: 300000, revenueActual: 320000, expenseBudget: 100000, expenseActual: 110000,
    lines: [
      { accountCode: '40101', name: 'Interest income on loans', type: 4, group: '', annual: 1200000, budget: 300000, actual: 320000, variance: 20000, favourable: true },
      { accountCode: '50101', name: 'Salaries', type: 5, group: '', annual: 400000, budget: 100000, actual: 110000, variance: 10000, favourable: false },
    ],
  }),
}))

const show = (canEdit: boolean) => render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AppThemeProvider><BudgetTab branchCode={1} canEdit={canEdit} /></AppThemeProvider></QueryClientProvider>)

describe('Budget', () => {
  it('sets budgets beside last year’s actual and saves the changes with one reason', async () => {
    save.mockResolvedValue(lines)
    show(true)
    const u = userEvent.setup()
    const salaries = await screen.findByLabelText('Budget of 50101')
    expect(screen.getByText('50101 Salaries').closest('tr')).toHaveTextContent('400,000.00')
    await u.type(salaries, '450000')
    expect(screen.getByText('1 change not saved')).toBeInTheDocument()
    await u.click(screen.getByRole('button', { name: 'Save changes' }))
    const dialog = await screen.findByRole('dialog')
    await u.type(within(dialog).getByLabelText(/^Reason/), 'Board approved')
    await u.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(save).toHaveBeenCalledWith(1, expect.any(Number), [{ accountCode: '50101', amount: 450000 }], 'Board approved'))
  })

  it('reads only for an auditor, and shows budget versus actual with favourable and unfavourable variances', async () => {
    show(false)
    const u = userEvent.setup()
    expect(await screen.findByText('40101 Interest income on loans')).toBeInTheDocument()
    expect(screen.queryByLabelText('Budget of 40101')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Save changes' })).toBeNull()
    await u.click(screen.getByRole('button', { name: 'Budget versus actual' }))
    const table = await screen.findByRole('table', { name: 'Budget versus actual' })
    expect(within(table).getByTitle('Favourable')).toHaveTextContent('20,000.00')
    expect(within(table).getByTitle('Unfavourable')).toHaveTextContent('10,000.00')
    expect(within(table).getByText('Net surplus').closest('tr')).toHaveTextContent('210,000.00')
  })
})
