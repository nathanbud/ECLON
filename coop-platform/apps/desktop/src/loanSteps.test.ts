import { describe, expect, it } from 'vitest'
import { sortApplications } from './loanSteps'

const app = (id: number, date: string, clientName: string | null, status: number, waitingForDeductions = false, releaseStatus: 'APPROVED' | 'POSTED' | null = null) =>
  ({ id, date, clientName, clientId: id, status, waitingForDeductions, releaseStatus })
const rows = [
  app(1, '2026-11-02', 'santos, Ana', 2),
  app(2, '2026-11-05', 'Reyes, Ben', 1, true),
  app(3, '2026-11-01', null, 1),
  app(4, '2026-11-05', 'Abad, Carla', 1),
]
const ids = (r: { id: number }[]) => r.map((a) => a.id)

describe('sortApplications', () => {
  it('orders by date, newest or oldest first, the later saved first on the same day', () => {
    expect(ids(sortApplications(rows, 'date-desc'))).toEqual([4, 2, 1, 3])
    expect(ids(sortApplications(rows, 'date-asc'))).toEqual([3, 1, 2, 4])
  })

  it('orders by name ignoring case, a member with no name as Member <number>', () => {
    expect(ids(sortApplications(rows, 'name'))).toEqual([4, 3, 2, 1])
  })

  it('orders by step: Deduction, then Verification, then Approval, newest first within a step', () => {
    expect(ids(sortApplications(rows, 'status'))).toEqual([2, 4, 3, 1])
  })

  it('puts approved ones after approval and released ones last when ordering by step', () => {
    const more = [...rows, app(5, '2026-11-06', 'Lim, Dan', 3, false, 'POSTED'), app(6, '2026-10-01', 'Uy, Eva', 3, false, 'APPROVED')]
    expect(ids(sortApplications(more, 'status'))).toEqual([2, 4, 3, 1, 6, 5])
  })

  it('leaves the list it was given as it was', () => {
    sortApplications(rows, 'name')
    expect(ids(rows)).toEqual([1, 2, 3, 4])
  })
})
