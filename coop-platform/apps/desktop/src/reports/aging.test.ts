import { describe, expect, it } from 'vitest'
import type { AgingLine, AgingResult } from '../api/receivables'
import { agingCsv, forgetRequest, groupIndexes, parents, rememberedRequest, rememberRequest, shares, visible } from './aging'

const line = (kind: AgingLine['kind'], level: number, label: string, outstanding = 100, buckets = [100, 0, 0, 0, 0, 0, 0]): AgingLine => ({
  kind, level, label, detail: kind === 'account' ? 'Regular · 7' : 'SL type', client: kind === 'account' ? 42 : null, slClass: null, slType: null, ref: null,
  accounts: 1, outstanding, buckets, pastDue: 0, atRisk: 0, riskPct: 0, days: kind === 'account' ? 0 : null, released: null, maturity: null, lastTrans: null,
  interestDue: 0, interestAhead: 0, penaltyDue: 0, totalDue: outstanding,
})

// Loans > Regular > (two accounts), Loans > Emergency > one account, then the total
const LINES = [
  line('group', 0, 'Loans'), line('group', 1, 'Regular'), line('account', 2, 'Abad'), line('account', 2, 'Cruz'),
  line('group', 1, 'Emergency'), line('account', 2, 'Diaz'), line('total', 0, 'All receivables'),
]

describe('aging helpers', () => {
  it('knows the groups above each line', () => {
    expect(parents(LINES)).toEqual([[], [0], [0, 1], [0, 1], [0], [0, 4], []])
  })

  it('hides what is inside a closed group, never the total', () => {
    expect(visible(LINES, new Set())).toEqual([0, 1, 2, 3, 4, 5, 6])
    expect(visible(LINES, new Set([1]))).toEqual([0, 1, 4, 5, 6])
    expect(visible(LINES, new Set([0]))).toEqual([0, 6])
    expect(groupIndexes(LINES, 1)).toEqual([1, 4])
    expect(groupIndexes(LINES)).toEqual([0, 1, 4])
  })

  it('writes a CSV with the nesting and plain numbers', () => {
    const r: AgingResult = {
      cutOff: '2026-04-24', basis: 'PAR', parDays: 30, buckets: ['Current', 'a', 'b', 'c', 'd', 'e', 'f'], groupBy: ['slclass', 'sltype'], lines: LINES,
      note: 'A note, with a comma', ranAt: '2026-04-24T10:00:00', ranBy: 'ana', accrual: false, penalty: false,
    }
    const csv = agingCsv(r).split('\r\n')
    expect(csv[1]).toBe('"A note, with a comma"')
    expect(csv[4]).toContain('At risk (PAR 30)')
    expect(csv[5]).toBe('Group (SL type),Loans,,,1,100,100,0,0,0,0,0,0,0,0,0,,,,')
    expect(csv[7]).toContain('Account,    Abad,Regular · 7,42,')
    expect(csv[csv.length - 1].startsWith('Total,All receivables')).toBe(true)
    // with the accrued interest tab on, its columns follow
    const withInterest = agingCsv({ ...r, accrual: true, penalty: false }).split('\r\n')
    expect(withInterest[4].endsWith('Interest due,Interest paid ahead,Total owed')).toBe(true)
    expect(withInterest[5].endsWith(',0,0,100')).toBe(true)
  })

  it('works out each bucket share', () => {
    expect(shares(line('total', 0, 'x', 200, [50, 150, 0, 0, 0, 0, 0]))).toEqual([25, 75, 0, 0, 0, 0, 0])
    expect(shares(line('total', 0, 'x', 0, [0, 0, 0, 0, 0, 0, 0]))).toEqual([0, 0, 0, 0, 0, 0, 0])
  })

  it('remembers the choices but always starts on the given cut-off date', () => {
    forgetRequest()
    expect(rememberedRequest('2026-11-19').groupBy).toEqual(['slclass', 'sltype'])
    rememberRequest({ ...rememberedRequest('2026-01-01'), parDays: 1, groupBy: ['collector'] })
    const back = rememberedRequest('2026-11-19')
    expect(back.parDays).toBe(1)
    expect(back.groupBy).toEqual(['collector'])
    expect(back.cutOff).toBe('2026-11-19')
    forgetRequest()
  })
})
