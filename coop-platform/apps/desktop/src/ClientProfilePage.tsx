import { displayName, type Id } from './clients'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useMe } from './session'
import { getCapabilities, getLookups, getMember, getPendingChange, submitChange } from './api'
import { useLeaveGuard } from './leaveGuard'
import { hasPendingFind, VOICE_FIND } from './voice/bus'
import { ACTIONS, ClientMenu, closeMatch, mod } from './profile/ClientMenu'
import { Box, Button, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle, Snackbar } from '@mui/material'
import { appBackground, glass } from './glass'
import { ToolbarPortal } from './toolbarSlot'
import FindClientDialog, { FindBody } from './FindClientDialog'
import AdvancedRegistration from './AdvancedRegistration'
import { DraftsList } from './profile/DraftsList'
import type { MemberDraft } from './api'
import { Gated, NEEDS_ADMIN, NEEDS_EDIT } from './ui/Gated'
import { NewMemberScreen } from './profile/NewMember'
import { EditCtx } from './profile/editing'
import { EditBar, PendingBanner } from './profile/Changes'
import { CloseDialog, ReactivateDialog } from './MemberRequests'
import { Profile } from './profile/Profile'

// ---- page ---------------------------------------------------------------------------------------------------------

export default function ClientProfilePage({ selected, onSelect }: { selected: Id | null; onSelect: (id: Id) => void }) {
  const qc = useQueryClient()
  const [finding, setFinding] = useState(false)
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [discarding, setDiscarding] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [registering, setRegistering] = useState(false)
  const [resumed, setResumed] = useState<MemberDraft | null>(null)
  const [advanced, setAdvanced] = useState(false)
  const me = useMe()
  const [closing, setClosing] = useState(false)
  const [reactivating, setReactivating] = useState(false)

  const { data: lk } = useQuery({ queryKey: ['lookups'], queryFn: getLookups, staleTime: Infinity })
  const { data: caps } = useQuery({ queryKey: ['capabilities'], queryFn: getCapabilities, staleTime: 60_000 })
  const { data: member } = useQuery({
    queryKey: ['member', selected],
    queryFn: () => getMember(selected!.branchCode, selected!.clientId),
    enabled: !!selected,
  })
  const { data: pending } = useQuery({
    queryKey: ['pending-change', selected],
    queryFn: () => getPendingChange(selected!.branchCode, selected!.clientId),
    enabled: !!selected,
  })
  const changes = Object.keys(draft).length
  useLeaveGuard(editing && changes > 0 ? `You have ${changes} unsaved change${changes === 1 ? '' : 's'} on this profile.` : null)
  const key = selected ? `${selected.branchCode}-${selected.clientId}` : ''

  // Opening another client ends any edit in progress. (State is reset while rendering, not in an effect, so the
  // new client never flashes with the old client's unsaved values.)
  const [shownKey, setShownKey] = useState(key)
  if (key !== shownKey) {
    setShownKey(key)
    setEditing(false)
    setDraft({})
    setError(null)
  }

  const set = (col: string, value: string, original: string) =>
    setDraft((d) => {
      const next = { ...d }
      if (value === original) delete next[col]
      else next[col] = value
      return next
    })
  const leave = () => { setEditing(false); setDraft({}); setError(null); setDiscarding(false) }
  const cancel = () => (changes > 0 ? setDiscarding(true) : leave())

  const save = async () => {
    if (!selected || !member || changes === 0) return
    setSaving(true)
    setError(null)
    try {
      const done = await submitChange(selected.branchCode, selected.clientId, { changes: draft, expectedUpdatedAt: member.updatedAt })
      await Promise.all([
        qc.invalidateQueries({ queryKey: ['pending-change', selected] }),
        qc.invalidateQueries({ queryKey: ['pending-count'] }),
        qc.invalidateQueries({ queryKey: ['changes'] }),
        // A Membership Officer's edit is applied at once, so the profile is read again.
        qc.invalidateQueries({ queryKey: ['member'] }),
        qc.invalidateQueries({ queryKey: ['member-details'] }),
        qc.invalidateQueries({ queryKey: ['members'] }),
      ])
      leave()
      setNotice(done.status === 'APPROVED' ? 'Saved. The change is recorded in the audit log' : 'Sent for approval')
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setSaving(false)
    }
  }

  // With no client open the finder is already on the page, so Find just focuses it. With a client open, Find
  // (the toolbar item or Ctrl/Cmd+F) opens the pop-up so the profile stays visible behind it.
  const find = () => {
    if (selected) setFinding(true)
    else document.getElementById('find-client-input')?.focus()
  }

  const runs: Record<string, (() => void) | undefined> = {
    New: caps?.edit && !editing && !registering && !advanced && !saving ? () => { setRegistering(true); setError(null) } : undefined,
    Edit: caps?.edit && selected && member && !pending && !editing ? () => { setEditing(true); setError(null) } : undefined,
    Save: editing && changes > 0 && !saving ? save : undefined,
    Cancel: editing && !saving ? cancel : undefined,
  }
  const actions = ACTIONS.map((a) => ({ ...a, run: runs[a.label] }))
  const closeRun = caps?.edit && selected && member && (member.accountStatus ?? 1) !== 3 && !editing && !registering && !advanced ? () => setClosing(true) : undefined

  // With a member open, "find client X" by voice opens the finder pop-up (the finder screen is already there when none is open).
  useEffect(() => {
    if (selected && hasPendingFind()) setFinding(true)
    const on = () => { if (selected && !advanced && !registering) setFinding(true) }
    window.addEventListener(VOICE_FIND, on)
    return () => window.removeEventListener(VOICE_FIND, on)
  }, [selected, advanced, registering])

  // Keyboard: Ctrl/Cmd+F runs Find; the other shortcuts run their action while it is possible.
  useEffect(() => {
    const on = (e: KeyboardEvent) => {
      if (mod(e) && e.key === 'f') {
        e.preventDefault()
        find()
        return
      }
      if (finding || discarding) return
      if (closeMatch(e) && closeRun) { e.preventDefault(); closeRun(); return }
      const action = actions.find((a) => a.match(e))
      if (action?.run) { e.preventDefault(); action.run() }
    }
    window.addEventListener('keydown', on)
    return () => window.removeEventListener('keydown', on)
  })

  return (
    <Box sx={(t) => ({ display: 'flex', flexDirection: 'column', height: 'calc(100dvh - 44px)', background: appBackground(t) })}>
      {/* The title-bar menu carries Find Client plus the record actions. */}
      <ToolbarPortal><ClientMenu onFind={find} actions={actions} editOn={!!caps?.edit} pendingNote={pending ? 'A change is waiting for approval.' : null} onCloseMembership={closeRun} /></ToolbarPortal>
      <FindClientDialog open={finding} onClose={() => setFinding(false)} lk={lk} onPick={(id) => { setFinding(false); onSelect(id) }} />

      <Dialog open={discarding} onClose={() => setDiscarding(false)} aria-labelledby="discard-title">
        <DialogTitle id="discard-title">Discard changes?</DialogTitle>
        <DialogContent>
          <DialogContentText>{changes} change{changes === 1 ? '' : 's'} will be lost.</DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDiscarding(false)} autoFocus>Keep editing</Button>
          <Button onClick={leave} color="error">Discard</Button>
        </DialogActions>
      </Dialog>
      <Snackbar open={!!notice}autoHideDuration={3000} onClose={() => setNotice(null)} message={notice} />

      <Box component="section" aria-label="Client profile" sx={{ flex: 1, display: 'flex', flexDirection: 'column', minHeight: 0, minWidth: 0, maxWidth: 1600, width: '100%', mx: 'auto', p: 1.5 }}>
        {advanced
          ? <AdvancedRegistration onClose={() => setAdvanced(false)} onDone={setNotice} />
          : registering
          ? <NewMemberScreen key={resumed?.id ?? 'new'} lk={lk} canImages={!!caps?.images} initial={resumed ?? undefined} onClose={() => { setRegistering(false); setResumed(null) }} onDone={setNotice} />
          : selected
          ? (
            <EditCtx.Provider value={{ editing, draft, set }}>
              {pending && <PendingBanner change={pending} id={selected} canApprove={!!caps?.approve} onDone={setNotice} />}
              {member && ((member.accountStatus ?? 1) === 2 || (member.accountStatus ?? 1) === 3) && !editing && (
                <Box sx={{ display: 'flex', justifyContent: 'flex-end', gap: 1, mb: 0.5 }}>
                  <Gated allowed={!!caps?.edit} reason={NEEDS_EDIT}><Button size="small" onClick={() => setReactivating(true)}>Reactivate membership</Button></Gated>
                </Box>
              )}
              {reactivating && member && <ReactivateDialog branchCode={selected.branchCode} clientId={selected.clientId} name={displayName(member)} onClose={() => setReactivating(false)} onDone={setNotice} />}
              {closing && member && <CloseDialog branchCode={selected.branchCode} clientId={selected.clientId} name={displayName(member)} onClose={() => setClosing(false)} onDone={setNotice} />}
              {editing && <EditBar changes={changes} saving={saving} error={error} direct={!!caps?.direct} onSave={save} onCancel={cancel} />}
              <Profile key={key} id={selected} lk={lk} canPhoto={!!caps?.images} onNotice={setNotice} />
            </EditCtx.Provider>
          )
          : (
            // First opening: the search is ready with the cursor in it, so there is nothing to click first.
            <Box sx={{ flex: 1, display: 'flex', justifyContent: 'center', alignItems: 'flex-start', pt: '10vh' }}>
              <Box sx={(t) => ({ width: '100%', maxWidth: 580, overflow: 'hidden', ...glass(t, { elevated: true }) })}>
                <FindBody inline lk={lk} onPick={onSelect} />
                <Box sx={{ p: 1, textAlign: 'center' }}>
                  <Gated allowed={!!caps?.edit} reason={NEEDS_EDIT}><Button size="small" onClick={() => { setRegistering(true); setError(null) }}>New Client</Button></Gated>
                  <Gated allowed={!!caps?.edit && me.admin} reason={caps?.edit ? NEEDS_ADMIN : NEEDS_EDIT}><Button size="small" color="warning" onClick={() => setAdvanced(true)}>Advanced mode: import a list</Button></Gated>
                </Box>
                {caps?.edit && <DraftsList onResume={(d) => { setResumed(d); setRegistering(true); setError(null) }} />}
              </Box>
            </Box>
          )}
      </Box>
    </Box>
  )
}
