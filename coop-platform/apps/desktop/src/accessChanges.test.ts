import { describe, expect, it } from 'vitest'
import { accessChanges, anyChange, type AccessItem } from './accessChanges'

const item = (key: string, group: string, label: string, value: string): AccessItem => ({ key, group, label, value })
const before: AccessItem[] = [
  item('level', 'Your account', 'Level', 'USER'),
  item('role:ACCOUNTANT', 'Roles', 'Accountant', 'Yes'),
  item('AccessCanAddLoanApplication', 'Receivables · Loans', 'Add loan application', 'No'),
  item('AccessCanApproveLoanApplication', 'Receivables · Loans', 'Approve loan application', 'Yes'),
  item('AccessMaxWithdrawal', 'Transactions · Areas and limits', 'Max withdrawal', '50,000.00'),
  item('TRANS:10:CAN_POST', 'Transaction types · Cash Receipt', 'Post', 'No'),
]

describe('accessChanges', () => {
  it('names what was switched on, what was switched off and what limit changed', () => {
    const after = before.map((i) =>
      i.key === 'AccessCanAddLoanApplication' ? { ...i, value: 'Yes' }
      : i.key === 'AccessCanApproveLoanApplication' ? { ...i, value: 'No' }
      : i.key === 'AccessMaxWithdrawal' ? { ...i, value: '100,000.00' }
      : i.key === 'TRANS:10:CAN_POST' ? { ...i, value: 'Yes' } : i)
    const c = accessChanges(before, after)
    expect(c.allowed.map((x) => x.text)).toEqual(['Add loan application', 'Cash Receipt: Post'])
    expect(c.allowed[0].where).toBe('Receivables · Loans')
    expect(c.removed.map((x) => x.text)).toEqual(['Approve loan application'])
    expect(c.changed.map((x) => x.text)).toEqual(['Max withdrawal: 50,000.00 → 100,000.00'])
  })

  it('reports a role given or taken away, and a new level', () => {
    const after = [...before.filter((i) => i.key !== 'role:ACCOUNTANT').map((i) => (i.key === 'level' ? { ...i, value: 'ADMIN' } : i)),
      item('role:MEMBERSHIP_OFFICER', 'Roles', 'Membership officer', 'Yes')]
    const c = accessChanges(before, after)
    expect(c.allowed.map((x) => x.text)).toEqual(['Membership officer role'])
    expect(c.removed.map((x) => x.text)).toEqual(['Accountant role'])
    expect(c.changed.map((x) => x.text)).toEqual(['Level: USER → ADMIN'])
  })

  it('says nothing when nothing changed', () => {
    expect(anyChange(accessChanges(before, before.map((i) => ({ ...i }))))).toBe(false)
  })
})
