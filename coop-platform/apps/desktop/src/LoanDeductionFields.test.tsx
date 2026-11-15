import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import type { LoanAppTemplate } from './api'
import LoanDeductionFields, { type DeductionAmounts, type Payoffs } from './LoanDeductionFields'
import { AppThemeProvider } from './theme'

const t: Pick<LoanAppTemplate, 'payoffs' | 'deductions'> = {
  deductions: [
    { id: 1, description: 'Service fee', behavior: 1, formula: null, needsMemberAccount: false, hasMemberAccount: false },
    { id: 2, description: 'Share capital', behavior: 1, formula: null, needsMemberAccount: true, hasMemberAccount: true },
  ],
  payoffs: [{ typeCode: 48, typeName: 'Back to back Loan', refNo: 7, status: 11, outstanding: 2000, interestDue: 50, daysPastDue: 0 }],
} as Pick<LoanAppTemplate, 'payoffs' | 'deductions'>

function Form() {
  const [ded, setDed] = useState<DeductionAmounts>({})
  const [paid, setPaid] = useState<Payoffs>({})
  return <AppThemeProvider><LoanDeductionFields t={t} amount={10000} ded={ded} setDed={setDed} paid={paid} setPaid={setPaid} /></AppThemeProvider>
}

describe('Deductions and pay off', () => {
  it('groups the deduction lines and the pay-offs into two tabs, with the total and net proceeds below', async () => {
    render(<Form />)
    const group = screen.getByRole('region', { name: 'Deductions and pay off' })
    expect(within(group).getByRole('tab', { name: /^Deductions/ })).toHaveAttribute('aria-selected', 'true')
    expect(within(group).getByRole('tab', { name: /Pay off \(1\)/ })).toBeInTheDocument()

    await userEvent.type(screen.getByRole('textbox', { name: 'Service fee' }), '250')
    expect(within(group).getByRole('tab', { name: /^Deductions/ })).toHaveTextContent('250.00')
    expect(screen.getByText(/Net proceeds/)).toHaveTextContent('Deductions 250.00 · Net proceeds 9,750.00')

    await userEvent.click(within(group).getByRole('tab', { name: /Pay off/ }))
    expect(screen.queryByRole('textbox', { name: 'Service fee' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('checkbox', { name: 'Pay off Back to back Loan 7' }))
    expect(within(group).getByRole('tab', { name: /Pay off/ })).toHaveTextContent('2,050.00')
    expect(screen.getByText(/Net proceeds/)).toHaveTextContent('Deductions 2,300.00 · Net proceeds 7,700.00')

    // what was typed stays when going back
    await userEvent.click(within(group).getByRole('tab', { name: /^Deductions/ }))
    expect((screen.getByRole('textbox', { name: 'Service fee' }) as HTMLInputElement).value).toMatch(/^250(\.00)?$/)
  })

  it('says when there is nothing to pay off', async () => {
    render(<AppThemeProvider><LoanDeductionFields t={{ ...t, payoffs: [] }} amount={10000} ded={{}} setDed={() => {}} paid={{}} setPaid={() => {}} /></AppThemeProvider>)
    await userEvent.click(screen.getByRole('tab', { name: 'Pay off' }))
    expect(screen.getByText('The member has no earlier loans to pay off.')).toBeInTheDocument()
  })
})
