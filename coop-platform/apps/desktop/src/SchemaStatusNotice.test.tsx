import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { SchemaApplyResult, SchemaStatus } from './api'
import { SchemaStatusNotice } from './SchemaStatusNotice'
import { AppThemeProvider } from './theme'

const status = vi.fn<() => Promise<SchemaStatus>>()
const apply = vi.fn<(u: string, p: string, r: string) => Promise<SchemaApplyResult>>()
vi.mock('./api', async (orig) => ({ ...(await orig<typeof import('./api')>()), getSchemaStatus: () => status(), applySchemaChanges: (u: string, p: string, r: string) => apply(u, p, r) }))

const base: SchemaStatus = { state: 'UP_TO_DATE', database: 'coop', message: 'The database coop has every database change this version of the application needs (up to V033).', missing: [], newer: [], changed: [], lastExpected: 'V033', lastRecorded: 'V033' }

function show(showOk = false, canApply = false) {
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AppThemeProvider><SchemaStatusNotice showOk={showOk} canApply={canApply} /></AppThemeProvider></QueryClientProvider>)
}

// Each test sets its own answer. No mockReset() before each: with a rejecting answer, this Vitest then reports the rejection as a failure of the test.
describe('the database changes notice', () => {
  it('says nothing on Settings while the database is up to date', async () => {
    status.mockResolvedValue(base)
    show()
    await new Promise((r) => setTimeout(r, 20))
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.queryByText(/every database change/)).toBeNull()
  })

  it('confirms it on the Database connection screen', async () => {
    status.mockResolvedValue(base)
    show(true)
    expect(await screen.findByText(/has every database change/)).toBeInTheDocument()
  })

  it('names the missing changes when the database is behind', async () => {
    status.mockResolvedValue({ ...base, state: 'BEHIND', database: 'coop_bgempc', message: 'The database coop_bgempc is missing 2 database changes this version of the application needs (V032, V033).', missing: ['V032 V032__create_users.sql', 'V033 V033__audit_action_length.sql'] })
    show()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('This database is missing database changes')
    expect(alert).toHaveTextContent('V032__create_users.sql')
    expect(alert).toHaveTextContent('V033__audit_action_length.sql')
  })

  it('says nothing when it cannot be asked', async () => {
    status.mockImplementation(() => Promise.reject(new Error('You do not have permission to do this.')))
    show(true)
    await new Promise((r) => setTimeout(r, 20))
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('lets a super administrator apply the missing changes with a database login that is not kept', async () => {
    const behind: SchemaStatus = { ...base, state: 'BEHIND', database: 'coop_bgempc', message: 'The database coop_bgempc is missing 1 database change.', missing: ['V035 V035__offices.sql'] }
    status.mockResolvedValue(behind)
    apply.mockImplementation(async () => {
      status.mockResolvedValue({ ...base, database: 'coop_bgempc' }) // the database is up to date from now on
      return { applied: ['V035__offices.sql'], failedFile: null, message: 'Applied V035__offices.sql to coop_bgempc.', status: { ...base, database: 'coop_bgempc' } }
    })
    show(false, true)
    await userEvent.click(await screen.findByRole('button', { name: 'Apply them now' }))
    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveTextContent('V035__offices.sql')
    expect(within(dialog).getByLabelText(/Database administrator/)).toHaveValue('coop_admin')
    const go = within(dialog).getByRole('button', { name: 'Apply changes' })
    expect(go).toBeDisabled()
    await userEvent.type(within(dialog).getByLabelText(/^Password/), 's3cret')
    await userEvent.type(within(dialog).getByLabelText(/Reason/), 'Offices screen')
    await userEvent.click(go)
    expect(apply).toHaveBeenCalledWith('coop_admin', 's3cret', 'Offices screen')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(screen.queryByRole('alert')).toBeNull() // up to date now, so Settings says nothing
  })

  it('offers no apply button to other administrators', async () => {
    status.mockResolvedValue({ ...base, state: 'BEHIND', message: 'Missing.', missing: ['V035 V035__offices.sql'] })
    show(false, false)
    await screen.findByRole('alert')
    expect(screen.queryByRole('button', { name: 'Apply them now' })).toBeNull()
  })
})
