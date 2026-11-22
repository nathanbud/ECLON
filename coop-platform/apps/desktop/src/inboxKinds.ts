import type { Tint } from './theme'

/** The area of work a waiting item belongs to: its name, shown on the item, and its colour, the same as that area's tiles on the dashboard. */
export interface InboxFamily { area: string; color: Tint }

const MEMBERS: InboxFamily = { area: 'Members', color: 'orange' }
const TELLER: InboxFamily = { area: 'Teller', color: 'green' }
const LOANS: InboxFamily = { area: 'Loans', color: 'teal' }
const ACCOUNTING: InboxFamily = { area: 'Accounting', color: 'indigo' }
const PURCHASING: InboxFamily = { area: 'Purchasing', color: 'blue' }
const BUSINESS_DAY: InboxFamily = { area: 'Business day', color: 'red' }
const WORKFLOW: InboxFamily = { area: 'Approval', color: 'purple' }
const OTHER: InboxFamily = { area: 'Other', color: 'gray' }

/**
 * Which area an inbox item's kind (from `/api/inbox`, `InboxController`) belongs to. Vouchers come as `<WHAT>_VOUCHER_APPROVE|POST`: a release voucher is the loan's money,
 * a dividend voucher is accounting's, cash, check and accrual vouchers are the cashier's. The business day is red because nothing can be posted until it is dealt with.
 */
export function inboxFamily(kind: string): InboxFamily {
  const k = kind.toUpperCase()
  if (k.startsWith('BUSINESS_DAY')) return BUSINESS_DAY
  if (k.startsWith('LOAN_') || k.startsWith('RELEASE_VOUCHER')) return LOANS
  if (k === 'CHANGE' || k.startsWith('MEMBER_')) return MEMBERS
  if (k.startsWith('JOURNAL_') || k.startsWith('MEMO_') || k.startsWith('DISTRIBUTION_') || k.startsWith('DIVIDEND_VOUCHER') || k === 'PENALTY_WAIVER') return ACCOUNTING
  if (k.startsWith('CASH_VOUCHER') || k.startsWith('CHECK_VOUCHER') || k.startsWith('ACCRUAL_VOUCHER') || k === 'SUPERVISOR' || k === 'CHEQUE_RETURN') return TELLER
  if (k.startsWith('PURCHASE_') || k === 'STOCK_ADJUSTMENT') return PURCHASING
  if (k === 'WORKFLOW') return WORKFLOW
  return OTHER
}
