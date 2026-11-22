import type { ChartAccount } from '../api'

/**
 * The chart of accounts screen's rules, kept pure: which accounts a filter shows, the counts on the summary cards, the live checks on a new account's number
 * and name (the server checks again), and the CSV export.
 */

/** The account types by their first digit. */
export const TYPE_NAMES = ['', 'Assets', 'Liabilities', 'Equity', 'Revenues', 'Expenses', 'Net income'] as const
/** One account of a type, for a pill. */
export const TYPE_ONE = ['', 'Asset', 'Liability', 'Equity', 'Revenue', 'Expense', 'Net income'] as const

export type Status = 'active' | 'inactive' | 'all'
/** heading: totals the accounts under it, takes no postings; journal: takes journal entries; subsidiary: posted to by a member ledger only. */
export type Posting = 'heading' | 'journal' | 'subsidiary'
export interface ChartFilter { type: number; status: Status; posting: Posting | 'all' }

export const POSTING_WORDS: Record<Posting, string> = { heading: 'Heading', journal: 'Journal entries', subsidiary: 'Subsidiary ledger' }

/** A type's own normal balance: debit for assets and expenses, credit for liabilities, equity, revenues and net income. */
export const normalOf = (type: number): 'D' | 'C' => (type === 1 || type === 5 ? 'D' : 'C')

export const postingOf = (a: ChartAccount): Posting => (a.summary ? 'heading' : a.glOnly ? 'journal' : 'subsidiary')

/** Whether an account passes the type, status and posting filters (the search is applied on top, with the headings above a match kept). */
export function passes(a: ChartAccount, f: ChartFilter): boolean {
  if (f.type !== 0 && a.type !== f.type) return false
  if (f.status === 'active' && a.disabled) return false
  if (f.status === 'inactive' && !a.disabled) return false
  return f.posting === 'all' || postingOf(a) === f.posting
}

export const matches = (a: ChartAccount, q: string) => !q || a.code.toLowerCase().includes(q) || (a.name ?? '').toLowerCase().includes(q) || (a.description ?? '').toLowerCase().includes(q)

/** The summary cards: every account, those that take postings and are in use, in use, switched off. */
export function counts(list: ChartAccount[]) {
  let posting = 0, active = 0
  for (const a of list) {
    if (!a.disabled) active++
    if (!a.disabled && !a.summary) posting++
  }
  return { total: list.length, posting, active, inactive: list.length - active }
}

/** Why a new account's number cannot be used, or null. {@code parent} is the heading chosen, if any. */
export function codeProblem(code: string, parent: ChartAccount | null, chart: ChartAccount[]): string | null {
  const c = code.trim()
  if (!c) return 'Give the account number, or press Auto-generate.'
  if (!/^\d+$/.test(c)) return 'The account number is digits only.'
  if (c.length > 10) return 'The account number has at most 10 digits.'
  if (!/^[1-6]/.test(c)) return 'The first digit is the type: 1 assets, 2 liabilities, 3 equity, 4 revenues, 5 expenses, 6 net income.'
  if (parent && (c === parent.code || !c.startsWith(parent.code))) return `It must start with ${parent.code}, the number of the heading it goes under, and be longer.`
  const taken = chart.find((a) => a.code === c)
  if (taken) return `Account ${c} already exists: ${taken.name ?? ''}. Choose another number.`
  return null
}

/** Why a name cannot be used under a heading (another account there has it), or null. */
export function nameProblem(name: string, parent: string | null, chart: ChartAccount[], self?: string): string | null {
  const n = name.trim().toLowerCase()
  if (!n || !parent) return null
  const same = chart.find((a) => a.parent === parent && a.code !== self && (a.name ?? '').trim().toLowerCase() === n)
  return same ? `There is already an account called ${same.name} under ${parent} (${same.code}).` : null
}

const cell = (v: string) => (/[",\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v)

/** The accounts as a CSV file, in number order, with the words the screen uses. */
export function toCsv(list: ChartAccount[]): string {
  const head = ['Code', 'Name', 'Type', 'Normal balance', 'Posting', 'Status', 'Under', 'Description']
  const rows = [...list].sort((a, b) => a.code.localeCompare(b.code, undefined, { numeric: true })).map((a) => [
    a.code, a.name ?? '', TYPE_NAMES[a.type] ?? '', `${a.normalBalance === 'D' ? 'Debit' : 'Credit'}${a.contra ? ' (contra)' : ''}`,
    POSTING_WORDS[postingOf(a)], a.disabled ? 'Switched off' : 'Active', a.parent ?? '', a.description ?? '',
  ])
  return [head, ...rows].map((r) => r.map((v) => cell(String(v))).join(',')).join('\r\n') + '\r\n'
}
