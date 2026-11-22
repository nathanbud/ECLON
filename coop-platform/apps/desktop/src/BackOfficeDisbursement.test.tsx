import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { DraftPostPlan, FoDraftPayload, Me, VoucherSetup, WaitingVoucher } from './api'
import BackOfficePage from './BackOfficePage'
import { AppThemeProvider } from './theme'

vi.mock('./session', () => ({ useMe: () => ({ username: 'maker', name: 'Maker', roles: ['ROLE_ACCOUNTANT'], canEdit: false, canApprove: false, admin: false, mustChangePassword: false, branchCode: 1 }) as Me }))
vi.mock('./ui/BranchSelect', () => ({ BranchSelect: () => null }))

let setup: VoucherSetup
let plan: DraftPostPlan
const ready: DraftPostPlan = { kind: 'CASH_VOUCHER', kindWords: 'Cash Voucher', postsHow: null, lines: [], amount: 0, drawerCash: null, problems: [], needs: [], ready: true }
const waiting: WaitingVoucher = {
  year: 2026, code: 21, type: 'Cash Voucher', controlNo: 812, documentNo: '00000812', date: '2026-11-18', clientId: 100, clientName: 'Reyes, Ana', explanation: 'Reimbursement of seminar fees',
  amount: 1200, status: 'WAITING FOR APPROVAL', encodedBy: 7, encodedByName: 'maker', approvedBy: null, approvedByName: null, cheque: null,
}
const created = vi.fn(async (p: FoDraftPayload) => ({ id: 55, payload: p, totals: { debit: 0, credit: 0, difference: 0, balanced: true }, updatedAt: '' }))
const checked = vi.fn(async () => plan)
const posted = vi.fn(async () => ({ kind: 'CASH_VOUCHER' as const, year: 2026, code: 21, controlNo: 813, documentNo: '00000813', date: '2026-11-19', amount: 1500, status: 'WAITING FOR APPROVAL' }))
const deleted = vi.fn(async () => undefined)
const approved = vi.fn(async () => ({ voucher: waiting, lines: [], mayApprove: false, mayPost: false, problems: [] }))
let mayApprove = false

vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  listJournals: vi.fn(async () => []),
  listBackOfficeTypes: vi.fn(async (module: number) => module === 2
    ? [
        { id: 20, name: 'Loan Voucher', code: 'LV', entries: 2146, lastUsed: '2026-04-24', entry: null, madeElsewhere: 'Made by the Loan Module when a loan application is approved; released on Front Office > Disburse.' },
        { id: 21, name: 'Cash Voucher', code: 'CV', entries: 39884, lastUsed: '2026-04-24', entry: 'CV', madeElsewhere: null },
        { id: 22, name: 'Check Voucher', code: 'CKV', entries: 1153, lastUsed: '2026-04-20', entry: 'CKV', madeElsewhere: null },
        { id: 25, name: 'Encashment', code: 'ENC', entries: 0, lastUsed: null, entry: null, madeElsewhere: null },
      ]
    : [{ id: 1, name: 'General Journal', code: 'GJ', entries: 1, lastUsed: null, entry: 'GJ', madeElsewhere: null }]),
  getVoucherSetup: vi.fn(async () => setup),
  getWaitingVouchers: vi.fn(async () => [waiting]),
  getWaitingVoucher: vi.fn(async () => ({
    voucher: waiting, mayApprove, mayPost: false, problems: mayApprove ? [] : ['You encoded this voucher. Someone else approves it.'],
    lines: [{ seq: 1, account: '50101', description: 'Seminars', slc: null, slt: null, refNo: null, amount: 1200 }, { seq: 2, account: '20201', description: 'Vouchers payable', slc: 29, slt: 1, refNo: 3, amount: -1200 }],
  })),
  approveWaitingVoucher: () => approved(),
  getMember: vi.fn(async (_b: number, id: number) => ({ id: { branchCode: 1, clientId: id }, accountName: id === 100 ? 'Reyes, Ana' : 'Cruz, Ben', firstName: null, lastName: null })),
  getBankCashBanks: vi.fn(async () => [{ bankId: 3, name: 'Land Bank current', ledgerAccount: '10201', event: 61 }]),
  searchPostingAccounts: vi.fn(async () => [{ code: '50101', description: 'Seminars and trainings' }]),
  createFoDraft: (p: FoDraftPayload) => created(p),
  checkFoDraftPost: (...a: unknown[]) => checked(...(a as [])),
  postFoDraft: (...a: unknown[]) => posted(...(a as [])),
  deleteFoDraft: (...a: unknown[]) => deleted(...(a as [])),
}))

function show() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider><BackOfficePage initialTab="Disbursements" /></AppThemeProvider></QueryClientProvider>)
}
type User = ReturnType<typeof userEvent.setup>
const fillVoucher = async (user: User) => {
  await user.type(screen.getByLabelText(/^Member ID/), '200')
  await user.tab()
  expect(await screen.findByDisplayValue('Cruz, Ben')).toBeInTheDocument()
  await user.type(screen.getByRole('combobox', { name: 'Entry 1 account' }), '501')
  await user.click(await screen.findByRole('option', { name: /Seminars and trainings/ }))
  await user.type(screen.getByLabelText('Debit'), '1500')
  await user.type(screen.getByLabelText(/^Description/), 'Speaker fee for the members seminar')
  await user.tab()
}

describe('Back Office: Disbursement Transactions', () => {
  beforeEach(() => {
    setup = { payableAccount: '20201', payableAccountName: 'Vouchers payable', dept: 2, mayCashVoucher: true, mayCheckVoucher: true, problem: null }
    plan = ready
    mayApprove = false
    for (const f of [created, checked, posted, deleted, approved]) f.mockClear()
  })

  it('is the second window, with every disbursement type of the old system and the vouchers waiting', async () => {
    show()
    expect(screen.getByRole('tab', { name: 'Disbursement Transactions' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('combobox', { name: 'Trans. type' })).toHaveTextContent('Cash Voucher')
    const row = (await screen.findByText('00000812')).closest('tr')!
    expect(within(row).getByRole('img', { name: /At step 2 of 3: Review/ })).toBeInTheDocument()
    const user = userEvent.setup()
    await user.click(screen.getByRole('combobox', { name: 'Trans. type' }))
    expect(await screen.findByRole('option', { name: /Check Voucher.*CKV · 1,153 in the old data/ })).toBeInTheDocument()
    await user.click(screen.getByRole('option', { name: /^Loan Voucher/ }))
    expect(screen.getByText(/Made by the Loan Module/)).toBeInTheDocument()
    expect(screen.queryByRole('toolbar', { name: 'Entry' })).not.toBeInTheDocument()
  })

  it('makes a Cash Voucher: the debits typed, the payee’s vouchers payable credited by the window, then waits for approval', async () => {
    show()
    const user = userEvent.setup()
    await screen.findByText('00000812')
    await user.click(screen.getByRole('button', { name: 'New' }))
    expect(screen.getByText(/20201/)).toBeInTheDocument()
    await fillVoucher(user)
    const save = screen.getByRole('button', { name: /save and send for approval/i })
    await waitFor(() => expect(save).toBeEnabled())
    await user.click(save)
    await waitFor(() => expect(posted).toHaveBeenCalledTimes(1))
    const p = created.mock.calls[0][0]
    expect(p).toMatchObject({ trCode: 21, clientId: 200, clientName: 'Cruz, Ben', dept: 2, explanation: 'Speaker fee for the members seminar' })
    expect(p.lines).toEqual([
      expect.objectContaining({ accountCode: '50101', debit: '1500.00', credit: '0' }),
      expect.objectContaining({ accountCode: '20201', debit: '0', credit: '1500.00' }),
    ])
    expect(deleted).not.toHaveBeenCalled()
  }, 45_000)

  it('leaves nothing behind when the server will not encode it, and says why', async () => {
    plan = { ...ready, ready: false, problems: ['Your account has no department set, so this cannot be posted.'] }
    show()
    const user = userEvent.setup()
    await screen.findByText('00000812')
    await user.click(screen.getByRole('button', { name: 'New' }))
    await fillVoucher(user)
    await user.click(screen.getByRole('button', { name: /save and send for approval/i }))
    expect(await screen.findByText(/no department set/)).toBeInTheDocument()
    expect(deleted).toHaveBeenCalledTimes(1)
    expect(posted).not.toHaveBeenCalled()
  }, 45_000)

  it('makes a Check Voucher with the cooperative’s cheque: the bank credited, the payee on the cheque', async () => {
    show()
    const user = userEvent.setup()
    await screen.findByText('00000812')
    await user.click(screen.getByRole('combobox', { name: 'Trans. type' }))
    await user.click(await screen.findByRole('option', { name: /^Check Voucher/ }))
    await user.click(screen.getByRole('button', { name: 'New' }))
    await fillVoucher(user)
    expect(screen.getByText(/To save, fill in the And\/Or/)).toBeInTheDocument()
    const payee = screen.getByLabelText(/^And\/Or/)
    await user.click(payee)
    expect(payee).toHaveValue('Cruz, Ben') // the client is the payee unless another name is typed
    await user.clear(payee)
    await user.type(payee, 'Ben Cruz')
    await user.click(screen.getByRole('combobox', { name: /Bank account/ }))
    await user.click(await screen.findByRole('option', { name: /Land Bank current/ }))
    await user.type(screen.getByLabelText(/^Cheque number/), '00451')
    const save = screen.getByRole('button', { name: /save and send for approval/i })
    await waitFor(() => expect(save).toBeEnabled())
    await user.click(save)
    await waitFor(() => expect(posted).toHaveBeenCalledTimes(1))
    expect(created.mock.calls[0][0].lines[1]).toMatchObject({ accountCode: '10201', credit: '1500.00' })
    expect(posted.mock.calls[0]).toEqual(expect.arrayContaining([55, expect.objectContaining({ cheque: expect.objectContaining({ bankId: 3, number: '00451', payee: 'Ben Cruz' }) })]))
  }, 60_000)

  it('does not offer New without the right to make that voucher, and says why', async () => {
    setup = { ...setup, mayCashVoucher: false }
    show()
    await screen.findByText('00000812')
    expect(screen.getByRole('button', { name: 'New' })).toBeDisabled()
  })

  it('shows a waiting voucher with its entries, and approves only for someone other than the encoder', async () => {
    show()
    const user = userEvent.setup()
    await user.click(await screen.findByText('00000812'))
    expect(await screen.findByText('Seminars')).toBeInTheDocument()
    expect(within(screen.getByLabelText('Totals')).getByText(/Difference/)).toHaveTextContent('0.00')
    expect(screen.getByRole('button', { name: 'Approve' })).toBeDisabled()
  })

  it('lets another person approve it', async () => {
    mayApprove = true
    show()
    const user = userEvent.setup()
    await user.click(await screen.findByText('00000812'))
    const approve = await screen.findByRole('button', { name: 'Approve' })
    await waitFor(() => expect(approve).toBeEnabled())
    await user.click(approve)
    await waitFor(() => expect(approved).toHaveBeenCalledTimes(1))
  })
})
