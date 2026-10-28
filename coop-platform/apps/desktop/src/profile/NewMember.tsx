import { EditCtx, useEdit } from './editing'
import { Alert, Avatar, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle, IconButton, Tooltip, Typography } from '@mui/material'
import { glass } from '../glass'
import { tileFill } from '../theme'
import { FiCamera, FiEdit2, FiPlus, FiSave, FiUser } from 'react-icons/fi'
import { IoFingerPrint } from 'react-icons/io5'
import { quietChip } from './fields'
import { registerMember, type Lookups, type StagedFinger } from '../api'
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useLeaveGuard } from '../leaveGuard'
import { Profile } from './Profile'
import { FRESH_ID } from './constants'
import MemberPhotoDialog from '../MemberPhotoDialog'
import StagedFingerprintDialog from '../StagedFingerprintDialog'

/** The header of a member who is being registered: no photo, status or fingerprint yet, only the name as it is typed. */
function NewMemberHeader({ photo, prints, canImages, onPhoto, onPrints }: { photo: string | null; prints: number; canImages: boolean; onPhoto: () => void; onPrints: () => void }) {
  const { draft } = useEdit()
  const name = [draft.LName, [draft.FName, draft.MName].filter(Boolean).join(' ')].filter(Boolean).join(', ')
  return (
    <Box sx={(t) => ({ display: 'flex', alignItems: 'center', gap: 2, p: 1.75, ...glass(t, { elevated: true }) })}>
      <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'stretch', gap: 1.25, flexShrink: 0, width: 120 }}>
        <Box sx={{ position: 'relative' }}>
          <Avatar variant="rounded" src={photo ?? undefined} alt="New photo" sx={{ width: 120, height: 120, borderRadius: '8px', background: `linear-gradient(160deg, ${tileFill.orange}F2, ${tileFill.orange})` }}><FiUser size={56} aria-hidden /></Avatar>
          {canImages && (
            <Tooltip title="Take or upload a photo">
              <IconButton size="small" aria-label="Take or upload a photo" onClick={onPhoto} sx={{ position: 'absolute', right: -8, bottom: -8, width: 30, height: 30, bgcolor: 'background.paper', border: 1, borderColor: 'divider' }}><FiCamera size={14} /></IconButton>
            </Tooltip>
          )}
        </Box>
        {canImages && (
          <Chip size="small" variant="outlined" clickable color={prints ? 'success' : 'default'} icon={<IoFingerPrint />} onClick={onPrints}
            label={prints ? `Fingerprint · ${prints}/10` : 'Fingerprint'} aria-label="Fingerprints" sx={{ justifyContent: 'flex-start' }} />
        )}
      </Box>
      <Box sx={{ minWidth: 0, flex: 1 }}>
        <Chip size="small" color="primary" label="New member" icon={<FiPlus />} sx={{ ...quietChip, mb: 0.75 }} />
        <Typography component="h2" noWrap sx={{ fontSize: 22, fontWeight: 700, letterSpacing: '-0.02em', lineHeight: 1.2 }}>{name || 'Unnamed member'}</Typography>
        <Typography sx={{ fontSize: 13 }} color="text.secondary">The member number is given when a Membership Officer approves. {canImages ? 'The photo and fingerprints taken here are kept with the registration until then.' : 'A photo and fingerprints need the edit-client-images permission.'}</Typography>
      </Box>
    </Box>
  )
}

/** Registering a new member on the member profile itself: the same tabs and fields, all open for typing. Sending asks a Membership Officer (not you) to approve. */
export function NewMemberScreen({ lk, canImages, onClose, onDone }: { lk?: Lookups; canImages: boolean; onClose: () => void; onDone: (msg: string) => void }) {
  const qc = useQueryClient()
  const [draft, setDraft] = useState<Record<string, string>>({ ClientType: '1' })
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [discarding, setDiscarding] = useState(false)
  const [photo, setPhoto] = useState<Blob | null>(null)
  const [photoUrl, setPhotoUrl] = useState<string | null>(null)
  const [consent, setConsent] = useState(false)
  const [prints, setPrints] = useState<StagedFinger[]>([])
  const [photoOpen, setPhotoOpen] = useState(false)
  const [printsOpen, setPrintsOpen] = useState(false)
  const typed = Object.keys(draft).filter((k) => k !== 'ClientType').length + (photo ? 1 : 0) + prints.length
  const keepPhoto = (b: Blob) => { setPhoto(b); setPhotoUrl((old) => { if (old) URL.revokeObjectURL(old); return URL.createObjectURL(b) }) }
  useLeaveGuard(typed > 0 ? 'The new member you are registering has not been sent yet.' : null)
  const set = (col: string, value: string, original: string) =>
    setDraft((d) => {
      const next = { ...d }
      if (value === original) delete next[col]
      else next[col] = value
      return next
    })
  const send = async () => {
    const { ClientType, ...fields } = draft
    setSaving(true)
    setError(null)
    try {
      const encoded = photo ? await new Promise<string>((ok, no) => { const fr = new FileReader(); fr.onload = () => ok(String(fr.result).split(',')[1] ?? ''); fr.onerror = () => no(new Error('The photo could not be read.')); fr.readAsDataURL(photo) }) : null
      const staged = encoded || prints.length ? { photo: encoded, consent, fingerprints: prints } : undefined
      const r = await registerMember({ branchCode: 1, clientType: Number(ClientType || 1), fields, staged })
      void qc.invalidateQueries({ queryKey: ['member-requests'] })
      onDone(`Registration sent for approval: ${r.summary}`)
      onClose()
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setSaving(false)
    }
  }
  const ready = !!draft.LName?.trim() && !!draft.FName?.trim()
  return (
    <EditCtx.Provider value={{ editing: true, draft, set }}>
      <Box sx={{ mb: 1.5 }}>
        <Box sx={(t) => ({ display: 'flex', alignItems: 'center', gap: 1.5, px: 2, py: 1, ...glass(t) })}>
          <FiEdit2 aria-hidden />
          <Typography sx={{ flex: 1, fontSize: 13.5 }} role="status">
            Registering a new member. Last name, first name, date of birth, gender and civil status are required. Sending asks a Membership Officer other than you to approve.
          </Typography>
          <Button size="small" onClick={() => (typed > 0 ? setDiscarding(true) : onClose())} disabled={saving}>Cancel</Button>
          <Button size="small" variant="contained" startIcon={<FiSave />} onClick={() => void send()} disabled={saving || !ready}>{saving ? 'Sending…' : 'Send for approval'}</Button>
        </Box>
        {error && <Alert severity="error" sx={{ mt: 1 }}>{error}</Alert>}
      </Box>
      <Profile id={FRESH_ID} lk={lk} fresh newHeader={<NewMemberHeader photo={photoUrl} prints={prints.length} canImages={canImages} onPhoto={() => setPhotoOpen(true)} onPrints={() => setPrintsOpen(true)} />} />
      {photoOpen && <MemberPhotoDialog id={FRESH_ID} name={draft.LName ? `${draft.LName}, ${draft.FName ?? ''}` : 'the new member'} onPick={keepPhoto} onClose={() => setPhotoOpen(false)} onDone={onDone} />}
      {printsOpen && <StagedFingerprintDialog memberKey={`new-${[draft.LName, draft.FName, draft.DateOfBirth].join('|')}`} name={draft.LName ? `${draft.LName}, ${draft.FName ?? ''}` : 'the new member'} consent={consent} prints={prints}
        onChange={(c, p) => { setConsent(c); setPrints(p) }} onClose={() => setPrintsOpen(false)} />}
      <Dialog open={discarding} onClose={() => setDiscarding(false)}>
        <DialogTitle>Discard this registration?</DialogTitle>
        <DialogContent><DialogContentText>What you typed will be lost.</DialogContentText></DialogContent>
        <DialogActions>
          <Button onClick={() => setDiscarding(false)} autoFocus>Keep editing</Button>
          <Button color="error" onClick={onClose}>Discard</Button>
        </DialogActions>
      </Dialog>
    </EditCtx.Provider>
  )
}
