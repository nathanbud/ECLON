import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { JournalSummary, MemoDetail, MemoSummary, Me } from './api'
import BackOfficePage from './BackOfficePage'
import { AppThemeProvider } from './theme'

let username = 'maker'
let isAdmin = false
vi.mock('./session', () => ({ useMe: () => ({ username, name: 'Someone', roles: ['ROLE_ACCOUNTANT'], canEdit: false, canApprove: false, admin: isAdmin, mustChangePassword: false, branchCode: 1 }) as Me }))
vi.mock('./ui/BranchSelect', () => ({ BranchSelect: () => null }))
vi.mock('./PenaltyWaiversTab', () => ({ default: () => <p>Penalty waivers list</p> }))

const memoBase: MemoSummary = {
  id: 7, branchCode: 1, reference: 'MEMO-2026-0007', memoDate: '2026-10-31', description: 'Transfer to beneficiary, authorised by the family', status: 'FOR_REVIEW', createdBy: 'maker', approvedBy: null,
  postedBy: null, total: 500, reverses: null, documentNo: null,
}
let memo: MemoSummary = memoBase
const memoDetail = (): MemoDetail => ({
  summary: memo,
  lines: [
    { lineNo: 1, kind: 'SAVINGS', clientId: 100, clientName: 'Reyes, Ana', typeCode: 1, type: 'SD - Regular', refNo: 5, refText: '000005', accountCode: null, accountTitle: null, debit: 500, credit: 0, note: null },
    { lineNo: 2, kind: 'SAVINGS', clientId: 200, clientName: 'Reyes, Juan', typeCode: 1, type: 'SD - Regular', refNo: 9, refText: '000009', accountCode: null, accountTitle: null, debit: 0, credit: 500, note: null },
  ],
  cancelReason: null,
})
const journals: JournalSummary[] = [
  { id: 2, branchCode: 1, reference: 'GJ-0002', journalDate: '2026-11-02', description: 'Accrued rent', status: 'APPROVED', createdBy: 'maker', approvedBy: 'checker', total: 1500 },
  { id: 1, branchCode: 1, reference: 'GJ-0001', journalDate: '2026-11-01', description: 'Office supplies', status: 'DRAFT', createdBy: 'maker', approvedBy: null, total: 250 },
]
const memoCreated = vi.fn(async (body: unknown) => ({ ...memoBase, status: 'DRAFT' as const, id: 8, description: (body as { description: string }).description }))
const journalCreated = vi.fn(async (body: unknown) => ({ ...journals[1], id: 3, reference: (body as { reference: string }).reference }))
const memoStepped = vi.fn(async () => memoBase)
const journalStepped = vi.fn(async () => journals[0])

vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  listMemos: vi.fn(async () => [memo]),
  getMemo: vi.fn(async () => memoDetail()),
  getMemoAccounts: vi.fn(async (_b: number, id: number) => ({
    clientId: id, clientName: id === 100 ? 'Reyes, Ana' : 'Reyes, Juan', clientStatus: 1,
    accounts: [{ kind: 'SAVINGS', typeCode: 1, type: 'SD - Regular', refNo: id === 100 ? 5 : 9, refText: id === 100 ? '000005' : '000009', status: 15, balance: 1200 }],
  })),
  createMemo: (body: unknown) => memoCreated(body),
  memoStep: () => memoStepped(),
  listJournals: vi.fn(async () => journals),
  getJournal: vi.fn(async (id: number) => ({ summary: journals.find((j) => j.id === id)!, lines: [{ accountCode: '50101', debit: 250, credit: 0 }, { accountCode: '10001', debit: 0, credit: 250 }] })),
  createJournal: (body: unknown) => journalCreated(body),
  journalStep: (...a: unknown[]) => journalStepped(...(a as [])),
  listBackOfficeTypes: vi.fn(async () => [
    { id: 1, name: 'General Journal', code: 'GJ', entries: 13351, lastUsed: '2026-04-24', entry: 'GJ', madeElsewhere: null },
    { id: 2, name: 'Debit/Credit Memo', code: 'DCM', entries: 303, lastUsed: '2026-03-14', entry: 'DCM', madeElsewhere: null },
    { id: 3, name: 'Transfer Member', code: 'TRC', entries: 0, lastUsed: null, entry: null, madeElsewhere: null },
    { id: 6, name: 'Remittance Journal', code: 'RJ', entries: 1248, lastUsed: '2026-04-24', entry: null, madeElsewhere: null },
    { id: 81, name: 'Reversing Entry', code: 'RVE', entries: 10164, lastUsed: '2026-04-24', entry: null, madeElsewhere: 'Made with Reverse… on a posted journal or memo.' },
  ]),
  searchPostingAccounts: vi.fn(async (_b: number, q: string) => [{ code: q.startsWith('1') ? '10001' : '50101', description: q.startsWith('1') ? 'Cash on hand' : 'Office supplies' }]),
}))

function show(initialTab?: string) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider><BackOfficePage initialTab={initialTab} /></AppThemeProvider></QueryClientProvider>)
}
const openMemo = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.click(await screen.findByText('MEMO-2026-0007'))
  return screen.findByText(/Reyes, Ana/)
}

describe('Back Office', () => {
  beforeEach(() => { username = 'maker'; isAdmin = false; memo = memoBase; memoCreated.mockClear(); journalCreated.mockClear(); memoStepped.mockClear(); journalStepped.mockClear() })

  it('is one entry window: the type on top, the toolbar, and the entries of that type to open', async () => {
    show()
    expect(screen.getByRole('heading', { name: 'Back Office' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Trans. type' })).toHaveTextContent('General Journal')
    const bar = screen.getByRole('toolbar', { name: 'Entry' })
    for (const name of ['Find', 'New', 'Save', 'Cancel', 'Previous', 'Next']) expect(within(bar).getByRole('button', { name })).toBeInTheDocument()
    expect(within(bar).getByRole('button', { name: 'Save' })).toBeDisabled()
    const row = (await screen.findByText('GJ-0001')).closest('tr')!
    expect(within(row).getByText('Draft')).toBeInTheDocument()
  })

  it('opens on the type the menu, the search or the inbox named, and shows penalty waivers as their own list', async () => {
    show('Debit/credit memos')
    expect(screen.getByRole('combobox', { name: 'Trans. type' })).toHaveTextContent('Debit/Credit Memo')
    expect(await screen.findByText('MEMO-2026-0007')).toBeInTheDocument()
    const user = userEvent.setup()
    await user.click(screen.getByRole('combobox', { name: 'Trans. type' }))
    await user.click(await screen.findByRole('option', { name: /^Penalty Waiver/ }))
    expect(screen.getByText('Penalty waivers list')).toBeInTheDocument()
    expect(screen.queryByRole('toolbar', { name: 'Entry' })).not.toBeInTheDocument()
  })

  it('shows a memo with its GL/SL entries, totals and no difference, and does not let the maker approve it', async () => {
    show('Debit/credit memos')
    const user = userEvent.setup()
    await openMemo(user)
    const grid = screen.getByRole('table', { name: 'GL/SL entries' })
    expect(within(grid).getByText('Reyes, Juan')).toBeInTheDocument()
    expect(within(screen.getByLabelText('Totals')).getByText(/Difference/)).toHaveTextContent('0.00')
    expect(screen.getByText(/someone other than the person who made this memo has to approve/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Approve' })).toBeDisabled()
  })

  it('lets another accountant approve a memo', async () => {
    username = 'checker'
    show('Debit/credit memos')
    const user = userEvent.setup()
    await openMemo(user)
    const approve = screen.getByRole('button', { name: 'Approve' })
    expect(approve).toBeEnabled()
    await user.click(approve)
    await waitFor(() => expect(memoStepped).toHaveBeenCalledTimes(1))
  })

  it('needs a third person to post a memo: neither the maker nor the approver can', async () => {
    memo = { ...memoBase, status: 'APPROVED', approvedBy: 'checker' }
    username = 'checker'
    show('Debit/credit memos')
    const user = userEvent.setup()
    await openMemo(user)
    expect(screen.getByRole('button', { name: 'Post' })).toBeDisabled()
    expect(screen.getByText(/third person/i)).toBeInTheDocument()
  })

  it('makes a memo between two members: picks each member’s account and saves equal debits and credits', async () => {
    show('Debit/credit memos')
    const user = userEvent.setup()
    await screen.findByText('MEMO-2026-0007')
    await user.click(screen.getByRole('button', { name: 'New' }))
    const save = screen.getByRole('button', { name: /save draft/i })
    expect(save).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
    await user.type(screen.getByLabelText(/description: the reason and who authorised it/i), 'Transfer to beneficiary, authorised by the family')

    const kinds = screen.getAllByRole('combobox', { name: 'Applies to' })
    for (const k of kinds) {
      await user.click(k)
      await user.click(await screen.findByRole('option', { name: 'Member savings' }))
    }
    const members = screen.getAllByLabelText('Member ID')
    await user.type(members[0], '100')
    await user.tab()
    await user.type(members[1], '200')
    await user.tab()
    await user.click((await screen.findAllByRole('combobox', { name: /Reyes, Ana/ }))[0])
    await user.click(await screen.findByRole('option', { name: /000005/ }))
    await user.click((await screen.findAllByRole('combobox', { name: /Reyes, Juan/ }))[0])
    await user.click(await screen.findByRole('option', { name: /000009/ }))

    await user.type(screen.getAllByLabelText('Debit')[0], '500')
    await user.type(screen.getAllByLabelText('Credit')[1], '500')
    await user.tab()
    await waitFor(() => expect(save).toBeEnabled())
    await user.click(save)
    await waitFor(() => expect(memoCreated).toHaveBeenCalledTimes(1))
    const body = memoCreated.mock.calls[0][0] as { lines: { kind: string; clientId: number; typeCode: number; refNo: number; debit: number; credit: number }[] }
    expect(body.lines[0]).toMatchObject({ kind: 'SAVINGS', clientId: 100, typeCode: 1, refNo: 5, debit: 500, credit: 0 })
    expect(body.lines[1]).toMatchObject({ kind: 'SAVINGS', clientId: 200, typeCode: 1, refNo: 9, debit: 0, credit: 500 })
  }, 45_000) // many typed fields and menus: about 18 s in jsdom, close to the default 20 s

  it('makes a general journal: needs the Ref. Number and balanced entries, and shows the difference until then', async () => {
    show()
    const user = userEvent.setup()
    await screen.findByText('GJ-0001')
    await user.click(screen.getByRole('button', { name: 'New' }))
    expect(screen.getByText(/To save, fill in the Ref. Number/)).toBeInTheDocument()
    await user.type(screen.getByLabelText(/Ref. Number/), 'GJ-0003')
    const pick = async (n: number, q: string, option: RegExp) => {
      await user.type(screen.getByRole('combobox', { name: `Entry ${n} account` }), q)
      await user.click(await screen.findByRole('option', { name: option }))
    }
    await pick(1, '5', /Office supplies/)
    await pick(2, '1', /Cash on hand/)
    await user.type(screen.getAllByLabelText('Debit')[0], '250')
    await user.tab()
    expect(within(screen.getByLabelText('Totals')).getByText(/Difference/)).toHaveTextContent('250.00')
    await user.type(screen.getAllByLabelText('Credit')[1], '250')
    await user.tab()
    const save = screen.getByRole('button', { name: /save draft/i })
    await waitFor(() => expect(save).toBeEnabled())
    await user.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(journalCreated).toHaveBeenCalledTimes(1))
    expect(journalCreated.mock.calls[0][0]).toMatchObject({ reference: 'GJ-0003', lines: [{ accountCode: '50101', debit: 250, credit: 0 }, { accountCode: '10001', debit: 0, credit: 250 }], controlAccountReason: null })
    // an accountant who is not an administrator is not offered to change a control account directly
    expect(screen.queryByLabelText(/changes a control account directly/)).toBeNull()
  })

  it('lets an administrator make a journal that changes a control account, only with a reason', async () => {
    isAdmin = true
    show()
    const user = userEvent.setup()
    await screen.findByText('GJ-0001')
    await user.click(screen.getByRole('button', { name: 'New' }))
    await user.type(screen.getByLabelText(/Ref. Number/), 'GJ-OPEN')
    const pick = async (n: number, q: string, option: RegExp) => {
      await user.type(screen.getByRole('combobox', { name: `Entry ${n} account` }), q)
      await user.click(await screen.findByRole('option', { name: option }))
    }
    await pick(1, '1', /Cash on hand/)
    await pick(2, '5', /Office supplies/)
    await user.type(screen.getAllByLabelText('Debit')[0], '100')
    await user.type(screen.getAllByLabelText('Credit')[1], '100')
    await user.click(screen.getByLabelText(/changes a control account directly/))
    expect(screen.getByText(/Use a debit\/credit memo to change members/)).toBeInTheDocument()
    expect(screen.getByText(/why this journal changes a control account/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /save draft/i })).toBeDisabled()
    await user.type(screen.getByLabelText(/Why it changes a control account/), 'Opening balance brought in at conversion')
    await waitFor(() => expect(screen.getByRole('button', { name: /save draft/i })).toBeEnabled())
    await user.click(screen.getByRole('button', { name: /save draft/i }))
    await waitFor(() => expect(journalCreated).toHaveBeenCalledTimes(1))
    expect(journalCreated.mock.calls[0][0]).toMatchObject({ reference: 'GJ-OPEN', controlAccountReason: 'Opening balance brought in at conversion' })
  })

  it('asks before throwing away an entry being made', async () => {
    show()
    const user = userEvent.setup()
    await screen.findByText('GJ-0001')
    await user.click(screen.getByRole('button', { name: 'New' }))
    await user.type(screen.getByLabelText(/Ref. Number/), 'GJ-0009')
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    const ask = await screen.findByRole('dialog', { name: 'Discard the entry?' })
    await user.click(within(ask).getByRole('button', { name: 'Keep editing' }))
    expect(screen.getByLabelText(/Ref. Number/)).toHaveValue('GJ-0009')
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    await user.click(within(await screen.findByRole('dialog', { name: 'Discard the entry?' })).getByRole('button', { name: 'Discard' }))
    expect(screen.queryByLabelText(/Ref. Number/)).not.toBeInTheDocument()
  })

  it('moves between entries with Page Up and Page Down, as in the old window', async () => {
    show()
    await screen.findByText('GJ-0001')
    fireEvent.keyDown(window, { key: 'PageDown' })
    expect(await screen.findByText('Accrued rent')).toBeInTheDocument()
    expect(screen.getByText('1 of 2')).toBeInTheDocument()
    fireEvent.keyDown(window, { key: 'PageDown' })
    expect(await screen.findByText('Office supplies')).toBeInTheDocument()
    expect(screen.getByText('2 of 2')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
    fireEvent.keyDown(window, { key: 'PageUp' })
    expect(await screen.findByText('1 of 2')).toBeInTheDocument()
  })

  it('lets the approver of a journal post it, since journals need only a different approver', async () => {
    username = 'checker'
    show()
    const user = userEvent.setup()
    await user.click(await screen.findByText('GJ-0002'))
    expect(await screen.findByRole('button', { name: 'Post' })).toBeEnabled()
  })

  it('lists every type of the old system, with its code and use, and says when one is not made here', async () => {
    show()
    const user = userEvent.setup()
    await screen.findByText('GJ-0001')
    await user.click(screen.getByRole('combobox', { name: 'Trans. type' }))
    expect(await screen.findByRole('option', { name: /General Journal.*GJ · 13,351 in the old data/ })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: /Transfer Member.*TRC · never used · not available yet/ })).toBeInTheDocument()
    await user.click(screen.getByRole('option', { name: /^Remittance Journal/ }))
    expect(screen.getByRole('heading', { name: /Remittance Journal/ })).toBeInTheDocument()
    expect(screen.getByText(/The old system has 1,248 of these, the last dated 04\/24\/2026/)).toBeInTheDocument()
    expect(screen.getByText(/does not make this type of entry yet/)).toBeInTheDocument()
    expect(screen.queryByRole('toolbar', { name: 'Entry' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('combobox', { name: 'Trans. type' }))
    await user.click(await screen.findByRole('option', { name: /^Reversing Entry/ }))
    expect(screen.getByText('Made with Reverse… on a posted journal or memo.')).toBeInTheDocument()
  })

  it('sorts the Find list by the Sort by choice, the reverse button, and the column headings', async () => {
    show()
    const user = userEvent.setup()
    await screen.findByText('GJ-0001')
    await user.click(screen.getByRole('button', { name: 'Find' }))
    const dialog = await screen.findByRole('dialog', { name: /Find a general journal/ })
    const order = () => within(dialog).getAllByRole('row').slice(1).map((r) => within(r).getAllByRole('cell')[0].textContent)
    expect(within(dialog).getByText(/2 of 2 · Date, newest first/)).toBeInTheDocument()
    expect(order()).toEqual(['GJ-0002', 'GJ-0001'])
    await user.click(within(dialog).getByRole('button', { name: /Order: Date, newest first. Reverse it/ }))
    expect(order()).toEqual(['GJ-0001', 'GJ-0002'])
    await user.click(within(dialog).getByRole('combobox', { name: 'Sort by' }))
    await user.click(await screen.findByRole('option', { name: 'Amount' }))
    expect(within(dialog).getByText(/Amount, largest first/)).toBeInTheDocument()
    expect(order()).toEqual(['GJ-0002', 'GJ-0001'])
    await user.click(within(dialog).getByRole('button', { name: 'Status' }))
    expect(within(dialog).getByText(/Status \(by step\), first step first/)).toBeInTheDocument()
    expect(order()).toEqual(['GJ-0001', 'GJ-0002'])
  })

  it('shows where each entry stands as steps, like the loan applications: draft, review, posting, then posted', async () => {
    show()
    const draft = (await screen.findByText('GJ-0001')).closest('tr')!
    expect(within(draft).getByRole('img', { name: /At step 1 of 3: Draft, waiting for being sent for review/ })).toBeInTheDocument()
    const approved = screen.getByText('GJ-0002').closest('tr')!
    expect(within(approved).getByRole('img', { name: /At step 3 of 3: Posting, waiting for posting. Done: Draft, Review./ })).toBeInTheDocument()
    expect(within(approved).getByText('Posting')).toBeInTheDocument()
  })
})
