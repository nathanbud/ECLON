import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Inbox, Me } from './api'
import Dashboard from './Dashboard'
import { AppThemeProvider } from './theme'

let roles: string[] = []
let admin = false
vi.mock('./session', () => ({ useMe: () => ({ username: 'u', name: 'Una', roles, canEdit: false, canApprove: false, admin, mustChangePassword: false }) as Me }))

let inbox: Inbox = { items: [], total: 0, byKind: {} }
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  getInbox: vi.fn(async () => inbox),
  getTdMaturityCount: vi.fn(async () => [2]),
}))

function renderHome() {
  const open = vi.fn()
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider><Dashboard onOpen={open} /></AppThemeProvider></QueryClientProvider>)
  return open
}

describe('the home page', () => {
  beforeEach(() => { roles = []; admin = false; inbox = { items: [], total: 0, byKind: {} } })

  it('greets the person and groups tiles by what they are doing', async () => {
    renderHome()
    expect(await screen.findByRole('heading', { level: 1, name: /Una/ })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Members' })).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Members' })).getByRole('button', { name: /Client Profile/ })).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Teller and cash' })).getByRole('button', { name: /Front Office/ })).toBeInTheDocument()
  })

  it('offers only the tiles the roles allow, and no tile for what is not built', () => {
    renderHome()
    for (const hidden of ['Accounting', 'Purchasing', 'Point of Sale', 'Settings', 'Audit Log', 'CIC submission']) expect(screen.queryByRole('button', { name: new RegExp(hidden) })).not.toBeInTheDocument()
    expect(screen.queryByText(/coming soon/i)).not.toBeInTheDocument()
  })

  it('gives an accountant the Accounting tile, which opens the whole screen', async () => {
    roles = ['ROLE_ACCOUNTANT']
    const open = renderHome()
    await userEvent.setup().click(screen.getByRole('button', { name: /^Accounting/ }))
    expect(open).toHaveBeenCalledWith('accounting', undefined)
  })

  it('shows the count of maturing deposits on the Front Office tile', async () => {
    renderHome()
    expect(await screen.findByRole('button', { name: 'Front Office, 2 waiting' })).toBeInTheDocument()
  })

  it('says when nothing is waiting', async () => {
    renderHome()
    const card = await screen.findByRole('region', { name: 'Waiting for you' })
    expect(within(card).getByText('Nothing is waiting for you.')).toBeInTheDocument()
    expect(within(card).queryByRole('button', { name: 'See all' })).not.toBeInTheDocument()
  })

  it('lists what is waiting and opens the screen that decides each, on the right tab', async () => {
    inbox = {
      total: 7, byKind: {},
      items: [{ kind: 'MEMO_REVIEW', label: 'Memo to review', summary: 'MEMO-2026-0007 - Transfer to beneficiary', by: 'maker', at: '2026-10-31T09:00:00', view: 'accounting', tab: 'Memos' }],
    }
    roles = ['ROLE_ACCOUNTANT']
    const open = renderHome()
    const card = await screen.findByRole('region', { name: 'Waiting for you' })
    expect(await within(card).findByText(/Waiting for you \(7\)/)).toBeInTheDocument()
    const user = userEvent.setup()
    await user.click(within(card).getByRole('button', { name: /MEMO-2026-0007/ }))
    expect(open).toHaveBeenCalledWith('accounting', 'Memos')
    await user.click(within(card).getByRole('button', { name: 'See all' }))
    expect(open).toHaveBeenCalledWith('approvals')
  })
})
