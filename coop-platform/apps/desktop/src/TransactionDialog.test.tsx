import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { TransactionDetail } from './api'
import TransactionDialog, { glCode, txKeyOf, txRowProps } from './TransactionDialog'
import { AppThemeProvider } from './theme'

const detail: TransactionDetail = {
  header: {
    year: 2026, module: 2, code: 20, ctlNo: 1999, type: 'Loan Voucher', typeShort: 'LV', controlNo: '2026 - 00001999', docNo: '00001999', date: '2026-01-28', time: '16:06:30', batchNo: '20260128',
    department: 'MAIN OFFICE', client: 'Osting, Samson M.', clientNo: '0001-0028711-9', andOr: null, sourceReference: null, explanation: 'per Approved Salary Loan of Osting, Samson Mallare', status: 'APPROVED',
  },
  lines: [
    { seq: 1, posted: true, sc: 12, st: 21, se: 11, sn: 11, glCode: '10110129', title: 'Salary Loan : Principal', debit: 1129000, credit: 0, clientNo: '0001-0028711-9', clientName: 'Osting, Samson M.' },
    { seq: 2, posted: true, sc: null, st: null, se: null, sn: null, glCode: '2010503', title: 'Insurance payable - climbs', debit: 0, credit: 1129000, clientNo: '0001-0028711-9', clientName: 'Osting, Samson M.' },
  ],
  cheques: [{ seq: 1, type: 'Check', number: '000000000923606', bank: 'BPI', date: '2026-04-20', amount: 4460, payee: null, cleared: true, clearedOn: '2026-04-24' }],
  debit: 1129000, credit: 1129000, difference: 0,
}

vi.mock('./api', async (orig) => ({ ...(await orig<typeof import('./api')>()), getTransaction: vi.fn(async () => detail) }))

function renderDialog(onClose = vi.fn()) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider><TransactionDialog id={{ branchCode: 1, clientId: 28711 }} tx={{ year: 2026, code: 20, ctlNo: 1999 }} onClose={onClose} /></AppThemeProvider></QueryClientProvider>)
  return onClose
}

describe('Transaction dialog', () => {
  it('shows the header, the status and the ledger lines with their codes and totals', async () => {
    renderDialog()
    const dialog = await screen.findByRole('dialog')
    expect(await within(dialog).findByText('APPROVED')).toBeInTheDocument()
    expect(within(dialog).getByText(/Loan Voucher \(LV\)/)).toBeInTheDocument()
    expect(within(dialog).getByText('2026 - 00001999')).toBeInTheDocument()
    expect(within(dialog).getByText('01/28/2026')).toBeInTheDocument()
    expect(within(dialog).getByText('MAIN OFFICE')).toBeInTheDocument()
    expect(within(dialog).getByText('101.10.12.9')).toBeInTheDocument()
    expect(within(dialog).getByText('201.05.03')).toBeInTheDocument()
    expect(within(dialog).getByText('Salary Loan : Principal')).toBeInTheDocument()
    expect(within(dialog).getByText('Insurance payable - climbs')).toBeInTheDocument()
    expect(within(dialog).getByText(/per Approved Salary Loan/)).toBeInTheDocument()
    expect(within(dialog).getAllByText('1,129,000.00').length).toBeGreaterThanOrEqual(3)
  })

  it('shows the cheques on the COCI Details tab and closes with the Close button', async () => {
    const onClose = renderDialog()
    const user = userEvent.setup()
    const dialog = await screen.findByRole('dialog')
    await user.click(await within(dialog).findByRole('tab', { name: /COCI Details/ }))
    expect(within(dialog).getByText('000000000923606')).toBeInTheDocument()
    expect(within(dialog).getByText('Cleared 04/24/2026')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Close' }))
    expect(onClose).toHaveBeenCalled()
  })
})

describe('opening a transaction from a ledger row', () => {
  it('formats a ledger account the way the old system prints it', () => {
    expect(glCode('10110129')).toBe('101.10.12.9')
    expect(glCode('2010503')).toBe('201.05.03')
    expect(glCode('41137')).toBe('411.37')
    expect(glCode(null)).toBe('')
  })

  it('names the transaction from the year of the date, the code and the control number', () => {
    expect(txKeyOf({ date: '2026-01-28', transactionCode: 20, controlNo: 1999 })).toEqual({ year: 2026, code: 20, ctlNo: 1999 })
    expect(txKeyOf({ date: '2026-01-28', transactionCode: 20, controlNo: null })).toBeNull()
  })

  it('makes a row clickable only when it carries a control number', () => {
    const open = vi.fn()
    expect(txRowProps({ date: '2026-01-28', transactionCode: 20 }, open)).toEqual({})
    const p = txRowProps({ date: '2026-01-28', transactionCode: 20, controlNo: 1999 }, open) as { onClick: () => void; tabIndex: number }
    p.onClick()
    expect(open).toHaveBeenCalledWith({ year: 2026, code: 20, ctlNo: 1999 })
    expect(p.tabIndex).toBe(0)
  })
})
