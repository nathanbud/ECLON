import { describe, expect, it } from 'vitest'
import { displayName, initials, memberNo, statusOf } from './clients'

const base = { accountName: null, lastName: null, firstName: null, middleName: null, suffix: null }

describe('member display helpers', () => {
  it('writes the member number with the branch and padding', () => {
    expect(memberNo({ branchCode: 1, clientId: 33189 })).toBe('0001-0000033189'.replace('0000033189', '0033189'))
  })

  it('builds a name as Last, First M.', () => {
    expect(displayName({ ...base, lastName: 'Reyes', firstName: 'Ana', middleName: 'Santos' })).toBe('Reyes, Ana S.')
    expect(displayName({ ...base, lastName: 'Cruz', firstName: 'Ben', suffix: 'Jr.' })).toBe('Cruz, Ben Jr.')
  })

  it('prefers an account name and never returns an empty string', () => {
    expect(displayName({ ...base, accountName: 'Baguio Teachers Coop', lastName: 'X' })).toBe('Baguio Teachers Coop')
    expect(displayName(base)).toBe('Unnamed client')
  })

  it('takes initials from the first and last name', () => {
    expect(initials({ accountName: null, lastName: 'Reyes', firstName: 'Ana' })).toBe('AR')
    expect(initials({ accountName: null, lastName: null, firstName: null })).toBe('?')
  })

  it('reads the status, defaulting to active', () => {
    expect(statusOf({ accountStatus: 1, closedDate: null }).kind).toBe('success')
    expect(statusOf({ accountStatus: null, closedDate: '2026-01-01' }).label).toBe('Closed')
    expect(statusOf({ accountStatus: 2, closedDate: null }).kind).toBe('warning')
  })
})
