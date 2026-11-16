import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { currentStep } from './loanSteps'
import { LoanStepStatus } from './LoanStepStatus'
import { AppThemeProvider } from './theme'

const show = (status: number, statusName: string, waitingForDeductions: boolean) =>
  render(<AppThemeProvider><LoanStepStatus application={{ status, statusName, waitingForDeductions }} /></AppThemeProvider>)

describe('LoanStepStatus', () => {
  it('works out the step an application waits at', () => {
    expect(currentStep({ status: 1, waitingForDeductions: true })).toBe(1)
    expect(currentStep({ status: 1, waitingForDeductions: false })).toBe(2)
    expect(currentStep({ status: 2, waitingForDeductions: false })).toBe(3)
    expect(currentStep({ status: 5, waitingForDeductions: false })).toBeNull()
  })

  it('names the step it waits at and the steps done', () => {
    show(2, 'Verified', false)
    expect(screen.getByRole('img', { name: 'At step 4 of 4: Approval. Done: Profiling, Deduction, Verification.' })).toHaveTextContent('Approval')
  })

  it('shows an application waiting for deductions at step 2', () => {
    show(1, 'New', true)
    expect(screen.getByRole('img', { name: 'At step 2 of 4: Deduction. Done: Profiling.' })).toBeInTheDocument()
  })

  it('falls back to the status word outside the steps', () => {
    show(5, 'Disapproved', false)
    expect(screen.queryByRole('img')).toBeNull()
    expect(screen.getByText('Disapproved')).toBeInTheDocument()
  })
})
