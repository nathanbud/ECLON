import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { LoanAppTemplate } from './api'
import LoanApplicationDialog from './LoanApplicationDialog'
import { AppThemeProvider } from './theme'
import { Notices } from './ui/notices'

const pdf = vi.fn<(b: number, c: number, body: { typeCode: number; amount: number }, refNo?: number) => Promise<Blob>>()
const printed = vi.fn<(file: Blob, name: string) => Promise<'printing' | 'saved'>>()
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  getLoanAppPermissions: () => Promise.resolve({ canAdd: true, canVerify: false, canApprove: false, canDeduct: true }),
  getLoanAppTemplate: () => Promise.resolve(template),
  getLoanTypes: () => Promise.resolve([]),
  searchMembers: () => Promise.resolve({ content: [], totalElements: 0, number: 0, size: 8, last: true }),
  getAmortizationPdf: (b: number, c: number, body: { typeCode: number; amount: number }, refNo?: number) => pdf(b, c, body, refNo),
}))
vi.mock('./ui/printPdf', () => ({ printPdf: (file: Blob, name: string) => printed(file, name) }))

const template: LoanAppTemplate = {
  typeCode: 21, typeName: 'Salary loan', terms: 12, termUnit: 4, paymentMode: 1, interestRate: 12, hasVouchersPayable: true, nextRefNo: 1,
  deductions: [], purposes: [{ id: 5, name: 'Education' }], collateralTypes: [], payoffs: [],
  setup: { termUnit: 'Months', principalPayment: 'Monthly', interestPayment: 'Monthly', basedOn: null, diminish: null, penaltyRate: null, yearDays: 360, collateralRequired: false, unsupported: null },
}

function show() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider>
    <LoanApplicationDialog embedded part="profile" branchCode={1} clientId={5} typeCode={21} onClose={() => {}} onNext={() => {}} />
    <Notices />
  </AppThemeProvider></QueryClientProvider>)
}

describe('Printing the amortization schedule while profiling', () => {
  beforeEach(() => { pdf.mockReset(); printed.mockReset() })

  it('shows only Cancel until the member applying is chosen', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={qc}><AppThemeProvider>
      <LoanApplicationDialog embedded part="profile" onClose={() => {}} onNext={() => {}} />
    </AppThemeProvider></QueryClientProvider>)
    expect(await screen.findByText('Who is applying for a loan?')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Print amortization' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Next: Deduction' })).not.toBeInTheDocument()
  })

  it('can be pressed once Profiling is complete, and prints the schedule of what is on the form', async () => {
    pdf.mockResolvedValue(new Blob(['%PDF'], { type: 'application/pdf' }))
    printed.mockResolvedValue('printing')
    show()
    await screen.findByRole('textbox', { name: /Principal/ })
    const button = screen.getByRole('button', { name: 'Print amortization' })
    expect(button).toBeDisabled()

    await waitFor(() => expect(screen.getByRole('textbox', { name: /Principal/ })).toHaveFocus())
    await userEvent.keyboard('25000')
    await userEvent.click(button)
    await waitFor(() => expect(printed).toHaveBeenCalledOnce())
    expect(pdf).toHaveBeenCalledWith(1, 5, expect.objectContaining({ typeCode: 21, amount: 25000 }), undefined)
  })

  it('says why when the schedule cannot be made, as a pop-up', async () => {
    pdf.mockRejectedValue(new Error('The term is too long.'))
    show()
    await userEvent.type(await screen.findByRole('textbox', { name: /Principal/ }), '25000')
    await userEvent.click(screen.getByRole('button', { name: 'Print amortization' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('The term is too long.')
    expect(printed).not.toHaveBeenCalled()
  })
})
