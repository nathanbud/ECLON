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
let inboxFails = false
// when set, the inbox answers only once release() is called
let inboxPending = false
let release: (() => void) | null = null
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  getInbox: vi.fn(async () => {
    if (inboxPending) await new Promise<void>((done) => { release = done })
    if (inboxFails) throw new Error('Cannot reach the server')
    return inbox
  }),
  getTdMaturityCount: vi.fn(async () => [2]),
}))

function renderHome() {
  const open = vi.fn()
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider><Dashboard onOpen={open} /></AppThemeProvider></QueryClientProvider>)
  return open
}

describe('the home page', () => {
  beforeEach(() => { roles = []; admin = false; inboxFails = false; inboxPending = false; release = null; inbox = { items: [], total: 0, byKind: {} } })

  it('greets the person and groups tiles by what they are doing', async () => {
    renderHome()
    expect(await screen.findByRole('heading', { level: 1, name: /Una/ })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Members' })).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Members' })).getByRole('button', { name: /Member Profile/ })).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Teller and cash' })).getByRole('button', { name: /Front Office/ })).toBeInTheDocument()
  })

  it('keeps Member Profile at full size and draws the other tiles a quarter smaller', () => {
    roles = ['ROLE_ACCOUNTANT']
    renderHome()
    expect(screen.getByRole('button', { name: 'Member Profile' })).toHaveStyle({ width: '112px', height: '112px' })
    for (const name of ['KYC review', 'Accounting', 'Reports']) {
      const tile = screen.queryByRole('button', { name })
      if (tile) expect(tile).toHaveStyle({ width: '84px', height: '84px' })
    }
    expect(screen.getByRole('button', { name: /^Front Office/ })).toHaveStyle({ width: '84px' })
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

  it('says All caught up only when the inbox answered with nothing', async () => {
    renderHome()
    expect(await screen.findByText('All caught up')).toBeInTheDocument()
  })

  it('says it could not check, not All caught up, when the inbox does not answer', async () => {
    inboxFails = true
    renderHome()
    expect(await screen.findByText("Couldn't check what's waiting")).toBeInTheDocument()
    expect(screen.queryByText('All caught up')).not.toBeInTheDocument()
    const card = screen.getByRole('region', { name: 'Waiting for you' })
    expect(card).toHaveTextContent("Couldn't check what is waiting for you")
    expect(within(card).queryByText('Nothing is waiting for you.')).not.toBeInTheDocument()
  })

  it('says when nothing is waiting', async () => {
    renderHome()
    const card = await screen.findByRole('region', { name: 'Waiting for you' })
    expect(await within(card).findByText('Nothing is waiting for you.')).toBeInTheDocument()
    expect(within(card).queryByRole('button', { name: 'See all' })).not.toBeInTheDocument()
  })

  it('draws the waiting area and every tile from the first frame, before the inbox answers, so nothing moves when it does', async () => {
    inboxPending = true
    renderHome()
    // at once, with no waiting: the tiles and the waiting area in its place, marked busy
    const card = screen.getByRole('region', { name: 'Waiting for you' })
    expect(card).toHaveAttribute('aria-busy', 'true')
    expect(within(card).getByLabelText('Checking what is waiting')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Members' })).getByRole('button', { name: /Member Profile/ })).toBeVisible()
    // the waiting area comes first on the board, before the other areas
    const regions = screen.getAllByRole('region').map((r) => r.getAttribute('aria-label'))
    expect(regions[0]).toBe('Waiting for you')
    // the answer fills the same area
    inboxPending = false
    release?.()
    expect(await within(card).findByText('Nothing is waiting for you.')).toBeInTheDocument()
    expect(card).toHaveAttribute('aria-busy', 'false')
    expect(screen.getAllByRole('region').map((r) => r.getAttribute('aria-label'))).toEqual(regions)
  })

  it('lists what is waiting and opens the screen that decides each, on the right tab', async () => {
    inbox = {
      total: 7, byKind: {},
      items: [{ kind: 'MEMO_REVIEW', label: 'Memo to review', summary: 'MEMO-2026-0007 - Transfer to beneficiary', by: 'maker', at: '2026-10-31T09:00:00', view: 'backoffice', tab: 'Debit/credit memos' }],
    }
    roles = ['ROLE_ACCOUNTANT']
    const open = renderHome()
    const card = await screen.findByRole('region', { name: 'Waiting for you' })
    expect(await within(card).findByText(/Waiting for you \(7\)/)).toBeInTheDocument()
    const user = userEvent.setup()
    await user.click(within(card).getByRole('button', { name: /MEMO-2026-0007/ }))
    expect(open).toHaveBeenCalledWith('backoffice', 'Debit/credit memos')
    await user.click(within(card).getByRole('button', { name: 'See all (6 more)' }))
    expect(open).toHaveBeenCalledWith('approvals')
  })

  it('shows five waiting items as thin rows, each naming its area of work, and the rest under See all', async () => {
    const item = (kind: string, label: string, summary: string) => ({ kind, label, summary, by: 'maker', at: '2026-10-31T09:00:00', view: 'approvals', tab: null })
    inbox = {
      total: 7, byKind: {},
      items: [
        item('LOAN_APPROVE', 'Loan: approve', 'Dela Cruz, Juan · 50,000.00'),
        item('CASH_VOUCHER_POST', 'Post cash voucher', 'Voucher 1201 · 3,000.00'),
        item('JOURNAL_REVIEW', 'Journal to review', 'JV-2026-0001'),
        item('CHANGE', 'Profile change', 'Changes to Reyes, Ana'),
        item('BUSINESS_DAY_END', 'End the business day', 'The business day is still open'),
        item('PURCHASE_ORDER', 'Purchase order', 'PO-2026-0002'),
        item('WORKFLOW', 'Workflow step', 'Something else'),
      ],
    }
    renderHome()
    const card = await screen.findByRole('region', { name: 'Waiting for you' })
    expect(await within(card).findByRole('button', { name: /^Loans: Loan: approve\. Dela Cruz, Juan/ })).toBeInTheDocument()
    for (const [area, label] of [['Teller', 'Post cash voucher'], ['Accounting', 'Journal to review'], ['Members', 'Profile change'], ['Business day', 'End the business day']]) {
      expect(within(card).getByRole('button', { name: new RegExp(`^${area}: ${label}`) })).toBeInTheDocument()
    }
    // the sixth and seventh wait under See all
    expect(within(card).queryByText('Purchase order')).not.toBeInTheDocument()
    expect(within(card).getAllByRole('button').filter((b) => !/^See all/.test(b.textContent ?? ''))).toHaveLength(5)
    expect(within(card).getByRole('button', { name: 'See all (2 more)' })).toBeInTheDocument()
    // the area is written on the row too, not shown by colour alone
    expect(within(card).getByText('Loans')).toBeInTheDocument()
  })
})
