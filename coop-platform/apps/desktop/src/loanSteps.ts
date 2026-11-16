import type { LoanApplicationItem } from './api'

export const LOAN_STEPS = ['Profiling', 'Deduction', 'Verification', 'Approval'] as const

/**
 * The step a waiting application is at (0-based in LOAN_STEPS), or null when its status is none of them. A saved application has always been
 * profiled; without its deductions it waits at Deduction, New waits at Verification, Verified at Approval.
 */
export function currentStep(a: Pick<LoanApplicationItem, 'status' | 'waitingForDeductions'>): number | null {
  if (a.waitingForDeductions) return 1
  if (a.status === 1) return 2
  if (a.status === 2) return 3
  return null
}
