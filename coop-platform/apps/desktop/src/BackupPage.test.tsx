import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Me } from './api'
import BackupPage from './BackupPage'
import { AppThemeProvider } from './theme'
import { Notices } from './ui/notices'
import { sizeOf } from './ui/format'

let roles: string[] = ['ROLE_ADMIN']
vi.mock('./session', () => ({ useMe: () => ({ username: 'boss', name: 'Boss', roles, canEdit: true, canApprove: false, admin: true, mustChangePassword: false }) as Me }))

const status = vi.fn()
const run = vi.fn()
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  getBackups: () => status(),
  runBackup: () => run(),
}))

const entry = (name: string, complete: boolean) => ({
  name, takenAt: '2026-11-08T07:09:00', bytes: 6_611_357, complete,
  files: complete ? [{ name: 'biometric.key', bytes: 44 }, { name: 'ecis-legacy.sql', bytes: 6_611_312 }] : [{ name: 'ecis-legacy.sql', bytes: 6_611_312 }],
})

function show() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider><BackupPage /><Notices /></AppThemeProvider></QueryClientProvider>)
}

describe('backup screen', () => {
  beforeEach(() => {
    roles = ['ROLE_ADMIN']
    status.mockReset().mockResolvedValue({ engine: 'postgres', folder: 'C:\backups', scriptFound: true, running: false, backups: [entry('20261108-0709', true), entry('20261105-0900', false)] })
    run.mockReset().mockResolvedValue({ ok: true, message: 'Backed up 84 tables', backup: entry('20261108-0800', true) })
  })

  it('lists the backups and marks one that is missing the key', async () => {
    show()
    expect(await screen.findByText('20261108-0709')).toBeInTheDocument()
    expect(screen.getByText('Complete')).toBeInTheDocument()
    expect(screen.getByText('Incomplete')).toBeInTheDocument()
    expect(screen.getByText(/PostgreSQL database/)).toBeInTheDocument()
  })

  it('takes a backup when asked and shows what it made', async () => {
    show()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Back up now' }))
    await waitFor(() => expect(run).toHaveBeenCalledTimes(1))
    expect(await screen.findByText(/Backup 20261108-0800 is ready/)).toBeInTheDocument()
  })

  it('shows why a backup failed', async () => {
    run.mockRejectedValue(new Error('The backup failed. No ecis_ tables were found.'))
    show()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Back up now' }))
    expect(await screen.findByText(/No ecis_ tables were found/)).toBeInTheDocument()
  })

  it('cannot start a backup when the script is missing', async () => {
    status.mockResolvedValue({ engine: 'postgres', folder: 'x', scriptFound: false, running: false, backups: [] })
    show()
    expect(await screen.findByText(/backup script was not found/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Back up now' })).toBeDisabled()
  })

  it('does not read anything for a person who is not an administrator', async () => {
    roles = ['ROLE_ACCOUNTANT']
    show()
    expect(await screen.findByText(/Only an administrator/)).toBeInTheDocument()
    expect(status).not.toHaveBeenCalled()
  })
})

describe('sizeOf', () => {
  it('writes sizes for people', () => {
    expect(sizeOf(44)).toBe('44 B')
    expect(sizeOf(2048)).toBe('2.0 KB')
    expect(sizeOf(6_611_312)).toBe('6.3 MB')
  })
})
