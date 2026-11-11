import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Kyc, KycInput, Me } from '../api'
import { AppThemeProvider } from '../theme'
import { KycTab } from './Kyc'

let canEdit = true
vi.mock('../session', () => ({ useMe: () => ({ username: 'maker', name: 'Maker', roles: [], canEdit, canApprove: false, admin: false, mustChangePassword: false }) as Me }))

const none: Kyc = {
  idType: null, idNumber: null, idIssuer: null, idExpiry: null, sourceOfFunds: null, pep: false, pepNote: null, risk: null, nextReview: null, remarks: null,
  hasScan: false, updatedBy: null, updatedAt: null, status: 'NONE', idTypes: ['Passport', "Driver's License", 'UMID'],
}
let record: Kyc = none
const saved = vi.fn(async (_b: number, _c: number, body: KycInput): Promise<Kyc> => ({ ...none, ...body, status: 'COMPLETE', updatedBy: 'maker', updatedAt: '2026-11-08T10:00:00' }))

vi.mock('../api', async (orig) => ({
  ...(await orig<typeof import('../api')>()),
  getKyc: vi.fn(async () => record),
  getKycScan: vi.fn(async () => null),
  saveKyc: (b: number, c: number, body: KycInput) => saved(b, c, body),
  uploadKycScan: vi.fn(async () => undefined),
}))

function renderTab(canImages = true) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider><KycTab id={{ branchCode: 1, clientId: 100 }} canImages={canImages} /></AppThemeProvider></QueryClientProvider>)
}

describe('KYC section', () => {
  beforeEach(() => { canEdit = true; record = none; saved.mockClear() })

  it('says nothing is recorded yet and keeps Save off until something is typed', async () => {
    renderTab()
    expect(await screen.findByText('Not started')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save KYC' })).toBeDisabled()
  })

  it('saves what is typed and shows the new status', async () => {
    renderTab()
    const user = userEvent.setup()
    await user.type(await screen.findByLabelText('ID number'), 'P1234567A')
    await user.type(screen.getByLabelText(/Where the member's money comes from/), 'Salary')
    await user.click(screen.getByRole('button', { name: 'Save KYC' }))
    await waitFor(() => expect(saved).toHaveBeenCalled())
    expect(saved.mock.calls[0][2]).toMatchObject({ idNumber: 'P1234567A', sourceOfFunds: 'Salary', pep: false })
    expect(await screen.findByText('Complete')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('recorded in the audit log')
  })

  it('asks for the public position when the member is politically exposed', async () => {
    renderTab()
    const user = userEvent.setup()
    expect(screen.queryByLabelText('Position held')).not.toBeInTheDocument()
    await user.click(await screen.findByRole('switch', { name: /Politically exposed/ }))
    expect(screen.getByLabelText('Position held')).toBeInTheDocument()
  })

  it('shows an expired ID plainly', async () => {
    record = { ...none, idType: 'Passport', idNumber: 'P1234567A', idExpiry: '2020-01-01', sourceOfFunds: 'Salary', risk: 'LOW', nextReview: '2030-01-01', status: 'EXPIRED' }
    renderTab()
    expect(await screen.findByText('ID expired')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('has expired')
  })

  it('is read only without permission to edit members', async () => {
    canEdit = false
    renderTab()
    expect(await screen.findByLabelText('ID number')).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Save KYC' })).not.toBeInTheDocument()
  })

  it('offers the ID scan only to someone who may handle images', async () => {
    renderTab(false)
    await screen.findByText('Not started')
    expect(screen.queryByRole('button', { name: /Add a scan/ })).not.toBeInTheDocument()
  })
})
