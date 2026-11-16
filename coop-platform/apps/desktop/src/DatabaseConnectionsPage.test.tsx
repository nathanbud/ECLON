import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Me } from './api'
import DatabaseConnectionsPage from './DatabaseConnectionsPage'
import { AppThemeProvider } from './theme'

let roles: string[] = ['ROLE_ADMIN', 'ROLE_SUPERADMIN']
vi.mock('./session', () => ({ useMe: () => ({ username: 'boss', name: 'Boss', roles, canEdit: true, canApprove: false, admin: true, mustChangePassword: false }) as Me }))

const startup = { id: 'startup', name: 'Server startup setting', host: 'localhost', port: 3311, dbName: 'iaccsbsucmpc01', branchId: null, branchName: null, isDefault: false, active: true, startup: true }
const second = { id: 'c1', name: 'Practice', host: 'localhost', port: 3311, dbName: 'iaccs_sim', branchId: 1, branchName: 'Main Office', isDefault: true, active: false, startup: false }
let rows = [startup, second]

const create = vi.fn()
const update = vi.fn()
const remove = vi.fn()
const activate = vi.fn()
const databases = vi.fn()
const branches = vi.fn()
const test = vi.fn()
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  getConnections: async () => rows,
  createConnection: (...a: unknown[]) => create(...a),
  updateConnection: (...a: unknown[]) => update(...a),
  deleteConnection: (...a: unknown[]) => remove(...a),
  activateConnection: (...a: unknown[]) => activate(...a),
  loadDatabases: (...a: unknown[]) => databases(...a),
  loadBranches: (...a: unknown[]) => branches(...a),
  testConnection: (...a: unknown[]) => test(...a),
}))

function show() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider><DatabaseConnectionsPage /></AppThemeProvider></QueryClientProvider>)
}

describe('database connection settings', () => {
  beforeEach(() => {
    roles = ['ROLE_ADMIN', 'ROLE_SUPERADMIN']
    rows = [startup, second]
    for (const f of [create, update, remove, activate, databases, branches, test]) f.mockReset()
  })
  afterEach(() => vi.restoreAllMocks())

  it('is for a SUPERADMIN only', () => {
    roles = ['ROLE_ADMIN']
    show()
    expect(screen.getByText(/Only a SUPERADMIN/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'New' })).not.toBeInTheDocument()
  })

  it('lists the connections, marks the one in use, and opens it read-only without edit or delete for the startup setting', async () => {
    show()
    const nav = await screen.findByRole('navigation', { name: 'Saved connections' })
    await screen.findByText('Practice')
    expect(nav).toHaveTextContent('Server startup setting')
    expect(nav).toHaveTextContent('In use')
    expect(nav).toHaveTextContent('Default')
    await waitFor(() => expect(screen.getByLabelText('Connection Name')).toHaveValue('Server startup setting'))
    expect(screen.getByLabelText('Connection Name')).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Edit' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Delete' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'In use now' })).toBeDisabled()
  })

  it('adds a connection the way the old window did: host and port, Load Databases, Get List, Save', async () => {
    databases.mockResolvedValue(['iaccs_sim', 'iaccsbsucmpc01'])
    branches.mockResolvedValue([{ id: 1, name: 'Main Office' }, { id: 2, name: 'Extension' }])
    create.mockResolvedValue({ ...second, id: 'c9', name: 'Branch 2', branchId: 2, branchName: 'Extension', isDefault: false })
    const user = userEvent.setup()
    show()
    await screen.findByRole('navigation', { name: 'Saved connections' })
    await user.click(screen.getByRole('button', { name: 'New' }))
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()

    await user.type(screen.getByLabelText('Connection Name'), 'Branch 2')
    await user.clear(screen.getByLabelText('Server Host'))
    await user.type(screen.getByLabelText('Server Host'), 'office-pc')
    await user.clear(screen.getByLabelText('Port'))
    await user.type(screen.getByLabelText('Port'), '3311')
    await user.click(screen.getByRole('button', { name: 'Load Databases' }))
    expect(databases).toHaveBeenCalledWith('office-pc', 3311)

    await user.click(screen.getByLabelText('DatabaseName'))
    await user.click(await screen.findByRole('option', { name: 'iaccs_sim' }))
    await user.click(screen.getByRole('button', { name: 'Get List' }))
    expect(branches).toHaveBeenCalledWith({ host: 'office-pc', port: 3311, dbName: 'iaccs_sim' })
    await user.click(screen.getByLabelText('Branch/Office'))
    await user.click(await screen.findByRole('option', { name: 'Extension' }))

    await user.click(screen.getByRole('button', { name: 'Save' }))
    expect(create).toHaveBeenCalledWith({ name: 'Branch 2', host: 'office-pc', port: 3311, dbName: 'iaccs_sim', branchId: 2, makeDefault: false })
  })

  it('refuses a port that is not a number, and shows a failed load in words', async () => {
    databases.mockRejectedValue(new Error('Could not reach the server office-pc:3311. The server did not answer.'))
    const user = userEvent.setup()
    show()
    await screen.findByRole('navigation', { name: 'Saved connections' })
    await user.click(screen.getByRole('button', { name: 'New' }))
    await user.type(screen.getByLabelText('Connection Name'), 'X')
    await user.clear(screen.getByLabelText('Server Host'))
    await user.type(screen.getByLabelText('Server Host'), 'office-pc')
    await user.clear(screen.getByLabelText('Port'))
    await user.type(screen.getByLabelText('Port'), '99999')
    expect(screen.getByRole('button', { name: 'Load Databases' })).toBeDisabled()
    await user.clear(screen.getByLabelText('Port'))
    await user.type(screen.getByLabelText('Port'), '3311')
    await user.click(screen.getByRole('button', { name: 'Load Databases' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('The server did not answer')
  })

  it('asks before moving the server, and says everyone signs in again', async () => {
    activate.mockResolvedValue({ active: 'c1', connections: [] })
    const user = userEvent.setup()
    show()
    const nav = await screen.findByRole('navigation', { name: 'Saved connections' })
    await screen.findByText('Practice')
    await user.click(nav.querySelectorAll('button')[1] as HTMLElement) // Practice
    await waitFor(() => expect(screen.getByLabelText('Connection Name')).toHaveValue('Practice'))
    await user.click(screen.getByRole('button', { name: 'Use this connection' }))
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('has to sign in again')
    expect(activate).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Move the server' }))
    expect(activate).toHaveBeenCalledWith('c1')
  })

  it('does not offer to delete the connection in use, and confirms before deleting another', async () => {
    remove.mockResolvedValue(undefined)
    const user = userEvent.setup()
    show()
    const nav = await screen.findByRole('navigation', { name: 'Saved connections' })
    await screen.findByText('Practice')
    await user.click(nav.querySelectorAll('button')[1] as HTMLElement)
    await waitFor(() => expect(screen.getByLabelText('Connection Name')).toHaveValue('Practice'))
    await user.click(screen.getByRole('button', { name: 'Delete' }))
    expect(remove).not.toHaveBeenCalled()
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Delete' }))
    expect(remove).toHaveBeenCalledWith('c1')
  })
})

import { within } from '@testing-library/react'
