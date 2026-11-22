import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AdminUser, CreatedUser, Me, NewUser } from './api'
import NewUserDialog from './NewUserDialog'
import { AppThemeProvider } from './theme'

let roles: string[] = ['ADMIN']
vi.mock('./session', () => ({ useMe: () => ({ username: 'boss', name: 'Boss', roles, canEdit: true, canApprove: false, admin: true, mustChangePassword: false, branchCode: 1 }) as Me }))
const create = vi.fn()
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  createUser: (u: NewUser) => create(u),
  listBranches: async () => [{ code: 1, name: 'Main Office' }],
}))

const user = (username: string, name: string, extra: Partial<AdminUser> = {}): AdminUser =>
  ({ username, name, level: 'USER', enabled: true, canEdit: true, hasPassword: true, passwordState: 'ACTIVE', tempExpiresAt: null, roles: [], ...extra })
const USERS = [user('ana.teller', 'Ana Cruz', { roles: ['MEMBERSHIP_OFFICER'] }), user('ben', 'Ben Reyes')]

function show(onCreated = vi.fn()) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider><NewUserDialog open users={USERS} onClose={vi.fn()} onCreated={onCreated} /></AppThemeProvider></QueryClientProvider>)
  return onCreated
}

describe('New user', () => {
  beforeEach(() => {
    roles = ['ADMIN']
    create.mockReset().mockImplementation(async (u: NewUser): Promise<CreatedUser> => ({
      username: u.username, accessId: 65, level: u.level, branchCode: 1, copiedFrom: u.copyFrom, transactionRights: 12, roles: u.copyFrom ? ['MEMBERSHIP_OFFICER'] : [],
      password: { username: u.username, password: 'ABCD2345WXYZ', validityHours: u.validityHours, expiresAt: '2026-11-21T10:00:00' },
    }))
  })

  it('creates a user from another one’s permissions and shows the first password once', async () => {
    const onCreated = show()
    const u = userEvent.setup()
    await u.type(screen.getByRole('textbox', { name: 'Username' }), 'nina.teller')
    await u.type(screen.getByRole('textbox', { name: 'First name' }), 'Nina')
    await u.type(screen.getByRole('textbox', { name: 'Last name' }), 'Santos')
    await u.click(screen.getByRole('combobox', { name: /Copy permissions from/ }))
    await u.click(await screen.findByRole('option', { name: /ana\.teller/ }))
    expect(screen.getByText(/and the roles MEMBERSHIP_OFFICER\. Never their name, password or member number/)).toBeInTheDocument()
    await u.click(screen.getByRole('button', { name: '4 hours' }))
    await u.click(screen.getByRole('button', { name: 'Create user' }))

    expect(create).toHaveBeenCalledWith({ username: 'nina.teller', firstName: 'Nina', middleName: null, lastName: 'Santos', branchCode: 1, level: 'USER', copyFrom: 'ana.teller', validityHours: 4 })
    expect(await screen.findByText('User created')).toBeInTheDocument()
    const pw = screen.getByRole('group', { name: 'Temporary password' })
    expect(within(pw).getByText('ABCD')).toBeInTheDocument()
    expect(screen.getByText(/with ana\.teller's permissions \(12 transaction types; roles MEMBERSHIP_OFFICER\)/)).toBeInTheDocument()
    expect(onCreated).toHaveBeenCalled()
  })

  it('will not create a taken username, and says so', async () => {
    show()
    const u = userEvent.setup()
    await u.type(screen.getByRole('textbox', { name: 'Username' }), 'BEN')
    await u.tab()
    expect(screen.getByText('This username is already taken.')).toBeInTheDocument()
    await u.type(screen.getByRole('textbox', { name: 'First name' }), 'B')
    await u.type(screen.getByRole('textbox', { name: 'Last name' }), 'R')
    expect(screen.getByRole('button', { name: 'Create user' })).toBeDisabled()
  })

  it('offers administrator levels only to a super administrator', async () => {
    show()
    const u = userEvent.setup()
    expect(screen.getByText('Only a super administrator can create an administrator.')).toBeInTheDocument()
    await u.click(screen.getByRole('combobox', { name: 'Level' }))
    expect(screen.getByRole('option', { name: /^Administrator/ })).toHaveAttribute('aria-disabled', 'true')
  })

  it('shows why the server refused', async () => {
    roles = ['ADMIN', 'SUPERADMIN']
    create.mockReset().mockRejectedValue(new Error('The username nina is already taken.'))
    show()
    const u = userEvent.setup()
    await u.type(screen.getByRole('textbox', { name: 'Username' }), 'nina')
    await u.type(screen.getByRole('textbox', { name: 'First name' }), 'Nina')
    await u.type(screen.getByRole('textbox', { name: 'Last name' }), 'Santos')
    await u.click(screen.getByRole('button', { name: 'Create user' }))
    expect(await screen.findByText('The username nina is already taken.')).toBeInTheDocument()
  })
})
