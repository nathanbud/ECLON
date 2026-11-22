import type { LoanApplicationItem } from './api'

export const LOAN_STEPS = ['Profiling', 'Deduction', 'Verification', 'Approval', 'Loan Release'] as const

/** The status code of an approved application (the release voucher is made). */
export const APPROVED = 3

type Stage = Pick<LoanApplicationItem, 'status' | 'waitingForDeductions' | 'releaseStatus'>

/** Released: approved and its release voucher posted, so the money was paid out. */
export const isReleased = (a: Stage) => a.status === APPROVED && a.releaseStatus === 'POSTED'

/**
 * The step an application is at (0-based in LOAN_STEPS), LOAN_STEPS.length once released, or null when its status is none of them (disapproved,
 * cancelled). A saved application has always been profiled; without its deductions it waits at Deduction, New waits at Verification, Verified at
 * Approval, and Approved at Loan Release until its voucher is posted.
 */
export function currentStep(a: Stage): number | null {
  if (a.status === APPROVED) return isReleased(a) ? LOAN_STEPS.length : 4
  if (a.waitingForDeductions) return 1
  if (a.status === 1) return 2
  if (a.status === 2) return 3
  return null
}

/** Where the release stands, in words, for an approved application; null before approval. */
export function releaseWords(a: Stage & Pick<LoanApplicationItem, 'releasedOn'>): string | null {
  if (a.status !== APPROVED) return null
  switch (a.releaseStatus) {
    case 'POSTED': return a.releasedOn ? `Released on ${a.releasedOn}` : 'Released'
    case 'APPROVED': return 'Release voucher approved, waiting to be posted in Front Office'
    case 'WAITING FOR APPROVAL': return 'Waiting for release in Front Office'
    default: return 'Approved, waiting for release'
  }
}

/** How the waiting list is ordered. */
export type LoanSort = 'date-desc' | 'date-asc' | 'name' | 'status'

export const LOAN_SORTS: { value: LoanSort; label: string }[] = [
  { value: 'date-desc', label: 'Date, newest first' },
  { value: 'date-asc', label: 'Date, oldest first' },
  { value: 'name', label: 'Name, A to Z' },
  { value: 'status', label: 'Status, by step' },
]

type Sortable = Pick<LoanApplicationItem, 'id' | 'date' | 'clientName' | 'clientId' | 'status' | 'waitingForDeductions' | 'releaseStatus'>

const nameOf = (a: Sortable) => (a.clientName ?? `Member ${a.clientId}`).trim()
/** Newest first: by date, then by id (the later saved first) for the same day. */
const newest = (a: Sortable, b: Sortable) => b.date.localeCompare(a.date) || b.id - a.id

/** The list in the chosen order, as a new array. Ties fall back to newest first, so the order is always the same. */
export function sortApplications<T extends Sortable>(rows: readonly T[], sort: LoanSort): T[] {
  const by: Record<LoanSort, (a: T, b: T) => number> = {
    'date-desc': newest,
    'date-asc': (a, b) => a.date.localeCompare(b.date) || a.id - b.id,
    name: (a, b) => nameOf(a).localeCompare(nameOf(b), undefined, { sensitivity: 'base' }) || newest(a, b),
    status: (a, b) => (currentStep(a) ?? 99) - (currentStep(b) ?? 99) || newest(a, b),
  }
  return [...rows].sort(by[sort])
}
