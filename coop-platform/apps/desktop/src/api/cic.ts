import { getJson, http, send } from './http'

export type CicStatus = 'DRAFT' | 'GENERATED' | 'VALIDATED' | 'APPROVED' | 'SUBMITTED' | 'RESULT' | 'RECONCILED'

export interface CicBatch {
  id: number; ref: string; submission_type: number; cut_off_date: string; provider_code: string; version: string; remarks: string | null; status: CicStatus
  created_by: string; created_at: string; subjects: number; installment_contracts: number; other_contracts: number; errors: number; warnings: number; excluded: number
  approved_by: string | null; approved_at: string | null; submitted_by: string | null; submitted_at: string | null; submission_reference: string | null; corrects_batch_id: number | null
  header: { recordType: string; providerCode: string; fileReferenceDate: string; version: string; submissionType: string; providerComments: string }
  logs: Record<'MEMBERS' | 'LOANS' | 'FILES', string[]>
  mappingVersion: string; mappingConfirmed: boolean; unverified: string[]
}
export interface CicRecord { id: number; record_type: 'ID' | 'CI' | 'CN'; seq: number; subject_key: string; contract_no: string | null; status: 'VALID' | 'WARNING' | 'ERROR'; excluded: number }
export interface CicFinding { severity: 'WARNING' | 'ERROR'; field: string; message: string; record_id: number | null; record_type: string | null; subject_key: string | null; contract_no: string | null }

export const SUBMISSION_TYPES = ['Standard', 'Correction', 'Full resubmission']

export const listCicBatches = () => getJson<CicBatch[]>('/api/cic/batches')
export const getCicBatch = (id: number) => getJson<CicBatch>(`/api/cic/batches/${id}`)
export const createCicBatch = (b: { submissionType: string; cutOffDate: string; providerCode: string; version: string; remarks: string }) => send<CicBatch>('POST', '/api/cic/batches', b)
export const generateCic = (id: number, sample: number) => send<CicBatch>('POST', `/api/cic/batches/${id}/generate?sample=${sample}`, {})
export const validateCic = (id: number) => send<CicBatch>('POST', `/api/cic/batches/${id}/validate`, {})
export const approveCic = (id: number, excludeErrors: boolean, note: string) => send<CicBatch>('POST', `/api/cic/batches/${id}/approve`, { excludeErrors, note })
export const submitCic = (id: number, reference: string) => send<CicBatch>('POST', `/api/cic/batches/${id}/submit`, { reference })
export const resultCic = (id: number, accepted: number, rejected: number, note: string) => send<CicBatch>('POST', `/api/cic/batches/${id}/response`, { accepted, rejected, note })
export const reconcileCic = (id: number) => send<CicBatch>('POST', `/api/cic/batches/${id}/reconcile`, {})
export const correctionCic = (id: number) => send<CicBatch>('POST', `/api/cic/batches/${id}/correction`, {})
export const listCicRecords = (id: number, type: string, status: string, page: number) =>
  getJson<{ total: number; items: CicRecord[] }>(`/api/cic/batches/${id}/records?type=${type}&status=${status}&page=${page}&size=25`)
export const listCicFindings = (id: number, record?: number) => getJson<CicFinding[]>(`/api/cic/batches/${id}/findings${record ? `?record=${record}` : ''}`)

/** Fetches the batch's file with the sign-in and hands it to the browser as a download. Resolves to the file name the server chose (PREVIEW_ before approval). */
export async function downloadCicFile(id: number): Promise<string> {
  const res = await http(`/api/cic/batches/${id}/file`)
  if (!res.ok) {
    let message = `Request failed (${res.status})`
    try { const j = await res.json(); if (j.message) message = j.message } catch { /* keep the status message */ }
    throw new Error(message)
  }
  const name = /filename="([^"]+)"/.exec(res.headers.get('Content-Disposition') ?? '')?.[1] ?? `cic-${id}.txt`
  const url = URL.createObjectURL(await res.blob())
  const a = document.createElement('a')
  a.href = url
  a.download = name
  a.click()
  URL.revokeObjectURL(url)
  return name
}
