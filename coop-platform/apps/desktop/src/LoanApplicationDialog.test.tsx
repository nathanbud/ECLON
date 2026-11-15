import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import LoanApplicationDialog from './LoanApplicationDialog'
import { AppThemeProvider } from './theme'

const perms = vi.fn()
const template = vi.fn(() => new Promise(() => {}))
// the member search stands in: it picks member 5 at once
vi.mock('./FindClientDialog', () => ({
  FindBody: ({ onPickMember }: { onPickMember: (m: unknown) => void }) => (
    <button onClick={() => onPickMember({ id: { branchCode: 1, clientId: 5 }, firstName: 'Ana', lastName: 'Cruz', accountName: null, dateOpened: null, accountStatus: 1 })}>pick member</button>
  ),
}))
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  getLoanAppPermissions: () => perms(),
  getLoanAppTemplate: () => template(), // still loading: the notice must not wait for it
  getLoanTypes: () => Promise.resolve([
    { code: 48, name: 'Back to back loan', terms: 5, rate: 10, loans: 900, available: true },
    { code: 21, name: 'Salary loan', terms: 12, rate: 12, loans: 500, available: true },
  ]),
  getMember: () => new Promise(() => {}),
}))

function show() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider><LoanApplicationDialog branchCode={1} clientId={5} typeCode={21} onClose={() => {}} /></AppThemeProvider></QueryClientProvider>)
}

describe('Loan application form', () => {
  beforeEach(() => { perms.mockReset(); template.mockClear() })

  it('says at once when the person may not add loan applications, and where to get the permission', async () => {
    perms.mockResolvedValue({ canAdd: false, canVerify: false, canApprove: false, canDeduct: true })
    show()
    const note = await screen.findByText(/Your account may not do the Profiling step/)
    expect(note).toHaveTextContent('Settings > Users: open your user, tab Receivables, Loan process, 1 · Profiling')
    expect(note).toHaveTextContent('You may do 2 · Deduction: open an application waiting for deductions from the Loan Module')
  })

  it('says nothing when the person may add them', async () => {
    perms.mockResolvedValue({ canAdd: true, canVerify: false, canApprove: false, canDeduct: true })
    show()
    await waitFor(() => expect(perms).toHaveBeenCalled())
    expect(screen.queryByText(/may not do the Profiling step/)).toBeNull()
  })

  it('with Profiling only, saves without deductions and sends them on', async () => {
    perms.mockResolvedValue({ canAdd: true, canVerify: false, canApprove: false, canDeduct: false })
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={qc}><AppThemeProvider><LoanApplicationDialog embedded part="profile" branchCode={1} clientId={5} typeCode={21} onClose={() => {}} onNext={() => {}} /></AppThemeProvider></QueryClientProvider>)
    expect(await screen.findByRole('button', { name: 'Save and send for deductions' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Next: Deduction' })).toBeNull()
  })

  it('starts on "Select Loan Type" and loads no setup until a type is chosen', async () => {
    perms.mockResolvedValue({ canAdd: true, canVerify: false, canApprove: false, canDeduct: true })
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={qc}><AppThemeProvider><LoanApplicationDialog embedded part="profile" onClose={() => {}} onNext={() => {}} /></AppThemeProvider></QueryClientProvider>)
    await userEvent.click(await screen.findByRole('button', { name: 'pick member' }))
    const box = await screen.findByRole('combobox', { name: 'Loan type' })
    await waitFor(() => expect(box).toHaveTextContent('-- Select Loan Type --'))
    expect(screen.getByText(/Select the loan type to load its setup/)).toBeInTheDocument()
    expect(template).not.toHaveBeenCalled()
    await userEvent.click(box)
    await userEvent.click(await screen.findByRole('option', { name: 'Salary loan' }))
    await waitFor(() => expect(template).toHaveBeenCalled())
    expect(screen.getByRole('combobox', { name: 'Loan type' })).toHaveTextContent('Salary loan')
  })
})
