import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { MemberDefaultsSetup } from './api/memberDefaults'
import MemberDefaultsPage from './MemberDefaultsPage'
import { AppThemeProvider } from './theme'

const save = vi.fn()
let setup: MemberDefaultsSetup
vi.mock('./api/memberDefaults', async (orig) => ({
  ...(await orig<typeof import('./api/memberDefaults')>()),
  getMemberDefaultsSetup: async () => setup,
  saveMemberDefaults: (d: number | null, o: number | null, t: number[], r: string) => save(d, o, t, r),
}))

const choice = (code: number, name: string) => ({ code, name, disabled: false })
const base = (editable: boolean): MemberDefaultsSetup => ({
  editable,
  applies: { department: null, departmentName: null, office: 1, officeName: 'MAIN OFFICE', types: [choice(1, 'Regular Individual')] },
  departments: [choice(2, 'MAIN OFFICE')], offices: [choice(1, 'MAIN OFFICE')],
  memberTypes: [choice(1, 'Regular Individual'), choice(2, 'Associate')],
})

function show() {
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AppThemeProvider><MemberDefaultsPage /></AppThemeProvider></QueryClientProvider>)
}

describe('Member defaults', () => {
  it('lets a super administrator choose the department and add a member type', async () => {
    setup = base(true)
    save.mockReset().mockResolvedValue(setup)
    show()
    await userEvent.click(await screen.findByLabelText(/Department a new member is given/))
    await userEvent.click(within(screen.getByRole('listbox')).getByText('2 · MAIN OFFICE'))
    await userEvent.click(screen.getByLabelText('Associate'))
    await userEvent.type(screen.getByLabelText(/Reason for the change/), 'Our own defaults')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(save).toHaveBeenCalledWith(2, 1, [1, 2], 'Our own defaults')
  })

  it('is read only for an administrator', async () => {
    setup = base(false)
    show()
    expect(await screen.findByText(/Only a super administrator changes these/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save' })).toBeNull()
  })
})
