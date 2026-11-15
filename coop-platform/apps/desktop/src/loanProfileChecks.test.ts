import { describe, expect, it } from 'vitest'
import { firstMissing, missingText, profileChecks, type ProfileState } from './loanProfileChecks'

const base: ProfileState = { typeChosen: true, amount: '', terms: '', unit: 4, typeUnit: 4, typeTerms: 12, collateralRequired: false, collType: '', collAmount: '', collDesc: '' }

describe('profileChecks', () => {
  it('needs the loan type first, then the principal', () => {
    const none = profileChecks({ ...base, typeChosen: false })
    expect(none.map((c) => c.field)).toEqual(['type', 'principal'])
    expect(firstMissing(none)).toBe('type')
    expect(firstMissing(profileChecks(base))).toBe('principal')
  })

  it('takes a blank term as the loan type’s own, but not when it is counted in another unit', () => {
    expect(profileChecks({ ...base, amount: '5000' }).every((c) => c.done)).toBe(true)
    const years = profileChecks({ ...base, amount: '5000', unit: 7 })
    expect(firstMissing(years)).toBe('term')
    expect(years.find((c) => c.field === 'term')!.message).toBe('Enter the term in years: the loan type counts it in months.')
    expect(profileChecks({ ...base, amount: '5000', unit: 7, terms: '2' }).every((c) => c.done)).toBe(true)
  })

  it('asks for the collateral only when the loan type requires it', () => {
    expect(profileChecks({ ...base, amount: '5000' }).some((c) => c.field.startsWith('coll'))).toBe(false)
    const coll = profileChecks({ ...base, amount: '5000', collateralRequired: true, collType: '2' })
    expect(coll.filter((c) => !c.done).map((c) => c.field)).toEqual(['collAmount', 'collDesc'])
    expect(missingText(coll)).toBe('Collateral amount and Collateral description')
    expect(profileChecks({ ...base, amount: '5000', collateralRequired: true, collType: '2', collAmount: '80000', collDesc: 'TCT 1234' }).every((c) => c.done)).toBe(true)
  })

  it('lists what is missing in words', () => {
    expect(missingText(profileChecks({ ...base, typeChosen: false }))).toBe('Loan type and Principal')
    expect(missingText(profileChecks({ ...base, amount: '1' }))).toBe('')
  })
})
