import { errorFrom, getJson, http, send } from './http'

/** Opening balances: a new cooperative's balances on the day it starts on this system (Accounting > Opening balances, OpeningBalanceController). */
export type OpeningStatus = 'DRAFT' | 'FOR_REVIEW' | 'APPROVED' | 'POSTED' | 'CANCELLED'
/** GL is a general ledger account; SAVINGS and SHARE are a member's savings or share capital account, posted to its type's control account. */
export type OpeningKind = 'GL' | 'SAVINGS' | 'SHARE'

export interface OpeningSummary {
  id: number; branchCode: number; reference: string; openingDate: string; description: string; status: OpeningStatus; createdBy: string; approvedBy: string | null
  postedBy: string | null; lines: number; debits: number; credits: number; documentNo: string | null
}
export interface OpeningLine {
  lineNo: number; kind: OpeningKind; clientId: number | null; memberName: string | null; typeCode: number | null; typeName: string | null; refNo: number | null
  accountNo: string | null; accountCode: string | null; accountTitle: string | null; debit: number; credit: number; note: string | null
}
export interface OpeningTrialLine { account: string; title: string; debit: number; credit: number; memberAccounts: number; control: boolean }
export interface OpeningDetail { summary: OpeningSummary; lines: OpeningLine[]; trialBalance: OpeningTrialLine[]; problems: string[]; cancelReason: string | null }
export interface OpeningLineIn {
  kind: OpeningKind; clientId: number | null; typeCode: number | null; refNo: number | null; accountCode: string | null; debit: number | null; credit: number | null; note: string | null
}
export interface OpeningBatchIn { branchCode: number; openingDate: string; description: string; lines: OpeningLineIn[] }
export interface OpeningImport { lines: OpeningLineIn[]; problems: string[] }

const BASE = '/api/opening-balances'

export const listOpeningBatches = (branchCode: number) => getJson<OpeningSummary[]>(`${BASE}?branchCode=${branchCode}`)
export const getOpeningBatch = (id: number) => getJson<OpeningDetail>(`${BASE}/${id}`)
export const createOpeningBatch = (body: OpeningBatchIn) => send<OpeningSummary>('POST', BASE, body)
export const updateOpeningBatch = (id: number, body: OpeningBatchIn) => send<OpeningSummary>('PUT', `${BASE}/${id}`, body)
export const openingStep = (id: number, step: 'submit' | 'approve' | 'post') => send<OpeningSummary>('POST', `${BASE}/${id}/${step}`, {})
export const returnOpeningBatch = (id: number, reason: string) => send<OpeningSummary>('POST', `${BASE}/${id}/return`, { reason })
export const cancelOpeningBatch = (id: number, reason: string) => send<OpeningSummary>('POST', `${BASE}/${id}/cancel`, { reason })

/** Reads the lines of a CSV or Excel file; nothing is saved until the draft is. */
export async function importOpeningFile(file: File): Promise<OpeningImport> {
  const body = new FormData()
  body.append('file', file, file.name)
  const res = await http(`${BASE}/import`, { method: 'POST', body })
  if (!res.ok) throw new Error(await errorFrom(res))
  return res.json() as Promise<OpeningImport>
}

/** The columns a file of opening balances has, with three example rows, as a CSV to fill in. */
export const OPENING_TEMPLATE = [
  'Kind,Member,Type,Account no,Ledger account,Debit,Credit,Note',
  'GL,,,,10101,25000.00,,Cash on hand per count',
  'Savings,1,1,,,,15000.00,',
  'Share capital,1,1,,,,10000.00,',
].join('\r\n') + '\r\n'

export function downloadOpeningTemplate() {
  const url = URL.createObjectURL(new Blob([OPENING_TEMPLATE], { type: 'text/csv' }))
  const a = document.createElement('a')
  a.href = url
  a.download = 'opening-balances.csv'
  a.click()
  URL.revokeObjectURL(url)
}
