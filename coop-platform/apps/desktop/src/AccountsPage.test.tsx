import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AccountsPage from './AccountsPage'
import type { ChartAccount, ChartForm, Me } from './api'
import { AppThemeProvider } from './theme'

vi.mock('./session', () => ({ useMe: () => ({ username: 'acct', name: 'Accountant', roles: ['ROLE_ACCOUNTANT'], admin: false, branchCode: 1 }) as unknown as Me }))

const create = vi.fn()
const update = vi.fn()
const acct = (code: string, name: string, parent: string | null, extra: Partial<ChartAccount> = {}): ChartAccount => {
  const type = Number(code[0])
  return {
    code, name, type, typeName: null, summary: false, parent, disabled: false, glOnly: true, hasChildren: false, inUse: false,
    normalBalance: type === 1 || type === 5 ? 'D' : 'C', contra: false, description: null, ...extra,
  }
}
const CHART: ChartAccount[] = [
  acct('1', 'ASSETS', null, { summary: true, hasChildren: true }),
  acct('1000', 'CASH ON HAND', '1', { summary: true, hasChildren: true }),
  acct('10001', 'Cash in vault', '1000', { inUse: true }),
  acct('10002', 'Cash on hand - cashiers', '1000', { glOnly: false }),
  acct('10009', 'Old fund', '1000', { disabled: true }),
  acct('2', 'LIABILITIES', null, { summary: true, hasChildren: true }),
]
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  getChart: async () => CHART,
  getChartUsage: async (code: string) => ({ code, entries: 0, children: 0, usedBy: [] }),
  getChartHistory: async () => [
    { at: '2026-11-23T09:15:02', by: 'acct', action: 'chart.account.update', reason: 'Clearer name', changes: [{ field: 'name', label: 'Account name', before: 'Vault', after: 'Cash in vault' }] },
  ],
  createChartAccount: (f: ChartForm) => create(f),
  updateChartAccount: (code: string, f: ChartForm) => update(code, f),
}))

function show() {
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AppThemeProvider><AccountsPage /></AppThemeProvider></QueryClientProvider>)
}

describe('Chart of Accounts', () => {
  beforeEach(() => {
    create.mockReset().mockImplementation(async (f: ChartForm) => acct(f.code!, f.name!, f.parent ?? null))
    update.mockReset().mockImplementation(async (code: string) => CHART.find((a) => a.code === code))
  })

  it('shows the summary cards and the hierarchy with type, normal balance, posting and status', async () => {
    show()
    const cards = await screen.findByRole('button', { name: /Total accounts/ })
    await waitFor(() => expect(cards).toHaveTextContent('6'))
    expect(screen.getByRole('button', { name: /Switched off/ })).toHaveTextContent('1')
    const tree = screen.getByRole('treegrid', { name: 'Chart of accounts' })
    await user().click(within(tree).getAllByRole('button', { name: 'Expand' })[0])
    const vault = (await within(tree).findByText('Cash in vault')).closest('tr')!
    expect(vault).toHaveTextContent('Asset')
    expect(vault).toHaveTextContent('Debit')
    expect(vault).toHaveTextContent('Journal entries')
    expect(vault).toHaveTextContent('Active')
    expect(within(tree).getByText('Cash on hand - cashiers').closest('tr')).toHaveTextContent('Subsidiary ledger')
    // switched-off accounts are left out until asked for
    expect(within(tree).queryByText('Old fund')).toBeNull()
    await user().click(screen.getByRole('button', { name: /Switched off/ }))
    expect(await within(tree).findByText('Old fund')).toBeInTheDocument()
  })

  it('makes a new account under a heading with its number generated, the normal balance suggested, and a contra when chosen', async () => {
    show()
    const u = user()
    await u.click(await screen.findByRole('button', { name: 'New account' }))
    const panel = await screen.findByRole('presentation')
    await u.type(within(panel).getByLabelText(/^Account name/), 'Petty cash fund')
    await u.click(within(panel).getByLabelText(/^Heading it goes under/))
    await u.click(await screen.findByRole('option', { name: /1000 CASH ON HAND/ }))
    expect(within(panel).getByLabelText(/^Account code/)).toHaveValue('100010')
    expect(within(panel).getByText('Debit, as every asset account.')).toBeInTheDocument()
    await u.click(within(panel).getByRole('radio', { name: /Credit/ }))
    expect(within(panel).getByText(/A contra account/)).toBeInTheDocument()
    await u.type(within(panel).getByLabelText(/^Reason for this change/), 'New fund')
    await u.click(within(panel).getByRole('button', { name: 'Save account' }))
    await waitFor(() => expect(create).toHaveBeenCalledWith(expect.objectContaining({
      code: '100010', name: 'Petty cash fund', parent: '1000', summary: false, glOnly: true, normalBalance: 'C', reason: 'New fund',
    })))
  })

  it('refuses a number already in use and a name already under the heading before sending', async () => {
    show()
    const u = user()
    await u.click(await screen.findByRole('button', { name: 'New account' }))
    const panel = await screen.findByRole('presentation')
    await u.type(within(panel).getByLabelText(/^Account name/), 'Cash in vault')
    await u.click(within(panel).getByLabelText(/^Heading it goes under/))
    await u.click(await screen.findByRole('option', { name: /1000 CASH ON HAND/ }))
    expect(within(panel).getByText(/already an account called Cash in vault under 1000/)).toBeInTheDocument()
    const code = within(panel).getByLabelText(/^Account code/)
    await u.clear(code)
    await u.type(code, '10001')
    expect(within(panel).getByText(/Account 10001 already exists/)).toBeInTheDocument()
    await u.click(within(panel).getByRole('button', { name: 'Save account' }))
    expect(create).not.toHaveBeenCalled()
    await u.click(within(panel).getByRole('button', { name: 'Auto-generate' }))
    expect(code).toHaveValue('100010')
  })

  it('opens every heading in the background with the list marked busy meanwhile, then lets it be used again', async () => {
    show()
    const u = user()
    const tree = await screen.findByRole('treegrid', { name: 'Chart of accounts' })
    await within(tree).findAllByRole('button', { name: 'Expand' })
    await u.click(screen.getByRole('button', { name: 'Expand all' }))
    expect(await within(tree).findByText('Cash in vault')).toBeInTheDocument()
    await waitFor(() => expect(tree.closest('[aria-busy]')).toHaveAttribute('aria-busy', 'false'))
    expect(screen.queryByRole('progressbar')).toBeNull()
    await u.click(screen.getByRole('button', { name: 'Collapse all' }))
    await waitFor(() => expect(within(tree).queryByText('Cash in vault')).toBeNull())
  })

  it('shows an account with who changed it and what changed', async () => {
    show()
    const u = user()
    const tree = await screen.findByRole('treegrid', { name: 'Chart of accounts' })
    await u.click((await within(tree).findAllByRole('button', { name: 'Expand' }))[0])
    await u.click(await within(tree).findByRole('button', { name: 'More for 10001' }))
    await u.click(await screen.findByRole('menuitem', { name: 'History' }))
    const panel = await screen.findByRole('presentation')
    expect(await within(panel).findByText('Account changed')).toBeInTheDocument()
    expect(within(panel).getByText(/Clearer name/)).toBeInTheDocument()
    expect(within(panel).getByRole('cell', { name: 'Vault' })).toBeInTheDocument()
  })
})

function user() {
  return userEvent.setup()
}
