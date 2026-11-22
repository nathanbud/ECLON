import { describe, expect, it } from 'vitest'
import { DEFAULT_SORT, describeSort, entryStep, nextSort, sortEntries } from './backOfficeSort'

const e = (id: number, reference: string, date: string, total: number, status: string, createdBy: string, description = '') =>
  ({ id, reference, date, total, status, createdBy, description })
const list = [
  e(1, 'GJ-0009', '2026-11-01', 250, 'POSTED', 'maria', 'Office supplies'),
  e(2, 'GJ-0010', '2026-11-03', 1500, 'DRAFT', 'Ana', 'accrued rent'),
  e(3, 'GJ-0002', '2026-11-03', 90, 'FOR_REVIEW', 'ben', 'Bank charges'),
  e(4, 'GJ-0100', '2026-10-15', 1500, 'APPROVED', 'ana', 'Year-end accrual'),
]
const ids = (s: Parameters<typeof sortEntries>[1]) => sortEntries(list, s).map((x) => x.id)

describe('sorting the Back Office entries', () => {
  it('starts on the date, newest first, with ties newest id first', () => {
    expect(ids(DEFAULT_SORT)).toEqual([3, 2, 1, 4])
    expect(describeSort(DEFAULT_SORT)).toBe('Date, newest first')
  })

  it('sorts control numbers by their number, not letter by letter', () => {
    expect(ids({ key: 'reference', dir: 'asc' })).toEqual([3, 1, 2, 4])
  })

  it('sorts amounts, and equal amounts newest first', () => {
    expect(ids({ key: 'total', dir: 'desc' })).toEqual([2, 4, 1, 3])
    expect(ids({ key: 'total', dir: 'asc' })).toEqual([3, 1, 2, 4])
  })

  it('sorts the status by the step an entry is at, not by its name', () => {
    expect(ids({ key: 'status', dir: 'asc' })).toEqual([2, 3, 4, 1])
  })

  it('sorts names and descriptions without minding capitals', () => {
    expect(ids({ key: 'createdBy', dir: 'asc' })).toEqual([2, 4, 3, 1])
    expect(ids({ key: 'description', dir: 'asc' })).toEqual([2, 3, 1, 4])
  })

  it('flips the order when the same key is chosen again, and starts a new key in its own order', () => {
    expect(nextSort(DEFAULT_SORT, 'date')).toEqual({ key: 'date', dir: 'asc' })
    expect(nextSort(DEFAULT_SORT, 'reference')).toEqual({ key: 'reference', dir: 'asc' })
    expect(nextSort(DEFAULT_SORT, 'total')).toEqual({ key: 'total', dir: 'desc' })
  })

  it('does not change the list it was given', () => {
    const before = list.map((x) => x.id)
    sortEntries(list, { key: 'total', dir: 'asc' })
    expect(list.map((x) => x.id)).toEqual(before)
  })

  it('puts each status at its step: draft, review, posting, then posted; cancelled is outside the steps', () => {
    expect(['DRAFT', 'FOR_REVIEW', 'APPROVED', 'POSTED', 'CANCELLED'].map(entryStep)).toEqual([0, 1, 2, 3, null])
  })
})
