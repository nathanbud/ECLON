import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AccessWatcher from './AccessWatcher'
import type { Me, MyAccess } from './api'

const access = vi.fn<(since?: string) => Promise<MyAccess>>()
const restore = vi.fn<() => Promise<Me | null>>()
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  getMyAccess: (since?: string) => access(since),
  restoreSession: () => restore(),
}))

const items = (addLoan: string, roles: string[] = []) => [
  { key: 'level', group: 'Your account', label: 'Level', value: 'USER' },
  ...roles.map((r) => ({ key: `role:${r}`, group: 'Roles', label: r.charAt(0) + r.slice(1).toLowerCase(), value: 'Yes' })),
  { key: 'AccessCanAddLoanApplication', group: 'Receivables · Loans', label: 'Add loan application', value: addLoan },
]
const me: Me = { username: 'teller1', name: 'Ana Cruz', roles: ['ACCOUNTANT'], canEdit: false, canApprove: false, admin: false, mustChangePassword: false, branchCode: 1 }

function show(onMe = vi.fn()) {
  render(<QueryClientProvider client={new QueryClient()}><AccessWatcher onMe={onMe} /></QueryClientProvider>)
  return onMe
}

describe('AccessWatcher', () => {
  beforeEach(() => { access.mockReset(); restore.mockReset(); restore.mockResolvedValue(me) })

  it('says what an administrator changed, by whom, and updates the person without a new sign-in', async () => {
    access.mockResolvedValueOnce({ fingerprint: 'a', changed: false, items: items('No'), lastChange: null })
    const onMe = show()
    await waitFor(() => expect(access).toHaveBeenCalledTimes(1))
    expect(screen.queryByRole('alert')).toBeNull()

    access.mockResolvedValueOnce({ fingerprint: 'b', changed: true, items: items('Yes', ['ACCOUNTANT']), lastChange: { by: 'admin1', at: '2026-11-15T10:42:00' } })
    await act(async () => { window.dispatchEvent(new Event('focus')) })
    const notice = await screen.findByRole('alert')
    expect(access).toHaveBeenLastCalledWith('a')
    expect(notice).toHaveTextContent('Your access was changed')
    expect(notice).toHaveTextContent('admin1 (an administrator) changed what you may do')
    expect(notice).toHaveTextContent('You can now')
    expect(notice).toHaveTextContent('Add loan application')
    expect(notice).toHaveTextContent('Accountant role')
    expect(notice).toHaveTextContent('There is no need to sign out')
    expect(onMe).toHaveBeenCalledWith(me)
  })

  it('stays quiet while nothing changes', async () => {
    access.mockResolvedValueOnce({ fingerprint: 'a', changed: false, items: items('No'), lastChange: null })
    access.mockResolvedValueOnce({ fingerprint: 'a', changed: false, items: null, lastChange: null })
    const onMe = show()
    await waitFor(() => expect(access).toHaveBeenCalledTimes(1))
    await act(async () => { window.dispatchEvent(new Event('focus')) })
    await waitFor(() => expect(access).toHaveBeenCalledTimes(2))
    expect(screen.queryByRole('alert')).toBeNull()
    expect(onMe).not.toHaveBeenCalled()
  })

  it('warns once in the console when the server cannot answer, instead of failing without a trace', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    access.mockRejectedValue(new Error('That record was not found. It may have been removed; reload the screen.'))
    show()
    await waitFor(() => expect(warn).toHaveBeenCalledTimes(1))
    await act(async () => { window.dispatchEvent(new Event('focus')) })
    await waitFor(() => expect(access).toHaveBeenCalledTimes(2))
    expect(warn).toHaveBeenCalledTimes(1)
    expect(String(warn.mock.calls[0][0])).toContain('the server is older than this app')
    warn.mockRestore()
  })
})
