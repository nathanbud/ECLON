import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import LoanModulePage from './LoanModulePage'
import { AppThemeProvider } from './theme'

const perms = vi.fn()
const openWindow = vi.fn(() => true)
vi.mock('./detachedWindow', async (orig) => ({ ...(await orig<typeof import('./detachedWindow')>()), openLoanApplicationWindow: () => openWindow() }))
const waiting = vi.fn(() => Promise.resolve([] as unknown[]))
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  getLoanAppPermissions: () => perms(),
  getLoanApplications: () => waiting(),
}))
vi.mock('./session', () => ({ useMe: () => ({ username: 'clerk', name: 'Clerk', roles: [], canEdit: false, canApprove: false, admin: false, mustChangePassword: false, branchCode: 1 }) }))
vi.mock('./toolbarSlot', () => ({ ToolbarPortal: ({ children }: { children: React.ReactNode }) => <div data-testid="title-bar">{children}</div> }))

function show() {
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AppThemeProvider><LoanModulePage /></AppThemeProvider></QueryClientProvider>)
}

describe('Loan Module', () => {
  beforeEach(() => { perms.mockReset(); openWindow.mockClear(); waiting.mockImplementation(() => Promise.resolve([])) })

  it('starts applications and lists them through approval, and sends the release to Front Office', async () => {
    perms.mockResolvedValue({ canAdd: true, canDeduct: true, canVerify: false, canApprove: false })
    show()
    expect(await screen.findByRole('button', { name: 'New loan application' })).toBeEnabled()
    const steps = screen.getByRole('list', { name: 'Loan steps' })
    for (const s of ['1 · Profiling', '2 · Deduction', '3 · Verification', '4 · Approval', '5 · Loan Release']) expect(steps).toHaveTextContent(s)
    expect(steps).toHaveTextContent('In Front Office > Disburse')
    expect(await screen.findByText('Loan applications waiting')).toBeInTheDocument()
  })

  it('says so when the person has none of the steps', async () => {
    perms.mockResolvedValue({ canAdd: false, canDeduct: false, canVerify: false, canApprove: false })
    show()
    expect(await screen.findByText(/Your account has none of the loan application steps/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'New loan application' })).toBeDisabled()
  })

  it('opens a new loan application with Ctrl+N, and not for someone who may not do Profiling', async () => {
    perms.mockResolvedValue({ canAdd: true, canDeduct: false, canVerify: false, canApprove: false })
    show()
    const button = await screen.findByRole('button', { name: 'New loan application' })
    expect(button).toHaveAttribute('aria-keyshortcuts', 'Control+N')
    expect(button).toHaveTextContent('Ctrl+N')
    await userEvent.keyboard('{Control>}n{/Control}')
    expect(openWindow).toHaveBeenCalledOnce()
  })

  it('does nothing on Ctrl+N without Profiling', async () => {
    perms.mockResolvedValue({ canAdd: false, canDeduct: true, canVerify: false, canApprove: false })
    show()
    await waitFor(() => expect(screen.getByRole('button', { name: 'New loan application' })).toBeDisabled())
    await userEvent.keyboard('{Control>}n{/Control}')
    expect(openWindow).not.toHaveBeenCalled()
  })

  it('has a Loan Module menu in the title bar that starts a loan and narrows the list to one step', async () => {
    perms.mockResolvedValue({ canAdd: true, canDeduct: true, canVerify: true, canApprove: true })
    waiting.mockImplementation(() => Promise.resolve([{ id: 1, documentNo: 'D1', clientId: 1, clientName: 'Member 1', typeCode: 21, typeName: 'Salary loan', refNo: 1, amount: 5000, terms: 12, status: 1, statusName: 'New', encodedByName: 'Ana', date: '2099-01-01', voucherYear: null, voucherControlNo: null, branchCode: 1, waitingForDeductions: true }, { id: 2, documentNo: 'D2', clientId: 2, clientName: 'Member 2', typeCode: 21, typeName: 'Salary loan', refNo: 2, amount: 5000, terms: 12, status: 1, statusName: 'New', encodedByName: 'Ana', date: '2099-01-01', voucherYear: null, voucherControlNo: null, branchCode: 1, waitingForDeductions: false }, { id: 3, documentNo: 'D3', clientId: 3, clientName: 'Member 3', typeCode: 21, typeName: 'Salary loan', refNo: 3, amount: 5000, terms: 12, status: 2, statusName: 'Verified', encodedByName: 'Ana', date: '2099-01-01', voucherYear: null, voucherControlNo: null, branchCode: 1, waitingForDeductions: false }]))
    show()
    const bar = await screen.findByTestId('title-bar')
    const menuButton = within(bar).getByRole('button', { name: /Loan Module/ })
    expect(menuButton).toHaveAttribute('aria-keyshortcuts', 'Alt+L')
    await userEvent.click(menuButton)
    const menu = await screen.findByRole('menu')
    expect(within(menu).getByRole('menuitem', { name: /New loan application/ })).toHaveTextContent('Ctrl+N')
    expect(within(menu).getByRole('menuitem', { name: /Waiting for deductions/ })).toHaveTextContent('1')
    expect(within(menu).queryByRole('menuitem', { name: /Loan Setup/ })).toBeNull()
    await userEvent.click(within(menu).getByRole('menuitem', { name: /Waiting for deductions/ }))
    expect(await screen.findByText('Loan applications waiting · Waiting for deductions')).toBeInTheDocument()
    expect(screen.getByText(/Member 1 ·/)).toBeInTheDocument()
    expect(screen.queryByText(/Member 2 ·/)).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: 'Show all waiting' }))
    expect(await screen.findByText(/Member 2 ·/)).toBeInTheDocument()
  })
})
