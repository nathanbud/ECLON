import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { Me, SchemaStatus } from './api'
import { DatabaseChangeNotice } from './DatabaseChangeNotice'
import { SessionCtx } from './sessionContext'
import { AppThemeProvider } from './theme'

const status = vi.fn<() => Promise<SchemaStatus>>()
vi.mock('./api', async (orig) => ({ ...(await orig<typeof import('./api')>()), getSchemaStatus: () => status() }))

const behind: SchemaStatus = { state: 'BEHIND', database: 'coop_bgempc', message: 'Missing 1.', missing: ['V035 V035__offices.sql'], newer: [], changed: [], lastExpected: 'V035', lastRecorded: 'V034' }
const person = (roles: string[]): Me => ({ username: 'u', name: 'U', roles, canEdit: false, canApprove: false, admin: roles.includes('ROLE_ADMIN'), mustChangePassword: false })

function show(me: Me) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <SessionCtx.Provider value={me}><AppThemeProvider><DatabaseChangeNotice>Offices cannot be changed yet.</DatabaseChangeNotice></AppThemeProvider></SessionCtx.Provider>
    </QueryClientProvider>,
  )
}

describe('the not-set-up notice on a screen', () => {
  it('lets a super administrator apply the missing changes there', async () => {
    status.mockResolvedValue(behind)
    show(person(['ROLE_ADMIN', 'ROLE_SUPERADMIN']))
    await userEvent.click(await screen.findByRole('button', { name: 'Apply them now' }))
    expect(screen.getByRole('dialog')).toHaveTextContent('V035__offices.sql')
  })

  it('tells anyone else who can set it up', async () => {
    status.mockResolvedValue(behind)
    show(person(['ROLE_ADMIN']))
    expect(await screen.findByText(/A super administrator can set it up/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Apply them now' })).toBeNull()
    expect(status).not.toHaveBeenCalled()
  })
})
