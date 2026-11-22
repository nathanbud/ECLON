import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Inbox, Me } from './api'
import ApprovalsPage from './ApprovalsPage'
import { AppThemeProvider } from './theme'

vi.mock('./session', () => ({ useMe: () => ({ username: 'u', name: 'Una', roles: ['ROLE_ACCOUNTANT'], canEdit: false, canApprove: true, admin: false, mustChangePassword: false }) as Me }))
// the two panels that decide things on this page have their own tests
vi.mock('./MemberRequests', () => ({ default: () => null }))
vi.mock('./WorkflowInbox', () => ({ default: () => null }))

let inbox: Inbox = { items: [], total: 0, byKind: {} }
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  getInbox: vi.fn(async () => inbox),
  listChanges: vi.fn(async () => []),
}))

function renderPage() {
  const open = vi.fn()
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider><ApprovalsPage onOpenClient={vi.fn()} onOpen={open} /></AppThemeProvider></QueryClientProvider>)
  return open
}

describe('Waiting for you', () => {
  beforeEach(() => { inbox = { items: [], total: 0, byKind: {} } })

  it('says so when nothing is waiting', async () => {
    renderPage()
    expect(await screen.findByText('Nothing is waiting for you')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: 'Waiting for you' })).toBeInTheDocument()
  })

  it('lists everything in one table and opens the screen that decides each item', async () => {
    inbox = {
      total: 3, byKind: {},
      items: [
        { kind: 'JOURNAL_REVIEW', label: 'Journal to review', summary: 'J-1 - Month end', by: 'maker', at: '2026-10-30T09:00:00', view: 'backoffice', tab: 'General journal' },
        { kind: 'MEMO_POST', label: 'Memo ready to post', summary: 'MEMO-2026-0007 - Transfer', by: 'maker', at: '2026-10-31T09:00:00', view: 'backoffice', tab: 'Debit/credit memos' },
        { kind: 'MEMBER_REQUEST', label: 'Close membership', summary: 'Close 100 (Reyes, Ana)', by: 'teller', at: '2026-10-31T10:00:00', view: 'approvals', tab: null },
      ],
    }
    const open = renderPage()
    const table = await screen.findByRole('table', { name: 'Everything waiting for you' })
    const rows = within(table).getAllByRole('row')
    expect(rows).toHaveLength(4)
    const user = userEvent.setup()
    await user.click(within(rows[2]).getByRole('button', { name: 'Open' }))
    expect(open).toHaveBeenCalledWith('backoffice', 'Debit/credit memos')
    expect(within(rows[3]).getByText('Decide below')).toBeInTheDocument()
    expect(within(rows[3]).queryByRole('button', { name: 'Open' })).not.toBeInTheDocument()
  })
})
