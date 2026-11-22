import { describe, expect, it } from 'vitest'
import type { ChartAccount } from '../api'
import { nextCode, suggestHeadings, words } from './chartSuggest'

const acct = (code: string, name: string, parent: string | null, summary = false, disabled = false): ChartAccount =>
  ({ code, name, type: Number(code[0]), typeName: null, summary, parent, disabled, glOnly: true, hasChildren: summary, inUse: false })

const CHART: ChartAccount[] = [
  acct('2', 'LIABILITIES', null, true),
  acct('201', 'DEPOSIT LIABILITIES', '2', true),
  acct('20101', 'SAVINGS DEPOSITS', '201', true),
  acct('2010101', 'Savings Deposit', '20101'),
  acct('2010106', 'Kaya savings deposit', '20101'),
  acct('20102', 'TIME DEPOSITS', '201', true),
  acct('2010201', 'Time deposit - short term', '20102'),
  acct('20214', 'OTHER DEPOSIT LIABILITITES', '2', true),
  acct('2021401', 'Mutual benefit funds payable', '20214'),
  acct('3', 'EQUITY', null, true),
  acct('30101', 'SHARE CAPITAL', '3', true),
  acct('3010101', 'Paid-up share capital', '30101'),
  acct('30199', 'OLD HEADING', '3', true, true),
]

describe('chart suggestions', () => {
  it('puts a new savings account under the savings heading, with the next free number', () => {
    const s = suggestHeadings(CHART, 'Holiday savings deposit', '')
    expect(s[0].heading.code).toBe('20101')
    expect(s[0].nextCode).toBe('2010107')
    expect(s[0].why).toContain('Savings Deposit')
  })

  it('suggests the heading whose own name matches', () => {
    expect(suggestHeadings(CHART, 'Preferred share capital', '')[0].heading.code).toBe('30101')
  })

  it('keeps to the headings the typed number starts with, deepest first', () => {
    const s = suggestHeadings(CHART, '', '20102')
    expect(s.map((x) => x.heading.code)).toEqual(['20102'])
    expect(s[0].why).toBe('The number starts with 20102')
    expect(s[0].nextCode).toBe('2010202')
  })

  it('never suggests a heading that is switched off, and nothing when there is nothing to go on', () => {
    expect(suggestHeadings(CHART, 'old heading', '').map((x) => x.heading.code)).not.toContain('30199')
    expect(suggestHeadings(CHART, '', '')).toEqual([])
  })

  it('numbers the first account under an empty heading with 01 and skips numbers already taken', () => {
    const empty = acct('20103', 'SPECIAL DEPOSITS', '201', true)
    expect(nextCode([...CHART, empty], empty)).toBe('2010301')
    const taken = [...CHART, empty, acct('2010301', 'Taken', null)]
    expect(nextCode(taken, empty)).toBe('2010302')
  })

  it('reads words without short or everyday ones', () => {
    expect(words('Other Payable - MP2 Savings of the account')).toEqual(['payable', 'savings'])
  })
})
