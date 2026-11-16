import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { AppThemeProvider } from './theme'
import TellerTransaction, { type TellerOnly } from './TellerTransaction'

vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  getSavings: async () => ({ totalBalance: 3000, accounts: [
    { typeCode: 1, refNo: 10, refNoText: '0010736-300.00', type: 'SD - Regular', status: 15, statusName: 'Active', balance: 2520.23, available: 2520.23, held: 0, uncleared: 0 },
    { typeCode: 4, refNo: 11, refNoText: '0010736-301.00', type: 'SD - Kaya', status: 15, statusName: 'Active', balance: 500, available: 500, held: 0, uncleared: 0 },
  ] }),
  getShares: async () => ({ totalBalance: 20257.31, accounts: [{ typeCode: 1, refNo: 5, refNoText: null, type: 'Paid up Share Cap', status: 15, balance: 20257.31 }] }),
  getLoans: async () => ({ loans: [{ typeCode: 2, refNo: 77, type: 'Regular Loan', status: 15, outstanding: 18500 }] }),
}))

function show(only: TellerOnly, onShowAll = vi.fn()) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider><TellerTransaction id={{ branchCode: 1, clientId: 9735 }} preset={null} only={only} onShowAll={onShowAll} /></AppThemeProvider></QueryClientProvider>)
  return onShowAll
}

const offered = async () => {
  await userEvent.click(await screen.findByRole('combobox', { name: 'Account' }))
  const names = within(await screen.findByRole('listbox')).getAllByRole('option').map((o) => o.textContent ?? '')
  await userEvent.keyboard('{Escape}')
  return names
}

describe('teller transaction accounts', () => {
  it('offers every kind of account when nothing is chosen', async () => {
    show(null)
    const names = await offered()
    expect(names).toHaveLength(4)
    expect(names.some((n) => n.startsWith('Regular Loan'))).toBe(true)
  })

  it('offers only savings accounts once Savings is chosen, and says so', async () => {
    const showAll = show('savings')
    const names = await offered()
    expect(names.map((n) => n.split(' ').slice(0, 3).join(' '))).toEqual(['SD - Regular', 'SD - Kaya'])
    expect(screen.getByText('Savings accounts')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Show all' }))
    expect(showAll).toHaveBeenCalled()
  })

  it('offers only loans once Loan is chosen, with payment as the only action', async () => {
    show('loan')
    const names = await offered()
    expect(names).toHaveLength(1)
    expect(names[0]).toMatch(/^Regular Loan #77/)
    expect(screen.getByRole('button', { name: 'Payment' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Withdraw' })).not.toBeInTheDocument()
  })

  it('points to the Time Deposits section instead of a form for time deposits', async () => {
    show('td')
    expect(await screen.findByText(/in the Time Deposits section below/)).toBeInTheDocument()
    expect(screen.queryByRole('combobox', { name: 'Account' })).not.toBeInTheDocument()
  })
})
