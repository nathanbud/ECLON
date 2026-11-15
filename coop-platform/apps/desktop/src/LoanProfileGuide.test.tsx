import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { LoanAppTemplate } from './api'
import LoanApplicationDialog from './LoanApplicationDialog'
import { AppThemeProvider } from './theme'

const template = vi.fn<() => Promise<LoanAppTemplate>>()
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  getLoanAppPermissions: () => Promise.resolve({ canAdd: true, canVerify: false, canApprove: false, canDeduct: true }),
  getLoanAppTemplate: () => template(),
  getLoanTypes: () => Promise.resolve([]),
}))

const setup = (collateralRequired: boolean): LoanAppTemplate => ({
  typeCode: 21, typeName: 'Salary loan', terms: 12, termUnit: 4, paymentMode: 1, interestRate: 12, hasVouchersPayable: true, nextRefNo: 1,
  deductions: [], purposes: [{ id: 5, name: 'Education' }], collateralTypes: [{ id: 2, name: 'Real estate' }], payoffs: [],
  setup: { termUnit: 'Months', principalPayment: 'Monthly', interestPayment: 'Monthly', basedOn: null, diminish: null, penaltyRate: null, yearDays: 360, collateralRequired, unsupported: null },
})

function show(onNext = vi.fn()) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider><LoanApplicationDialog embedded part="profile" branchCode={1} clientId={5} typeCode={21} onClose={() => {}} onNext={onNext} /></AppThemeProvider></QueryClientProvider>)
  return onNext
}

describe('Profiling guidance', () => {
  beforeEach(() => template.mockReset())

  it('puts the cursor on the principal once the loan type is loaded, and lists what is needed', async () => {
    template.mockResolvedValue(setup(false))
    show()
    const principal = await screen.findByRole('textbox', { name: /Principal/ })
    await waitFor(() => expect(principal).toHaveFocus())
    const needs = screen.getByRole('region', { name: 'What Profiling needs' })
    expect(needs).toHaveTextContent('Fields marked * are required')
    expect(needs).toHaveTextContent('2 of 3 done')
    expect(within(needs).getByRole('button', { name: 'Principal: still to fill in' })).toBeInTheDocument()
  })

  it('does not go on with something missing: it says what and puts the cursor there', async () => {
    template.mockResolvedValue(setup(false))
    const onNext = show()
    await screen.findByRole('textbox', { name: /Principal/ }) // the loan type's setup is in
    const next = screen.getByRole('button', { name: 'Next: Deduction' })
    expect(next).toBeEnabled()
    await userEvent.click(next)
    expect(onNext).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toHaveTextContent('Fill in Principal to go on.')
    expect(screen.getByText('Enter the principal, more than zero.')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('textbox', { name: /Principal/ })).toHaveFocus())
    await userEvent.keyboard('5000')
    await userEvent.click(next)
    expect(onNext).toHaveBeenCalledOnce()
  })

  it('asks for the collateral when the loan type requires it, opening its tab', async () => {
    template.mockResolvedValue(setup(true))
    const onNext = show()
    await screen.findByRole('tab', { name: /Collateral \*/ })
    await userEvent.type(await screen.findByRole('textbox', { name: /Principal/ }), '5000')
    await userEvent.click(screen.getByRole('button', { name: 'Next: Deduction' }))
    expect(onNext).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toHaveTextContent('Fill in Collateral type, Collateral amount and Collateral description to go on.')
    await waitFor(() => expect(screen.getByRole('tab', { name: /Collateral/ })).toHaveAttribute('aria-selected', 'true'))
  })

  it('moves on with Enter', async () => {
    template.mockResolvedValue(setup(false))
    show()
    const principal = await screen.findByRole('textbox', { name: /Principal/ })
    await waitFor(() => expect(principal).toHaveFocus())
    await userEvent.keyboard('5000{Enter}')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Next: Deduction' })).toHaveFocus())
  })
})
