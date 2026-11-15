import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChartImportPreview } from './api'
import ChartUpload from './ChartUpload'
import { AppThemeProvider } from './theme'

const preview = vi.fn()
const upload = vi.fn()
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  previewChartImport: (f: File) => preview(f),
  uploadChartImport: (f: File, reason: string, skip: boolean) => upload(f, reason, skip),
}))

const checked = (errors: boolean): ChartImportPreview => ({
  fileName: 'accounts.xlsx', total: errors ? 4 : 3, ready: 2, existing: 1, errors: errors ? 1 : 0, ignoredColumns: ['Remarks'],
  rows: [
    { line: 2, code: '100097', name: 'New group', state: 'NEW', note: 'New heading under 1000 CASH ON HAND' },
    { line: 3, code: '1000971', name: 'New account', state: 'NEW', note: 'New account under 100097 New group' },
    { line: 4, code: '10001', name: 'Cash in vault', state: 'EXISTS', note: 'Already in the chart. Left as it is.' },
    ...(errors ? [{ line: 5, code: '9001', name: 'Bad', state: 'PROBLEM' as const, note: 'The account number is digits only, up to 10, and starts with 1 to 6.' }] : []),
  ],
})

const file = new File(['x'], 'accounts.xlsx')

async function choose(onDone = vi.fn(), onClose = vi.fn()) {
  render(<AppThemeProvider><ChartUpload onClose={onClose} onDone={onDone} /></AppThemeProvider>)
  await userEvent.upload(screen.getByLabelText('List of accounts'), file)
  await screen.findByRole('table', { name: 'Checked rows' })
  return { onDone, onClose }
}

describe('Chart of accounts: adding accounts from a list', () => {
  beforeEach(() => { preview.mockReset(); upload.mockReset() })

  it('shows what each row will do and adds the new accounts once a reason is given', async () => {
    preview.mockResolvedValue(checked(false))
    upload.mockResolvedValue({ fileName: 'accounts.xlsx', added: 2, existing: 1, leftOut: 0 })
    const { onDone } = await choose()
    expect(screen.getByText('2 new')).toBeInTheDocument()
    expect(screen.getByText('1 already in the chart')).toBeInTheDocument()
    expect(screen.getByText('Columns not used: Remarks.')).toBeInTheDocument()
    expect(within(screen.getByRole('table', { name: 'Checked rows' })).getByText('New heading under 1000 CASH ON HAND')).toBeInTheDocument()
    const add = screen.getByRole('button', { name: 'Add 2 accounts to the chart' })
    expect(add).toBeDisabled() // no reason yet
    await userEvent.type(screen.getByLabelText(/Reason for adding these accounts/), 'New product line')
    await userEvent.click(add)
    await waitFor(() => expect(onDone).toHaveBeenCalledWith('2 accounts added from accounts.xlsx'))
    expect(upload).toHaveBeenCalledWith(file, 'New product line', false)
  })

  it('does not add a list with problems until those rows are left out', async () => {
    preview.mockResolvedValue(checked(true))
    upload.mockResolvedValue({ fileName: 'accounts.xlsx', added: 2, existing: 1, leftOut: 1 })
    await choose()
    await userEvent.type(screen.getByLabelText(/Reason for adding these accounts/), 'Year-end')
    const add = screen.getByRole('button', { name: 'Add 2 accounts to the chart' })
    expect(add).toBeDisabled()
    await userEvent.click(screen.getByLabelText('Show only problems'))
    expect(screen.queryByText('New group')).not.toBeInTheDocument()
    expect(screen.getByText('Bad')).toBeInTheDocument()
    await userEvent.click(screen.getByLabelText('Leave out the 1 row with problems'))
    await userEvent.click(add)
    await waitFor(() => expect(upload).toHaveBeenCalledWith(file, 'Year-end', true))
  })

  it('says why a file was refused', async () => {
    preview.mockRejectedValue(new Error('The file has a header but no accounts.'))
    render(<AppThemeProvider><ChartUpload onClose={vi.fn()} onDone={vi.fn()} /></AppThemeProvider>)
    await userEvent.upload(screen.getByLabelText('List of accounts'), file)
    expect(await screen.findByText('The file has a header but no accounts.')).toBeInTheDocument()
    expect(screen.queryByRole('table', { name: 'Checked rows' })).not.toBeInTheDocument()
  })
})
