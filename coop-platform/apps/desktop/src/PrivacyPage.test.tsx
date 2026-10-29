import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import PrivacyPage from './PrivacyPage'
import { AppThemeProvider } from './theme'

const step = vi.fn()

vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  listPrivacyRequests: async () => [
    { id: 1, ref: 'DSR-2026-0001', request_type: 'ACCESS', status: 'OPEN', identity_verified: 0, due_on: '2026-01-01', requester_name: 'Ana Cruz', branch_code: 1, client_id: 100, summary: 'Wants a copy', outcome: null },
    { id: 2, ref: 'DSR-2026-0002', request_type: 'ACCESS', status: 'IN_PROGRESS', identity_verified: 1, due_on: '2999-01-01', requester_name: 'Ben Ong', branch_code: 1, client_id: 101, summary: 'Wants a copy', outcome: null },
  ],
  stepPrivacyRequest: (...a: unknown[]) => step(...a),
  getPrivacyReview: async () => [{ what: 'Retention rules not yet confirmed', count: 7, note: 'Confirm each rule.' }],
  listExports: async () => [],
  listRegister: async () => [],
}))

function show() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider><PrivacyPage /></AppThemeProvider></QueryClientProvider>)
}

describe('data privacy', () => {
  beforeEach(() => step.mockReset().mockResolvedValue({}))

  it('flags an overdue request, and offers the member data only after the identity is checked', async () => {
    show()
    expect(await screen.findByText('DSR-2026-0001')).toBeInTheDocument()
    expect(screen.getByText('Overdue')).toBeInTheDocument()
    expect(screen.getAllByText('Download member data')).toHaveLength(1)
    expect(screen.getAllByText('Check identity')).toHaveLength(1)
  })

  it('asks how the identity was checked before recording it', async () => {
    show()
    await userEvent.click(await screen.findByText('Check identity'))
    await userEvent.type(await screen.findByLabelText('How was the identity checked?'), 'ID shown at the counter')
    await userEvent.click(screen.getByText('Confirm'))
    expect(step).toHaveBeenCalledWith(1, 'verify-identity', 'ID shown at the counter')
  })

  it('lists what needs a decision, with the draft warning', async () => {
    show()
    await userEvent.click(screen.getByRole('tab', { name: 'Review and exports' }))
    expect(await screen.findByText('Retention rules not yet confirmed')).toBeInTheDocument()
    expect(screen.getByText(/not legal advice/)).toBeInTheDocument()
  })
})
