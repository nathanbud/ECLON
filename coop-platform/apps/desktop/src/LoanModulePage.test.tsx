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
    // what each step is sits behind its ⓘ, not on the card
    expect(steps).not.toHaveTextContent('In Front Office > Disburse')
    await userEvent.hover(within(steps).getByRole('button', { name: 'About Loan Release' }))
    expect(await screen.findByRole('tooltip')).toHaveTextContent('In Front Office > Disburse')
    expect(await screen.findByText('Loan applications waiting')).toBeInTheDocument()
  })

  it('marks each step granted to the person or not, Loan Release from the Cash Voucher right', async () => {
    perms.mockResolvedValue({ canAdd: true, canDeduct: false, canVerify: true, canApprove: false, canRelease: true })
    show()
    const steps = screen.getByRole('list', { name: 'Loan steps' })
    expect(await within(steps).findByRole('listitem', { name: '1 · Profiling, granted to you' })).toHaveTextContent('Granted to you')
    expect(within(steps).getByRole('listitem', { name: '2 · Deduction, not granted to you' })).toHaveTextContent('Not granted')
    expect(within(steps).getByRole('listitem', { name: '3 · Verification, granted to you' })).toBeInTheDocument()
    expect(within(steps).getByRole('listitem', { name: '4 · Approval, not granted to you' })).toBeInTheDocument()
    expect(within(steps).getByRole('listitem', { name: '5 · Loan Release, granted to you' })).toBeInTheDocument()
    expect(screen.getByText(/A step marked Not granted is given by an administrator/)).toBeInTheDocument()
  })

  it('leaves Loan Release unmarked when an older server does not say', async () => {
    perms.mockResolvedValue({ canAdd: true, canDeduct: true, canVerify: true, canApprove: true })
    show()
    const steps = screen.getByRole('list', { name: 'Loan steps' })
    await within(steps).findByRole('listitem', { name: '4 · Approval, granted to you' })
    expect(within(steps).getByRole('listitem', { name: '5 · Loan Release' })).not.toHaveTextContent(/granted/i)
    expect(screen.queryByText(/A step marked Not granted/)).toBeNull()
  })

  it('sorts the list by date, name or status, newest first to start', async () => {
    perms.mockResolvedValue({ canAdd: true, canDeduct: true, canVerify: true, canApprove: true })
    const item = (id: number, name: string, date: string, status: number, waitingForDeductions: boolean) => ({ id, documentNo: `D${id}`, clientId: id, clientName: name, typeCode: 21, typeName: 'Salary loan', refNo: id, amount: 5000, terms: 12, status, statusName: 'New', encodedByName: 'Ana', date, voucherYear: null, voucherControlNo: null, branchCode: 1, waitingForDeductions })
    waiting.mockImplementation(() => Promise.resolve([item(1, 'Cruz', '2099-01-02', 2, false), item(2, 'Abad', '2099-01-01', 1, false), item(3, 'Bato', '2099-01-03', 1, true)]))
    show()
    await screen.findByText(/Cruz ·/)
    const order = () => screen.getAllByText(/^(Abad|Bato|Cruz) ·/).map((e) => e.textContent!.split(' ')[0])
    expect(order()).toEqual(['Bato', 'Cruz', 'Abad'])
    const choose = async (label: string) => {
      await userEvent.click(screen.getByRole('combobox', { name: 'Sort by' }))
      await userEvent.click(await screen.findByRole('option', { name: label }))
    }
    await choose('Name, A to Z')
    expect(order()).toEqual(['Abad', 'Bato', 'Cruz'])
    await choose('Status, by step')
    expect(order()).toEqual(['Bato', 'Abad', 'Cruz'])
    await choose('Date, oldest first')
    expect(order()).toEqual(['Abad', 'Cruz', 'Bato'])
  })

  it('opens newest first again, whatever order was chosen before', async () => {
    perms.mockResolvedValue({ canAdd: true, canDeduct: true, canVerify: true, canApprove: true })
    show()
    expect(await screen.findByRole('combobox', { name: 'Sort by' })).toHaveTextContent('Date, newest first')
  })

  it('lists approved applications at 5 · Release and released ones apart, with their own step buttons', async () => {
    perms.mockResolvedValue({ canAdd: true, canDeduct: true, canVerify: true, canApprove: true })
    const item = (id: number, status: number, releaseStatus: string | null, releasedOn: string | null = null) => ({ id, documentNo: `D${id}`, clientId: id, clientName: `Member ${id}`, typeCode: 21, typeName: 'Salary loan', refNo: id, amount: 5000, terms: 12, status, statusName: status === 3 ? 'Approved' : 'New', encodedByName: 'Ana', date: '2099-01-01', voucherYear: status === 3 ? 2099 : null, voucherControlNo: status === 3 ? id : null, branchCode: 1, waitingForDeductions: false, approvedOn: status === 3 ? '2099-01-02' : null, releaseStatus, releasedOn })
    waiting.mockImplementation(() => Promise.resolve([item(1, 1, null), item(2, 3, 'WAITING FOR APPROVAL'), item(3, 3, 'POSTED', '2099-01-03')]))
    show()
    const steps = await screen.findByRole('group', { name: 'Show applications at step' })
    expect(await within(steps).findByRole('button', { name: 'All (2)' })).toBeInTheDocument()
    expect(screen.queryByText(/Member 3 ·/)).toBeNull() // released: only under Released
    expect(screen.getByRole('img', { name: /^At step 5 of 5: Loan Release \(Waiting for release in Front Office\)/ })).toBeInTheDocument()
    await userEvent.click(within(steps).getByRole('button', { name: '5 · Release (1)' }))
    expect(screen.getByText(/Member 2 ·/)).toBeInTheDocument()
    expect(screen.queryByText(/Member 1 ·/)).toBeNull()
    await userEvent.click(within(steps).getByRole('button', { name: 'Released (1)' }))
    expect(screen.getByText(/Member 3 ·/)).toBeInTheDocument()
    expect(screen.getByText(/released 2099-01-03/)).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Released on 2099-01-03. All 5 steps done.' })).toBeInTheDocument()
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
    await userEvent.click(screen.getByRole('button', { name: 'All (3)' }))
    expect(await screen.findByText(/Member 2 ·/)).toBeInTheDocument()
  })

  it('narrows the list with the step buttons over it, each with its count', async () => {
    perms.mockResolvedValue({ canAdd: true, canDeduct: true, canVerify: true, canApprove: true })
    const item = (id: number, status: number, statusName: string, waitingForDeductions: boolean) => ({ id, documentNo: `D${id}`, clientId: id, clientName: `Member ${id}`, typeCode: 21, typeName: 'Salary loan', refNo: id, amount: 5000, terms: 12, status, statusName, encodedByName: 'Ana', date: '2099-01-01', voucherYear: null, voucherControlNo: null, branchCode: 1, waitingForDeductions })
    waiting.mockImplementation(() => Promise.resolve([item(1, 1, 'New', true), item(2, 1, 'New', false), item(3, 2, 'Verified', false)]))
    show()
    const steps = await screen.findByRole('group', { name: 'Show applications at step' })
    expect(within(steps).getByRole('button', { name: 'All (3)' })).toHaveAttribute('aria-pressed', 'true')
    for (const [name, shown, hidden] of [['2 · Deduction (1)', 1, 2], ['3 · Verification (1)', 2, 3], ['4 · Approval (1)', 3, 1]] as const) {
      await userEvent.click(within(steps).getByRole('button', { name }))
      expect(within(steps).getByRole('button', { name })).toHaveAttribute('aria-pressed', 'true')
      expect(screen.getByText(new RegExp(`Member ${shown} ·`))).toBeInTheDocument()
      expect(screen.queryByText(new RegExp(`Member ${hidden} ·`))).toBeNull()
    }
  })
})
