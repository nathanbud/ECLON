import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Me } from './api'
import BrandingPage from './BrandingPage'
import { AppThemeProvider } from './theme'

let roles: string[] = ['ROLE_ADMIN', 'ROLE_SUPERADMIN']
vi.mock('./session', () => ({ useMe: () => ({ username: 'boss', name: 'Boss', roles, canEdit: true, canApprove: false, admin: true, mustChangePassword: false }) as Me }))

const setText = vi.fn()
const upload = vi.fn()
const remove = vi.fn()
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  getBranding: async () => ({ coopName: 'Old Name', tagline: 'Old tagline', hasLogo: false, version: '1' }),
  setBrandingText: (...a: unknown[]) => setText(...a),
  uploadBrandingLogo: (...a: unknown[]) => upload(...a),
  removeBrandingLogo: () => remove(),
}))

function show() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider><BrandingPage /></AppThemeProvider></QueryClientProvider>)
}

describe('sign-in screen settings', () => {
  beforeEach(() => {
    roles = ['ROLE_ADMIN', 'ROLE_SUPERADMIN']
    setText.mockReset().mockResolvedValue({ coopName: 'New Name', tagline: 'Old tagline', hasLogo: false, version: '2' })
    upload.mockReset().mockResolvedValue({ coopName: 'New Name', tagline: 'Old tagline', hasLogo: true, version: '3' })
    remove.mockReset()
  })
  afterEach(() => vi.restoreAllMocks())

  it('fills the form from what is saved and previews the change as it is typed', async () => {
    show()
    const name = await screen.findByLabelText('Cooperative name')
    await waitFor(() => expect(name).toHaveValue('Old Name'))
    expect(screen.getByLabelText('Tagline')).toHaveValue('Old tagline')
    const user = userEvent.setup()
    await user.clear(name)
    await user.type(name, 'Brand New Coop')
    expect(screen.getAllByText('Brand New Coop').length).toBeGreaterThan(0) // the live preview
  })

  it('saves only when something changed, and sends both fields', async () => {
    show()
    const user = userEvent.setup()
    const name = await screen.findByLabelText('Cooperative name')
    await waitFor(() => expect(name).toHaveValue('Old Name'))
    const save = screen.getByRole('button', { name: 'Save' })
    expect(save).toBeDisabled()
    await user.clear(name)
    await user.type(name, 'New Name')
    expect(save).toBeEnabled()
    await user.click(save)
    expect(setText).toHaveBeenCalledWith('New Name', 'Old tagline')
  })

  it('uploads a chosen logo at once', async () => {
    show()
    const user = userEvent.setup()
    await screen.findByLabelText('Cooperative name')
    const file = new File([new Uint8Array([137, 80, 78, 71])], 'logo.png', { type: 'image/png' })
    await user.upload(document.querySelector('input[type="file"]') as HTMLInputElement, file)
    expect(upload).toHaveBeenCalledWith(file)
  })

  it('is read only for an administrator who is not a SUPERADMIN', async () => {
    roles = ['ROLE_ADMIN']
    show()
    expect(await screen.findByText(/Only a SUPERADMIN can change this/)).toBeInTheDocument()
    expect(screen.getByLabelText('Cooperative name')).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Upload logo' })).toBeDisabled()
  })
})
