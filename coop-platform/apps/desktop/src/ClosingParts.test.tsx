import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { CloseReview } from './api'
import { CloseStatusNotice, MonthReview } from './ClosingParts'
import { AppThemeProvider } from './theme'

const tick = vi.fn()
const sign = vi.fn()
let review: CloseReview
const base = (over: Partial<CloseReview> = {}): CloseReview => ({
  scope: 'MONTH', period: '2026-10', from: '2026-10-01', to: '2026-10-31', complete: false, open: 2, signedBy: null, signedAt: null, ended: true, monthlyClose: false,
  items: [
    { key: 'BOOKS_BALANCE', label: 'The books balance', help: 'Debits equal credits.', automatic: true, status: 'ATTENTION', detail: 'Debits and credits differ by 2,464.00.', note: null, by: null, at: null },
    { key: 'BANKS_RECONCILED', label: 'Banks reconciled', help: '', automatic: true, status: 'OK', detail: 'All 2 reconciled.', note: null, by: null, at: null },
    { key: 'CASH_COUNTED', label: 'Cash counted', help: '', automatic: false, status: 'OPEN', detail: null, note: null, by: null, at: null },
  ],
  ...over,
})
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  getCloseReview: async () => review,
  tickCloseItem: (...a: unknown[]) => tick(...a),
  signOffMonth: (...a: unknown[]) => sign(...a),
  getCloseStatus: async () => ({ policy: { monthlyClose: true, yearEndDueDays: 120 }, lastClosedYear: 2024, yearToClose: 2025, yearDue: '2026-04-30', yearOverdue: true, monthsToReview: ['2026-09'] }),
}))

const show = (ui: React.ReactNode) => render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AppThemeProvider>{ui}</AppThemeProvider></QueryClientProvider>)

describe('Closing', () => {
  beforeEach(() => { review = base(); tick.mockReset(); sign.mockReset() })

  it('accepts what the books show wanting only with an explanation, marks a manual item done, then signs the month off', async () => {
    tick.mockImplementation(async () => base({ complete: true, open: 0 }))
    sign.mockImplementation(async () => base({ complete: true, open: 0, signedBy: 'acct', signedAt: '2026-11-02T09:00' }))
    show(<MonthReview branchCode={1} canEdit />)
    const u = userEvent.setup()
    expect(await screen.findByText('Debits and credits differ by 2,464.00.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^Sign off/ })).toBeDisabled()
    await u.click(screen.getByRole('button', { name: 'Explain and accept' }))
    const dialog = await screen.findByRole('dialog')
    const accept = within(dialog).getByRole('button', { name: 'Accept' })
    await u.type(within(dialog).getByLabelText(/Why it can be accepted/), 'Too short')
    expect(accept).toBeDisabled()
    await u.type(within(dialog).getByLabelText(/Why it can be accepted/), ' - old journal 2336 under review')
    await u.click(accept)
    await waitFor(() => expect(tick).toHaveBeenCalledWith(1, 'month', expect.any(String), 'BOOKS_BALANCE', true, 'Too short - old journal 2336 under review'))
    review = base({ complete: true, open: 0 })
    expect(await screen.findByText('Every item is done.')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await u.click(screen.getByRole('button', { name: /^Sign off/ }))
    const signing = await screen.findByRole('dialog', { name: /Sign off/ })
    await u.click(within(signing).getByRole('button', { name: 'Sign off' }))
    await waitFor(() => expect(sign).toHaveBeenCalled())
  })

  it('reads only for an auditor', async () => {
    show(<MonthReview branchCode={1} canEdit={false} />)
    expect(await screen.findByText('Cash counted')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Mark done' })).toBeNull()
    expect(screen.queryByRole('button', { name: /^Sign off/ })).toBeNull()
  })

  it('says when the year-end close is overdue and which months wait for review', async () => {
    show(<CloseStatusNotice branchCode={1} />)
    expect(await screen.findByText(/year-end close of 2025 is overdue: it was due by 2026-04-30/)).toBeInTheDocument()
    expect(screen.getByText(/2026-09 is not signed off yet/)).toBeInTheDocument()
  })
})
