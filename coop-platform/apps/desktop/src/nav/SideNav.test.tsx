import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Me } from '../api'
import { AppThemeProvider } from '../theme'
import SideNav from './SideNav'

let roles: string[] = []
let admin = false
vi.mock('../session', () => ({ useMe: () => ({ username: 'u', name: 'U', roles, canEdit: false, canApprove: false, admin, mustChangePassword: false }) as Me }))
vi.mock('../api', async (orig) => ({ ...(await orig<typeof import('../api')>()), getInboxCount: vi.fn(async () => ({ count: 3 })) }))

function renderNav(view: Parameters<typeof SideNav>[0]['view'] = 'home', tab?: string) {
  const go = vi.fn()
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider><SideNav view={view} tab={tab} onNavigate={go} /></AppThemeProvider></QueryClientProvider>)
  return go
}

describe('the side menu', () => {
  beforeEach(() => { roles = []; admin = false; try { localStorage.clear() } catch { /* none */ } })

  it('is a navigation landmark of links grouped by what a person is doing', () => {
    renderNav()
    const nav = screen.getByRole('navigation', { name: 'Main' })
    expect(within(nav).getByRole('link', { name: 'Home' })).toBeInTheDocument()
    expect(within(nav).getByRole('region', { name: 'Members' })).toBeInTheDocument()
    expect(within(nav).getByRole('region', { name: 'Teller and cash' })).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: 'Client Profile' })).toBeInTheDocument()
    expect(within(nav).queryByRole('button', { name: 'Client Profile' })).not.toBeInTheDocument()
  })

  it('shows only what the roles allow', () => {
    renderNav()
    const nav = screen.getByRole('navigation', { name: 'Main' })
    expect(within(nav).queryByRole('link', { name: 'Debit/credit memos' })).not.toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: 'Users' })).not.toBeInTheDocument()
  })

  it('shows an accountant the memos, on their own tab of Accounting', async () => {
    roles = ['ROLE_ACCOUNTANT']
    const go = renderNav()
    const user = userEvent.setup()
    await user.click(within(screen.getByRole('navigation', { name: 'Main' })).getByRole('link', { name: 'Debit/credit memos' }))
    expect(go).toHaveBeenCalledWith('accounting', 'Memos')
  })

  it('marks where you are, using the tab when there is one', () => {
    roles = ['ROLE_ACCOUNTANT']
    renderNav('accounting', 'Memos')
    const nav = screen.getByRole('navigation', { name: 'Main' })
    expect(within(nav).getByRole('link', { name: 'Debit/credit memos' })).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('link', { name: 'Journals' })).not.toHaveAttribute('aria-current')
  })

  it('says how many things are waiting, in its label', async () => {
    renderNav()
    expect(await screen.findByRole('link', { name: 'Waiting for you, 3 waiting' })).toBeInTheDocument()
  })

  it('collapses to icons, remembers that, and can be opened again', async () => {
    renderNav()
    const user = userEvent.setup()
    const nav = screen.getByRole('navigation', { name: 'Main' })
    expect(nav).toHaveAttribute('data-collapsed', 'false')
    await user.click(screen.getByRole('button', { name: 'Collapse the menu' }))
    expect(nav).toHaveAttribute('data-collapsed', 'true')
    expect(localStorage.getItem('coop.nav.collapsed')).toBe('1')
    expect(within(nav).getByRole('link', { name: 'Client Profile' }), 'still reachable by name').toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Expand the menu' }))
    expect(nav).toHaveAttribute('data-collapsed', 'false')
  })

  it('opens Home from the top entry', async () => {
    const go = renderNav('members')
    await userEvent.setup().click(screen.getByRole('link', { name: 'Home' }))
    expect(go).toHaveBeenCalledWith('home')
  })
})
