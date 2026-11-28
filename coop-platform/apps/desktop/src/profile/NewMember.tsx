import { EditCtx, useEdit } from './editing'
import { Avatar, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle, IconButton, Tooltip, Typography } from '@mui/material'
import { glass, SIDE } from '../glass'
import { PHOTO, Ring, tag } from './ProfileHeader'
import { FiCamera, FiEdit2, FiEdit3, FiPlus, FiSave, FiUser } from 'react-icons/fi'
import { IoFingerPrint } from 'react-icons/io5'
import { quietChip } from './fields'
import { deleteMemberDraft, getMembershipPolicy, registerMember, saveMemberDraft, type Lookups, type MemberDraft, type StagedFinger } from '../api'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useMe } from '../session'
import { getMemberDefaults } from '../api/memberDefaults'
import { useState } from 'react'
import { useLeaveGuard } from '../leaveGuard'
import { Profile } from './Profile'
import { FRESH_ID } from './constants'
import MemberPhotoDialog from '../MemberPhotoDialog'
import SignatureDialog from '../SignatureDialog'
import { notifyError } from '../ui/notices'
import StagedFingerprintDialog from '../StagedFingerprintDialog'

/** The identity block in the left panel of a member who is being registered: the square photo (camera to take or upload one), the name as it is typed, and the fingerprint tag. */
function NewMemberIdentity({ photo, prints, signed, canImages, onPhoto, onPrints, onSign }: { photo: string | null; prints: number; signed: boolean; canImages: boolean; onPhoto: () => void; onPrints: () => void; onSign: () => void }) {
  const { draft } = useEdit()
  const name = [draft.LName, [draft.FName, draft.MName].filter(Boolean).join(' ')].filter(Boolean).join(', ')
  return (
    <Box sx={{ px: 2, pt: 2.5, pb: 1.5, textAlign: 'center' }}>
      <Ring color={SIDE.line}>
        <Avatar variant="square" src={photo ?? undefined} alt="New photo" sx={{ width: PHOTO, height: PHOTO, borderRadius: '2px', bgcolor: 'rgba(255,255,255,0.14)', color: SIDE.text }}><FiUser size={72} aria-hidden /></Avatar>
        {canImages && (
          <Tooltip title="Take or upload a photo">
            <IconButton size="small" aria-label="Take or upload a photo" onClick={onPhoto}
              sx={{ position: 'absolute', right: -4, bottom: -4, width: 28, height: 28, bgcolor: 'background.paper', color: 'text.primary', border: 1, borderColor: 'divider', '&:hover': { bgcolor: 'background.paper' } }}><FiCamera size={14} /></IconButton>
          </Tooltip>
        )}
      </Ring>
      <Typography component="h2" sx={{ mt: 1.5, fontSize: 20, fontWeight: 600, letterSpacing: '-0.01em', lineHeight: 1.2, overflowWrap: 'anywhere' }}>{name || 'New member'}</Typography>
      <Typography sx={{ mt: 0.25, color: SIDE.muted }}>Not registered yet</Typography>
      {canImages && (
        <Box role="group" aria-label="Member records" sx={{ display: 'flex', gap: 0.75, mt: 1.25, justifyContent: 'center' }}>
          <Chip size="small" variant="outlined" clickable icon={<IoFingerPrint />} onClick={onPrints} aria-label="Fingerprints" sx={tag(prints ? 'success' : 'default')}
            label={prints ? `Fingerprint \u00b7 ${prints}/10` : 'Fingerprint'} />
          <Chip size="small" variant="outlined" clickable icon={<FiEdit3 />} onClick={onSign} aria-label="Signature" sx={tag(signed ? 'success' : 'default')}
            label={signed ? 'Signature \u00b7 taken' : 'Signature'} />
        </Box>
      )}
    </Box>
  )
}

/** The card on the right of a member who is being registered: what this screen is for. The photo and fingerprints are in the left panel. */
function NewMemberHeader({ canImages }: { canImages: boolean }) {
  return (
    <Box sx={(t) => ({ display: 'flex', alignItems: 'center', gap: 2, p: 1.75, ...glass(t, { elevated: true }) })}>
      <Box sx={{ minWidth: 0, flex: 1 }}>
        <Chip size="small" color="primary" label="New member" icon={<FiPlus />} sx={{ ...quietChip, mb: 0.75 }} />
        <Typography color="text.secondary">The member number is given when a Membership Officer approves. {canImages ? 'The photo, signature and fingerprints taken here are kept with the registration until then.' : 'A photo, signature and fingerprints need the edit-member-images permission.'}</Typography>
      </Box>
    </Box>
  )
}

/** Registering a new member on the member profile itself: the same tabs and fields, all open for typing. Sending asks a Membership Officer (not you) to approve. */
export function NewMemberScreen({ lk, canImages, onClose, onDone, initial }: { lk?: Lookups; canImages: boolean; onClose: () => void; onDone: (msg: string) => void; initial?: MemberDraft }) {
  const qc = useQueryClient()
  const me = useMe()
  const policy = useQuery({ queryKey: ['membership-policy'], queryFn: getMembershipPolicy, staleTime: 60_000, retry: false })
  // whether sending opens the member at once: a Membership Officer, and a super administrator allows officers to register directly
  const direct = !!policy.data?.officerRegistersDirectly && me.roles.some((r) => r.replace('ROLE_', '') === 'MEMBERSHIP_OFFICER')
  const [draft, setDraft] = useState<Record<string, string>>(() => ({ ...(initial?.fields ?? {}), ClientType: String(initial?.clientType ?? 1) }))
  // only the member types this cooperative registers (Settings > Membership > Member defaults) are offered; the first one until another is chosen
  const defaults = useQuery({ queryKey: ['member-defaults'], queryFn: getMemberDefaults, staleTime: 60_000, retry: false })
  const allowed = defaults.data?.types
  const typeFor = (t: string | undefined) => (allowed && !allowed.some((x) => String(x.code) === t) ? String(allowed[0]?.code ?? '') : (t ?? ''))
  const memberLk = lk && allowed ? { ...lk, clientType: Object.fromEntries(allowed.map((t) => [t.code, t.name])) } : lk
  // a draft is what was typed, saved on the server for this person only; photo, signature and fingerprints are not part of it
  const [draftId, setDraftId] = useState<number | null>(initial?.id ?? null)
  const [savedSnap, setSavedSnap] = useState<string | null>(() => (initial ? JSON.stringify({ ...initial.fields, ClientType: String(initial.clientType) }) : null))
  const [savedAt, setSavedAt] = useState<Date | null>(null)
  const [saving, setSaving] = useState(false)
  const [discarding, setDiscarding] = useState(false)
  const [photo, setPhoto] = useState<Blob | null>(null)
  const [photoUrl, setPhotoUrl] = useState<string | null>(null)
  const [consent, setConsent] = useState(false)
  const [prints, setPrints] = useState<StagedFinger[]>([])
  const [signature, setSignature] = useState<Blob | null>(null)
  const [signatureUrl, setSignatureUrl] = useState<string | null>(null)
  const [signatureOpen, setSignatureOpen] = useState(false)
  const [photoOpen, setPhotoOpen] = useState(false)
  const [printsOpen, setPrintsOpen] = useState(false)
  const typed = Object.keys(draft).filter((k) => k !== 'ClientType').length + (photo ? 1 : 0) + (signature ? 1 : 0) + prints.length
  const keepPhoto = (b: Blob) => { setPhoto(b); setPhotoUrl((old) => { if (old) URL.revokeObjectURL(old); return URL.createObjectURL(b) }) }
  const keepSignature = (b: Blob) => { setSignature(b); setSignatureUrl((old) => { if (old) URL.revokeObjectURL(old); return URL.createObjectURL(b) }) }
  const fieldCount = Object.keys(draft).filter((k) => k !== 'ClientType').length
  const unsaved = typed > 0 && (savedSnap === null || JSON.stringify(draft) !== savedSnap || !!photo || !!signature || prints.length > 0)
  useLeaveGuard(unsaved ? 'The new member you are registering has not been sent or saved as a draft.' : null)
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
    try {
      const encoded = photo ? await new Promise<string>((ok, no) => { const fr = new FileReader(); fr.onload = () => ok(String(fr.result).split(',')[1] ?? ''); fr.onerror = () => no(new Error('The photo could not be read.')); fr.readAsDataURL(photo) }) : null
      const signed = signature ? await new Promise<string>((ok, no) => { const fr = new FileReader(); fr.onload = () => ok(String(fr.result).split(',')[1] ?? ''); fr.onerror = () => no(new Error('The signature could not be read.')); fr.readAsDataURL(signature) }) : null
      const staged = encoded || signed || prints.length ? { photo: encoded, consent, fingerprints: prints, signature: signed } : undefined
      const r = await registerMember({ branchCode: 1, clientType: Number(typeFor(ClientType)), fields, staged })
      void qc.invalidateQueries({ queryKey: ['member-requests'] })
      if (draftId !== null) void deleteMemberDraft(draftId).catch(() => undefined).finally(() => qc.invalidateQueries({ queryKey: ['member-drafts'] }))
      // A Membership Officer's own registration is opened at once when a super administrator allows it (Membership policy); otherwise it waits for approval.
      onDone(r.status === 'APPROVED' ? `Member ${r.clientId ?? ''} opened: ${r.summary}` : `Registration sent for approval: ${r.summary}`)
      onClose()
    } catch (e) {
      notifyError((e as Error).message)
    } finally {
      setSaving(false)
    }
  }
  /** Keeps the typed fields as a draft (a new one, or the one being continued). Returns false when it could not be saved. */
  const saveDraft = async (): Promise<boolean> => {
    const { ClientType, ...fields } = draft
    setSaving(true)
    try {
      const saved = await saveMemberDraft(draftId, { branchCode: 1, clientType: Number(typeFor(ClientType)), fields })
      setDraftId(saved.id)
      setSavedSnap(JSON.stringify(draft))
      setSavedAt(new Date())
      void qc.invalidateQueries({ queryKey: ['member-drafts'] })
      return true
    } catch (e) {
      notifyError((e as Error).message)
      return false
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
          <Typography sx={{ flex: 1 }} role="status">
            Registering a new member. Last name, first name, date of birth, gender and civil status are required. {direct
              ? 'You are a Membership Officer and a super administrator allows officers to register directly, so sending opens the member at once.'
              : 'Sending asks a Membership Officer other than you to approve.'}
          </Typography>
          {savedAt && !unsaved && <Typography variant="body2" color="text.secondary" role="status">Draft saved {savedAt.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })}</Typography>}
          <Button size="small" onClick={() => (unsaved ? setDiscarding(true) : onClose())} disabled={saving}>{unsaved ? 'Cancel' : 'Close'}</Button>
          <Button size="small" variant="outlined" onClick={() => void saveDraft()} disabled={saving || fieldCount === 0 || !unsaved}>Save as draft</Button>
          <Button size="small" variant="contained" startIcon={<FiSave />} onClick={() => void send()} disabled={saving || !ready}>{saving ? 'Sending…' : direct ? 'Register member' : 'Send for approval'}</Button>
        </Box>
      </Box>
      <Profile id={FRESH_ID} lk={memberLk} fresh newHeader={<NewMemberHeader canImages={canImages} />} newIdentity={<NewMemberIdentity photo={photoUrl} prints={prints.length} signed={!!signature} canImages={canImages} onPhoto={() => setPhotoOpen(true)} onPrints={() => setPrintsOpen(true)} onSign={() => setSignatureOpen(true)} />} />
      {photoOpen && <MemberPhotoDialog id={FRESH_ID} name={draft.LName ? `${draft.LName}, ${draft.FName ?? ''}` : 'the new member'} onPick={keepPhoto} onClose={() => setPhotoOpen(false)} onDone={onDone} />}
      {signatureOpen && <SignatureDialog id={FRESH_ID} name={draft.LName ? `${draft.LName}, ${draft.FName ?? ''}` : 'the new member'} current={signatureUrl} canCapture onPick={keepSignature} onClose={() => setSignatureOpen(false)} onDone={onDone} />}
      {printsOpen && <StagedFingerprintDialog memberKey={`new-${[draft.LName, draft.FName, draft.DateOfBirth].join('|')}`} name={draft.LName ? `${draft.LName}, ${draft.FName ?? ''}` : 'the new member'} consent={consent} prints={prints}
        onChange={(c, p) => { setConsent(c); setPrints(p) }} onClose={() => setPrintsOpen(false)} />}
      <Dialog open={discarding} onClose={() => setDiscarding(false)}>
        <DialogTitle>Leave this registration?</DialogTitle>
        <DialogContent>
          <DialogContentText>
            {draftId !== null ? 'Changes since the draft was last saved will be lost.' : 'What you typed will be lost unless you save it as a draft.'}
            {(photo || signature || prints.length > 0) && ' The photo, signature and fingerprints are not kept in a draft; take them again when you continue.'}
          </DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDiscarding(false)} autoFocus>Keep editing</Button>
          <Button color="error" onClick={onClose}>{draftId !== null ? 'Leave without saving' : 'Discard'}</Button>
          <Button variant="contained" disabled={fieldCount === 0 || saving} onClick={() => void saveDraft().then((ok) => { if (ok) onClose() })}>Save as draft and close</Button>
        </DialogActions>
      </Dialog>
    </EditCtx.Provider>
  )
}
