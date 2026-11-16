/**
 * What the Profiling step of a loan application needs before it can go on, for the loan type chosen: the loan type, the principal, the term
 * (filled in with the loan type's own term, which is also the longest it allows), and the collateral's type, amount and description when the
 * loan type's setup requires collateral. The form shows these as a checklist, marks the fields, and points at the first one missing.
 * Pure, so it can be tested.
 */
export type ProfileField = 'type' | 'principal' | 'term' | 'collType' | 'collAmount' | 'collDesc'

export interface ProfileCheck { field: ProfileField; label: string; done: boolean; message: string }

export interface ProfileState {
  typeChosen: boolean
  amount: string
  terms: string
  /** The unit the clerk counts the term in, and the loan type's own (4 months, 7 years). */
  unit: number
  typeUnit: number
  typeTerms: number
  collateralRequired: boolean
  collType: string
  collAmount: string
  collDesc: string
}

const unitName = (u: number) => (u === 7 ? 'years' : 'months')
/** "36 months", "1 year". */
const count = (n: number, u: number) => `${n} ${n === 1 ? (u === 7 ? 'year' : 'month') : unitName(u)}`

export function profileChecks(s: ProfileState): ProfileCheck[] {
  const checks: ProfileCheck[] = [
    { field: 'type', label: 'Loan type', done: s.typeChosen, message: 'Choose the loan type.' },
    { field: 'principal', label: 'Principal', done: Number(s.amount) > 0, message: 'Enter the principal, more than zero.' },
  ]
  if (!s.typeChosen) return checks
  const most = mostTerm(s.unit, s.typeUnit, s.typeTerms)
  const n = Number(s.terms)
  const typed = s.terms.trim() !== ''
  const over = typed && most > 0 && n > most
  checks.push({
    field: 'term', label: 'Term', done: typed && Number.isInteger(n) && n > 0 && !over,
    message: over ? termLimitText(s.unit, s.typeUnit, s.typeTerms)
      : `Enter the term in whole ${unitName(s.unit)}${most > 0 ? `, up to ${most}` : ''}.`,
  })
  if (s.collateralRequired) {
    checks.push(
      { field: 'collType', label: 'Collateral type', done: s.collType !== '', message: 'This loan type needs collateral: choose its type.' },
      { field: 'collAmount', label: 'Collateral amount', done: Number(s.collAmount) > 0, message: 'Enter the collateral’s value, more than zero.' },
      { field: 'collDesc', label: 'Collateral description', done: s.collDesc.trim() !== '', message: 'Describe the collateral (what it is, its papers).' },
    )
  }
  return checks
}

/**
 * The longest term the loan type allows, counted in the unit the clerk chose: the loan type's own term is the most (36 months is 3 years; 10 years is
 * 120 months). In years, only whole years count. 0 when the loan type has no term.
 */
export function mostTerm(unit: number, typeUnit: number, typeTerms: number): number {
  if (!(typeTerms > 0)) return 0
  const months = typeUnit === 7 ? typeTerms * 12 : typeTerms
  return unit === 7 ? Math.floor(months / 12) : months
}

/** Says the limit: "This loan type allows at most 36 months." (with the other unit too when the clerk counts in it). */
export function termLimitText(unit: number, typeUnit: number, typeTerms: number): string {
  const own = count(typeTerms, typeUnit)
  return unit === typeUnit ? `This loan type allows at most ${own}.` : `This loan type allows at most ${own} (${count(mostTerm(unit, typeUnit, typeTerms), unit)}).`
}

/** The first field still missing, to put the cursor on; null when all is done. */
export const firstMissing = (checks: ProfileCheck[]) => checks.find((c) => !c.done)?.field ?? null

/** "Principal and Collateral amount", for the summary line. */
export function missingText(checks: ProfileCheck[]): string {
  const m = checks.filter((c) => !c.done).map((c) => c.label)
  return m.length <= 1 ? (m[0] ?? '') : `${m.slice(0, -1).join(', ')} and ${m[m.length - 1]}`
}
