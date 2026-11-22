import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { StarterList, StarterResult, StarterStatus } from './api'
import StarterSetupPage, { standing } from './StarterSetupPage'
import { AppThemeProvider } from './theme'

const list = (table: string, label: string, group: string, more: Partial<StarterList> = {}): StarterList => ({
  table, label, group, inPackage: 72, here: 72, missing: 0, leftOut: 0, leftOutWhy: [], problem: null, ...more,
})
const status = vi.fn<() => Promise<StarterStatus>>()
const load = vi.fn<(reason: string) => Promise<StarterResult>>()
vi.mock('./api', async (orig) => ({ ...(await orig<typeof import('./api')>()), getStarterSetup: () => status(), loadStarterLists: (r: string) => load(r) }))

function show() {
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AppThemeProvider><StarterSetupPage /></AppThemeProvider></QueryClientProvider>)
}

const empty: StarterStatus = {
  lists: [
    list('cts_type', 'Account statuses that balances are posted under', 'Accounts and ledger', { here: 0, missing: 72 }),
    list('inv_adjreason', 'Stock adjustment reasons', 'Inventory and sales', { inPackage: 4, here: 0, missing: 3, leftOut: 1, leftOutWhy: ['A row with br_code = 77 points to a record of branches this database does not have, so it was left out.'] }),
    list('gender', 'Genders', 'Members', { inPackage: 3, here: 3 }),
  ],
  missing: 75, leftOut: 1, canLoad: true, cannotLoad: null,
}
const complete: StarterStatus = { ...empty, lists: empty.lists.map((l) => ({ ...l, here: l.inPackage, missing: 0, leftOut: 0, leftOutWhy: [] })), missing: 0, leftOut: 0 }

// Each test sets its own answers (no mockReset; see SchemaStatusNotice.test.tsx).
describe('starter setup', () => {
  it('shows what is missing, asks for a reason, confirms, then loads only the missing rows', async () => {
    status.mockResolvedValue(empty)
    load.mockResolvedValue({ added: 75, loaded: ['cts_type', 'inv_adjreason'], status: complete })
    show()
    expect(await screen.findByText(/75 rows are missing in 2 lists/)).toBeInTheDocument()
    const table = screen.getByRole('table', { name: 'Standard lists' })
    expect(within(table).getByText('72 rows to add')).toBeInTheDocument()
    expect(within(table).getByText(/br_code = 77/)).toBeInTheDocument()
    expect(within(table).queryByText('Genders')).toBeNull()

    const button = screen.getByRole('button', { name: 'Load 75 missing rows' })
    expect(button).toBeDisabled()
    await userEvent.type(screen.getByLabelText('Reason'), 'setting up the new database')
    await userEvent.click(button)
    status.mockResolvedValue(complete) // the screen asks again after loading
    await userEvent.click(await screen.findByRole('button', { name: 'Load them' }))
    expect(load).toHaveBeenCalledWith('setting up the new database')
    expect(await screen.findByText('Every standard list is complete on this database.')).toBeInTheDocument()
  })

  it('says why nothing can be loaded when the database user lacks the right', async () => {
    status.mockResolvedValue({ ...empty, canLoad: false, cannotLoad: 'The application\'s database user may not add to the standard lists yet, so nothing can be loaded.' })
    show()
    expect(await screen.findByText(/may not add to the standard lists yet/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Load 75 missing rows' })).toBeDisabled()
  })

  it('words each list\'s standing', () => {
    expect(standing(list('a', 'A', 'G')).text).toBe('Complete')
    expect(standing(list('a', 'A', 'G', { missing: 1 })).text).toBe('1 row to add')
    expect(standing(list('a', 'A', 'G', { problem: 'laid out differently' })).color).toBe('error')
  })
})
