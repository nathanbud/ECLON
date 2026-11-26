import { errorFrom, getJson, http, send } from './http'

/** Opening balances: a new cooperative's balances on the day it starts on this system (Accounting > Opening balances, OpeningBalanceController). */
export type OpeningStatus = 'DRAFT' | 'FOR_REVIEW' | 'APPROVED' | 'POSTED' | 'CANCELLED'
/**
 * GL is a general ledger account; SAVINGS and SHARE are a member's savings or share capital account, posted to its type's control account; LOAN and TD are a
 * member's loan still being paid and time deposit still running, opened with their terms when the batch is posted.
 */
export type OpeningKind = 'GL' | 'SAVINGS' | 'SHARE' | 'LOAN' | 'TD'
/** Loan statuses a loan can be opened with (cts_type). */
export const LOAN_STATUSES: Record<number, string> = { 11: 'Current', 12: 'Past due', 13: 'In litigation', 14: 'Restructured' }

export interface OpeningSummary {
  id: number; branchCode: number; reference: string; openingDate: string; description: string; status: OpeningStatus; createdBy: string; approvedBy: string | null
  postedBy: string | null; lines: number; debits: number; credits: number; documentNo: string | null
}
export interface OpeningLine {
  lineNo: number; kind: OpeningKind; clientId: number | null; memberName: string | null; typeCode: number | null; typeName: string | null; refNo: number | null
  accountNo: string | null; accountCode: string | null; accountTitle: string | null; debit: number; credit: number; note: string | null
  /** A loan or time deposit line's terms as saved, its maturity (time deposit) and the account its accrued interest goes to. */
  line: OpeningLineIn | null; maturity: string | null; accrualAccount: string | null
}
export interface OpeningTrialLine { account: string; title: string; debit: number; credit: number; memberAccounts: number; control: boolean }
export interface OpeningDetail { summary: OpeningSummary; lines: OpeningLine[]; trialBalance: OpeningTrialLine[]; problems: string[]; cancelReason: string | null }
export interface OpeningLineIn {
  kind: OpeningKind; clientId: number | null; typeCode: number | null; refNo: number | null; accountCode: string | null; debit: number | null; credit: number | null; note: string | null
  /** Loan: status, yearly rate, months still to run, next due date (startDate), amount and date granted. Time deposit: rate, term in days, placement date (startDate), accrued interest. */
  status?: number | null; rate?: number | null; terms?: number | null; startDate?: string | null; originalAmount?: number | null; grantedOn?: string | null; accrued?: number | null
}
/** A loan or time deposit type a line can open, with the rate (and for a loan the term in months and payment mode) the screen fills in. */
export interface OpeningType { kind: 'LOAN' | 'TD'; typeCode: number; name: string | null; rate: number | null; termMonths: number | null; paymentMode: string | null }
export interface OpeningBatchIn { branchCode: number; openingDate: string; description: string; lines: OpeningLineIn[] }
export interface OpeningImport { lines: OpeningLineIn[]; problems: string[] }

const BASE = '/api/opening-balances'

export const listOpeningBatches = (branchCode: number) => getJson<OpeningSummary[]>(`${BASE}?branchCode=${branchCode}`)
export const getOpeningBatch = (id: number) => getJson<OpeningDetail>(`${BASE}/${id}`)
export const getOpeningTypes = (branchCode: number) => getJson<OpeningType[]>(`${BASE}/types?branchCode=${branchCode}`)
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

/** The columns a file of opening balances has, with an example row of each kind, as a CSV to fill in. Loan and time deposit rows also give their terms. */
export const OPENING_TEMPLATE = [
  'Kind,Member,Type,Account no,Ledger account,Debit,Credit,Note,Status,Rate,Term,Next due,Amount granted,Granted on,Placed on,Accrued interest,Certificate no',
  'GL,,,,10101,25000.00,,Cash on hand per count,,,,,,,,,',
  'Savings,1,1,,,,15000.00,,,,,,,,,,',
  'Share capital,1,1,,,,10000.00,,,,,,,,,,',
  'Loan,1,10,,,30000.00,,Balance per loan ledger,Current,12,12,2027-01-15,50000.00,2026-01-15,,,',
  'Time deposit,1,2,,,,100000.00,,,5,360,,,,2026-10-01,1250.00,0457',
].join('\r\n') + '\r\n'

export function downloadOpeningTemplate() {
  const url = URL.createObjectURL(new Blob([OPENING_TEMPLATE], { type: 'text/csv' }))
  const a = document.createElement('a')
  a.href = url
  a.download = 'opening-balances.csv'
  a.click()
  URL.revokeObjectURL(url)
}
