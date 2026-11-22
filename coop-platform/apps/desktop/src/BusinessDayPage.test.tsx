import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { BusinessDayState, DayCheck } from './api'
import BusinessDayPage from './BusinessDayPage'
import { BusinessDayChip } from './BusinessDayChip'
import { AppThemeProvider } from './theme'

const state = vi.fn()
const status = vi.fn()
const startCheck = vi.fn()
const start = vi.fn()
const end = vi.fn()
const jobs = vi.fn()
const runJobs = vi.fn()
const drawerRule = vi.fn()
const setRule = vi.fn()
const me = { username: 'ana', name: 'Ana Cruz', roles: [] as string[], admin: false, branchCode: 1 }
vi.mock('./session', () => ({ useMe: () => me }))
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  getBusinessDay: () => state(),
  getBusinessDayStatus: () => status(),
  checkStartDay: (d: string) => startCheck(d),
  startDay: (d: string, c: boolean) => start(d, c),
  endDay: (r: string | null) => end(r),
  getDayJobs: (d?: string) => jobs(d),
  runDayJobs: () => runJobs(),
  getDrawerOpen: () => drawerRule(),
  setDrawerOpenPolicy: (on: boolean, reason: string) => setRule(on, reason),
}))

const step = (key: string, label: string, status: 'NOT RUN' | 'DONE' | 'FAILED', result: string | null = null, error: string | null = null) =>
  ({ key, label, status, attempts: status === 'NOT RUN' ? 0 : 1, finishedAt: status === 'NOT RUN' ? null : '2026-11-16 17:02', result, error })
const notRun = { date: '2026-11-16', available: true, status: 'NOT RUN', startedBy: null, finishedAt: null,
  steps: [step('balances', 'Balance snapshot', 'NOT RUN'), step('loan_aging', 'Loan aging and penalties', 'NOT RUN')] }

const closed = { date: '2026-11-14', words: 'Saturday, 14 November 2026', status: 'CLOSED' as const, openedBy: 'Ana Cruz', openedAt: '2026-11-14 07:58', closedBy: 'Ben Uy', closedAt: '2026-11-14 17:40', overrideReason: null, failedChecks: null }
const base: BusinessDayState = {
  branch: 1, inUse: true, available: true, calendarDate: '2026-11-16', open: null, lastClosed: closed, suggested: '2026-11-16', legacyDate: '2026-04-24',
  mayStart: true, mayEnd: true, mayReopen: false, checks: [], recent: [closed],
}
const openDay = { ...closed, date: '2026-11-16', words: 'Monday, 16 November 2026', status: 'OPEN' as const, closedBy: null, closedAt: null }
const check = (key: string, ok: boolean, mustPass: boolean, detail: string): DayCheck => ({ key, label: key, ok, mustPass, detail })

function show(ui = <BusinessDayPage />) {
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AppThemeProvider>{ui}</AppThemeProvider></QueryClientProvider>)
}

describe('Business Day', () => {
  beforeEach(() => {
    state.mockReset(); status.mockReset(); startCheck.mockReset(); start.mockReset(); end.mockReset(); jobs.mockReset(); runJobs.mockReset(); drawerRule.mockReset(); setRule.mockReset()
    me.admin = false
    jobs.mockResolvedValue(notRun)
    drawerRule.mockResolvedValue({ required: true, policyOn: true, available: true, businessDate: '2026-11-16', drawers: [] })
  })

  it('lists the end-of-day jobs, runs them, and shows a step that failed with its reason', async () => {
    state.mockResolvedValue({ ...base, open: openDay, checks: [check('drawers', true, true, 'ok')] })
    runJobs.mockResolvedValue({ ...notRun, status: 'FAILED', steps: [step('balances', 'Balance snapshot', 'DONE', 'Cash in drawers 12,000.00'), step('loan_aging', 'Loan aging and penalties', 'FAILED', null, 'The database did not answer.')] })
    show()
    const list = await screen.findByRole('list', { name: 'End-of-day jobs' })
    expect(within(list).getAllByRole('listitem')).toHaveLength(2)
    expect(within(list).getByLabelText('Balance snapshot: not run')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Run the jobs now' }))
    expect(await screen.findByText('Cash in drawers 12,000.00')).toBeInTheDocument()
    expect(screen.getByLabelText('Loan aging and penalties: failed')).toHaveTextContent('The database did not answer.')
    expect(runJobs).toHaveBeenCalledOnce()
  })

  it('lets only an administrator switch the open-the-drawer rule, with a reason', async () => {
    state.mockResolvedValue(base)
    show()
    expect(await screen.findByText('Open the drawer before cash')).toBeInTheDocument()
    expect(screen.queryByRole('switch')).not.toBeInTheDocument()
  })

  it('asks an administrator why before the drawer rule is switched off', async () => {
    me.admin = true
    state.mockResolvedValue(base)
    setRule.mockResolvedValue({ required: false, policyOn: false, available: true, businessDate: '2026-11-16', drawers: [] })
    show()
    await userEvent.click(await screen.findByRole('switch'))
    expect(await screen.findByText('Stop requiring the drawer count')).toBeInTheDocument()
    expect(setRule).not.toHaveBeenCalled()
  })

  it('starts the suggested day, after the person confirms what it warns about', async () => {
    state.mockResolvedValue(base)
    startCheck.mockResolvedValue({ problem: null, warnings: ['This opens a past date, 1 day before today.'] })
    start.mockResolvedValue({ ...base, open: openDay, lastClosed: closed, recent: [openDay, closed] })
    show()
    expect(await screen.findByText('No business day is open')).toBeInTheDocument()
    expect(screen.getByText('Posting closed')).toBeInTheDocument()
    expect(screen.getByLabelText('Business date')).toHaveValue('2026-11-16')
    const button = screen.getByRole('button', { name: 'Start Day' })
    expect(await screen.findByText('This opens a past date, 1 day before today.')).toBeInTheDocument()
    expect(button).toBeDisabled()
    await userEvent.click(screen.getByRole('checkbox', { name: /open the day anyway/ }))
    expect(button).toBeEnabled()
    await userEvent.click(button)
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('every deposit, withdrawal, payment and voucher is dated on it')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Start Day' }))
    await waitFor(() => expect(start).toHaveBeenCalledWith('2026-11-16', true))
    expect(await screen.findByText('Open for posting')).toBeInTheDocument()
  })

  it('will not start a date the server refuses', async () => {
    state.mockResolvedValue(base)
    startCheck.mockResolvedValue({ problem: 'The business day must come after the last closed day.', warnings: [] })
    show()
    expect(await screen.findByText('The business day must come after the last closed day.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Start Day' })).toBeDisabled()
  })

  it('shows the End Day checks and asks for a reason while one that must pass fails', async () => {
    state.mockResolvedValue({ ...base, open: openDay, recent: [openDay, closed],
      checks: [check('Drawers closed', false, true, 'Teller 3 has not counted and closed the drawer.'), check('Drafts finished', false, false, '2 drafts are not posted.'), check('Debits equal credits', true, true, 'Every voucher balances.')] })
    end.mockResolvedValue({ ...base, recent: [closed] })
    show()
    const list = await screen.findByRole('list', { name: 'End Day checks' })
    expect(within(list).getByRole('listitem', { name: 'Drawers closed: failed' })).toHaveTextContent('Teller 3')
    expect(within(list).getByRole('listitem', { name: 'Drafts finished: to review' })).toBeInTheDocument()
    expect(within(list).getByRole('listitem', { name: 'Debits equal credits: passed' })).toBeInTheDocument()
    const button = screen.getByRole('button', { name: 'End Day' })
    expect(button).toBeDisabled()
    await userEvent.type(screen.getByLabelText('Reason for ending the day anyway'), 'short')
    expect(button).toBeDisabled()
    await userEvent.type(screen.getByLabelText('Reason for ending the day anyway'), ' count on paper')
    expect(button).toBeEnabled()
    await userEvent.click(button)
    await userEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'End Day' }))
    await waitFor(() => expect(end).toHaveBeenCalledWith('short count on paper'))
  })

  it('ends a day whose checks all pass without a reason', async () => {
    state.mockResolvedValue({ ...base, open: openDay, recent: [openDay], checks: [check('Drawers closed', true, true, 'All closed.')] })
    end.mockResolvedValue({ ...base, recent: [closed] })
    show()
    expect(await screen.findByText('Every check that must pass passes. The day can be ended.')).toBeInTheDocument()
    expect(screen.queryByLabelText('Reason for ending the day anyway')).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: 'End Day' }))
    await userEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'End Day' }))
    await waitFor(() => expect(end).toHaveBeenCalledWith(null))
  })

  it('says who may start the day when the person may not', async () => {
    state.mockResolvedValue({ ...base, mayStart: false })
    show()
    expect(await screen.findByText(/does not have the Start Day right/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Start Day' })).toBeNull()
  })
})

describe('Business day in the title bar', () => {
  beforeEach(() => { status.mockReset() })

  it('says posting is closed while no day is open, and opens Business Day', async () => {
    status.mockResolvedValue({ inUse: true, calendarDate: '2026-11-16', openDate: null, openWords: null })
    const go = vi.fn()
    show(<BusinessDayChip onOpen={go} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Posting closed: no business day is open' }))
    expect(go).toHaveBeenCalledOnce()
  })

  it('names the open day and marks one that is not today', async () => {
    status.mockResolvedValue({ inUse: true, calendarDate: '2026-11-16', openDate: '2026-11-14', openWords: 'Saturday, 14 November 2026' })
    show(<BusinessDayChip onOpen={vi.fn()} />)
    expect(await screen.findByRole('button', { name: 'Business day Saturday, 14 November 2026, not today' })).toBeInTheDocument()
  })

  it('stays out of the way where the business day is not used', async () => {
    status.mockResolvedValue({ inUse: false, calendarDate: '2026-11-16', openDate: null, openWords: null })
    show(<BusinessDayChip onOpen={vi.fn()} />)
    await waitFor(() => expect(status).toHaveBeenCalled())
    expect(screen.queryByRole('button')).toBeNull()
  })
})
