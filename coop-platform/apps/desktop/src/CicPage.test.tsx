import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import CicPage from './CicPage'
import type { CicBatch } from './api/cic'

const batch = (status: CicBatch['status'], over: Partial<CicBatch> = {}): CicBatch => ({
  id: 7, ref: 'CIC-2026-0007', submission_type: 0, cut_off_date: '2024-12-31', provider_code: 'CO008840', version: '1.0', remarks: null, status, created_by: 'acct', created_at: '2026-11-01T10:00:00',
  subjects: 3, installment_contracts: 4, other_contracts: 1, errors: 0, warnings: 2, excluded: 0, approved_by: null, approved_at: null, submitted_by: null, submitted_at: null,
  submission_reference: null, corrects_batch_id: null, header: { recordType: 'HD', providerCode: 'CO008840', fileReferenceDate: '31122024', version: '1.0', submissionType: '0', providerComments: '' },
  logs: { MEMBERS: ['3 borrowers read.'], LOANS: [], FILES: [] }, mappingVersion: 'CSDF-1', mappingConfirmed: false, unverified: ['a', 'b'], ...over,
})

const api = vi.hoisted(() => ({
  listCicBatches: vi.fn(), getCicBatch: vi.fn(), createCicBatch: vi.fn(), generateCic: vi.fn(), validateCic: vi.fn(), approveCic: vi.fn(), submitCic: vi.fn(), resultCic: vi.fn(),
  reconcileCic: vi.fn(), correctionCic: vi.fn(), listCicRecords: vi.fn(), listCicFindings: vi.fn(), downloadCicFile: vi.fn(),
}))
vi.mock('./api/cic', async (orig) => ({ ...(await orig<typeof import('./api/cic')>()), ...api }))

function show(b: CicBatch | null) {
  api.listCicBatches.mockResolvedValue(b ? [b] : [])
  api.getCicBatch.mockResolvedValue(b)
  api.listCicRecords.mockResolvedValue({ total: 0, items: [] })
  api.listCicFindings.mockResolvedValue([{ severity: 'WARNING', field: 'Mobile number', message: 'There is no mobile number.', record_id: 1, record_type: 'ID', subject_key: '01000100523', contract_no: null }])
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><CicPage /></QueryClientProvider>)
}

describe('CicPage', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows an empty list and creates a batch only with a provider code', async () => {
    show(null)
    expect(await screen.findByText(/No batches yet/)).toBeInTheDocument()
    const create = screen.getByRole('button', { name: 'Create batch' })
    expect(create).toBeDisabled()
    await userEvent.type(screen.getByLabelText(/Provider code/), 'CO008840')
    expect(create).toBeEnabled()
    api.createCicBatch.mockResolvedValue(batch('DRAFT'))
    await userEvent.click(create)
    expect(api.createCicBatch).toHaveBeenCalledWith(expect.objectContaining({ providerCode: 'CO008840', submissionType: 'Standard' }))
  })

  it('shows a validated batch with its counts, findings and the warning that the mapping is unconfirmed', async () => {
    show(batch('VALIDATED'))
    expect(await screen.findByText(/3 borrowers · 4 installment loans · 1 lump-sum loans/)).toBeInTheDocument()
    expect(await screen.findByText('There is no mobile number.')).toBeInTheDocument()
    expect(screen.getByText(/has not been checked against a file the CIC accepted/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Download preview' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Approve' })).toBeEnabled()
  })

  it('needs the box ticked before a batch with errors can be approved', async () => {
    show(batch('VALIDATED', { errors: 5 }))
    const approve = await screen.findByRole('button', { name: 'Approve' })
    expect(approve).toBeDisabled()
    await userEvent.click(screen.getByLabelText('Leave out records with errors'))
    expect(approve).toBeEnabled()
    api.approveCic.mockResolvedValue(batch('APPROVED'))
    await userEvent.click(approve)
    expect(api.approveCic).toHaveBeenCalledWith(7, true, '')
  })

  it('asks for the CIC’s reference before recording the submission and for both counts before the result', async () => {
    show(batch('APPROVED'))
    const record = await screen.findByRole('button', { name: 'Record as submitted' })
    expect(record).toBeDisabled()
    await userEvent.type(screen.getByLabelText('CIC reference or receipt'), 'R-1')
    expect(record).toBeEnabled()
  })
})
