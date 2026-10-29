import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Me } from './api'
import AuditLogPage from './AuditLogPage'
import { AppThemeProvider } from './theme'

vi.mock('./session', () => ({ useMe: () => ({ username: 'boss', name: 'Boss', roles: ['ROLE_ADMIN'], canEdit: true, canApprove: false, admin: true, mustChangePassword: false }) as Me }))

let integrity = { installed: true, seals: 3, lastSealedId: 4037, lastSealedAt: '2026-10-29 02:30:05.0', unsealedRows: 5, nightly: true }
let verification: object = { ok: true, sealsChecked: 3, rowsChecked: 120, unsealedRows: 5, problems: [] }
const seal = vi.fn()

vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  getAuditFacets: async () => ({ entities: [], actions: [], users: [] }),
  getAuditLog: async () => ({
    content: [{ id: 9, at: '2026-10-29T10:00:00', username: 'ana', action: 'member.update', entity: 'client', entityId: '1-100', previous: null, current: null, reason: 'Fix', clientIp: '203.0.113.9', clientInfo: 'CoopPlatform/0.1' },
              { id: 8, at: '2026-10-29T09:00:00', username: 'system', action: 'audit.sealed', entity: 'audit_seal', entityId: '3', previous: null, current: null, reason: null, clientIp: null, clientInfo: null }],
    totalElements: 2, number: 0, size: 50,
  }),
  getAuditIntegrity: async () => integrity,
  verifyAuditLog: async () => verification,
  sealAuditLog: (...a: unknown[]) => seal(...a),
}))

function show() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider><AuditLogPage /></AppThemeProvider></QueryClientProvider>)
}

describe('audit log integrity', () => {
  beforeEach(() => {
    integrity = { installed: true, seals: 3, lastSealedId: 4037, lastSealedAt: '2026-10-29 02:30:05.0', unsealedRows: 5, nightly: true }
    verification = { ok: true, sealsChecked: 3, rowsChecked: 120, unsealedRows: 5, problems: [] }
    seal.mockReset().mockResolvedValue({ ...integrity, unsealedRows: 0 })
  })

  it('shows where each entry came from, and System when the system did it', async () => {
    show()
    expect(await screen.findByText('203.0.113.9')).toBeInTheDocument()
    expect(screen.getByText('System')).toBeInTheDocument()
  })

  it('says how far the log is sealed and how much is not', async () => {
    show()
    expect(await screen.findByText(/Sealed through entry #4037/)).toBeInTheDocument()
    expect(screen.getByText(/5 newer entries are not sealed yet/)).toBeInTheDocument()
  })

  it('reports a clean check', async () => {
    show()
    await userEvent.click(await screen.findByRole('button', { name: 'Check the seals' }))
    expect(await screen.findByText(/All 3 seals match/)).toBeInTheDocument()
  })

  it('reports exactly what no longer matches', async () => {
    verification = { ok: false, sealsChecked: 3, rowsChecked: 118, unsealedRows: 5, problems: ['Seal #2: 2 audit row(s) between #10 and #40 have been removed.'] }
    show()
    await userEvent.click(await screen.findByRole('button', { name: 'Check the seals' }))
    expect(await screen.findByText(/does not match its seals/)).toBeInTheDocument()
    expect(screen.getByText(/have been removed/)).toBeInTheDocument()
  })

  it('seals on request', async () => {
    show()
    await userEvent.click(await screen.findByRole('button', { name: 'Seal now' }))
    expect(seal).toHaveBeenCalled()
  })
})
