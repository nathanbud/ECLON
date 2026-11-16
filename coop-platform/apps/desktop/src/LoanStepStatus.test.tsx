import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { LoanApplicationItem } from './api'
import { currentStep } from './loanSteps'
import { LoanStepStatus } from './LoanStepStatus'
import { AppThemeProvider } from './theme'

type A = Pick<LoanApplicationItem, 'status' | 'statusName' | 'waitingForDeductions' | 'releaseStatus' | 'releasedOn'>
const show = (a: Partial<A> & Pick<A, 'status'>) =>
  render(<AppThemeProvider><LoanStepStatus application={{ statusName: 'New', waitingForDeductions: false, ...a }} /></AppThemeProvider>)

describe('LoanStepStatus', () => {
  it('works out the step an application is at, release included', () => {
    expect(currentStep({ status: 1, waitingForDeductions: true })).toBe(1)
    expect(currentStep({ status: 1, waitingForDeductions: false })).toBe(2)
    expect(currentStep({ status: 2, waitingForDeductions: false })).toBe(3)
    expect(currentStep({ status: 3, waitingForDeductions: false, releaseStatus: 'WAITING FOR APPROVAL' })).toBe(4)
    expect(currentStep({ status: 3, waitingForDeductions: false })).toBe(4) // an older server that does not say
    expect(currentStep({ status: 3, waitingForDeductions: false, releaseStatus: 'POSTED' })).toBe(5)
    expect(currentStep({ status: 5, waitingForDeductions: false })).toBeNull()
  })

  it('names the step it waits at and the steps done', () => {
    show({ status: 2, statusName: 'Verified' })
    expect(screen.getByRole('img', { name: 'At step 4 of 5: Approval. Done: Profiling, Deduction, Verification.' })).toHaveTextContent('Approval')
  })

  it('shows an application waiting for deductions at step 2', () => {
    show({ status: 1, waitingForDeductions: true })
    expect(screen.getByRole('img', { name: 'At step 2 of 5: Deduction. Done: Profiling.' })).toBeInTheDocument()
  })

  it('shows an approved application at Loan Release with where the voucher stands', () => {
    show({ status: 3, statusName: 'Approved', releaseStatus: 'APPROVED' })
    expect(screen.getByRole('img', { name: /^At step 5 of 5: Loan Release \(Release voucher approved, waiting to be posted in Front Office\)\. Done: Profiling, Deduction, Verification, Approval\.$/ }))
      .toHaveTextContent('Loan Release')
  })

  it('checks every step once released, with the day', () => {
    show({ status: 3, statusName: 'Approved', releaseStatus: 'POSTED', releasedOn: '2026-11-14' })
    expect(screen.getByRole('img', { name: 'Released on 2026-11-14. All 5 steps done.' })).toHaveTextContent('Released')
  })

  it('falls back to the status word outside the steps', () => {
    show({ status: 5, statusName: 'Disapproved' })
    expect(screen.queryByRole('img')).toBeNull()
    expect(screen.getByText('Disapproved')).toBeInTheDocument()
  })
})
