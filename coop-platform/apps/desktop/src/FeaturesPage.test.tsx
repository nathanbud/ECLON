import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Me } from './api'
import type { FeatureRow } from './api/features'
import FeaturesPage from './FeaturesPage'
import { visibleScreens } from './nav/screens'
import { AppThemeProvider } from './theme'

const save = vi.fn()
let me: Me
vi.mock('./session', () => ({ useMe: () => me }))
vi.mock('./api/features', async (orig) => ({
  ...(await orig<typeof import('./api/features')>()),
  getFeatures: async (): Promise<FeatureRow[]> => [
    { key: 'savings', label: 'Savings', note: 'Savings deposit accounts.', on: true },
    { key: 'pos', label: 'Point of sale', note: 'Selling goods.', on: false },
  ],
  saveFeatures: (changes: Record<string, boolean>, reason: string) => save(changes, reason),
}))

const person = (roles: string[], featuresOff: string[] = []): Me =>
  ({ username: 'u', name: 'U', roles, canEdit: true, canApprove: true, admin: true, mustChangePassword: false, featuresOff })

function show() {
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AppThemeProvider><FeaturesPage /></AppThemeProvider></QueryClientProvider>)
}

describe('Features', () => {
  beforeEach(() => { save.mockReset().mockResolvedValue([]) })

  it('lets a super administrator switch an area off with a reason', async () => {
    me = person(['ADMIN', 'SUPERADMIN'])
    show()
    await userEvent.click(await screen.findByRole('switch', { name: 'Savings on' }))
    await userEvent.type(screen.getByLabelText(/Reason for the change/), 'No savings offered')
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(save).toHaveBeenCalledWith({ savings: false }, 'No savings offered')
  })

  it('only shows them to an administrator who is not a super administrator', async () => {
    me = person(['ADMIN'])
    show()
    expect(await screen.findByText(/Only a super administrator switches these/)).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: 'Savings on' })).toBeDisabled()
  })

  it('leaves the screens of a switched-off area out of the menu, tiles and search', () => {
    const all = visibleScreens(person(['ADMIN', 'SUPERADMIN', 'ACCOUNTANT', 'PURCHASER', 'INVENTORY', 'CASHIER']))
    const less = visibleScreens(person(['ADMIN', 'SUPERADMIN', 'ACCOUNTANT', 'PURCHASER', 'INVENTORY', 'CASHIER'], ['loans', 'savings']))
    expect(all.some((s) => s.view === 'loans')).toBe(true)
    expect(less.some((s) => s.view === 'loans' || s.view === 'loanapp' || s.view === 'loansetup' || s.view === 'dormancy')).toBe(false)
    expect(less.some((s) => s.view === 'members')).toBe(true)
  })
})
