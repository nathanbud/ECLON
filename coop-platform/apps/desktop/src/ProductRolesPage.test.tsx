import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { ProductRoles } from './api/productRoles'
import ProductRolesPage from './ProductRolesPage'
import { AppThemeProvider } from './theme'

const set = vi.fn()
let roles: ProductRoles
vi.mock('./api/productRoles', () => ({ getProductRoles: async () => roles, setProductRoles: (...a: unknown[]) => set(...a) }))

const show = () => render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AppThemeProvider><ProductRolesPage /></AppThemeProvider></QueryClientProvider>)

describe('Product roles', () => {
  it('says when vouchers payable is not chosen, and chooses one of this cooperative’s own types with a reason', async () => {
    roles = { vouchersPayable: null, choices: [{ code: 1, name: 'MA', disabled: false }, { code: 2, name: 'Vouchers payable', disabled: false }] }
    set.mockResolvedValue({ ...roles, vouchersPayable: 2 })
    show()
    const u = userEvent.setup()
    expect(await screen.findByText(/None is chosen yet/)).toBeInTheDocument()
    await u.click(screen.getByLabelText(/^Account type/))
    await u.click(await screen.findByRole('option', { name: '29/2 Vouchers payable' }))
    await u.click(screen.getByRole('button', { name: 'Save' }))
    const dialog = await screen.findByRole('dialog')
    await u.type(within(dialog).getByLabelText(/^Reason/), 'Our payable type')
    await u.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(set).toHaveBeenCalledWith(2, 'Our payable type'))
  })

  it('points to Fund and payable products when the database has no such type', async () => {
    roles = { vouchersPayable: null, choices: [] }
    show()
    expect(await screen.findByText(/Add one in Settings > Products > Fund and payable products/)).toBeInTheDocument()
  })
})
