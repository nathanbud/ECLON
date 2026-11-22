import { describe, expect, it } from 'vitest'
import type { ChartAccount } from '../api'
import { codeProblem, counts, nameProblem, normalOf, passes, postingOf, toCsv } from './chartTree'

const acct = (code: string, name: string, parent: string | null, extra: Partial<ChartAccount> = {}): ChartAccount => ({
  code, name, type: Number(code[0]), typeName: null, summary: false, parent, disabled: false, glOnly: true, hasChildren: false, inUse: false,
  normalBalance: normalOf(Number(code[0])), contra: false, description: null, ...extra,
})

const CHART = [
  acct('1', 'ASSETS', null, { summary: true }),
  acct('1000', 'CASH ON HAND', '1', { summary: true }),
  acct('10001', 'Cash in vault', '1000'),
  acct('10003', 'Cash on hand - tellers', '1000', { glOnly: false }),
  acct('10009', 'Old fund', '1000', { disabled: true }),
  acct('10010', 'Allowance for shortages', '1000', { normalBalance: 'C', contra: true, description: 'Contra, "cash" losses' }),
]

describe('chart of accounts rules', () => {
  it('suggests debit for assets and expenses, credit for the rest', () => {
    expect([1, 2, 3, 4, 5, 6].map(normalOf)).toEqual(['D', 'C', 'C', 'C', 'D', 'C'])
  })

  it('tells headings, journal accounts and subsidiary ledger accounts apart, and filters by them', () => {
    expect(CHART.map(postingOf)).toEqual(['heading', 'heading', 'journal', 'subsidiary', 'journal', 'journal'])
    expect(CHART.filter((a) => passes(a, { type: 0, status: 'active', posting: 'subsidiary' })).map((a) => a.code)).toEqual(['10003'])
    expect(CHART.filter((a) => passes(a, { type: 0, status: 'inactive', posting: 'all' })).map((a) => a.code)).toEqual(['10009'])
    expect(CHART.filter((a) => passes(a, { type: 2, status: 'all', posting: 'all' }))).toEqual([])
  })

  it('counts every account, the posting ones in use, the active and the switched-off', () => {
    expect(counts(CHART)).toEqual({ total: 6, posting: 3, active: 5, inactive: 1 })
  })

  it('checks a new number: digits, the type digit, its heading prefix, and that it is free', () => {
    const heading = CHART[1]
    expect(codeProblem('', heading, CHART)).toMatch(/Auto-generate/)
    expect(codeProblem('10a', heading, CHART)).toMatch(/digits only/)
    expect(codeProblem('70001', null, CHART)).toMatch(/first digit is the type/)
    expect(codeProblem('20001', heading, CHART)).toMatch(/must start with 1000/)
    expect(codeProblem('10001', heading, CHART)).toMatch(/already exists: Cash in vault/)
    expect(codeProblem('100011', heading, CHART)).toBeNull()
  })

  it('refuses a name already used under the same heading, but not the account itself', () => {
    expect(nameProblem('cash in VAULT', '1000', CHART)).toMatch(/already an account called Cash in vault under 1000/)
    expect(nameProblem('Cash in vault', '1000', CHART, '10001')).toBeNull()
    expect(nameProblem('Cash in vault', '1', CHART)).toBeNull()
  })

  it('exports the accounts as CSV in number order, quoting what needs it', () => {
    const csv = toCsv([CHART[5], CHART[2]])
    const lines = csv.trim().split('\r\n')
    expect(lines[0]).toBe('Code,Name,Type,Normal balance,Posting,Status,Under,Description')
    expect(lines[1]).toBe('10001,Cash in vault,Assets,Debit,Journal entries,Active,1000,')
    expect(lines[2]).toBe('10010,Allowance for shortages,Assets,Credit (contra),Journal entries,Active,1000,"Contra, ""cash"" losses"')
  })
})
