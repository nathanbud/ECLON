import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { SlChange, SlControls } from './api/slControls'
import SlControlsPage from './SlControlsPage'
import { AppThemeProvider } from './theme'

const save = vi.fn()
let controls: SlControls
vi.mock('./api/slControls', async (orig) => ({
  ...(await orig<typeof import('./api/slControls')>()),
  getSlControls: async () => controls,
  saveSlControls: (changes: SlChange[], reason: string) => save(changes, reason),
}))

const row = (slc: number, slt: number, name: string, regular: boolean, associate: boolean, extra: Partial<SlControls['classes'][0]['types'][0]> = {}) =>
  ({ slc, slt, name, minBalance: null, interestRate: null, disabled: false, on: { '1': regular, '2': associate }, ...extra })

function make(installed = true): SlControls {
  return {
    branch: 1, installed, lastChangedBy: 'system', lastChangedAt: null,
    memberTypes: [{ code: 1, name: 'Regular Individual' }, { code: 2, name: 'Associate' }],
    classes: [
      { slc: 22, name: 'Savings Deposit', note: 'Savings.', types: [row(22, 1, 'SD - Regular', true, false, { minBalance: 500 }), row(22, 2, 'SD- Associate', false, true)] },
      { slc: 29, name: 'Misc. Liability', note: 'Funds.', types: [row(29, 5, 'MBFP', true, false), row(29, 6, 'HABP', true, false), row(29, 9, 'Old fund', false, false, { disabled: true })] },
      { slc: 31, name: 'Share Capital', note: 'Share capital.', types: [row(31, 1, 'Paid up Share Cap', true, false, { minBalance: 1000 }), row(31, 3, 'Paid Up Cap Pref', false, true)] },
    ],
  }
}

function show() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider><SlControlsPage /></AppThemeProvider></QueryClientProvider>)
}

describe('SL Controls', () => {
  beforeEach(() => {
    controls = make()
    save.mockReset().mockImplementation(async () => controls)
  })

  it('shows a switch per account type and member type, and saves the changes with one reason', async () => {
    show()
    const habp = await screen.findByRole('switch', { name: 'HABP opens for Regular Individual members' })
    expect(habp).toBeChecked()
    expect(screen.getByRole('switch', { name: 'HABP opens for Associate members' })).not.toBeChecked()
    expect(screen.getByText('1,000.00')).toBeInTheDocument()
    const user = userEvent.setup()
    await user.click(habp)
    await user.click(screen.getByRole('switch', { name: 'MBFP opens for Associate members' }))
    expect(screen.getByText(/2 changes not saved/)).toBeInTheDocument()
    const saveButton = screen.getByRole('button', { name: 'Save changes' })
    expect(saveButton).toBeDisabled()
    await user.type(screen.getByRole('textbox', { name: 'Reason for the change' }), 'Board resolution 12')
    await user.click(saveButton)
    expect(save).toHaveBeenCalledWith(
      expect.arrayContaining([{ clientType: 1, slc: 29, slt: 6, on: false }, { clientType: 2, slc: 29, slt: 5, on: true }]), 'Board resolution 12')
    expect(save.mock.calls[0][0]).toHaveLength(2)
  })

  it('will not save a member type left without share capital, and discards on request', async () => {
    show()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('switch', { name: 'Paid Up Cap Pref opens for Associate members' }))
    expect(screen.getByText(/Associate members would have no share capital account/)).toBeInTheDocument()
    await user.type(screen.getByRole('textbox', { name: 'Reason for the change' }), 'testing')
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Discard' }))
    expect(screen.getByRole('switch', { name: 'Paid Up Cap Pref opens for Associate members' })).toBeChecked()
    expect(screen.queryByText(/would have no share capital account/)).not.toBeInTheDocument()
  })

  it('cannot switch on a type switched off in Account Types', async () => {
    show()
    expect(await screen.findByRole('switch', { name: 'Old fund opens for Regular Individual members' })).toBeDisabled()
    expect(screen.getAllByText('Switched off in Account Types').length).toBeGreaterThan(0)
  })

  it('says so when the table is not installed, and offers nothing to change', async () => {
    controls = make(false)
    show()
    expect(await screen.findByText(/not set up on this database yet/)).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: 'HABP opens for Regular Individual members' })).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Save changes' })).not.toBeInTheDocument()
  })
})
