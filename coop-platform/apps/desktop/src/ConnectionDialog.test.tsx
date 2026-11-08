import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ConnectionDialog from './ConnectionDialog'
import { activeServer, servers } from './api'
import { AppThemeProvider } from './theme'

const list = { active: 'c1', connections: [{ id: 'c1', name: 'Main', dbName: 'iaccsbsucmpc01', branch: 'Main Office', active: true }, { id: 'c2', name: 'Practice', dbName: 'iaccs_sim', branch: null, active: false }] }

function show(onClose = vi.fn()) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const view = render(<QueryClientProvider client={qc}><AppThemeProvider><ConnectionDialog onClose={onClose} /></AppThemeProvider></QueryClientProvider>)
  return { onClose, view }
}

describe('sign-in connection window', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(list), { status: 200, headers: { 'Content-Type': 'application/json' } })))
  })
  afterEach(() => vi.unstubAllGlobals())

  it('shows the databases the server has, marks the one in use, and tells where to move it', async () => {
    show()
    const saved = await screen.findByRole('list', { name: 'Saved database connections' })
    expect(within(saved).getByText('Main')).toBeInTheDocument()
    expect(within(saved).getByText('iaccsbsucmpc01 · Main Office')).toBeInTheDocument()
    expect(within(saved).getByText('In use')).toBeInTheDocument()
    expect(within(saved).getByText('iaccs_sim')).toBeInTheDocument()
    expect(screen.getByText(/only a SUPERADMIN can move it|A SUPERADMIN can move it/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Connected' })).toBeDisabled()
  })

  it('adds a server, refuses a bad address, and does not offer to change the built-in one', async () => {
    const user = userEvent.setup()
    show()
    await screen.findByRole('list', { name: 'Saved database connections' })
    expect(screen.getByRole('button', { name: 'Edit this server' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Delete this server' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Add a server' }))
    const form = screen.getByRole('form', { name: 'Add server' })
    await user.type(within(form).getByLabelText('Connection name'), 'Office')
    await user.clear(within(form).getByLabelText('Server address'))
    await user.type(within(form).getByLabelText('Server address'), 'ftp://nope')
    await user.click(within(form).getByRole('button', { name: 'Save' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('http://host:port')

    await user.clear(within(form).getByLabelText('Server address'))
    await user.type(within(form).getByLabelText('Server address'), 'http://192.168.1.20:8090')
    await user.click(within(form).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(servers().map((s) => s.name)).toContain('Office'))
    expect(screen.getByRole('button', { name: 'Connect' })).toBeEnabled() // the new one is picked, and is not the one in use
    expect(activeServer().name).toBe('This computer') // nothing changes until Connect
  })

  it('says so when the chosen server does not answer', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('down') }))
    show()
    expect(await screen.findByText(/does not answer/)).toBeInTheDocument()
  })

  it('has no accessibility violations', async () => {
    const { view } = show()
    await screen.findByRole('list', { name: 'Saved database connections' })
    const result = await axe.run(view.baseElement, { rules: { 'color-contrast': { enabled: false } } })
    expect(result.violations.map((v) => `${v.id}: ${v.nodes[0]?.html}`)).toEqual([])
  })
})
