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

  it('keeps Next and Print unusable until Profiling is complete, saying what is still needed', async () => {
    template.mockResolvedValue(setup(false))
    const onNext = show()
    await screen.findByRole('textbox', { name: /Principal/ }) // the loan type's setup is in
    const next = screen.getByRole('button', { name: 'Next: Deduction' })
    expect(next).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Print amortization' })).toBeDisabled()
    await userEvent.hover(next.parentElement!)
    expect(await screen.findByRole('tooltip')).toHaveTextContent('Fill in Principal first.')

    await waitFor(() => expect(screen.getByRole('textbox', { name: /Principal/ })).toHaveFocus())
    await userEvent.keyboard('5000')
    expect(next).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Print amortization' })).toBeEnabled()
    await userEvent.click(next)
    expect(onNext).toHaveBeenCalledOnce()
  })

  it('says what a required field needs once the person leaves it empty', async () => {
    template.mockResolvedValue(setup(false))
    show()
    const principal = await screen.findByRole('textbox', { name: /Principal/ })
    await waitFor(() => expect(principal).toHaveFocus())
    expect(screen.queryByText('Enter the principal, more than zero.')).not.toBeInTheDocument()
    await userEvent.tab()
    expect(screen.getByText('Enter the principal, more than zero.')).toBeInTheDocument()
  })

  it('waits for the collateral when the loan type requires it, and its checklist item opens the tab', async () => {
    template.mockResolvedValue(setup(true))
    show()
    await screen.findByRole('tab', { name: /Collateral \*/ })
    await userEvent.type(await screen.findByRole('textbox', { name: /Principal/ }), '5000')
    expect(screen.getByRole('button', { name: 'Next: Deduction' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: 'Collateral type: still to fill in' }))
    await waitFor(() => expect(screen.getByRole('tab', { name: /Collateral/ })).toHaveAttribute('aria-selected', 'true'))
  })

  it('starts the term at the loan type’s and says at once when it goes over', async () => {
    template.mockResolvedValue(setup(false))
    show()
    const term = await screen.findByRole('spinbutton', { name: /Term/ })
    expect(term).toHaveValue(12)
    expect(screen.getByText('Up to 12 months')).toBeInTheDocument()
    await userEvent.type(screen.getByRole('textbox', { name: /Principal/ }), '5000')
    await userEvent.clear(term)
    await userEvent.type(term, '13')
    expect(screen.getByText('This loan type allows at most 12 months.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Next: Deduction' })).toBeDisabled()
    await userEvent.clear(term)
    await userEvent.type(term, '12')
    expect(screen.getByRole('button', { name: 'Next: Deduction' })).toBeEnabled()
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
