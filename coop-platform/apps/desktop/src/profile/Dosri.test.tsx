import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { useState } from 'react'
import { MembershipTab } from './InfoTabs'
import { EditCtx } from './editing'
import { BLANK_MEMBER } from './constants'
import type { Lookups, Member } from '../api'
import { AppThemeProvider } from '../theme'

const lk: Lookups = { dosriTag: { '1': 'Directors', '2': 'Officers', '3': 'Staff', '4': 'Related Interests' } }
const member = (dosriTag: number | null) => ({ ...BLANK_MEMBER, id: { branchCode: 1, clientId: 5 }, dosriTag }) as Member

function Editing({ m, seen }: { m: Member; seen: (d: Record<string, string>) => void }) {
  const [draft, setDraft] = useState<Record<string, string>>({})
  const set = (col: string, v: string) => setDraft((d) => { const n = { ...d, [col]: v }; seen(n); return n })
  return <EditCtx.Provider value={{ editing: true, draft, set }}><MembershipTab m={m} lk={lk} /></EditCtx.Provider>
}

describe('DOSRI tag on the Membership section', () => {
  it('shows the type the member is tagged with', () => {
    render(<AppThemeProvider><MembershipTab m={member(2)} lk={lk} /></AppThemeProvider>)
    expect(screen.getByRole('group', { name: 'DOSRI' })).toHaveTextContent('Officers')
  })

  it('is chosen from the cooperative’s DOSRI list while editing', async () => {
    let draft: Record<string, string> = {}
    render(<AppThemeProvider><Editing m={member(null)} seen={(d) => { draft = d }} /></AppThemeProvider>)
    const u = userEvent.setup()
    await u.click(within(screen.getByRole('group', { name: 'DOSRI' })).getByRole('combobox'))
    const options = screen.getAllByRole('option').map((o) => o.textContent)
    expect(options).toEqual(['—', 'Directors', 'Officers', 'Staff', 'Related Interests'])
    await u.click(screen.getByRole('option', { name: 'Related Interests' }))
    expect(draft.DOSRITag).toBe('4')
  })
})
