import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import OpeningBalancesTab from './OpeningBalancesTab'
import type { Me, OpeningDetail, OpeningSummary } from './api'
import { AppThemeProvider } from './theme'

let username = 'ana'
vi.mock('./session', () => ({ useMe: () => ({ username, name: 'Someone', roles: ['ROLE_ACCOUNTANT'], canEdit: false, canApprove: false, admin: false, mustChangePassword: false, branchCode: 1 }) as Me }))

const summary = (over: Partial<OpeningSummary> = {}): OpeningSummary => ({
  id: 7, branchCode: 1, reference: 'OB-2027-0007', openingDate: '2027-01-01', description: 'Opening balances as of 1 January 2027', status: 'DRAFT', createdBy: 'ana',
  approvedBy: null, postedBy: null, lines: 3, debits: 1500, credits: 1500, documentNo: null, ...over,
})
const detail = (over: Partial<OpeningSummary> = {}, problems: string[] = []): OpeningDetail => ({
  summary: summary(over), problems, cancelReason: null,
  lines: [
    { lineNo: 1, kind: 'GL', clientId: null, memberName: null, typeCode: null, typeName: null, refNo: null, accountNo: null, accountCode: '10101', accountTitle: 'Cash on hand', debit: 1500, credit: 0, note: null, line: null, maturity: null, accrualAccount: null },
    { lineNo: 2, kind: 'SAVINGS', clientId: 12, memberName: 'Cruz, Maria', typeCode: 1, typeName: 'SD - Regular', refNo: 40, accountNo: '0000040', accountCode: '2010101', accountTitle: 'Savings deposits', debit: 0, credit: 1000, note: null, line: null, maturity: null, accrualAccount: null },
    { lineNo: 3, kind: 'SHARE', clientId: 12, memberName: 'Cruz, Maria', typeCode: 1, typeName: 'Paid up Share Cap', refNo: 41, accountNo: '0000041', accountCode: '3010101', accountTitle: 'Paid-up share capital', debit: 0, credit: 500, note: null, line: null, maturity: null, accrualAccount: null },
  ],
  trialBalance: [
    { account: '10101', title: 'Cash on hand', debit: 1500, credit: 0, memberAccounts: 0, control: false },
    { account: '2010101', title: 'Savings deposits', debit: 0, credit: 1000, memberAccounts: 1, control: true },
    { account: '3010101', title: 'Paid-up share capital', debit: 0, credit: 500, memberAccounts: 1, control: true },
  ],
})

const step = vi.fn()
const create = vi.fn()
const importFile = vi.fn()
let current: OpeningDetail = detail()
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  listOpeningBatches: async () => [current.summary],
  getOpeningBatch: async () => current,
  openingStep: (...a: unknown[]) => step(...a),
  createOpeningBatch: (...a: unknown[]) => create(...a),
  importOpeningFile: (...a: unknown[]) => importFile(...a),
  getMemoAccounts: async () => ({ clientId: 12, clientName: 'Cruz, Maria', clientStatus: 15, accounts: [] }),
  getOpeningTypes: async () => [
    { kind: 'LOAN', typeCode: 10, name: 'Appliance Loan', rate: 10, termMonths: 12, paymentMode: 'Monthly' },
    { kind: 'TD', typeCode: 2, name: 'TD - Lock In', rate: null, termMonths: null, paymentMode: null },
  ],
}))

const show = () => render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AppThemeProvider><OpeningBalancesTab branchCode={1} canEdit /></AppThemeProvider></QueryClientProvider>)

describe('Opening balances', () => {
  beforeEach(() => { username = 'ana'; current = detail(); step.mockReset(); create.mockReset(); importFile.mockReset() })

  it('shows the trial balance the batch makes, with the members added into their control accounts', async () => {
    show()
    const u = userEvent.setup()
    await u.click(await screen.findByText('OB-2027-0007'))
    const tb = await screen.findByRole('table', { name: 'Trial balance of the opening balances' })
    expect(within(tb).getByText('2010101').closest('tr')).toHaveTextContent('1 member account')
    expect(within(tb).getByText('Total').closest('tr')).toHaveTextContent('1,500.00')
    expect(screen.getByRole('table', { name: 'Lines of the opening balances' })).toHaveTextContent('Cruz, Maria · SD - Regular 0000040')
    await u.click(screen.getByRole('button', { name: 'Send for review' }))
    await waitFor(() => expect(step).toHaveBeenCalledWith(7, 'submit'))
  })

  it('cannot be sent on while it has problems, and lists them', async () => {
    current = detail({}, ['Line 1: account 2010101 is the control account of members’ accounts.'])
    show()
    const u = userEvent.setup()
    await u.click(await screen.findByText('OB-2027-0007'))
    expect(await screen.findByText(/To fix before it can be sent on \(1\)/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Send for review' })).toBeDisabled()
  })

  it('is approved by someone else and posted by a third person, who confirms first', async () => {
    current = detail({ status: 'FOR_REVIEW' })
    username = 'ana'
    const first = show()
    const u = userEvent.setup()
    await u.click(await screen.findByText('OB-2027-0007'))
    expect(await screen.findByText(/Waiting for another accountant/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve' })).toBeNull()
    first.unmount()

    current = detail({ status: 'APPROVED', approvedBy: 'ben' })
    username = 'cy'
    show()
    await u.click(await screen.findByText('OB-2027-0007'))
    await u.click(await screen.findByRole('button', { name: 'Post' }))
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('cannot be edited or cancelled')
    await u.click(within(dialog).getByRole('button', { name: 'Post' }))
    await waitFor(() => expect(step).toHaveBeenCalledWith(7, 'post'))
  })

  it('reads lines from a file into a new draft and saves it', async () => {
    importFile.mockResolvedValue({
      lines: [
        { kind: 'GL', clientId: null, typeCode: null, refNo: null, accountCode: '10101', debit: 500, credit: null, note: null },
        { kind: 'SHARE', clientId: 12, typeCode: 1, refNo: null, accountCode: null, debit: null, credit: 500, note: null },
      ],
      problems: ['File line 4: say in the Kind column whether the line is GL, Savings or Share capital.'],
    })
    create.mockResolvedValue(summary({ id: 8 }))
    show()
    const u = userEvent.setup()
    await u.click(await screen.findByRole('button', { name: 'New opening balances' }))
    const dialog = await screen.findByRole('dialog')
    await u.click(within(dialog).getByLabelText('Description'))
    await u.paste('Opening balances as of 1 January 2027')
    await u.upload(within(dialog).getByLabelText('Opening balances file'), new File(['x'], 'opening.csv', { type: 'text/csv' }))
    expect(await within(dialog).findByText(/1 row was left out of the file/)).toBeInTheDocument()
    expect(within(dialog).getByText('Balanced')).toBeInTheDocument()
    await u.click(within(dialog).getByRole('button', { name: 'Save draft' }))
    await waitFor(() => expect(create).toHaveBeenCalled())
    const body = create.mock.calls[0][0]
    expect(body.lines).toHaveLength(2)
    expect(body.lines[1]).toMatchObject({ kind: 'SHARE', clientId: 12, typeCode: 1, credit: 500 })
  })

  it('takes a loan with its remaining term and a time deposit with its accrued interest, which counts as a credit', async () => {
    importFile.mockResolvedValue({
      lines: [
        { kind: 'GL', clientId: null, typeCode: null, refNo: null, accountCode: '10101', debit: 89250, credit: null, note: null },
        { kind: 'LOAN', clientId: 12, typeCode: 10, refNo: null, accountCode: null, debit: 12000, credit: null, note: null, status: 12, rate: 12, terms: 12, startDate: '2027-01-15', originalAmount: 20000, grantedOn: '2026-07-15', accrued: null },
        { kind: 'TD', clientId: 12, typeCode: 2, refNo: null, accountCode: null, debit: null, credit: 100000, note: null, status: null, rate: 5, terms: 360, startDate: '2026-10-01', originalAmount: null, grantedOn: null, accrued: 1250 },
      ],
      problems: [],
    })
    create.mockResolvedValue(summary({ id: 9 }))
    show()
    const u = userEvent.setup()
    await u.click(await screen.findByRole('button', { name: 'New opening balances' }))
    const dialog = await screen.findByRole('dialog')
    await u.click(within(dialog).getByLabelText('Description'))
    await u.paste('Opening balances as of 1 January 2027')
    await u.upload(within(dialog).getByLabelText('Opening balances file'), new File(['x'], 'opening.csv', { type: 'text/csv' }))
    // the certificate's accrued interest is a credit too: 12,000 loan = 100,000 + 1,250 - 89,250 cash
    expect(await within(dialog).findByText('Balanced')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Next due of line 2')).toHaveValue('2027-01-15')
    expect(within(dialog).getByLabelText('Credit of line 2')).toBeDisabled()
    expect(within(dialog).getByLabelText('Accrued interest of line 3')).toHaveValue('1250')
    await u.clear(within(dialog).getByLabelText('Months to run of line 2'))
    await u.type(within(dialog).getByLabelText('Months to run of line 2'), '10')
    await u.click(within(dialog).getByRole('button', { name: 'Save draft' }))
    await waitFor(() => expect(create).toHaveBeenCalled())
    const body = create.mock.calls[0][0]
    expect(body.lines[1]).toMatchObject({ kind: 'LOAN', clientId: 12, typeCode: 10, refNo: null, debit: 12000, credit: null, status: 12, terms: 10, startDate: '2027-01-15', originalAmount: 20000 })
    expect(body.lines[2]).toMatchObject({ kind: 'TD', typeCode: 2, debit: null, credit: 100000, rate: 5, terms: 360, startDate: '2026-10-01', accrued: 1250 })
  })

  it('lists a posted loan with its number and terms', async () => {
    current = detail({ status: 'POSTED', documentNo: '*0000123', postedBy: 'cy', approvedBy: 'ben' })
    current.lines.push({ lineNo: 4, kind: 'LOAN', clientId: 12, memberName: 'Cruz, Maria', typeCode: 10, typeName: 'Appliance Loan', refNo: 315, accountNo: '315', accountCode: '10110110',
      accountTitle: 'Loans receivable', debit: 12000, credit: 0, note: null, maturity: null, accrualAccount: null,
      line: { kind: 'LOAN', clientId: 12, typeCode: 10, refNo: 315, accountCode: null, debit: 12000, credit: 0, note: null, status: 11, rate: 12, terms: 12, startDate: '2027-01-15', originalAmount: 20000, grantedOn: '2026-07-15', accrued: null } })
    show()
    const u = userEvent.setup()
    await u.click(await screen.findByText('OB-2027-0007'))
    const lines = await screen.findByRole('table', { name: 'Lines of the opening balances' })
    const row = within(lines).getByText(/Appliance Loan 315/).closest('tr')
    expect(row).toHaveTextContent('Current · 12 months to run · next due 2027-01-15 · 12% a year · granted 20,000.00 on 2026-07-15')
  })
})
