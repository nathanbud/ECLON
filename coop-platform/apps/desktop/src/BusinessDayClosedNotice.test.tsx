import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { BusinessDayStatus } from './api'
import { BusinessDayClosedNotice, CLOSED_TITLE } from './BusinessDayClosedNotice'
import { AppThemeProvider } from './theme'

const status = vi.fn<() => Promise<BusinessDayStatus>>()
vi.mock('./api', async (orig) => ({ ...(await orig<typeof import('./api')>()), getBusinessDayStatus: () => status() }))

const closed: BusinessDayStatus = { inUse: true, calendarDate: '2026-11-21', openDate: null, openWords: null }

function show(hide = false) {
  const open = vi.fn()
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AppThemeProvider><BusinessDayClosedNotice onOpen={open} hide={hide} /></AppThemeProvider></QueryClientProvider>)
  return open
}
const settle = () => new Promise((r) => setTimeout(r, 20))

// Each test sets its own answer (no mockReset before each; see SchemaStatusNotice.test.tsx).
describe('the business day not started notice', () => {
  it('says that only looking is possible, and opens Business Day', async () => {
    status.mockResolvedValue(closed)
    const open = show()
    const notice = await screen.findByRole('alert')
    expect(notice).toHaveTextContent(CLOSED_TITLE)
    expect(notice).toHaveTextContent('You can look things up, but no transaction can be made until it is started')
    await userEvent.click(within(notice).getByRole('button', { name: 'Open Business Day' }))
    expect(open).toHaveBeenCalledOnce()
  })

  it('says nothing while a day is open', async () => {
    status.mockResolvedValue({ ...closed, openDate: '2026-11-21', openWords: 'Saturday, 21 November 2026' })
    show()
    await settle()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('says nothing where business days are not in use', async () => {
    status.mockResolvedValue({ ...closed, inUse: false })
    show()
    await settle()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('stays out of the Business Day screen', async () => {
    status.mockResolvedValue(closed)
    show(true)
    await settle()
    expect(screen.queryByRole('alert')).toBeNull()
  })
})
