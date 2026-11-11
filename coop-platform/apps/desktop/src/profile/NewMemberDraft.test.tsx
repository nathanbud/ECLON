import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { MemberDraft, MemberDraftSummary } from '../api'
import { AppThemeProvider } from '../theme'
import { DraftsList } from './DraftsList'
import { NewMemberScreen } from './NewMember'

const saveMemberDraft = vi.fn()
const deleteMemberDraft = vi.fn()
const listMemberDrafts = vi.fn()
const getMemberDraft = vi.fn()
const registerMember = vi.fn()
vi.mock('../api', async (orig) => ({
  ...(await orig<typeof import('../api')>()),
  saveMemberDraft: (...a: unknown[]) => saveMemberDraft(...a),
  deleteMemberDraft: (...a: unknown[]) => deleteMemberDraft(...a),
  listMemberDrafts: () => listMemberDrafts(),
  getMemberDraft: (...a: unknown[]) => getMemberDraft(...a),
  registerMember: (...a: unknown[]) => registerMember(...a),
}))
// the profile tabs reach for data this test does not need
vi.mock('./Profile', async () => {
  const { useEdit } = await import('./editing')
  return {
    Profile: () => {
      const { draft, set } = useEdit()
      return (
        <div>
          <label>Last name<input value={draft.LName ?? ''} onChange={(e) => set('LName', e.target.value, '')} /></label>
          <label>First name<input value={draft.FName ?? ''} onChange={(e) => set('FName', e.target.value, '')} /></label>
        </div>
      )
    },
  }
})

function renderNew(initial?: MemberDraft) {
  const onClose = vi.fn()
  const onDone = vi.fn()
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={qc}><AppThemeProvider><NewMemberScreen canImages={false} onClose={onClose} onDone={onDone} initial={initial} /></AppThemeProvider></QueryClientProvider>)
  return { onClose, onDone }
}

describe('saving a new member as a draft', () => {
  beforeEach(() => {
    for (const m of [saveMemberDraft, deleteMemberDraft, listMemberDrafts, getMemberDraft, registerMember]) m.mockReset()
    saveMemberDraft.mockResolvedValue({ id: 7, title: 'Cruz, Ana', clientType: 1, updatedAt: '2026-11-10T09:00:00' } satisfies MemberDraftSummary)
    deleteMemberDraft.mockResolvedValue(undefined)
  })

  it('has nothing to save until something is typed, then keeps the typed fields', async () => {
    const user = userEvent.setup()
    renderNew()
    expect(screen.getByRole('button', { name: 'Save as draft' })).toBeDisabled()
    await user.type(screen.getByLabelText('Last name'), 'Cruz')
    await user.type(screen.getByLabelText('First name'), 'Ana')
    await user.click(screen.getByRole('button', { name: 'Save as draft' }))
    await waitFor(() => expect(saveMemberDraft).toHaveBeenCalledWith(null, { branchCode: 1, clientType: 1, fields: { LName: 'Cruz', FName: 'Ana' } }))
    expect(await screen.findByText(/Draft saved/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save as draft' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Close' })).toBeInTheDocument()
  })

  it('carries on with a saved draft: its fields are filled in and saving changes the same draft', async () => {
    const user = userEvent.setup()
    renderNew({ id: 7, title: 'Cruz, Ana', clientType: 2, branchCode: 1, updatedAt: '2026-11-10T09:00:00', fields: { LName: 'Cruz', FName: 'Ana' } })
    expect(screen.getByLabelText('Last name')).toHaveValue('Cruz')
    await user.type(screen.getByLabelText('First name'), 'a')
    await user.click(screen.getByRole('button', { name: 'Save as draft' }))
    await waitFor(() => expect(saveMemberDraft).toHaveBeenCalledWith(7, { branchCode: 1, clientType: 2, fields: { LName: 'Cruz', FName: 'Anaa' } }))
  })

  it('offers to save as a draft when leaving with unsaved work, and closes after saving', async () => {
    const user = userEvent.setup()
    const { onClose } = renderNew()
    await user.type(screen.getByLabelText('Last name'), 'Reyes')
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/unless you save it as a draft/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Save as draft and close' }))
    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(saveMemberDraft).toHaveBeenCalledTimes(1)
  })

  it('does not close when the draft could not be saved', async () => {
    saveMemberDraft.mockRejectedValue(new Error('You already have 50 drafts.'))
    const user = userEvent.setup()
    const { onClose } = renderNew()
    await user.type(screen.getByLabelText('Last name'), 'Reyes')
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    await user.click(await screen.findByRole('button', { name: 'Save as draft and close' }))
    await waitFor(() => expect(saveMemberDraft).toHaveBeenCalled())
    expect(onClose).not.toHaveBeenCalled()
  })

  it('removes the draft once the registration is sent', async () => {
    registerMember.mockResolvedValue({ summary: 'Cruz, Ana' })
    const user = userEvent.setup()
    const { onDone } = renderNew({ id: 7, title: 'Cruz, Ana', clientType: 1, branchCode: 1, updatedAt: '2026-11-10T09:00:00', fields: { LName: 'Cruz', FName: 'Ana' } })
    await user.click(screen.getByRole('button', { name: /Send for approval/ }))
    await waitFor(() => expect(onDone).toHaveBeenCalled())
    await waitFor(() => expect(deleteMemberDraft).toHaveBeenCalledWith(7))
  })
})

describe('the list of saved drafts', () => {
  function renderList(onResume = vi.fn()) {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={qc}><AppThemeProvider><DraftsList onResume={onResume} /></AppThemeProvider></QueryClientProvider>)
    return onResume
  }
  beforeEach(() => {
    for (const m of [deleteMemberDraft, listMemberDrafts, getMemberDraft]) m.mockReset()
    deleteMemberDraft.mockResolvedValue(undefined)
  })

  it('shows nothing when there are no drafts', async () => {
    listMemberDrafts.mockResolvedValue([])
    renderList()
    await waitFor(() => expect(listMemberDrafts).toHaveBeenCalled())
    expect(screen.queryByRole('region', { name: 'Saved drafts' })).not.toBeInTheDocument()
  })

  it('lists the drafts and opens one to continue', async () => {
    listMemberDrafts.mockResolvedValue([{ id: 7, title: 'Cruz, Ana', clientType: 1, updatedAt: '2026-11-10T09:00:00' }])
    const full: MemberDraft = { id: 7, title: 'Cruz, Ana', clientType: 1, branchCode: 1, updatedAt: '2026-11-10T09:00:00', fields: { LName: 'Cruz' } }
    getMemberDraft.mockResolvedValue(full)
    const onResume = renderList()
    const list = await screen.findByRole('region', { name: 'Saved drafts' })
    expect(within(list).getByText('Cruz, Ana')).toBeInTheDocument()
    await userEvent.setup().click(within(list).getByRole('button', { name: /Continue the draft for Cruz, Ana/ }))
    await waitFor(() => expect(onResume).toHaveBeenCalledWith(full))
  })

  it('asks before deleting a draft', async () => {
    listMemberDrafts.mockResolvedValue([{ id: 7, title: 'Cruz, Ana', clientType: 1, updatedAt: '2026-11-10T09:00:00' }])
    const user = userEvent.setup()
    renderList()
    await user.click(await screen.findByRole('button', { name: /Delete the draft for Cruz, Ana/ }))
    expect(deleteMemberDraft).not.toHaveBeenCalled()
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(deleteMemberDraft).toHaveBeenCalledWith(7))
  })
})
