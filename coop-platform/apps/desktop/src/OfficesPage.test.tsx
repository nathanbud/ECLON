import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Offices } from './api/offices'
import OfficesPage, { cannotDelete } from './OfficesPage'
import { AppThemeProvider } from './theme'

const add = vi.fn()
const rename = vi.fn()
const remove = vi.fn()
let data: Offices
vi.mock('./api/offices', async (orig) => ({
  ...(await orig<typeof import('./api/offices')>()),
  getOffices: async () => data,
  addOffice: (id: number, name: string, reason: string) => add(id, name, reason),
  renameOffice: (id: number, name: string, reason: string) => rename(id, name, reason),
  deleteOffice: (id: number, reason: string) => remove(id, reason),
}))

const coop: Offices = {
  editable: true, nextNumber: 6, maxNameLength: 200,
  offices: [
    { id: 1, name: 'MAIN OFFICE', members: 30000, users: 64 },
    { id: 2, name: 'BAUKO SATELLITE', members: 1200, users: 60 },
    { id: 5, name: 'BRENTWOOD', members: 0, users: 0 },
  ],
}

function show() {
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AppThemeProvider><OfficesPage /></AppThemeProvider></QueryClientProvider>)
}

describe('Offices', () => {
  beforeEach(() => {
    data = coop
    add.mockReset().mockImplementation(async () => data)
    rename.mockReset().mockImplementation(async () => data)
    remove.mockReset().mockImplementation(async () => data)
  })

  it('lists the offices with who belongs to each', async () => {
    show()
    const row = (await screen.findByText('BAUKO SATELLITE')).closest('tr')!
    expect(row).toHaveTextContent('1,200')
    expect(row).toHaveTextContent('60')
  })

  it('lets only an unused office other than 1 be deleted', async () => {
    expect(cannotDelete(coop.offices[0])).toMatch(/Office 1/)
    expect(cannotDelete(coop.offices[1])).toMatch(/1200 members and 60 users/)
    expect(cannotDelete(coop.offices[2])).toBeNull()
    show()
    expect(await screen.findByRole('button', { name: 'Delete MAIN OFFICE' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Delete BAUKO SATELLITE' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: 'Delete BRENTWOOD' }))
    const dialog = screen.getByRole('dialog')
    await userEvent.type(within(dialog).getByLabelText(/Reason/), 'Added by mistake')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete office' }))
    expect(remove).toHaveBeenCalledWith(5, 'Added by mistake')
  })

  it('adds an office with the next free number and a reason', async () => {
    show()
    await userEvent.click(await screen.findByRole('button', { name: 'Add office' }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByLabelText(/Office number/)).toHaveValue('6')
    const save = within(dialog).getByRole('button', { name: 'Add office' })
    await userEvent.type(within(dialog).getByLabelText(/Office name/), 'Buguias Satellite')
    expect(save).toBeDisabled()
    await userEvent.type(within(dialog).getByLabelText(/Reason/), 'New satellite opened')
    await userEvent.click(save)
    expect(add).toHaveBeenCalledWith(6, 'Buguias Satellite', 'New satellite opened')
  })

  it('refuses a name another office has', async () => {
    show()
    await userEvent.click(await screen.findByRole('button', { name: 'Rename BRENTWOOD' }))
    const dialog = screen.getByRole('dialog')
    const name = within(dialog).getByLabelText(/Office name/)
    await userEvent.clear(name)
    await userEvent.type(name, 'main office')
    await userEvent.type(within(dialog).getByLabelText(/Reason/), 'Test')
    expect(within(dialog).getByText('Another office already has this name.')).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Save name' })).toBeDisabled()
  })

  it('on a database with no offices, says why and starts the first one as office 1, MAIN OFFICE', async () => {
    data = { editable: true, nextNumber: 1, maxNameLength: 200, offices: [] }
    show()
    expect(await screen.findByText(/new members cannot be registered/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Add office' }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByLabelText(/Office number/)).toHaveValue('1')
    expect(within(dialog).getByLabelText(/Office name/)).toHaveValue('MAIN OFFICE')
  })

  it('only shows the list while the database does not allow changes', async () => {
    data = { ...coop, editable: false }
    show()
    expect(await screen.findByText(/can only be read/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Add office' })).toBeNull()
    expect(screen.queryByRole('button', { name: /Rename/ })).toBeNull()
  })
})
