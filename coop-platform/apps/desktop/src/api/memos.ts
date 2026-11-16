import { getJson, send } from './http'

export type MemoStatus = 'DRAFT' | 'FOR_REVIEW' | 'APPROVED' | 'POSTED' | 'CANCELLED'
/** SAVINGS and SHARE are on a member's account; LEDGER is on a general ledger account. */
export type MemoKind = 'SAVINGS' | 'SHARE' | 'LEDGER'

export interface MemoSummary {
  id: number; branchCode: number; reference: string; memoDate: string; description: string; status: MemoStatus; createdBy: string; approvedBy: string | null; postedBy: string | null
  total: number; reverses: number | null; documentNo: string | null
}
export interface MemoLine {
  lineNo: number; kind: MemoKind; clientId: number | null; clientName: string | null; typeCode: number | null; type: string | null; refNo: number | null; refText: string | null
  accountCode: string | null; accountTitle: string | null; debit: number; credit: number; note: string | null
}
export interface MemoDetail { summary: MemoSummary; lines: MemoLine[]; cancelReason: string | null }
export interface MemoLineIn { kind: MemoKind; clientId: number | null; typeCode: number | null; refNo: number | null; accountCode: string | null; debit: number; credit: number; note: string | null }
export interface MemoAccount { kind: 'SAVINGS' | 'SHARE'; typeCode: number; type: string | null; refNo: number; refText: string | null; status: number; balance: number }
export interface MemoAccounts { clientId: number; clientName: string; clientStatus: number; accounts: MemoAccount[] }

export const listMemos = (branchCode: number, status?: MemoStatus) => getJson<MemoSummary[]>(`/api/memos?branchCode=${branchCode}${status ? `&status=${status}` : ''}`)
export const getMemo = (id: number) => getJson<MemoDetail>(`/api/memos/${id}`)
export const getMemoAccounts = (branchCode: number, clientId: number) => getJson<MemoAccounts>(`/api/memos/accounts?branchCode=${branchCode}&clientId=${clientId}`)
export const createMemo = (body: { branchCode: number; memoDate: string; description: string; lines: MemoLineIn[] }) => send<MemoSummary>('POST', '/api/memos', body)
export const memoStep = (id: number, step: 'submit' | 'approve' | 'post') => send<MemoSummary>('POST', `/api/memos/${id}/${step}`, {})
export const cancelMemo = (id: number, reason: string) => send<MemoSummary>('POST', `/api/memos/${id}/cancel`, { reason })
export const reverseMemo = (id: number, reason: string) => send<MemoSummary>('POST', `/api/memos/${id}/reverse`, { reason })
