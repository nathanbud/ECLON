import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AppropriationTab from './AppropriationTab'
import type { AppropriationDetail, AppropriationStart, AppropriationSummary, Me } from './api'
import { AppThemeProvider } from './theme'

let username = 'ana'
vi.mock('./session', () => ({ useMe: () => ({ username, name: 'Someone', roles: ['ROLE_ACCOUNTANT'], canEdit: false, canApprove: false, admin: false, mustChangePassword: false, branchCode: 1 }) as Me }))

const year = new Date().getFullYear() - 1
const settings = { registeredOn: null, sourceAccount: '30198', sourceTitle: 'Undivided net surplus', payoutAccount: '20126', payoutTitle: 'Int on ShareCap. & Patronage ref payable', mayEdit: false }
const start = (over: Partial<AppropriationStart> = {}): AppropriationStart => ({
  year, netSurplus: 1000000, reserveMinimum: 10, problems: [], settings, existing: null,
  lines: [
    { kind: 'RESERVE', name: 'Reserve fund', percent: 10, amount: null, account: '3011' },
    { kind: 'EDUCATION', name: 'Education and training fund', percent: 10, amount: null, account: '3012' },
    { kind: 'COMMUNITY', name: 'Community development fund', percent: 3, amount: null, account: '3013' },
    { kind: 'OPTIONAL', name: 'Optional fund', percent: 7, amount: null, account: '3014' },
    { kind: 'ISC', name: 'Interest on share capital', percent: null, amount: 0, account: '20126' },
    { kind: 'PR', name: 'Patronage refund', percent: null, amount: 0, account: '20126' },
  ],
  ...over,
})
const summary = (over: Partial<AppropriationSummary> = {}): AppropriationSummary => ({
  id: 4, branchCode: 1, year, reference: `NS-${year}-0004`, entryDate: `${year + 1}-03-20`, resolution: 'GA Resolution No. 3', netSurplus: 1000000, status: 'FOR_REVIEW',
  createdBy: 'ana', approvedBy: null, postedBy: null, allocated: 950000, documentNo: null, ...over,
})
const detail = (over: Partial<AppropriationSummary> = {}, percents: Record<string, number> = { RESERVE: 10, EDUCATION: 10, COMMUNITY: 3, OPTIONAL: 7, OTHER: 0 }): AppropriationDetail => ({
  summary: summary(over), problems: [], cancelReason: null, percents, reserveMinimum: 10,
  lines: [
    { lineNo: 1, kind: 'RESERVE', name: 'Reserve fund', percent: 10, amount: 100000, account: '3011', accountTitle: 'Reserve fund' },
    { lineNo: 2, kind: 'ISC', name: 'Interest on share capital', percent: null, amount: 400000, account: '20126', accountTitle: 'Payable' },
  ],
  totals: { netSurplus: 1000000, funds: 300000, isc: 400000, pr: 250000, allocated: 950000, undivided: 50000 },
  entry: [{ account: '30198', title: 'Undivided net surplus', debit: 950000, credit: 0 }, { account: '20126', title: 'Payable', debit: 0, credit: 650000 }],
})

const step = vi.fn()
const create = vi.fn()
let current = detail()
let begin = start()
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  listAppropriations: async () => [current.summary],
  getAppropriation: async () => current,
  startAppropriation: async () => begin,
  appropriationStep: (...a: unknown[]) => step(...a),
  createAppropriation: (...a: unknown[]) => create(...a),
}))

const show = () => render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AppThemeProvider><AppropriationTab /></AppThemeProvider></QueryClientProvider>)

describe('Net surplus appropriation', () => {
  beforeEach(() => { username = 'ana'; current = detail(); begin = start(); step.mockReset(); create.mockReset() })

  it('works the funds out from their percent and saves the draft for the closed year', async () => {
    current = detail({ status: 'POSTED' })
    begin = start()
    create.mockResolvedValue(summary({ id: 9, status: 'DRAFT' }))
    show()
    const u = userEvent.setup()
    await u.click(await screen.findByRole('button', { name: `Prepare the ${year} appropriation` }))
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('1,000,000.00')
    expect(dialog).toHaveTextContent('100,000.00')
    await u.type(within(dialog).getByLabelText('Amount of line 5'), '400000')
    await u.type(within(dialog).getByLabelText('Amount of line 6'), '300000')
    expect(within(dialog).getByText('Left undivided 0.00')).toBeInTheDocument()
    await u.type(within(dialog).getByLabelText('General assembly resolution'), 'GA Resolution No. 3')
    await u.click(within(dialog).getByRole('button', { name: 'Save draft' }))
    await waitFor(() => expect(create).toHaveBeenCalled())
    const body = create.mock.calls[0][0]
    expect(body.year).toBe(year)
    expect(body.lines[0]).toMatchObject({ kind: 'RESERVE', percent: 10, amount: null, account: '3011' })
    expect(body.lines[4]).toMatchObject({ kind: 'ISC', percent: null, amount: 400000, account: '20126' })
  })

  it('cannot be prepared for a year that is not closed', async () => {
    begin = start({ netSurplus: null, problems: [`The books for ${year} are not closed yet.`] })
    show()
    expect(await screen.findByText(`The books for ${year} are not closed yet.`)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: `Prepare the ${year} appropriation` })).toBeDisabled()
  })

  it('shows the Article 86 limits and the journal, and is approved by someone else', async () => {
    current = detail({}, { RESERVE: 8, EDUCATION: 10, COMMUNITY: 3, OPTIONAL: 7, OTHER: 0 })
    show()
    const u = userEvent.setup()
    await u.click(await screen.findByText(`NS-${year}-0004`))
    const limits = await screen.findByRole('table', { name: 'Article 86 limits' })
    expect(within(limits).getByText('Reserve fund').closest('tr')).toHaveTextContent('Outside the limit')
    expect(screen.getByRole('table', { name: 'General Journal of the appropriation' })).toHaveTextContent('950,000.00')
    expect(screen.getByRole('table', { name: 'Lines of the appropriation' })).toHaveTextContent('Left in the undivided net surplus')
    expect(await screen.findByText(/Waiting for another accountant/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve' })).toBeNull()
  })

  it('is posted by a third person, who confirms first', async () => {
    current = detail({ status: 'APPROVED', approvedBy: 'ben' })
    username = 'cy'
    show()
    const u = userEvent.setup()
    await u.click(await screen.findByText(`NS-${year}-0004`))
    await u.click(await screen.findByRole('button', { name: 'Post' }))
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('cannot be edited or cancelled')
    await u.click(within(dialog).getByRole('button', { name: 'Post' }))
    await waitFor(() => expect(step).toHaveBeenCalledWith(4, 'post'))
  })
})
