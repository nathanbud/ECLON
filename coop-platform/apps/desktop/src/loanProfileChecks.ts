/**
 * What the Profiling step of a loan application needs before it can go on, for the loan type chosen: the loan type, the principal, the term
 * (blank means the loan type's own term, unless it is counted in another unit), and the collateral's type, amount and description when the
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

export function profileChecks(s: ProfileState): ProfileCheck[] {
  const checks: ProfileCheck[] = [
    { field: 'type', label: 'Loan type', done: s.typeChosen, message: 'Choose the loan type.' },
    { field: 'principal', label: 'Principal', done: Number(s.amount) > 0, message: 'Enter the principal, more than zero.' },
  ]
  if (!s.typeChosen) return checks
  const ownUnit = s.unit === s.typeUnit
  const termOk = s.terms.trim() === '' ? ownUnit : Number(s.terms) > 0
  checks.push({
    field: 'term', label: 'Term', done: termOk,
    message: ownUnit ? 'The term must be more than zero, or blank for the loan type’s own term.'
      : `Enter the term in ${unitName(s.unit)}: the loan type counts it in ${unitName(s.typeUnit)}.`,
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

/** The first field still missing, to put the cursor on; null when all is done. */
export const firstMissing = (checks: ProfileCheck[]) => checks.find((c) => !c.done)?.field ?? null

/** "Principal and Collateral amount", for the summary line. */
export function missingText(checks: ProfileCheck[]): string {
  const m = checks.filter((c) => !c.done).map((c) => c.label)
  return m.length <= 1 ? (m[0] ?? '') : `${m.slice(0, -1).join(', ')} and ${m[m.length - 1]}`
}
