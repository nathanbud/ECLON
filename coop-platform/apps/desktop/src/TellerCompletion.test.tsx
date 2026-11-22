import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { DraftPostPlan, FoSetup, WaitingVoucher } from './api'
import DraftPostDialog from './DraftPostDialog'
import DrawerOpen from './DrawerOpen'
import { WaitingVouchersPanel } from './WaitingVouchersPanel'
import { AppThemeProvider } from './theme'

const check = vi.fn()
const post = vi.fn()
const waiting = vi.fn()
const detail = vi.fn()
const approve = vi.fn()
const postVoucher = vi.fn()
const mine = vi.fn()
const open = vi.fn()
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  checkFoDraftPost: (...a: unknown[]) => check(...a),
  postFoDraft: (...a: unknown[]) => post(...a),
  getBankCashBanks: () => Promise.resolve([{ bankId: 33, name: 'SecBank-LTB', ledgerAccount: '10020208', event: 61 }]),
  getChequeSetup: () => Promise.resolve({ banks: [], kinds: [] }),
  getWaitingVouchers: () => waiting(),
  getWaitingVoucher: () => detail(),
  approveWaitingVoucher: () => approve(),
  postWaitingVoucher: () => postVoucher(),
  getDrawerOpen: () => mine(),
  getDrawersOpenedToday: () => Promise.resolve([]),
  openDrawer: (...a: unknown[]) => open(...a),
}))

const show = (ui: ReactNode) =>
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AppThemeProvider>{ui}</AppThemeProvider></QueryClientProvider>)

const receiptPlan: DraftPostPlan = {
  kind: 'OTHER_RECEIPT', kindWords: 'Other receipt', postsHow: 'Posted at once on your drawer. It cannot be edited afterwards.',
  lines: [{ account: '10003', description: 'Cash received at your drawer', amount: 150 }, { account: '4416', description: 'Membership fees', amount: -150 }],
  amount: 150, drawerCash: 2000, problems: [], needs: [], ready: true,
}

describe('Posting a front office draft', () => {
  beforeEach(() => { check.mockReset(); post.mockReset() })

  it('shows what will be written and posts an other receipt only after it is confirmed', async () => {
    check.mockResolvedValue(receiptPlan)
    post.mockResolvedValue({ kind: 'OTHER_RECEIPT', year: 2026, code: 10, controlNo: 353001, documentNo: '*0353001', date: '2026-11-17', amount: 150, status: 'POSTED' })
    const posted = vi.fn()
    show(<DraftPostDialog draftId={7} payor="A PAYOR" onClose={() => {}} onPosted={posted} />)
    const table = await screen.findByRole('table', { name: 'What will be written' })
    expect(within(table).getByText('Membership fees')).toBeInTheDocument()
    expect(screen.getByText('Your drawer holds 2,000.00.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Post' }))
    expect(post).not.toHaveBeenCalled()
    expect(await screen.findByText(/is posted to the ledger now/)).toBeInTheDocument()
    await userEvent.click(screen.getAllByRole('button', { name: 'Post' }).at(-1)!)
    await waitFor(() => expect(posted).toHaveBeenCalledOnce())
    expect(post.mock.calls[0][0]).toBe(7)
    expect(typeof post.mock.calls[0][2]).toBe('string') // the request key, so a second press does not post twice
  })

  it('will not post while the server names a problem, and says it', async () => {
    check.mockResolvedValue({ ...receiptPlan, ready: false, problems: ['Entry 2 is on a member\'s account. Take member payments on the Teller tab, which checks them.'] })
    show(<DraftPostDialog draftId={7} payor="" onClose={() => {}} onPosted={() => {}} />)
    expect(await screen.findByText(/Take member payments on the Teller tab/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Post' })).toBeDisabled()
  })

  it('sends a Check Voucher for approval with the cooperative\'s cheque', async () => {
    check.mockResolvedValue({ ...receiptPlan, kind: 'CHECK_VOUCHER', kindWords: 'Check Voucher', postsHow: 'Waits for another person\'s approval, then is posted with its cheque.', drawerCash: null })
    show(<DraftPostDialog draftId={9} payor="ITEST SUPPLIER" onClose={() => {}} onPosted={() => {}} />)
    expect(await screen.findByRole('button', { name: 'Send for approval' })).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('Cheque number'), '12ab34')
    expect(screen.getByLabelText('Cheque number')).toHaveValue('1234')
    expect(screen.getByLabelText('Pay to')).toHaveValue('ITEST SUPPLIER')
    await waitFor(() => expect(check.mock.calls.at(-1)?.[1]).toMatchObject({ cheque: { number: '1234', payee: 'ITEST SUPPLIER' } }))
  })
})

const voucher: WaitingVoucher = {
  year: 2026, code: 21, type: 'Cash Voucher', controlNo: 40100, documentNo: '*0040100', date: '2026-11-17', clientId: 92344, clientName: 'Cruz, Ana',
  explanation: 'PMES snacks', amount: 500, status: 'WAITING FOR APPROVAL', encodedBy: 7, encodedByName: 'Ben Uy', approvedBy: null, approvedByName: null, cheque: null,
}

describe('Vouchers waiting', () => {
  beforeEach(() => { waiting.mockReset(); detail.mockReset(); approve.mockReset(); postVoucher.mockReset() })

  it('lists them and lets someone other than the encoder approve after confirming', async () => {
    waiting.mockResolvedValue([voucher])
    detail.mockResolvedValue({ voucher, lines: [{ seq: 0, account: '30302', description: 'Coop. education and training funds', slc: null, slt: null, refNo: null, amount: 500 }], mayApprove: true, mayPost: false, problems: [] })
    approve.mockResolvedValue({ voucher: { ...voucher, status: 'APPROVED' }, lines: [], mayApprove: false, mayPost: false, problems: [] })
    show(<WaitingVouchersPanel />)
    expect(await screen.findByText(/Cash Voucher \*0040100 · 500.00 · Cruz, Ana/)).toBeInTheDocument()
    expect(screen.getByText('To approve')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Open' }))
    expect(await screen.findByRole('table', { name: 'Voucher entries' })).toHaveTextContent('30302')
    expect(screen.queryByRole('button', { name: 'Post' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Approve' }))
    await userEvent.click(screen.getAllByRole('button', { name: 'Approve' }).at(-1)!)
    await waitFor(() => expect(approve).toHaveBeenCalledOnce())
  })

  it('says how to make one when none is waiting', async () => {
    waiting.mockResolvedValue([])
    show(<WaitingVouchersPanel />)
    expect(await screen.findByText(/No Cash or Check Voucher is waiting/)).toBeInTheDocument()
  })
})

const setup = { denominations: [{ id: 1, kind: 'Bill', description: '1,000.00', amount: 1000 }, { id: 2, kind: 'Bill', description: '500.00', amount: 500 }] } as unknown as FoSetup

describe('Opening the drawer', () => {
  beforeEach(() => { mine.mockReset(); open.mockReset() })

  it('counts blind, then shows the difference the ledger found', async () => {
    mine.mockResolvedValue({ required: true, policyOn: true, available: true, businessDate: '2026-11-17', drawers: [{ event: 53, number: 31, label: 'Teller 31', opened: false, openedAt: null, counted: null, difference: null, reason: null }] })
    open.mockRejectedValueOnce(new Error('The count does not match the ledger. Count again, or give a reason for the difference.'))
      .mockResolvedValueOnce({ label: 'Teller 31', expected: 1500, counted: 1000, difference: -500, openedAt: '08:01' })
    show(<DrawerOpen setup={setup} head={false} />)
    expect(await screen.findByText(/Count Teller 31 before taking or paying out cash today/)).toBeInTheDocument()
    expect(screen.queryByText('1,500.00')).not.toBeInTheDocument() // the ledger's figure is not shown before the count
    await userEvent.click(screen.getByRole('button', { name: 'Open the drawer' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('does not match')
    await userEvent.type(screen.getByLabelText('Reason for the difference'), 'counted twice, 500 missing')
    await userEvent.click(screen.getByRole('button', { name: 'Open the drawer' }))
    expect(await screen.findByText(/short 500.00, recorded/)).toBeInTheDocument()
    expect(open.mock.calls[1]).toEqual([53, {}, 'counted twice, 500 missing'])
  })

  it('stays quiet once the drawer is open', async () => {
    mine.mockResolvedValue({ required: true, policyOn: true, available: true, businessDate: '2026-11-17', drawers: [{ event: 53, number: 31, label: 'Teller 31', opened: true, openedAt: '07:55', counted: 1500, difference: 0, reason: null }] })
    show(<DrawerOpen setup={setup} head={false} />)
    expect(await screen.findByText('Teller 31 opened at 07:55')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Open the drawer' })).not.toBeInTheDocument()
  })
})
