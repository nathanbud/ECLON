/** Sorting the Back Office entries in the Find pop-up, and the steps an entry goes through. Pure: no screen, no server. */

/** The steps of a journal or memo: made as a draft, reviewed and approved by another person (one step on the server), then posted. */
export const ENTRY_STEPS = ['Draft', 'Review', 'Posting'] as const
const AT: Record<string, number> = { DRAFT: 0, FOR_REVIEW: 1, APPROVED: 2, POSTED: 3 }

/** The step an entry waits at (0 to 2), 3 once posted, or null for a status outside the steps (cancelled). */
export function entryStep(status: string): number | null {
  return status in AT ? AT[status] : null
}

export type SortKey = 'date' | 'reference' | 'description' | 'total' | 'status' | 'createdBy'
export type SortDir = 'asc' | 'desc'
export interface Sort { key: SortKey; dir: SortDir }

/** What each key is called, and the order a key starts in when chosen (dates and amounts newest or largest first, words A to Z). */
export const SORT_KEYS: { key: SortKey; label: string; first: SortDir }[] = [
  { key: 'date', label: 'Date', first: 'desc' },
  { key: 'reference', label: 'Control No.', first: 'asc' },
  { key: 'description', label: 'Description', first: 'asc' },
  { key: 'total', label: 'Amount', first: 'desc' },
  { key: 'status', label: 'Status (by step)', first: 'asc' },
  { key: 'createdBy', label: 'Made by', first: 'asc' },
]
export const DEFAULT_SORT: Sort = { key: 'date', dir: 'desc' }

/** The steps in the order an entry goes through them; an unknown status goes last. */
const STEP = ['DRAFT', 'FOR_REVIEW', 'APPROVED', 'POSTED', 'CANCELLED']

interface Sortable { id: number; reference: string; date: string; description: string; total: number; status: string; createdBy: string }

const text = (a: string, b: string) => a.localeCompare(b, 'en', { sensitivity: 'base', numeric: true })

/** The entries in the order asked for; ties (and equal values) fall back to newest first, then the higher id, so the order never jumps about. */
export function sortEntries<T extends Sortable>(entries: readonly T[], sort: Sort): T[] {
  const sign = sort.dir === 'asc' ? 1 : -1
  const by = (a: T, b: T): number => {
    switch (sort.key) {
      case 'date': return text(a.date, b.date)
      case 'reference': return text(a.reference, b.reference)
      case 'description': return text(a.description.trim(), b.description.trim())
      case 'total': return a.total - b.total
      case 'status': {
        const s = (x: string) => { const i = STEP.indexOf(x); return i < 0 ? STEP.length : i }
        return s(a.status) - s(b.status)
      }
      case 'createdBy': return text(a.createdBy, b.createdBy)
    }
  }
  return [...entries].sort((a, b) => sign * by(a, b) || text(b.date, a.date) || b.id - a.id)
}

/** Choosing a key again flips its order; a new key starts in its own first order. */
export function nextSort(current: Sort, key: SortKey): Sort {
  if (current.key === key) return { key, dir: current.dir === 'asc' ? 'desc' : 'asc' }
  return { key, dir: SORT_KEYS.find((k) => k.key === key)?.first ?? 'asc' }
}

/** The order in words, for the screen and screen readers: "Date, newest first". */
export function describeSort(sort: Sort): string {
  const label = SORT_KEYS.find((k) => k.key === sort.key)?.label ?? sort.key
  const words: Record<SortKey, [string, string]> = {
    date: ['oldest first', 'newest first'], reference: ['A to Z', 'Z to A'], description: ['A to Z', 'Z to A'],
    total: ['smallest first', 'largest first'], status: ['first step first', 'last step first'], createdBy: ['A to Z', 'Z to A'],
  }
  return `${label}, ${words[sort.key][sort.dir === 'asc' ? 0 : 1]}`
}
