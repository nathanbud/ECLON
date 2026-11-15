import { describe, expect, it } from 'vitest'
import { firstMissing, missingText, mostTerm, profileChecks, termLimitText, type ProfileState } from './loanProfileChecks'

const base: ProfileState = { typeChosen: true, amount: '', terms: '12', unit: 4, typeUnit: 4, typeTerms: 12, collateralRequired: false, collType: '', collAmount: '', collDesc: '' }

describe('profileChecks', () => {
  it('needs the loan type first, then the principal', () => {
    const none = profileChecks({ ...base, typeChosen: false })
    expect(none.map((c) => c.field)).toEqual(['type', 'principal'])
    expect(firstMissing(none)).toBe('type')
    expect(firstMissing(profileChecks(base))).toBe('principal')
  })

  it('needs a term no longer than the loan type allows, in either unit', () => {
    const term = (st: Partial<ProfileState>) => profileChecks({ ...base, amount: '5000', ...st }).find((c) => c.field === 'term')!
    expect(term({}).done).toBe(true)
    expect(term({ terms: '6' }).done).toBe(true)
    expect(term({ terms: '' })).toMatchObject({ done: false, message: 'Enter the term in whole months, up to 12.' })
    expect(term({ terms: '13' })).toMatchObject({ done: false, message: 'This loan type allows at most 12 months.' })
    expect(term({ terms: '1.5' }).done).toBe(false)
    expect(term({ unit: 7, terms: '1' }).done).toBe(true)
    expect(term({ unit: 7, terms: '2' })).toMatchObject({ done: false, message: 'This loan type allows at most 12 months (1 year).' })
  })

  it('works out the longest term in the unit chosen', () => {
    expect(mostTerm(4, 4, 36)).toBe(36)
    expect(mostTerm(7, 4, 36)).toBe(3)
    expect(mostTerm(7, 4, 30)).toBe(2)
    expect(mostTerm(4, 7, 10)).toBe(120)
    expect(mostTerm(4, 4, 0)).toBe(0)
    expect(termLimitText(4, 7, 10)).toBe('This loan type allows at most 10 years (120 months).')
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
