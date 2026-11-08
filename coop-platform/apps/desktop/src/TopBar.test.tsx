import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { Me } from './api'
import { AppThemeProvider } from './theme'
import TopBar from './TopBar'

vi.mock('./session', () => ({ useMe: () => ({ username: 'u', name: 'Una', roles: [], canEdit: false, canApprove: false, admin: false, mustChangePassword: false }) as Me }))
vi.mock('./api', async (orig) => ({ ...(await orig<typeof import('./api')>()), getInboxCount: vi.fn(async () => ({ count: 2 })) }))
vi.mock('./GlobalSearch', () => ({ default: () => <div /> }))

function show(menuOpen = false) {
  const onMenu = vi.fn()
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider><TopBar title="Home" onNavigate={() => {}} onOpenClient={() => {}} onMenu={onMenu} menuOpen={menuOpen} /></AppThemeProvider></QueryClientProvider>)
  return onMenu
}

describe('the logo in the title bar', () => {
  it('is the button that opens the menu, and says how many things wait', async () => {
    const onMenu = show()
    const button = await screen.findByRole('button', { name: 'Menu, 2 waiting for you' })
    expect(button).toHaveAttribute('aria-expanded', 'false')
    await userEvent.setup().click(button)
    expect(onMenu).toHaveBeenCalledTimes(1)
  })

  it('shows that the menu is open', async () => {
    show(true)
    expect(await screen.findByRole('button', { name: /^Menu/ })).toHaveAttribute('aria-expanded', 'true')
  })
})
