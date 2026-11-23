import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { AdminUser, Me } from './api'
import { can } from './nav/screens'
import { AppThemeProvider } from './theme'
import UserRolesDialog from './UserRolesDialog'

const grant = vi.fn(async (_u: string, _r: string) => undefined)
const revoke = vi.fn(async (_u: string, _r: string) => undefined)
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  grantRole: (u: string, r: string) => grant(u, r),
  revokeRole: (u: string, r: string) => revoke(u, r),
}))

const user = { username: 'jdelacruz', name: 'Juan', level: 'USER', enabled: true, canEdit: false, hasPassword: true, roles: ['ACCOUNTANT'] } as unknown as AdminUser

describe('roles and the auditor', () => {
  it('grants and takes away a role with a switch each, and says so', async () => {
    const notice = vi.fn()
    render(<QueryClientProvider client={new QueryClient()}><AppThemeProvider><UserRolesDialog user={user} onClose={() => {}} onNotice={notice} /></AppThemeProvider></QueryClientProvider>)
    const u = userEvent.setup()
    expect(screen.getByRole('switch', { name: 'Accountant role' })).toBeChecked()
    await u.click(screen.getByRole('switch', { name: 'Auditor role' }))
    await waitFor(() => expect(grant).toHaveBeenCalledWith('jdelacruz', 'AUDITOR'))
    expect(await screen.findByRole('switch', { name: 'Auditor role' })).toBeChecked()
    expect(notice).toHaveBeenCalledWith('jdelacruz is now an auditor.')
    await u.click(screen.getByRole('switch', { name: 'Accountant role' }))
    await waitFor(() => expect(revoke).toHaveBeenCalledWith('jdelacruz', 'ACCOUNTANT'))
  })

  it('an auditor opens the books and the audit log, but not the screens that change them', () => {
    const auditor = { admin: false, roles: ['ROLE_AUDITOR'] } as unknown as Me
    expect(can(auditor, 'books')).toBe(true)
    expect(can(auditor, 'auditlog')).toBe(true)
    expect(can(auditor, 'accountant')).toBe(false)
    expect(can(auditor, 'admin')).toBe(false)
    const accountant = { admin: false, roles: ['ROLE_ACCOUNTANT'] } as unknown as Me
    expect(can(accountant, 'books')).toBe(true)
    expect(can(accountant, 'auditlog')).toBe(false)
  })
})
