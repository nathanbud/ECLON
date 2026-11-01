import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { MemoDetail, MemoSummary, Me } from './api'
import MemosTab from './MemosTab'
import { AppThemeProvider } from './theme'

let username = 'maker'
vi.mock('./session', () => ({ useMe: () => ({ username, name: 'Someone', roles: ['ROLE_ACCOUNTANT'], canEdit: false, canApprove: false, admin: false, mustChangePassword: false }) as Me }))

const base: MemoSummary = {
  id: 7, branchCode: 1, reference: 'MEMO-2026-0007', memoDate: '2026-10-31', description: 'Transfer to beneficiary, authorised by the family', status: 'FOR_REVIEW', createdBy: 'maker', approvedBy: null,
  postedBy: null, total: 500, reverses: null, documentNo: null,
}
let summary: MemoSummary = base
const detail = (): MemoDetail => ({
  summary,
  lines: [
    { lineNo: 1, kind: 'SAVINGS', clientId: 100, clientName: 'Reyes, Ana', typeCode: 1, type: 'SD - Regular', refNo: 5, refText: '000005', accountCode: null, accountTitle: null, debit: 500, credit: 0, note: null },
    { lineNo: 2, kind: 'SAVINGS', clientId: 200, clientName: 'Reyes, Juan', typeCode: 1, type: 'SD - Regular', refNo: 9, refText: '000009', accountCode: null, accountTitle: null, debit: 0, credit: 500, note: null },
  ],
  cancelReason: null,
})
const created = vi.fn(async (body: unknown) => ({ ...base, status: 'DRAFT' as const, id: 8, description: (body as { description: string }).description }))
const stepped = vi.fn(async () => base)

vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  listMemos: vi.fn(async () => [summary]),
  getMemo: vi.fn(async () => detail()),
  getMemoAccounts: vi.fn(async (_b: number, id: number) => ({
    clientId: id, clientName: id === 100 ? 'Reyes, Ana' : 'Reyes, Juan', clientStatus: 1,
    accounts: [{ kind: 'SAVINGS', typeCode: 1, type: 'SD - Regular', refNo: id === 100 ? 5 : 9, refText: id === 100 ? '000005' : '000009', status: 15, balance: 1200 }],
  })),
  createMemo: (body: unknown) => created(body),
  memoStep: () => stepped(),
}))

function renderTab() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider><MemosTab branchCode={1} /></AppThemeProvider></QueryClientProvider>)
}

describe('Memos tab', () => {
  beforeEach(() => { username = 'maker'; summary = base; created.mockClear(); stepped.mockClear() })

  it('lists the memos with their status, date month first and amount', async () => {
    renderTab()
    const row = (await screen.findByText('MEMO-2026-0007')).closest('tr')!
    expect(within(row).getByText('10/31/2026')).toBeInTheDocument()
    expect(within(row).getByText('500.00')).toBeInTheDocument()
    expect(within(row).getByText('For review')).toBeInTheDocument()
  })

  it('does not let the maker approve their own memo, and says why', async () => {
    renderTab()
    const user = userEvent.setup()
    await user.click(await screen.findByText('MEMO-2026-0007'))
    const dialog = await screen.findByRole('dialog')
    expect(await within(dialog).findByText(/Reyes, Ana/)).toBeInTheDocument()
    expect(within(dialog).getByText(/someone other than the person who made this memo has to approve/i)).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Approve' })).toBeDisabled()
  })

  it('lets another accountant approve it', async () => {
    username = 'checker'
    renderTab()
    const user = userEvent.setup()
    await user.click(await screen.findByText('MEMO-2026-0007'))
    const dialog = await screen.findByRole('dialog')
    const approve = await within(dialog).findByRole('button', { name: 'Approve' })
    expect(approve).toBeEnabled()
    await user.click(approve)
    await waitFor(() => expect(stepped).toHaveBeenCalledTimes(1))
  })

  it('needs a third person to post: neither the maker nor the approver can', async () => {
    summary = { ...base, status: 'APPROVED', approvedBy: 'checker' }
    username = 'checker'
    renderTab()
    const user = userEvent.setup()
    await user.click(await screen.findByText('MEMO-2026-0007'))
    const dialog = await screen.findByRole('dialog')
    expect(await within(dialog).findByRole('button', { name: 'Post' })).toBeDisabled()
    expect(within(dialog).getByText(/third person/i)).toBeInTheDocument()
  })

  it('makes a transfer between two members: picks each member’s account and sends equal debits and credits', async () => {
    renderTab()
    const user = userEvent.setup()
    await screen.findByText('MEMO-2026-0007')
    await user.click(screen.getByRole('button', { name: /new memo/i }))
    const dialog = await screen.findByRole('dialog')
    const save = within(dialog).getByRole('button', { name: /save draft/i })
    expect(save).toBeDisabled()
    await user.type(within(dialog).getByLabelText(/reason and who authorised/i), 'Transfer to beneficiary, authorised by the family')

    const members = within(dialog).getAllByLabelText('Member no.')
    await user.type(members[0], '100')
    await user.tab()
    await user.type(members[1], '200')
    await user.tab()
    const accounts = await within(dialog).findAllByRole('combobox', { name: /Reyes/ })
    await user.click(accounts[0])
    await user.click(await screen.findByRole('option', { name: /000005/ }))
    await user.click((await within(dialog).findAllByRole('combobox', { name: /Reyes, Juan/ }))[0])
    await user.click(await screen.findByRole('option', { name: /000009/ }))

    const debits = within(dialog).getAllByLabelText('Debit')
    const credits = within(dialog).getAllByLabelText('Credit')
    await user.type(debits[0], '500')
    await user.type(credits[1], '500')
    await user.tab()
    expect(await within(dialog).findByText(/Debits 500.00 \/ credits 500.00/)).toBeInTheDocument()
    await waitFor(() => expect(save).toBeEnabled())
    await user.click(save)
    await waitFor(() => expect(created).toHaveBeenCalledTimes(1))
    const body = created.mock.calls[0][0] as { lines: { kind: string; clientId: number; typeCode: number; refNo: number; debit: number; credit: number }[] }
    expect(body.lines[0]).toMatchObject({ kind: 'SAVINGS', clientId: 100, typeCode: 1, refNo: 5, debit: 500, credit: 0 })
    expect(body.lines[1]).toMatchObject({ kind: 'SAVINGS', clientId: 200, typeCode: 1, refNo: 9, debit: 0, credit: 500 })
  })
})
