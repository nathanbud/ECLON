import { errorFrom, getJson, http, send } from './http'

// ---- aging of receivables (Reports > Receivables; after the old AGING OF RECEIVABLES FILTER) --------------------------------
export interface AgingChoice { key: string; label: string }
export interface AgingClass { code: number; name: string; types: { code: number; name: string }[] }
export interface AgingOptions { cutOff: string; classes: AgingClass[]; groupings: AgingChoice[]; sorts: AgingChoice[]; buckets: string[] }

export type AgingBasis = 'PAR' | 'MATURITY'

export interface AgingRequest {
  cutOff: string
  basis: AgingBasis
  parDays: number
  classes: number[]
  /** "class:type", e.g. "12:3"; empty = all types of the chosen classes */
  types: string[]
  releasedFrom: string | null; releasedTo: string | null
  maturityFrom: string | null; maturityTo: string | null
  lastTransFrom: string | null; lastTransTo: string | null
  minBalance: number | null; maxBalance: number | null
  minDays: number | null; maxDays: number | null
  groupBy: string[]
  sort: string
  totalsOnly: boolean
  /** the Accrued Interest tab: work out interest (and penalty) owed on the cut-off date */
  accruedInterest: boolean
  penalty: boolean
  /** list only accounts that owe interest or penalty (needs accruedInterest) */
  owingOnly: boolean
}

export interface AgingLine {
  kind: 'group' | 'account' | 'total'
  level: number
  label: string
  /** a group: the grouping's name; an account: its type and number */
  detail: string | null
  client: number | null; slClass: number | null; slType: number | null; ref: number | null
  accounts: number
  outstanding: number
  buckets: number[]
  pastDue: number
  atRisk: number
  riskPct: number
  days: number | null
  released: string | null; maturity: string | null; lastTrans: string | null
  /** the Accrued Interest tab's figures (0 when it was off); totalDue = outstanding + interest + penalty due */
  interestDue: number; interestAhead: number; penaltyDue: number; totalDue: number
}

export interface AgingResult {
  cutOff: string; basis: AgingBasis; parDays: number; buckets: string[]; groupBy: string[]
  lines: AgingLine[]; note: string; ranAt: string; ranBy: string
  /** whether interest, and penalty, were worked out */
  accrual: boolean; penalty: boolean
}

export const getAgingOptions = () => getJson<AgingOptions>('/api/reports/receivables-aging/options')
export const runAging = (r: AgingRequest) => send<AgingResult>('POST', '/api/reports/receivables-aging', r)

/**
 * The report as an Excel workbook or a PDF (legal paper, landscape) for the same choices as the run on screen; the server reuses that run, so it comes at once. Each file
 * is written to the export log and the audit log. Resolves to the file name.
 */
export async function downloadAging(r: AgingRequest, kind: 'xlsx' | 'pdf'): Promise<string> {
  const res = await http(`/api/reports/receivables-aging/${kind}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(r) })
  if (!res.ok) throw new Error(await errorFrom(res))
  const name = `aging-of-receivables-${r.cutOff}.${kind}`
  const url = URL.createObjectURL(await res.blob())
  const a = document.createElement('a')
  a.href = url
  a.download = name
  a.click()
  setTimeout(() => URL.revokeObjectURL(url), 10_000)
  return name
}
