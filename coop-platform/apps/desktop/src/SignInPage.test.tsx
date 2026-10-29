import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Me } from './api'
import { brand } from './brand'
import SignInPage from './SignInPage'
import { AppThemeProvider } from './theme'

const me: Me = { username: 'nathan', name: 'Nathan', roles: [], canEdit: false, canApprove: false, admin: false, mustChangePassword: false }
const signIn = vi.fn()
vi.mock('./api', async (orig) => ({ ...(await orig<typeof import('./api')>()), signIn: (...a: unknown[]) => signIn(...a) }))

/** The server as the sign-in screen sees it: /api/me refuses an unsigned request, and the public branding answers with what the SUPERADMIN set. */
function server(branding: object = { coopName: null, tagline: null, hasLogo: false, version: '0' }, up = true) {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    if (!up) throw new Error('down')
    if (String(url).includes('/api/public/branding')) return new Response(JSON.stringify(branding), { status: 200, headers: { 'Content-Type': 'application/json' } })
    return new Response(null, { status: 401 })
  }))
}

function show(onSignedIn = vi.fn()) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider><SignInPage onSignedIn={onSignedIn} /></AppThemeProvider></QueryClientProvider>)
  return onSignedIn
}

describe('sign-in screen', () => {
  beforeEach(() => {
    signIn.mockReset()
    localStorage.clear()
    server()
  })
  afterEach(() => vi.unstubAllGlobals())

  it('shows the cooperative and the system on the left and the form on the right', async () => {
    show()
    expect(screen.getByRole('heading', { level: 1, name: brand.product })).toBeInTheDocument()
    // The acronym is also in the narrow-window header, so more than one is expected.
    expect(screen.getAllByText(brand.coop).length).toBeGreaterThan(0)
    expect(screen.getByText(brand.system)).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'Welcome back' })).toBeInTheDocument()
  })

  it('shows the name, tagline and logo a SUPERADMIN set', async () => {
    server({ coopName: 'Benguet Teachers Cooperative', tagline: 'Together we grow', hasLogo: true, version: '42' })
    show()
    expect(await screen.findByRole('heading', { level: 1, name: 'Benguet Teachers Cooperative' })).toBeInTheDocument()
    expect(screen.getByText('Together we grow')).toBeInTheDocument()
    const logos = screen.getAllByAltText('Benguet Teachers Cooperative logo')
    expect(logos[0]).toHaveAttribute('src', '/api/public/branding/logo?v=42')
  })

  it('keeps the server status on the right, with the form', async () => {
    show()
    const main = screen.getByRole('main')
    expect(await within(main).findByText('Server online')).toBeInTheDocument()
  })

  it('keeps Sign in disabled until both fields are filled, then signs in', async () => {
    signIn.mockResolvedValue(me)
    const done = show()
    const user = userEvent.setup()
    const button = screen.getByRole('button', { name: 'Sign in' })
    expect(button).toBeDisabled()
    await user.type(screen.getByLabelText('Username'), 'nathan')
    expect(button).toBeDisabled()
    await user.type(screen.getByLabelText('Password'), 'secret')
    expect(button).toBeEnabled()
    await user.click(button)
    expect(signIn).toHaveBeenCalledWith('nathan', 'secret')
    await waitFor(() => expect(done).toHaveBeenCalledWith(me))
    expect(localStorage.getItem('coop.lastUser')).toBe('nathan')
  })

  it('shows a refusal, clears the password and stays on the screen', async () => {
    signIn.mockRejectedValue(new Error('The username or password is wrong.'))
    const done = show()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('Username'), 'nathan')
    await user.type(screen.getByLabelText('Password'), 'nope')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('The username or password is wrong.')
    expect(screen.getByLabelText('Password')).toHaveValue('')
    expect(done).not.toHaveBeenCalled()
  })

  it('can show the typed password', async () => {
    show()
    const user = userEvent.setup()
    const field = screen.getByLabelText('Password')
    expect(field).toHaveAttribute('type', 'password')
    await user.click(screen.getByRole('button', { name: 'Show typed characters' }))
    expect(field).toHaveAttribute('type', 'text')
    expect(screen.getByRole('button', { name: 'Hide typed characters' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('says when the server cannot be reached, on the right side', async () => {
    server(undefined, false)
    show()
    expect(await screen.findByText(/cannot be reached right now/i)).toBeInTheDocument()
    expect(await within(screen.getByRole('main')).findByText('Server not reachable')).toBeInTheDocument()
  })

  it('has no accessibility violations', async () => {
    show()
    const result = await axe.run(document.body, { rules: { 'color-contrast': { enabled: false }, region: { enabled: false } } })
    expect(result.violations.map((v) => v.id + ': ' + v.help)).toEqual([])
  })
})
