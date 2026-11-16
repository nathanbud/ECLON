import { focusManager, QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import LoanApplicationWindow from './LoanApplicationWindow'
import { AppThemeProvider } from './theme'

const detail = vi.fn()
const release = vi.fn()
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  getLoanApplication: () => detail(),
  getLoanRelease: () => release(),
  getLoanAppPermissions: () => Promise.resolve({ canAdd: true, canDeduct: true, canVerify: true, canApprove: true, canRelease: false }),
}))
vi.mock('./detachedWindow', async (orig) => ({ ...(await orig<typeof import('./detachedWindow')>()), resizeThisWindow: vi.fn(), closeThisWindow: vi.fn(), announceLoanApplicationSaved: vi.fn() }))
vi.mock('./LoanApplicantCard', () => ({ default: () => null }))

const approved = (releaseStatus: string | null, releasedOn: string | null = null) => ({
  item: { id: 7, documentNo: 'D7', clientId: 5, clientName: 'Member 5', typeCode: 21, typeName: 'Salary loan', refNo: 9, amount: 5000, terms: 12, status: 3, statusName: 'Approved',
    encodedByName: 'Ana', date: '2026-11-01', voucherYear: 2026, voucherControlNo: 44, branchCode: 1, waitingForDeductions: false, approvedOn: '2026-11-02', releaseStatus, releasedOn },
  lines: [], totalDeductions: 0, netProceeds: 5000, problems: [], coMakerHolds: [],
})

describe('Loan window, Loan Release step', () => {
  beforeEach(() => {
    window.location.hash = '#/loan-app?a=7&b=1&c=5'
    detail.mockReset()
    release.mockReset()
    release.mockRejectedValue(new Error('You cannot look up this type of transaction.')) // no Cash Voucher right
  })

  it('shows where the release stands without the Cash Voucher right, and turns to Released when it is posted, without reopening', async () => {
    detail.mockResolvedValue(approved('WAITING FOR APPROVAL'))
    render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AppThemeProvider><LoanApplicationWindow /></AppThemeProvider></QueryClientProvider>)
    expect(await screen.findByText('Waiting for release in Front Office')).toBeInTheDocument()
    expect(await screen.findByText(/lines are shown to those who may look up Cash Vouchers/)).toBeInTheDocument()

    detail.mockResolvedValue(approved('POSTED', '2026-11-15'))
    act(() => { focusManager.setFocused(false); focusManager.setFocused(true) }) // the window regains focus
    expect(await screen.findByText('Released on 2026-11-15')).toBeInTheDocument()
    expect(screen.queryByText('Waiting for release in Front Office')).toBeNull()
  })
})
