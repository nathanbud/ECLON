import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { PassbookDetail } from './api'
import PassbookPanel from './PassbookPanel'
import { AppThemeProvider } from './theme'

const accounts = vi.fn()
const detail = vi.fn()
const nextPage = vi.fn()
const confirm = vi.fn()
const printPdf = vi.fn(() => Promise.resolve('printing'))
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  getLookups: () => Promise.resolve({}),
  getPassbookAccounts: () => accounts(),
  getPassbook: () => detail(),
  getPassbookNextPage: () => nextPage(),
  confirmPassbookPage: (...a: unknown[]) => confirm(...a),
}))
vi.mock('./ui/printPdf', () => ({ printPdf: () => printPdf() }))
vi.mock('./MemberSearchBox', () => ({ default: ({ onPick }: { onPick: (id: { branchCode: number; clientId: number }) => void }) => <button onClick={() => onPick({ branchCode: 1, clientId: 23 })}>Pick member</button> }))
vi.mock('./session', () => ({ useMe: () => ({ username: 'teller', name: 'Teller', roles: [], admin: false, branchCode: 1 }) }))

const line = (n: number, seq: number | null, extra: Partial<PassbookDetail['pages'][0]['lines'][0]> = {}) =>
  ({ line: n, seq, date: '11/02/26', reference: `*00${seq}`, deposit: '500.00', withdrawal: '', balance: '1,500.00', tc: 'CR', teller: '7', ...extra })
const base: PassbookDetail = {
  clientId: 23, clientName: 'Cruz, Ana', typeCode: 1, typeName: 'SD - Regular', refNo: 17, balance: 1500,
  position: { serial: '00008567', lastLine: 29, lastSeq: 76, lastDate: '2026-04-10', balance: '1,000.00' }, waiting: 3,
  pages: [{ number: 1, newPage: false, firstLine: 30, lastLine: 31, lastSeq: 78, lines: [line(30, 77), line(31, 78)] }, { number: 2, newPage: true, firstLine: 1, lastLine: 1, lastSeq: 79, lines: [line(1, 79)] }],
  warnings: [], linesPerPage: 31, mayCorrect: false, history: [],
}

function show() {
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AppThemeProvider><PassbookPanel /></AppThemeProvider></QueryClientProvider>)
}

describe('Passbook printing', () => {
  beforeEach(() => {
    accounts.mockReset(); detail.mockReset(); nextPage.mockReset(); confirm.mockReset(); printPdf.mockClear()
    accounts.mockResolvedValue([{ typeCode: 1, typeName: 'SD - Regular', refNo: 17, balance: 1500, serial: '00008567', lastLine: 29, lastDate: '2026-04-10', waiting: 3 }])
    nextPage.mockResolvedValue(new Blob(['%PDF']))
  })

  it('shows the lines the next page prints, prints it, and moves on only when the teller says it printed right', async () => {
    detail.mockResolvedValue(base)
    confirm.mockResolvedValue({ ...base, position: { ...base.position!, lastLine: 31, lastSeq: 78 }, pages: [{ ...base.pages[1] }] })
    show()
    await userEvent.click(screen.getByRole('button', { name: 'Pick member' }))
    expect(await screen.findByText('3 to print')).toBeInTheDocument()
    expect(await screen.findByText(/Page 1 of 2: lines 30 to 31, on the page in use/)).toBeInTheDocument()
    const preview = screen.getByRole('table', { name: 'Lines to print' })
    expect(within(preview).getAllByRole('row')).toHaveLength(3)
    expect(preview).toHaveTextContent('*0077')
    await userEvent.click(screen.getByRole('button', { name: 'Print page (2 lines)' }))
    await waitFor(() => expect(printPdf).toHaveBeenCalledOnce())
    expect(confirm).not.toHaveBeenCalled()
    expect(await screen.findByText(/Did all 2 lines print on lines 30 to 31/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Yes, printed right' }))
    await waitFor(() => expect(confirm).toHaveBeenCalledWith(23, 1, 17, 76, 29, 78))
    expect(await screen.findByText(/on a fresh page/)).toBeInTheDocument()
    expect(screen.getByText(/The page in use is full. Turn to the next page/)).toBeInTheDocument()
  })

  it('does not move the passbook when the page did not print', async () => {
    detail.mockResolvedValue(base)
    show()
    await userEvent.click(screen.getByRole('button', { name: 'Pick member' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Print page (2 lines)' }))
    await userEvent.click(await screen.findByRole('button', { name: 'No, print again' }))
    expect(confirm).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Print page (2 lines)' })).toBeInTheDocument()
  })

  it('says the passbook is up to date, and that an account without one needs the head cashier', async () => {
    detail.mockResolvedValue({ ...base, pages: [], waiting: 0 })
    show()
    await userEvent.click(screen.getByRole('button', { name: 'Pick member' }))
    expect(await screen.findByText('The passbook is up to date. Nothing is waiting to be printed.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'New passbook' })).toBeNull()
  })
})
