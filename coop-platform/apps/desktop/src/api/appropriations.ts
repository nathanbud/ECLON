import { getJson, send } from './http'

/** Appropriation of a closed year's net surplus to the statutory funds and to members (RA 9520 Article 86; AppropriationController). */
export type AppropriationStatus = 'DRAFT' | 'FOR_REVIEW' | 'APPROVED' | 'POSTED' | 'CANCELLED'
/** RESERVE ... OTHER are statutory or other funds, a percent of the net surplus; ISC and PR are the amounts allocated to interest on share capital and patronage refund. */
export type AppropriationKind = 'RESERVE' | 'EDUCATION' | 'COMMUNITY' | 'OPTIONAL' | 'OTHER' | 'ISC' | 'PR'
export const FUND_KINDS: AppropriationKind[] = ['RESERVE', 'EDUCATION', 'COMMUNITY', 'OPTIONAL', 'OTHER']

export interface AppropriationLineIn { kind: AppropriationKind; name: string; percent: number | null; amount: number | null; account: string | null }
export interface AppropriationIn { branchCode: number; year: number; entryDate: string; resolution: string; lines: AppropriationLineIn[] }
export interface AppropriationSummary {
  id: number; branchCode: number; year: number; reference: string; entryDate: string; resolution: string; netSurplus: number; status: AppropriationStatus
  createdBy: string; approvedBy: string | null; postedBy: string | null; allocated: number; documentNo: string | null
}
export interface AppropriationLine { lineNo: number; kind: AppropriationKind; name: string; percent: number | null; amount: number; account: string | null; accountTitle: string | null }
export interface AppropriationTotals { netSurplus: number; funds: number; isc: number; pr: number; allocated: number; undivided: number }
export interface AppropriationDetail {
  summary: AppropriationSummary; lines: AppropriationLine[]; totals: AppropriationTotals; percents: Record<string, number>; reserveMinimum: number
  entry: { account: string; title: string; debit: number; credit: number }[]; problems: string[]; cancelReason: string | null
}
export interface AppropriationSettings { registeredOn: string | null; sourceAccount: string; sourceTitle: string | null; payoutAccount: string; payoutTitle: string | null; mayEdit: boolean }
export interface AppropriationStart {
  year: number; netSurplus: number | null; reserveMinimum: number; problems: string[]; lines: AppropriationLineIn[]; settings: AppropriationSettings; existing: AppropriationSummary | null
}
export interface PostedAppropriation {
  id: number; reference: string; year: number; netSurplus: number; funds: { name: string; percent: number; amount: number }[]; isc: number; pr: number; resolution: string
}

const BASE = '/api/appropriations'

export const listAppropriations = (branchCode: number) => getJson<AppropriationSummary[]>(`${BASE}?branchCode=${branchCode}`)
export const getAppropriation = (id: number) => getJson<AppropriationDetail>(`${BASE}/${id}`)
export const startAppropriation = (branchCode: number, year: number) => getJson<AppropriationStart>(`${BASE}/start?branchCode=${branchCode}&year=${year}`)
export const getPostedAppropriation = (branchCode: number, year: number) =>
  getJson<{ posted: PostedAppropriation | null }>(`${BASE}/posted?branchCode=${branchCode}&year=${year}`).then((r) => r.posted)
export const createAppropriation = (body: AppropriationIn) => send<AppropriationSummary>('POST', BASE, body)
export const updateAppropriation = (id: number, body: AppropriationIn) => send<AppropriationSummary>('PUT', `${BASE}/${id}`, body)
export const appropriationStep = (id: number, step: 'submit' | 'approve' | 'post') => send<AppropriationSummary>('POST', `${BASE}/${id}/${step}`, {})
export const returnAppropriation = (id: number, reason: string) => send<AppropriationSummary>('POST', `${BASE}/${id}/return`, { reason })
export const cancelAppropriation = (id: number, reason: string) => send<AppropriationSummary>('POST', `${BASE}/${id}/cancel`, { reason })
export const saveRegistrationDate = (branchCode: number, registeredOn: string | null, reason: string) =>
  send<AppropriationSettings>('PUT', `${BASE}/settings?branchCode=${branchCode}`, { registeredOn, reason })
