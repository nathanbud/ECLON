import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { DormancyRow } from './api'
import SavingsDormancyPage from './SavingsDormancyPage'
import { AppThemeProvider } from './theme'

const review = vi.fn()
const mark = vi.fn()
const find = vi.fn()
const reactivate = vi.fn()
let me = { admin: true, roles: ['ROLE_ADMIN'] }
vi.mock('./session', () => ({ useMe: () => me }))
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  getDormancyReview: () => review(),
  markDormant: (a: unknown, r: string) => mark(a, r),
  findDormant: (q: string) => find(q),
  reactivateDormant: (k: unknown, r: string) => reactivate(k, r),
}))

const row = (clientId: number, name: string, refNo: number, last: string | null): DormancyRow =>
  ({ clientId, clientName: name, typeCode: 1, typeName: 'SD - Regular', refNo, accountNo: `22-${refNo}`, opened: '2015-03-01', lastActivity: last, balance: 812.5 })

function show() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider><SavingsDormancyPage /></AppThemeProvider></QueryClientProvider>)
}

describe('Savings dormancy', () => {
  beforeEach(() => {
    for (const f of [review, mark, find, reactivate]) f.mockReset()
    me = { admin: true, roles: ['ROLE_ADMIN'] }
    review.mockResolvedValue({ asOf: '2026-11-13', types: [{ typeCode: 1, typeName: 'SD - Regular', months: 24 }], rows: [row(7, 'Santos, Maria', 3, '2020-01-05'), row(9, 'Reyes, Ana', 4, null)], more: false })
  })

  it('lists the accounts due and marks the chosen ones with a reason', async () => {
    mark.mockResolvedValue({ marked: 1, skipped: [] })
    show()
    const user = userEvent.setup()
    expect(await screen.findByText(/SD - Regular 24 months/)).toBeInTheDocument()
    const table = screen.getByRole('table', { name: 'Accounts due to go dormant' })
    expect(within(table).getByText('Never')).toBeInTheDocument()
    await user.click(within(table).getByText('Santos, Maria'))
    await user.click(screen.getByRole('button', { name: 'Mark 1 dormant' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText(/Reason/), 'Yearly review')
    await user.click(within(dialog).getByRole('button', { name: 'Mark dormant' }))
    await waitFor(() => expect(mark).toHaveBeenCalledWith([{ clientId: 7, typeCode: 1, refNo: 3 }], 'Yearly review'))
    expect(await screen.findByText('1 account was marked dormant.')).toBeInTheDocument()
  })

  it('lets a Membership Officer reactivate but not mark', async () => {
    me = { admin: false, roles: ['ROLE_MEMBERSHIP_OFFICER'] }
    find.mockResolvedValue([row(7, 'Santos, Maria', 3, '2020-01-05')])
    reactivate.mockResolvedValue(row(7, 'Santos, Maria', 3, '2020-01-05'))
    show()
    const user = userEvent.setup()
    expect(screen.queryByRole('table', { name: 'Accounts due to go dormant' })).not.toBeInTheDocument()
    expect(review).not.toHaveBeenCalled()
    await user.type(screen.getByPlaceholderText(/Member number/), '7')
    const table = await screen.findByRole('table', { name: 'Dormant accounts' })
    await user.click(await within(table).findByRole('button', { name: 'Reactivate' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText(/Reason/), 'Member came to deposit')
    await user.click(within(dialog).getByRole('button', { name: 'Reactivate' }))
    await waitFor(() => expect(reactivate).toHaveBeenCalledWith({ clientId: 7, typeCode: 1, refNo: 3 }, 'Member came to deposit'))
  })
})
