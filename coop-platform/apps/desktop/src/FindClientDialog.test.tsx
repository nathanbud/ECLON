import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { Member, Page } from './api'
import FindClientDialog, { FindBody } from './FindClientDialog'
import { AppThemeProvider } from './theme'

const member = (id: number, last: string, first: string): Member => ({
  id: { branchCode: 1, clientId: id }, firstName: first, lastName: last, accountName: null, dateOpened: null, accountStatus: 1, cellNumber: null, city: 'Baguio', closedDate: null,
  updatedAt: null, middleName: null, suffix: null, clientType: 1, dateOfBirth: null, gender: null, civilStatus: null, email: null, street: null, barangay: null, province: null,
  referenceId: null, accountType: null, alertLevel: null, department: null, profession: null, religion: null, mailingAddress: null, dosriTag: null, includeHealthCare: null,
  monthlyIncome: null, zipCode: null, residenceSince: null, residenceType: null, provStreet: null, provBarangay: null, provCity: null, provProvince: null, provZipCode: null, houseNo: null, subdivision: null, provHouseNo: null, provSubdivision: null,
})

vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  searchMembers: vi.fn(async (q: string, _page: number, _size: number, includeInactive = false): Promise<Page<Member>> => {
    const all = [member(100, 'Budikey', 'Nathaniel'), ...(includeInactive ? [{ ...member(102, 'Budikey', 'Old'), accountStatus: 3 }] : []), member(101, 'Budiman', 'Ana')]
    const content = all.filter((m) => m.lastName!.toLowerCase().includes(q.toLowerCase()))
    return { content, totalElements: content.length, number: 0, size: 8, last: q !== 'budi' }
  }),
}))

function renderFind(onPick = vi.fn()) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider><FindBody onPick={onPick} inline /></AppThemeProvider></QueryClientProvider>)
  return onPick
}

describe('Find Client', () => {
  it('invites a search before anything is typed', () => {
    renderFind()
    expect(screen.getByText('Who are you looking for?')).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: /client name or member id/i })).toHaveFocus()
  })

  it('lists matches, highlights what was typed and opens the highlighted one with Enter', async () => {
    const onPick = renderFind()
    const user = userEvent.setup()
    await user.type(screen.getByRole('combobox'), 'budik')
    const option = await screen.findByRole('option', { name: /budikey/i })
    expect(option).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: /budiman/i })).not.toBeInTheDocument()
    await user.keyboard('{Enter}')
    expect(onPick).toHaveBeenCalledWith({ branchCode: 1, clientId: 100 })
  })

  it('says when nothing matches and clears with the clear button or Escape', async () => {
    renderFind()
    const user = userEvent.setup()
    const box = screen.getByRole('combobox')
    await user.type(box, 'zzzz')
    expect(await screen.findByText('No clients found')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /clear search/i }))
    expect(box).toHaveValue('')
    await user.type(box, 'bud')
    await waitFor(() => expect(screen.getAllByRole('option').length).toBe(2))
    await user.keyboard('{Escape}')
    expect(box).toHaveValue('')
  })

  it('says there are more matches, without a count, when the search stopped at a full page', async () => {
    renderFind()
    const user = userEvent.setup()
    await user.type(screen.getByRole('combobox'), 'budi')
    expect(await screen.findByText('First 2 matches')).toBeInTheDocument()
    expect(screen.getByText(/there are more matches/i)).toBeInTheDocument()
  })

  it('lists active clients only until Include inactive is turned on', async () => {
    renderFind()
    const user = userEvent.setup()
    await user.type(screen.getByRole('combobox'), 'budik')
    await waitFor(() => expect(screen.getAllByRole('option').length).toBe(1))
    await user.click(screen.getByRole('switch', { name: /include inactive/i }))
    await waitFor(() => expect(screen.getAllByRole('option').length).toBe(2))
  })
})

describe('Find Client pop-up (Ctrl+F)', () => {
  it('puts the cursor in the search field when it opens, even if a button had focus', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { rerender } = render(
      <QueryClientProvider client={qc}><AppThemeProvider><button>Edit</button><FindClientDialog open={false} onClose={() => {}} onPick={() => {}} /></AppThemeProvider></QueryClientProvider>,
    )
    screen.getByRole('button', { name: 'Edit' }).focus()
    rerender(
      <QueryClientProvider client={qc}><AppThemeProvider><button>Edit</button><FindClientDialog open onClose={() => {}} onPick={() => {}} /></AppThemeProvider></QueryClientProvider>,
    )
    const field = await screen.findByRole('combobox', { name: /client name or member id/i })
    await waitFor(() => expect(field).toHaveFocus())
  })
})
