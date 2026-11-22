import { describe, expect, it } from 'vitest'
import { inboxFamily } from './inboxKinds'

describe('the colour of each kind of waiting work', () => {
  it('puts every kind the server sends in its area of work', () => {
    const area = (k: string) => inboxFamily(k).area
    expect(['LOAN_DEDUCTION', 'LOAN_VERIFY', 'LOAN_APPROVE', 'RELEASE_VOUCHER_APPROVE', 'RELEASE_VOUCHER_POST'].map(area)).toEqual(Array(5).fill('Loans'))
    expect(['CHANGE', 'MEMBER_REQUEST', 'MEMBER_LIST'].map(area)).toEqual(Array(3).fill('Members'))
    expect(['JOURNAL_REVIEW', 'JOURNAL_POST', 'MEMO_REVIEW', 'MEMO_POST', 'DISTRIBUTION_REVIEW', 'DISTRIBUTION_ENCODE', 'DIVIDEND_VOUCHER_APPROVE', 'PENALTY_WAIVER'].map(area))
      .toEqual(Array(8).fill('Accounting'))
    expect(['CASH_VOUCHER_APPROVE', 'CHECK_VOUCHER_POST', 'ACCRUAL_VOUCHER_APPROVE', 'SUPERVISOR'].map(area)).toEqual(Array(4).fill('Teller'))
    expect(['PURCHASE_REQUEST', 'PURCHASE_ORDER', 'STOCK_ADJUSTMENT'].map(area)).toEqual(Array(3).fill('Purchasing'))
    expect(area('BUSINESS_DAY_START')).toBe('Business day')
    expect(area('WORKFLOW')).toBe('Approval')
    expect(area('SOMETHING_NEW')).toBe('Other')
  })

  it('uses the colour of the area on the dashboard, and red only for the business day', () => {
    expect(inboxFamily('LOAN_APPROVE').color).toBe('teal')
    expect(inboxFamily('CASH_VOUCHER_POST').color).toBe('green')
    expect(inboxFamily('JOURNAL_POST').color).toBe('indigo')
    expect(inboxFamily('BUSINESS_DAY_END').color).toBe('red')
  })
})
