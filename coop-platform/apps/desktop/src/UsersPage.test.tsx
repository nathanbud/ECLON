import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AdminUser } from './api'
import { AppThemeProvider } from './theme'
import UsersPage from './UsersPage'

const user = (username: string, more: Partial<AdminUser> = {}): AdminUser => ({
  username, name: username.toUpperCase(), level: 'USER', enabled: true, canEdit: false, hasPassword: true, passwordState: 'ACTIVE', tempExpiresAt: null, roles: [], ...more,
})
const USERS = [
  user('ana', { name: 'ANA DELA CRUZ', level: 'ADMIN' }),
  user('ben', { roles: ['MEMBERSHIP_OFFICER'] }),
  user('cora', { enabled: false }),
  user('dan', { passwordState: 'NONE', hasPassword: false }),
]
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  listUsers: vi.fn(async () => USERS),
  listSingleHand: vi.fn(async () => []),
  listLockedUsers: vi.fn(async () => [{ username: 'ben' }]),
  setUserEnabled: (...a: unknown[]) => setEnabled(...a),
}))
const setEnabled = vi.fn(async (..._: unknown[]) => ({ changed: 1 }))
vi.mock('./session', () => ({ useMe: () => ({ username: 'ana', name: 'ANA DELA CRUZ', roles: [], canEdit: true, canApprove: false, admin: true, mustChangePassword: false, branchCode: 1 }) }))
vi.mock('./toolbarSlot', () => ({ ToolbarPortal: ({ children }: { children: React.ReactNode }) => <div data-testid="title-bar">{children}</div> }))
vi.mock('./NewUserDialog', () => ({ default: ({ open }: { open: boolean }) => (open ? <div role="dialog" aria-label="New user window" /> : null) }))
vi.mock('./UserProfileDialog', () => ({ default: ({ user }: { user: AdminUser | null }) => (user ? <div role="dialog" aria-label={`Account setup of ${user.username}`} /> : null) }))

function show(onAuditLog = vi.fn()) {
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AppThemeProvider><UsersPage onAuditLog={onAuditLog} /></AppThemeProvider></QueryClientProvider>)
  return onAuditLog
}
const listed = () => within(screen.getByRole('table', { name: 'Users' })).queryAllByRole('row').slice(1).map((r) => r.querySelector('p')?.textContent)

describe('Users', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows each name with every word capitalised', async () => {
    show()
    expect(await screen.findByText('Ana Dela Cruz')).toBeInTheDocument()
  })

  it('has a Users menu in the title bar that narrows the list, with a count for each choice', async () => {
    show()
    await screen.findByText('Ana Dela Cruz')
    const menuButton = within(screen.getByTestId('title-bar')).getByRole('button', { name: /Users/ })
    expect(menuButton).toHaveAttribute('aria-keyshortcuts', 'Alt+U')
    await userEvent.click(menuButton)
    const menu = await screen.findByRole('menu')
    expect(within(menu).getByRole('menuitem', { name: /New user/ })).toHaveTextContent('Ctrl+N')
    for (const [label, count] of [['All users', '4'], ['Enabled', '3'], ['Disabled', '1'], ['Administrators', '1'], ['Membership Officers', '1'], ['Needs a password', '1'], ['Locked out', '1']]) {
      expect(within(menu).getByRole('menuitem', { name: new RegExp(`^${label}`) })).toHaveTextContent(count)
    }
    await userEvent.click(within(menu).getByRole('menuitem', { name: /^Disabled/ }))
    expect(listed()).toEqual(['cora'])
    await userEvent.click(screen.getByRole('button', { name: /Showing: Disabled · show all/ }))
    expect(listed()).toEqual(['ana', 'ben', 'cora', 'dan'])
  })

  it('shows who is locked out, and opens the Audit Log from the menu', async () => {
    const audit = show()
    await screen.findByText('Ana Dela Cruz')
    await userEvent.click(within(screen.getByTestId('title-bar')).getByRole('button', { name: /Users/ }))
    await userEvent.click(await screen.findByRole('menuitem', { name: /^Locked out/ }))
    expect(listed()).toEqual(['ben'])
    await userEvent.click(within(screen.getByTestId('title-bar')).getByRole('button', { name: /Users/ }))
    await userEvent.click(await screen.findByRole('menuitem', { name: /Audit Log/ }))
    expect(audit).toHaveBeenCalledOnce()
  })

  it('opens the New user window with Ctrl+N', async () => {
    show()
    await screen.findByText('Ana Dela Cruz')
    await userEvent.keyboard('{Control>}n{/Control}')
    expect(screen.getByRole('dialog', { name: 'New user window' })).toBeInTheDocument()
  })

  it('opens the account setup from anywhere on the row, not only the name, but not from its switches and buttons', async () => {
    show()
    await screen.findByText('Ana Dela Cruz')
    const row = screen.getByRole('row', { name: "Open ben's account setup" })
    const cells = within(row).getAllByRole('cell')
    // the Level and Password cells, beside their buttons
    await userEvent.click(cells[2])
    expect(screen.getByRole('dialog', { name: 'Account setup of ben' })).toBeInTheDocument()
  })

  it('does not open the account setup when a switch or button on the row is used', async () => {
    show()
    await screen.findByText('Ana Dela Cruz')
    const row = screen.getByRole('row', { name: "Open dan's account setup" })
    await userEvent.click(within(row).getByRole('button', { name: 'Roles of dan' }))
    expect(screen.queryByRole('dialog', { name: 'Account setup of dan', hidden: true })).not.toBeInTheDocument()
  })

  it('switches an account off after asking, and on again at once', async () => {
    show()
    await screen.findByText('Ana Dela Cruz')
    await userEvent.click(screen.getByRole('switch', { name: 'ben is enabled' }))
    const ask = await screen.findByRole('dialog', { name: 'Switch off ben?' })
    expect(ask).toHaveTextContent('will no longer be able to sign in')
    expect(setEnabled).not.toHaveBeenCalled()
    await userEvent.click(within(ask).getByRole('button', { name: 'Switch off' }))
    expect(setEnabled).toHaveBeenCalledWith('ben', false)
    // the page behind stays hidden from screen readers until the dialog's closing animation ends
    await userEvent.click(await screen.findByRole('switch', { name: 'cora is enabled' }))
    expect(setEnabled).toHaveBeenCalledWith('cora', true)
    expect(await screen.findByText('cora can sign in again')).toBeInTheDocument()
  })

  it('does nothing when switching off is cancelled, and never offers it on your own account', async () => {
    show()
    await screen.findByText('Ana Dela Cruz')
    expect(screen.getByRole('switch', { name: 'ana is enabled' })).toBeDisabled()
    await userEvent.click(screen.getByRole('switch', { name: 'dan is enabled' }))
    await userEvent.click(within(await screen.findByRole('dialog', { name: 'Switch off dan?' })).getByRole('button', { name: 'Cancel' }))
    expect(setEnabled).not.toHaveBeenCalled()
  })

  it('shows the reason when the server refuses', async () => {
    setEnabled.mockRejectedValueOnce(new Error('Only a super administrator can change an administrator\'s account or anyone\'s level.'))
    show()
    await screen.findByText('Ana Dela Cruz')
    await userEvent.click(screen.getByRole('switch', { name: 'cora is enabled' }))
    expect(await screen.findByText(/Only a super administrator can change/)).toBeInTheDocument()
  })

  it('opens the menu with Alt+U', async () => {
    show()
    await screen.findByText('Ana Dela Cruz')
    await userEvent.keyboard('{Alt>}u{/Alt}')
    expect(await screen.findByRole('menu')).toBeInTheDocument()
  })
})
