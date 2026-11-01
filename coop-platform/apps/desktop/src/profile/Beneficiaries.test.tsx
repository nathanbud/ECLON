import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Beneficiary, Lookups, Me, MemberRequest } from '../api'
import { AppThemeProvider } from '../theme'
import { BeneficiariesTab } from './Beneficiaries'

let canEdit = true
vi.mock('../session', () => ({ useMe: () => ({ username: 'maker', name: 'Maker', roles: [], canEdit, canApprove: false, admin: false, mustChangePassword: false }) as Me }))

const ana: Beneficiary = { id: 7, name: 'Ana Reyes', relationCode: 36, relation: 'Child', dateOfBirth: '2010-05-04', occupation: 'Student', typeCode: 1, type: 'Principal', added: '2026-04-24' }
const pending: MemberRequest = {
  id: 55, branchCode: 1, kind: 'BENEF', clientId: 100, summary: 'Remove beneficiary Ana Reyes of 100 (Reyes, Juan): Entered by mistake', status: 'PENDING', requestedBy: 'maker',
  requestedAt: '2026-11-01 09:00:00', decidedBy: null, decidedAt: null, decisionNote: null, payload: { action: 'REMOVE', benefId: '7' },
}
const sent = vi.fn(async (body: unknown) => ({ ...pending, id: 56, payload: body as Record<string, string> }))
let rows: Beneficiary[] = [ana]
let waiting: MemberRequest[] = []

vi.mock('../api', async (orig) => ({
  ...(await orig<typeof import('../api')>()),
  getBeneficiaries: vi.fn(async () => rows),
  listMemberRequests: vi.fn(async () => waiting),
  requestBeneficiary: (body: unknown) => sent(body),
  withdrawMemberRequest: vi.fn(async () => pending),
}))

const lk: Lookups = { relation: { 35: 'Spouse', 36: 'Child' } }
const id = { branchCode: 1, clientId: 100 }

function renderTab() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider><BeneficiariesTab id={id} lk={lk} /></AppThemeProvider></QueryClientProvider>)
}

describe('Beneficiaries tab', () => {
  beforeEach(() => { canEdit = true; rows = [ana]; waiting = []; sent.mockClear() })

  it('lists the beneficiaries with their relationship and type, dates month first', async () => {
    renderTab()
    const row = (await screen.findByText('Ana Reyes')).closest('tr')!
    expect(within(row).getByText('Child')).toBeInTheDocument()
    expect(within(row).getByText('05/04/2010')).toBeInTheDocument()
    expect(within(row).getByText('Principal')).toBeInTheDocument()
  })

  it('offers no way to change anything without edit permission', async () => {
    canEdit = false
    renderTab()
    await screen.findByText('Ana Reyes')
    expect(screen.queryByRole('button', { name: /add beneficiary/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /change|remove/i })).not.toBeInTheDocument()
  })

  it('sends an added beneficiary for approval and says who decides', async () => {
    renderTab()
    const user = userEvent.setup()
    await screen.findByText('Ana Reyes')
    await user.click(screen.getByRole('button', { name: /add beneficiary/i }))
    const dialog = await screen.findByRole('dialog')
    const send = within(dialog).getByRole('button', { name: /send for approval/i })
    expect(send).toBeDisabled()
    await user.type(within(dialog).getByRole('textbox', { name: /^name/i }), 'Juan Reyes')
    await user.click(within(dialog).getByRole('combobox', { name: /relationship/i }))
    await user.click(await screen.findByRole('option', { name: 'Spouse' }))
    await user.click(send)
    await waitFor(() => expect(sent).toHaveBeenCalledTimes(1))
    expect(sent).toHaveBeenCalledWith(expect.objectContaining({ action: 'ADD', clientId: 100, name: 'Juan Reyes', relation: 35, type: 1, benefId: null }))
    expect(await screen.findByText(/sent for approval as request #56/i)).toBeInTheDocument()
  })

  it('asks for a reason to remove and sends it for approval', async () => {
    renderTab()
    const user = userEvent.setup()
    await screen.findByText('Ana Reyes')
    await user.click(screen.getByRole('button', { name: 'Remove' }))
    const dialog = await screen.findByRole('dialog')
    const confirm = within(dialog).getByRole('button', { name: /confirm/i })
    expect(confirm).toBeDisabled()
    await user.type(within(dialog).getByRole('textbox', { name: /reason/i }), 'Entered by mistake')
    await user.click(confirm)
    await waitFor(() => expect(sent).toHaveBeenCalledWith(expect.objectContaining({ action: 'REMOVE', benefId: 7, note: 'Entered by mistake' })))
    expect(await screen.findByText(/sent for approval as request #56/i)).toBeInTheDocument()
  })

  it('shows what is waiting for approval and lets the sender withdraw it', async () => {
    waiting = [pending]
    renderTab()
    expect(await screen.findByText(/waiting for approval/i)).toBeInTheDocument()
    expect(screen.getByText(/entered by mistake/i)).toBeInTheDocument()
    expect(screen.getByText('Change waiting')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Withdraw' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Remove' })).not.toBeInTheDocument()
  })
})
