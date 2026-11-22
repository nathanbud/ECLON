import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { SystemError } from './api'
import SystemErrorsPage from './SystemErrorsPage'
import { AppThemeProvider } from './theme'

const entry = (reference: string, more: Partial<SystemError> = {}): SystemError => ({
  at: '2026-11-21T09:15:02', reference, user: 'nobudikey', method: 'PATCH', path: '/api/admin/users/ana/profile', status: 400, code: 'VALUE_TOO_LONG',
  userMessage: 'This was not saved because one of the entries is longer than the system allows.', problem: 'A value is longer than the database can keep (40 characters).',
  fix: 'Shorten what you typed and try again. If it is not something you typed, the database column needs to be wider.',
  exception: 'org.postgresql.util.PSQLException', detail: 'ERROR: value too long for type character varying(40)', trace: 'org.springframework.dao.DataIntegrityViolationException\n\tat ph.coop...', ...more,
})
const errors = vi.fn<() => Promise<SystemError[]>>()
vi.mock('./api', async (orig) => ({ ...(await orig<typeof import('./api')>()), getSystemErrors: () => errors() }))

function show() {
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AppThemeProvider><SystemErrorsPage /></AppThemeProvider></QueryClientProvider>)
}

// Each test sets its own answer (no mockReset before each; see SchemaStatusNotice.test.tsx).
describe('the error log', () => {
  it('lists failures with their technical cause, finds one by the reference a person quotes, and opens its details', async () => {
    errors.mockResolvedValue([entry('E-261121-AB3K'), entry('E-261121-ZZ99', { code: 'MISSING_TABLE', user: 'superadmin', problem: 'The database has no table this needs (ecis_sl_control).' })])
    show()
    const table = await screen.findByRole('table', { name: 'Error log' })
    expect(await within(table).findByText('A value is longer than the database can keep (40 characters).')).toBeInTheDocument()
    expect(within(table).getByText('Missing table')).toBeInTheDocument()

    await userEvent.type(screen.getByLabelText(/Search/), 'E-261121-ZZ99')
    expect(within(table).queryByText('E-261121-AB3K')).toBeNull()
    expect(screen.getByText('1 of 2 shown')).toBeInTheDocument()

    await userEvent.click(within(table).getByRole('button', { name: 'Show the details of E-261121-ZZ99' }))
    expect(await within(table).findByText('This was not saved because one of the entries is longer than the system allows.')).toBeInTheDocument()
    expect(within(table).getByText(/PATCH \/api\/admin\/users\/ana\/profile/)).toBeInTheDocument()
    expect(within(table).getByText(/DataIntegrityViolationException/)).toBeInTheDocument()
  })

  it('says so when nothing has gone wrong', async () => {
    errors.mockResolvedValue([])
    show()
    expect(await screen.findByText('No unexpected errors have been recorded.')).toBeInTheDocument()
  })
})
