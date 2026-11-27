/**
 * Words for the codes the server stores (DRAFT, FOR_REVIEW, SOFT_CLOSED ...), so a screen never shows a code. Use {@link labelOf} for any status,
 * kind or type; pass a screen's own words for a code that means something else there (for example SUBMITTED on a CIC batch means sent to the CIC).
 * A code with no entry is shown in sentence case ("TRANSFER_IN" becomes "Transfer in").
 */
const WORDS: Record<string, string> = {
  DRAFT: 'Draft',
  FOR_REVIEW: 'Waiting for review',
  SUBMITTED: 'Waiting for approval',
  PENDING: 'Waiting for approval',
  'WAITING FOR APPROVAL': 'Waiting for approval',
  PREPARED: 'Waiting to be posted',
  APPROVED: 'Approved',
  POSTED: 'Posted',
  REJECTED: 'Rejected',
  CANCELLED: 'Cancelled',
  WITHDRAWN: 'Withdrawn',
  REVERSED: 'Reversed',
  DISPUTED: 'Count differs',
  OPEN: 'Open',
  SOFT_CLOSED: 'Closed for review',
  CLOSED: 'Closed',
  LOCKED: 'Locked',
  FINAL: 'Finished',
  ACTIVE: 'In use',
  DISPOSED: 'Disposed of',
  SUPERSEDED: 'Replaced by a newer version',
  REMOVED: 'Removed',
  ORDERED: 'Ordered',
  PARTIAL: 'Partly received',
  RECEIVED: 'Received',
  MATURED: 'Matured',
  NO_LEDGER: 'No ledger entries',
  IN_PROGRESS: 'In progress',
  RESOLVED: 'Resolved',
  VALID: 'Valid',
  WARNING: 'Check this',
  ERROR: 'Has errors',
  NONE: 'Not asked',
}

/** A user's level (the old `access` table's ADMIN, SUPERADMIN, USER). */
export const USER_LEVEL_WORDS: Record<string, string> = {
  USER: 'User',
  ADMIN: 'Administrator',
  SUPERADMIN: 'Super administrator',
}

/** The CIC submission batch: these steps mean something particular there. */
export const CIC_WORDS: Record<string, string> = {
  DRAFT: 'Draft',
  GENERATED: 'Records made',
  VALIDATED: 'Checked',
  APPROVED: 'Approved for sending',
  SUBMITTED: 'Sent to the CIC',
  RESULT: 'CIC reply received',
  RECONCILED: 'Reply matched',
}

/** Kinds of request a person can make about their data (Data Privacy Act). */
export const PRIVACY_REQUEST_WORDS: Record<string, string> = {
  ACCESS: 'See the data we keep',
  CORRECTION: 'Correct their data',
  ERASURE: 'Delete their data',
  OBJECTION: 'Object to how it is used',
  PORTABILITY: 'Get a copy to take elsewhere',
  WITHDRAW_CONSENT: 'Withdraw consent',
  OTHER: 'Other',
}

export function labelOf(code: string | null | undefined, words?: Record<string, string>): string {
  if (code == null || code === '') return '—'
  const known = words?.[code] ?? WORDS[code]
  if (known) return known
  const plain = code.replace(/_/g, ' ').trim().toLowerCase()
  return plain.charAt(0).toUpperCase() + plain.slice(1)
}
