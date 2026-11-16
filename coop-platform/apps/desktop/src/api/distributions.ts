import type { DividendReq, DividendVoucher } from './deposits'
import { errorFrom, getJson, http, send } from './http'

const BASE = '/api/front-office/dividends'

export type DistributionStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'ENCODED' | 'REJECTED' | 'SUPERSEDED'

/** A stored yearly distribution of interest on share capital and patronage refund. */
export interface Distribution {
  id: number; branch: number; year: number; cutoff: string; method: 'RATES' | 'ALLOCATION'; status: DistributionStatus
  netSurplus: number | null; available: number | null; iscAllocation: number | null; prAllocation: number | null; approvalRef: string | null
  dividendRate: number; patronageRate: number; mutualBenefit: number | null; hospitalization: number | null; overrides: number
  totalAverageShare: number; totalPatronage: number; members: number; totalIsc: number; totalPr: number; total: number; held: number; excluded: number; policy: string
  preparedBy: string; preparedAt: string; submittedBy: string | null; submittedAt: string | null; decidedBy: string | null; decidedAt: string | null; decisionNote: string | null
  supersededBy: number | null; encodedBy: string | null; encodedAt: string | null; voucherYear: number | null; voucherFirst: number | null; voucherLast: number | null
}
export interface DistributionDetail {
  batch: Distribution
  funds: { name: string; percent: number; amount: number }[]
  patronage: { event: number; name: string; amount: number; members: number }[]
  credited: Record<string, number>
  leftOut: { clientId: number; name: string | null; total: number; reason: string }[]
  controls: string[]
  rights: { maySubmit: boolean; mayApprove: boolean; mayReject: boolean; mayEncode: boolean; note: string }
}
export interface DistributionStatement {
  batchId: number; clientId: number; name: string | null; year: number; averageShareMonth: number; dividendRate: number; dividend: number; qualifiedPatronage: number
  patronageRate: number; refund: number; total: number; mutualBenefit: number; hospitalization: number; rest: number; status: DistributionStatus
}
export interface ReportSection { heading: string; columns: string[]; rows: string[][]; notes: string[] }
export interface DistributionReport { title: string; subtitle: string; sections: ReportSection[] }
export type ReportKind = 'net-surplus' | 'funds' | 'isc' | 'pr' | 'ledger' | 'audit'

export interface DistributionOption { id: number; name: string }
export interface StatutoryFund { id: number | null; name: string; percent: number; account: string | null; active: boolean }
/** Null lists mean everyone. */
export interface DistributionPolicy { patronageEvents: number[]; eligibleStatuses: number[] | null; iscTypes: number[] | null; prTypes: number[] | null; minAverageShare: number; updatedBy: string | null; updatedAt: string | null }
export interface DistributionSettings { policy: DistributionPolicy; funds: StatutoryFund[]; events: DistributionOption[]; statuses: DistributionOption[]; types: DistributionOption[]; words: string; mayEdit: boolean }

export const getDistributions = () => getJson<Distribution[]>(`${BASE}/distributions`)
export const getDistribution = (id: number) => getJson<DistributionDetail>(`${BASE}/distributions/${id}`)
/** Works the year out with the settings and stores it as a draft, to be submitted and approved before vouchers are made. */
export const prepareDistribution = (b: DividendReq) => send<DistributionDetail>('POST', `${BASE}/distributions`, b)
export const submitDistribution = (id: number) => send<DistributionDetail>('POST', `${BASE}/distributions/${id}/submit`)
export const approveDistribution = (id: number, note: string | null) => send<DistributionDetail>('POST', `${BASE}/distributions/${id}/approve`, { note })
export const rejectDistribution = (id: number, note: string) => send<DistributionDetail>('POST', `${BASE}/distributions/${id}/reject`, { note })
/** Makes the vouchers of an approved distribution, from exactly its stored figures. */
export const encodeDistribution = (batchId: number) => send<DividendVoucher[]>('POST', `${BASE}/encode`, { batchId })
export const getDistributionStatement = (id: number, clientId: number) => getJson<DistributionStatement>(`${BASE}/distributions/${id}/statement?clientId=${clientId}`)
export const getDistributionReport = (id: number, kind: ReportKind) => getJson<DistributionReport>(`${BASE}/distributions/${id}/report/${kind}`)
export const getDistributionSettings = () => getJson<DistributionSettings>(`${BASE}/settings`)
export const saveDistributionPolicy = (p: Omit<DistributionPolicy, 'updatedBy' | 'updatedAt'>, reason: string) => send<DistributionSettings>('PUT', `${BASE}/settings/policy`, { ...p, reason })
export const saveStatutoryFunds = (funds: StatutoryFund[], reason: string) => send<DistributionSettings>('PUT', `${BASE}/settings/funds`, { funds, reason })

/** The stored register as a CSV download. Resolves to the file name. */
export async function downloadDistributionRegister(id: number): Promise<string> {
  const res = await http(`${BASE}/distributions/${id}/register.csv`)
  if (!res.ok) throw new Error(await errorFrom(res))
  const name = /filename="([^"]+)"/.exec(res.headers.get('Content-Disposition') ?? '')?.[1] ?? 'distribution-register.csv'
  const url = URL.createObjectURL(await res.blob())
  const a = document.createElement('a')
  a.href = url
  a.download = name
  a.click()
  setTimeout(() => URL.revokeObjectURL(url), 10_000)
  return name
}
