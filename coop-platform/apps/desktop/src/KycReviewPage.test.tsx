import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { KycReview, KycStatus } from './api'
import KycReviewPage from './KycReviewPage'
import { KycNotice } from './SavingsTellerPanel'
import { AppThemeProvider } from './theme'

const review = vi.fn()
const kyc = vi.fn()
const policy = vi.fn()
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  getKycReview: (s: KycStatus, p: number) => review(s, p),
  getKyc: () => kyc(),
  getMembershipPolicy: () => policy(),
}))

const counts = { EXPIRED: 2, INCOMPLETE: 1, REVIEW_DUE: 0, NONE: 120, COMPLETE: 7 }
const page = (status: KycStatus, rows: KycReview['rows'], total = rows.length): KycReview => ({ counts, status, page: 0, size: 50, total, rows })
const row = (clientId: number, name: string, extra: Partial<KycReview['rows'][0]> = {}) =>
  ({ branchCode: 1, clientId, name, status: 'EXPIRED' as KycStatus, idType: 'Passport', idExpiry: '2026-01-31', risk: 'HIGH', nextReview: '2027-01-31', updatedAt: '2025-01-31T10:00:00', ...extra })

function show(ui: React.ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider>{ui}</AppThemeProvider></QueryClientProvider>)
}

describe('KYC review', () => {
  beforeEach(() => {
    review.mockReset().mockImplementation(async (s: KycStatus) => s === 'EXPIRED'
      ? page('EXPIRED', [row(33189, 'Dela Cruz, Juan S.'), row(42, 'Reyes, Ana')])
      : s === 'NONE' ? page('NONE', [row(7, 'Santos, Maria', { status: 'NONE', idType: null, idExpiry: null, risk: null, nextReview: null, updatedAt: null })], 120)
        : page(s, []))
  })

  it('counts each kind, lists the expired IDs first and opens a member', async () => {
    const open = vi.fn()
    show(<KycReviewPage onOpenMember={open} />)
    expect(await screen.findByRole('button', { name: 'ID expired (2)' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'No record (120)' })).toBeInTheDocument()
    expect(screen.getByText(/7 of 130 active members are complete/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('row', { name: /Open Dela Cruz, Juan S\./ }))
    expect(open).toHaveBeenCalledWith({ branchCode: 1, clientId: 33189 })
  })

  it('switches to another kind, pages a long list, and says when one is empty', async () => {
    show(<KycReviewPage onOpenMember={vi.fn()} />)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'No record (120)' }))
    expect(await screen.findByText('Santos, Maria')).toBeInTheDocument()
    expect(screen.getByText('1–50 of 120')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Next page' }))
    await waitFor(() => expect(review).toHaveBeenCalledWith('NONE', 1))
    await user.click(screen.getByRole('button', { name: 'Review due (0)' }))
    expect(await screen.findByText('No review is overdue.')).toBeInTheDocument()
  })
})

describe('the teller’s KYC notice', () => {
  it('says nothing for a complete record', async () => {
    kyc.mockResolvedValue({ status: 'COMPLETE' })
    policy.mockResolvedValue({ returningPaysShareCapital: false, kycBlocksWithdrawals: true })
    show(<KycNotice id={{ branchCode: 1, clientId: 5 }} />)
    await waitFor(() => expect(kyc).toHaveBeenCalled())
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('reminds the teller while the policy is off, and says withdrawals are stopped when it is on', async () => {
    kyc.mockResolvedValue({ status: 'EXPIRED' })
    policy.mockResolvedValue({ returningPaysShareCapital: false, kycBlocksWithdrawals: false })
    show(<KycNotice id={{ branchCode: 1, clientId: 5 }} />)
    expect(await screen.findByText(/has expired\. Ask for the member/)).toBeInTheDocument()
  })

  it('stops withdrawals when the policy is on', async () => {
    kyc.mockResolvedValue({ status: 'NONE' })
    policy.mockResolvedValue({ returningPaysShareCapital: false, kycBlocksWithdrawals: true })
    show(<KycNotice id={{ branchCode: 1, clientId: 6 }} />)
    const alert = await screen.findByRole('alert')
    expect(within(alert).getByText(/no KYC record\. Withdrawals are stopped/)).toBeInTheDocument()
  })

  it('only notes an overdue review', async () => {
    kyc.mockResolvedValue({ status: 'REVIEW_DUE', nextReview: '2026-10-01' })
    policy.mockResolvedValue({ returningPaysShareCapital: false, kycBlocksWithdrawals: true })
    show(<KycNotice id={{ branchCode: 1, clientId: 7 }} />)
    expect(await screen.findByText(/review is overdue \(due 10\/01\/2026\)\. Withdrawals are not affected/)).toBeInTheDocument()
  })
})
