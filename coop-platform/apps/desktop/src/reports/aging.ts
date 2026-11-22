import type { AgingLine, AgingRequest, AgingResult } from '../api/receivables'

/** Pure helpers for the aging of receivables screen: which lines show when groups are folded, the CSV file, and the remembered choices. */

/** A line's place in the tree: the indexes of the groups above it. Lines come in tree order (a group, then what is inside it), the total last. */
export function parents(lines: AgingLine[]): number[][] {
  const stack: number[] = []
  return lines.map((l, i) => {
    if (l.kind === 'total') return []
    while (stack.length > 0 && lines[stack[stack.length - 1]].level >= l.level) stack.pop()
    const above = [...stack]
    if (l.kind === 'group') stack.push(i)
    return above
  })
}

/** The lines to show: a line inside a folded group is hidden. Returns the indexes. */
export function visible(lines: AgingLine[], folded: Set<number>): number[] {
  const up = parents(lines)
  const out: number[] = []
  lines.forEach((_, i) => { if (!up[i].some((p) => folded.has(p))) out.push(i) })
  return out
}

/** Every group's index (to fold or unfold them all), or only those at one level. */
export function groupIndexes(lines: AgingLine[], level?: number): number[] {
  return lines.flatMap((l, i) => (l.kind === 'group' && (level === undefined || l.level === level) ? [i] : []))
}

const cell = (s: string) => (/[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s)
const num = (n: number | null | undefined) => (n == null ? '' : String(n))

/** The whole result as CSV (plain numbers for a spreadsheet; groups indented by level so the nesting survives). */
export function agingCsv(r: AgingResult): string {
  const head = ['Line', 'Group / member', 'Detail', 'Member no.', 'Accounts', 'Outstanding', ...r.buckets, 'Past-due principal', `At risk (${r.basis === 'PAR' ? 'PAR' : 'past maturity'} ${r.parDays})`, 'At risk %', 'Days aged', 'Released', 'Maturity', 'Last transaction',
    ...(r.accrual ? ['Interest due', 'Interest paid ahead', ...(r.penalty ? ['Penalty due (estimate)'] : []), 'Total owed'] : [])]
  const rows = r.lines.map((l) => [
    l.kind === 'group' ? `Group (${l.detail ?? ''})` : l.kind === 'total' ? 'Total' : 'Account',
    `${'  '.repeat(l.level)}${l.label}`,
    l.kind === 'account' ? l.detail ?? '' : '',
    num(l.client), num(l.accounts), num(l.outstanding), ...l.buckets.map(num), num(l.pastDue), num(l.atRisk), num(l.riskPct), num(l.days),
    l.released ?? '', l.maturity ?? '', l.lastTrans ?? '',
    ...(r.accrual ? [num(l.interestDue), num(l.interestAhead), ...(r.penalty ? [num(l.penaltyDue)] : []), num(l.totalDue)] : []),
  ])
  const meta = [`Aging of receivables as of ${r.cutOff}`, r.note, `Run by ${r.ranBy} on ${r.ranAt.replace('T', ' ')}`]
  return [...meta.map((m) => cell(m)), '', head.map(cell).join(','), ...rows.map((x) => x.map((v) => cell(String(v))).join(','))].join('\r\n')
}

/** Share of each bucket in the total, in percent (0 when there is nothing). */
export function shares(total: AgingLine | undefined): number[] {
  if (!total || total.outstanding <= 0) return total ? total.buckets.map(() => 0) : []
  return total.buckets.map((b) => (b * 100) / total.outstanding)
}

export const DEFAULT_REQUEST = (cutOff: string): AgingRequest => ({
  cutOff, basis: 'PAR', parDays: 30, classes: [12, 13], types: [],
  releasedFrom: null, releasedTo: null, maturityFrom: null, maturityTo: null, lastTransFrom: null, lastTransTo: null,
  minBalance: null, maxBalance: null, minDays: null, maxDays: null, groupBy: ['slclass', 'sltype'], sort: 'name', totalsOnly: false,
  accruedInterest: false, penalty: false, owingOnly: false,
})

const KEY = 'coop:receivables-aging'

/** The choices this person used last (not the cut-off date, which starts on today); a convenience only, so any failure gives the defaults. */
export function rememberedRequest(cutOff: string): AgingRequest {
  const base = DEFAULT_REQUEST(cutOff)
  try {
    const saved = JSON.parse(localStorage.getItem(KEY) ?? 'null') as Partial<AgingRequest> | null
    if (!saved || typeof saved !== 'object') return base
    return { ...base, ...saved, cutOff }
  } catch {
    return base
  }
}

export function rememberRequest(r: AgingRequest) {
  try {
    localStorage.setItem(KEY, JSON.stringify(r))
  } catch {
    // private window or blocked storage: the choices are just not kept
  }
}

export function forgetRequest() {
  try {
    localStorage.removeItem(KEY)
  } catch {
    // nothing to forget
  }
}
