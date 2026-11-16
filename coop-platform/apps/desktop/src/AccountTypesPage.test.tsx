import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AccountTypesPage from './AccountTypesPage'
import type { AccountType, AccountTypeClass, AccountTypeLink, ChartAccount } from './api'
import { AppThemeProvider } from './theme'

const list = vi.fn()
const links = vi.fn()
const update = vi.fn()
const create = vi.fn()
const move = vi.fn()
const chart = vi.fn()
const first = vi.fn()
const addLink = vi.fn()
const linkOptions = vi.fn()
const drawers = vi.fn()
const addDrawer = vi.fn()
const delCheck = vi.fn()
const del = vi.fn()
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  getAccountTypes: () => list(),
  getAccountTypeLinks: (c: number, t: number) => links(c, t),
  updateAccountType: (c: number, t: number, f: unknown) => update(c, t, f),
  createAccountType: (c: number, from: number, f: unknown) => create(c, from, f),
  changeAccountTypeLink: (...a: unknown[]) => move(...a),
  getChart: () => chart(),
  createFirstAccountType: (c: number, f: unknown) => first(c, f),
  addAccountTypeLink: (...a: unknown[]) => addLink(...a),
  getAccountTypeLinkOptions: (c: number) => linkOptions(c),
  getCashDrawers: () => drawers(),
  addCashDrawer: (...a: unknown[]) => addDrawer(...a),
  checkAccountTypeDelete: (c: number, t: number) => delCheck(c, t),
  deleteAccountType: (...a: unknown[]) => del(...a),
}))

const type = (classId: number, code: number, name: string, extra: Partial<AccountType> = {}): AccountType =>
  ({ classId, code, name, fullName: name, priority: 99, interestRate: null, minBalance: null, maxBalance: null, dormantAfterMonths: null, disabled: false, accounts: null, links: 3, ...extra })
const classes: AccountTypeClass[] = [
  { id: 11, name: 'Cash and COCI', managed: 'CASH', fields: [], types: [type(11, 1, 'Cash-on-Hand')] },
  { id: 12, name: 'Loans Receivable', managed: 'LOAN_SETUP', fields: [], types: [type(12, 21, 'Salary Loan')] },
  { id: 22, name: 'Savings Deposit', managed: 'EDIT', fields: ['interestRate', 'minBalance', 'maxBalance', 'dormantAfterMonths'], types: [type(22, 1, 'SD - Regular', { interestRate: 1, minBalance: 500, dormantAfterMonths: 24, accounts: 37705 }), type(22, 2, 'SD- Associate', { interestRate: 0.25, minBalance: 500, accounts: 8467 })] },
  { id: 26, name: 'Accts Payable', managed: 'EDIT', fields: [], types: [type(26, 99, 'Other Payable', { accounts: 12 })] },
  { id: 14, name: 'Misc. Asset', managed: 'EDIT', fields: [], types: [] },
]
const sdLinks: AccountTypeLink[] = [
  { event: 11, what: 'Deposit balance', number: 15, statusName: 'Active', account: '2010101', accountName: 'Savings deposits', accountActive: true, removable: true },
  { event: 75, what: 'Accrued interest payable', number: 99, statusName: null, account: '2011603', accountName: 'Interest payable', accountActive: true, removable: true },
]
const acct = (code: string, name: string, t: number, summary = false): ChartAccount => ({ code, name, type: t, typeName: null, summary, parent: null, disabled: false, glOnly: false, hasChildren: false, inUse: false })

function show() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider><AccountTypesPage /></AppThemeProvider></QueryClientProvider>)
}

describe('Account types', () => {
  beforeEach(() => {
    for (const f of [list, links, update, create, move, chart, first, addLink, linkOptions, drawers, addDrawer, delCheck, del]) f.mockReset()
    list.mockResolvedValue(classes)
    links.mockResolvedValue(sdLinks)
    chart.mockResolvedValue([acct('201', 'DEPOSITS', 2, true), acct('2010101', 'Savings deposits', 2), acct('2010106', 'Savings - Kaya', 2), acct('412', 'Service fee', 4)])
  })

  it('lists the types by class and opens the first one that can be changed, with its ledger accounts', async () => {
    show()
    const nav = await screen.findByRole('navigation', { name: 'Account types by class' })
    expect(await within(nav).findByText('22 Savings Deposit')).toBeInTheDocument()
    expect(within(nav).getByText('14 Misc. Asset')).toBeInTheDocument() // a class with no types is listed, to add its first one
    expect(await screen.findByRole('heading', { name: 'SD - Regular' })).toBeInTheDocument()
    expect(screen.getByText(/37,705 accounts/)).toBeInTheDocument()
    expect(screen.getByLabelText('Interest rate (% a year)')).toHaveValue('1')
    expect(screen.getByLabelText('Minimum balance')).toHaveValue('500')
    expect(screen.getByLabelText('Maximum balance')).toHaveValue('')
    expect(screen.getByLabelText('Dormant after (months)')).toHaveValue('24')
    const table = await screen.findByRole('table', { name: 'Ledger accounts' })
    expect(await within(table).findByText('Deposit balance')).toBeInTheDocument()
    expect(within(table).getByText('Active')).toBeInTheDocument()
    expect(links).toHaveBeenCalledWith(22, 1)
  })

  it('saves a new rate only after a reason is given, saying what changes', async () => {
    update.mockImplementation(async (_c: number, _t: number, f: { interestRate: number }) => type(22, 1, 'SD - Regular', { interestRate: f.interestRate, minBalance: 500 }))
    show()
    const user = userEvent.setup()
    const rate = await screen.findByLabelText('Interest rate (% a year)')
    const save = screen.getByRole('button', { name: 'Save' })
    expect(save).toBeDisabled() // nothing changed yet
    await user.clear(rate)
    await user.type(rate, '1.75')
    await user.click(save)
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Changing: interest rate from 1 to 1.75 percent.')).toBeInTheDocument()
    const confirm = within(dialog).getByRole('button', { name: 'Save' })
    expect(confirm).toBeDisabled()
    await user.type(within(dialog).getByLabelText(/Reason/), 'Board resolution 12')
    await user.click(confirm)
    await waitFor(() => expect(update).toHaveBeenCalledWith(22, 1, { name: 'SD - Regular', fullName: 'SD - Regular', priority: 99, interestRate: 1.75, minBalance: 500, maxBalance: null, dormantAfterMonths: 24, disabled: false, reason: 'Board resolution 12' }))
    expect(await screen.findByText('0001 SD - Regular was saved.')).toBeInTheDocument()
  })

  it('shows loan types for reference only and points to Loan Setup', async () => {
    show()
    const user = userEvent.setup()
    await user.click(await screen.findByText('Salary Loan'))
    expect(await screen.findByText(/Loan types are kept in Settings > Loan Setup/)).toBeInTheDocument()
    expect(screen.getByLabelText(/^Name/)).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'New type' })).not.toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Change' })).not.toBeInTheDocument())
  })

  it('offers only the settings the class has', async () => {
    show()
    await userEvent.click(await screen.findByText('Other Payable'))
    expect(await screen.findByRole('heading', { name: 'Other Payable' })).toBeInTheDocument()
    expect(screen.queryByLabelText('Interest rate (% a year)')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Minimum balance')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Order in lists')).toHaveValue('99')
  })

  it('adds a type as a copy of another, with a reason', async () => {
    create.mockResolvedValue(type(22, 9, 'SD - Youth', { interestRate: 1, minBalance: 500, accounts: 0 }))
    show()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'New type' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByRole('heading', { name: 'New savings deposit type' })).toBeInTheDocument()
    const add = within(dialog).getByRole('button', { name: 'Add type' })
    await user.type(within(dialog).getByLabelText(/^Name/), 'SD - Youth')
    expect(add).toBeDisabled() // no reason yet
    await user.type(within(dialog).getByLabelText(/^Reason/), 'New product')
    await user.click(add)
    await waitFor(() => expect(create).toHaveBeenCalledWith(22, 1, { name: 'SD - Youth', fullName: '', priority: 99, interestRate: 1, minBalance: 500, maxBalance: null, dormantAfterMonths: 24, disabled: false, reason: 'New product' }))
    expect(await screen.findByText(/0009 SD - Youth was added/)).toBeInTheDocument()
  })

  it('points a ledger link only at a posting account of the same kind', async () => {
    move.mockResolvedValue([{ ...sdLinks[0], account: '2010106', accountName: 'Savings - Kaya' }, sdLinks[1]])
    show()
    const user = userEvent.setup()
    const table = await screen.findByRole('table', { name: 'Ledger accounts' })
    await user.click((await within(table).findAllByRole('button', { name: 'Change' }))[0])
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByLabelText(/Post to/))
    const options = (await screen.findAllByRole('option')).map((o) => o.textContent)
    expect(options).toEqual(['2010106 Savings - Kaya']) // not the heading, the income account or the one linked now
    await user.click(screen.getByRole('option', { name: '2010106 Savings - Kaya' }))
    await user.type(within(dialog).getByLabelText(/^Reason/), 'Separate ledger account')
    await user.click(within(dialog).getByRole('button', { name: 'Change account' }))
    await waitFor(() => expect(move).toHaveBeenCalledWith(22, 1, 11, 15, '2010106', 'Separate ledger account'))
    expect(await within(table).findByText('Savings - Kaya')).toBeInTheDocument()
  })

  it('adds the first type of a class that has none, with the account its balance posts to', async () => {
    first.mockResolvedValue(type(14, 1, 'Deposits paid', { links: 1 }))
    show()
    const user = userEvent.setup()
    await user.click(await screen.findByText('No types yet'))
    await user.click(await screen.findByRole('button', { name: 'Add the first type' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText(/^Name/), 'Deposits paid')
    await user.click(within(dialog).getByLabelText(/Balance posts to/))
    await user.click(await screen.findByRole('option', { name: '2010106 Savings - Kaya' }))
    await user.type(within(dialog).getByLabelText(/^Reason/), 'New ledger')
    await user.click(within(dialog).getByRole('button', { name: 'Add type' }))
    await waitFor(() => expect(first).toHaveBeenCalledWith(14, { name: 'Deposits paid', fullName: '', account: '2010106', reason: 'New ledger' }))
  })

  it('adds a link only for a posting the type does not have yet', async () => {
    linkOptions.mockResolvedValue({ events: [{ id: 11, name: 'Deposit balance' }, { id: 75, name: 'Accrued interest payable' }], statuses: [{ id: 15, name: 'Active' }, { id: 16, name: 'Dormant' }] })
    addLink.mockResolvedValue(sdLinks)
    show()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Add a link' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByLabelText(/^Posting/))
    // accrued interest is already linked; the balance is offered for the statuses still missing
    expect((await screen.findAllByRole('option')).map((o) => o.textContent)).toEqual(['Deposit balance'])
    await user.click(screen.getByRole('option', { name: 'Deposit balance' }))
    await user.click(within(dialog).getByLabelText(/For accounts that are/))
    expect((await screen.findAllByRole('option')).map((o) => o.textContent)).toEqual(['Dormant'])
    await user.click(screen.getByRole('option', { name: 'Dormant' }))
    await user.click(within(dialog).getByLabelText(/^Post to/))
    await user.click(await screen.findByRole('option', { name: '2010106 Savings - Kaya' }))
    await user.type(within(dialog).getByLabelText(/^Reason/), 'Dormant savings ledger')
    await user.click(within(dialog).getByRole('button', { name: 'Add link' }))
    await waitFor(() => expect(addLink).toHaveBeenCalledWith(22, 1, 11, 16, '2010106', 'Dormant savings ledger'))
  })

  it('shows the cash drawers and adds one with a free number', async () => {
    drawers.mockResolvedValue([{ event: 53, kind: 'Teller', number: 31, account: '10101', accountName: 'Cash on hand', heldBy: ['Ana Reyes'] }])
    addDrawer.mockResolvedValue([])
    show()
    const user = userEvent.setup()
    await user.click(await screen.findByText('Cash-on-Hand'))
    const table = await screen.findByRole('table', { name: 'Cash drawers' })
    expect(await within(table).findByText('Ana Reyes')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument() // the cash types themselves are fixed
    await user.click(screen.getByRole('button', { name: 'Add a drawer' }))
    const dialog = await screen.findByRole('dialog')
    const confirm = within(dialog).getByRole('button', { name: 'Add drawer' })
    await user.type(within(dialog).getByLabelText(/Drawer number/), '31')
    expect(within(dialog).getByText('That drawer already exists.')).toBeInTheDocument()
    await user.clear(within(dialog).getByLabelText(/Drawer number/))
    await user.type(within(dialog).getByLabelText(/Drawer number/), '32')
    await user.type(within(dialog).getByLabelText(/^Reason/), 'New teller window')
    expect(confirm).toBeEnabled()
    await user.click(confirm)
    await waitFor(() => expect(addDrawer).toHaveBeenCalledWith(53, 32, 'New teller window'))
  })

  it('deletes a type nothing used, with a reason, and offers to switch off one that was used', async () => {
    delCheck.mockResolvedValueOnce({ deletable: false, problems: ['It is in use: 37,705 accounts. Switch it off instead; a type that was used stays so its history can still be read.'], links: 2 })
    show()
    await screen.findByRole('heading', { name: 'SD - Regular' })
    await userEvent.click(screen.getByRole('button', { name: 'Delete type' }))
    const refused = await screen.findByRole('dialog', { name: 'SD - Regular cannot be deleted' })
    expect(refused).toHaveTextContent('37,705 accounts')
    expect(del).not.toHaveBeenCalled()
    await userEvent.click(within(refused).getByRole('button', { name: 'Switch it off instead' }))
    expect(await screen.findByRole('dialog', { name: 'Save SD - Regular' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())

    await userEvent.click(screen.getByRole('button', { name: 'Undo' }))
    delCheck.mockResolvedValueOnce({ deletable: true, problems: [], links: 2 })
    del.mockResolvedValueOnce({ deleted: true, name: 'SD - Regular', links: 2 })
    await userEvent.click(screen.getByRole('button', { name: 'Delete type' }))
    const ask = await screen.findByRole('dialog', { name: 'Delete SD - Regular' })
    expect(within(ask).getByRole('button', { name: 'Delete type' })).toBeDisabled()
    await userEvent.type(within(ask).getByLabelText(/Reason/), 'added by mistake')
    await userEvent.click(within(ask).getByRole('button', { name: 'Delete type' }))
    await waitFor(() => expect(del).toHaveBeenCalledWith(22, 1, 'added by mistake'))
    expect(await screen.findByText(/SD - Regular was deleted, with its 2 ledger links/)).toBeInTheDocument()
  })

  it('offers no delete for cash and loan types', async () => {
    show()
    await screen.findByRole('heading', { name: 'SD - Regular' })
    await userEvent.click(screen.getByText('Cash-on-Hand'))
    await screen.findByRole('heading', { name: 'Cash-on-Hand' })
    expect(screen.queryByRole('button', { name: 'Delete type' })).toBeNull()
  })
})
