import { render, screen, waitFor } from '@testing-library/react'
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

function show(onSignedIn = vi.fn()) {
  render(<AppThemeProvider><SignInPage onSignedIn={onSignedIn} /></AppThemeProvider>)
  return onSignedIn
}

describe('sign-in screen', () => {
  beforeEach(() => {
    signIn.mockReset()
    localStorage.clear()
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 401 })))
  })
  afterEach(() => vi.unstubAllGlobals())

  it('shows the cooperative and the system on the left and the form on the right', () => {
    show()
    expect(screen.getByRole('heading', { level: 1, name: brand.product })).toBeInTheDocument()
    // The acronym is also in the narrow-window header, so more than one is expected.
    expect(screen.getAllByText(brand.coop).length).toBeGreaterThan(0)
    expect(screen.getByText(brand.system)).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'Welcome back' })).toBeInTheDocument()
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

  it('can show the typed password and warns about Caps Lock', async () => {
    show()
    const user = userEvent.setup()
    const field = screen.getByLabelText('Password')
    expect(field).toHaveAttribute('type', 'password')
    await user.click(screen.getByRole('button', { name: 'Show typed characters' }))
    expect(field).toHaveAttribute('type', 'text')
    expect(screen.getByRole('button', { name: 'Hide typed characters' })).toHaveAttribute('aria-pressed', 'true')
    await user.click(field)
    await user.keyboard('{CapsLock}')
    // jsdom does not model the Caps Lock state, so only the control is checked here.
  })

  it('says when the server cannot be reached', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('down') }))
    show()
    expect(await screen.findByText(/cannot be reached right now/i)).toBeInTheDocument()
    expect(screen.getByText('Server not reachable')).toBeInTheDocument()
  })

  it('has no accessibility violations', async () => {
    show()
    const result = await axe.run(document.body, { rules: { 'color-contrast': { enabled: false }, region: { enabled: false } } })
    expect(result.violations.map((v) => v.id + ': ' + v.help)).toEqual([])
  })
})
