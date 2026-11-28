import { Alert, Box, Button, Checkbox, Chip, CircularProgress, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, LinearProgress, Stack, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { FiCheck } from 'react-icons/fi'
import { IoFingerPrint } from 'react-icons/io5'
import { enrolFingerprint, getFingerprints, giveBiometricConsent, revokeFingerprint, verifyFingerprint, withdrawBiometricConsent, type FingerprintSummary } from './api'
import type { Id } from './clients'
import { findScanner, type ScanResult } from './fingerprintScanner'

const NAMES: Record<number, string> = {
  1: 'Thumb', 2: 'Index', 3: 'Middle', 4: 'Ring', 5: 'Little', 6: 'Thumb', 7: 'Index', 8: 'Middle', 9: 'Ring', 10: 'Little',
}
export const RIGHT = [1, 2, 3, 4, 5]
export const LEFT = [6, 7, 8, 9, 10]

export function Fingers({ title, ids, s, selected, onPick }: { title: string; ids: number[]; s: FingerprintSummary; selected: number | null; onPick: (f: number) => void }) {
  return (
    <Box>
      <Typography variant="overline" sx={{ display: 'block', mb: 0.75 }} color="text.secondary">{title}</Typography>
      <Stack direction="row" spacing={0.75}>
        {ids.map((f) => {
          const row = s.fingers.find((x) => x.finger === f)
          return (
            <Chip key={f} clickable onClick={() => onPick(f)} color={row ? 'success' : 'default'} variant={selected === f ? 'filled' : 'outlined'}
              icon={row ? <FiCheck /> : <IoFingerPrint />} label={NAMES[f]} aria-label={`${title} ${NAMES[f]}${row ? `, enrolled, quality ${row.quality}` : ', not enrolled'}`}
              sx={{ outline: selected === f ? '2px solid' : 'none', outlineColor: 'primary.main' }} />
          )
        })}
      </Stack>
    </Box>
  )
}

/**
 * Fingerprint enrolment and check for one member. Steps: the member's consent is recorded; a finger is chosen and scanned; a poor scan asks for another; the server refuses a
 * finger already enrolled for someone else. Anyone signed in can check a finger; recording consent, scanning and erasing need the edit permissions.
 */
export default function FingerprintDialog({ id, name, canManage, onClose, onDone }: { id: Id; name: string; canManage: boolean; onClose: () => void; onDone: (msg: string) => void }) {
  const qc = useQueryClient()
  const key = `${id.branchCode}-${id.clientId}`
  const summary = useQuery({ queryKey: ['fingerprints', id], queryFn: () => getFingerprints(id.branchCode, id.clientId) })
  const scanner = useQuery({ queryKey: ['scanner'], queryFn: findScanner, staleTime: 60_000 })
  const [finger, setFinger] = useState<number | null>(null)
  const [agreed, setAgreed] = useState(false)
  const [busy, setBusy] = useState<string | null>(null)
  const [last, setLast] = useState<ScanResult | null>(null)
  const [message, setMessage] = useState<{ kind: 'success' | 'error' | 'info'; text: string } | null>(null)
  const [reasonFor, setReasonFor] = useState<'finger' | 'consent' | null>(null)
  const [reason, setReason] = useState('')
  const refresh = (s?: FingerprintSummary) => { if (s) qc.setQueryData(['fingerprints', id], s); else void qc.invalidateQueries({ queryKey: ['fingerprints', id] }) }
  const fail = (e: unknown) => setMessage({ kind: 'error', text: (e as Error).message })

  const consent = useMutation({ mutationFn: () => giveBiometricConsent(id.branchCode, id.clientId), onSuccess: (s) => { refresh(s); setMessage({ kind: 'success', text: 'Consent recorded. Choose a finger to scan.' }) }, onError: fail })

  const scan = async (which: number, then: (r: ScanResult) => Promise<void>) => {
    const sc = scanner.data
    if (!sc) return
    setMessage(null)
    setLast(null)
    setBusy('Place the finger on the scanner…')
    try {
      const r = await sc.capture(key, which)
      setLast(r)
      await then(r)
    } catch (e) { fail(e) } finally { setBusy(null) }
  }

  const enrol = () => {
    if (finger == null) return
    void scan(finger, async (r) => {
      if (r.quality < (summary.data?.minQuality ?? 50)) { setMessage({ kind: 'error', text: `The scan quality is ${r.quality} and at least ${summary.data?.minQuality} is needed. Clean the finger and the glass and scan again.` }); return }
      refresh(await enrolFingerprint(id.branchCode, id.clientId, { finger, template: r.template, format: r.format, quality: r.quality, device: r.device }))
      setMessage({ kind: 'success', text: `${NAMES[finger]} enrolled.` })
      onDone('Fingerprint saved')
    })
  }

  const check = () => void scan(finger ?? summary.data?.fingers[0]?.finger ?? 1, async (r) => {
    const v = await verifyFingerprint(id.branchCode, id.clientId, { template: r.template, format: r.format })
    setMessage(v.matched ? { kind: 'success', text: `Match: ${v.fingerName} (score ${Math.round(v.score * 100)}).` } : { kind: 'error', text: `No match (best score ${Math.round(v.score * 100)}). Try another finger or scan again.` })
  })

  const erase = async () => {
    setBusy('Working…')
    try {
      const s = reasonFor === 'finger' && finger != null ? await revokeFingerprint(id.branchCode, id.clientId, finger, reason) : await withdrawBiometricConsent(id.branchCode, id.clientId, reason)
      refresh(s)
      setMessage({ kind: 'success', text: reasonFor === 'finger' ? 'Fingerprint erased.' : 'Consent withdrawn and every fingerprint erased.' })
      setReasonFor(null)
      setReason('')
      onDone('Fingerprint record updated')
    } catch (e) { fail(e) } finally { setBusy(null) }
  }

  const s = summary.data
  const enrolled = finger != null && !!s?.fingers.some((f) => f.finger === finger)
  return (
    <Dialog open onClose={busy ? undefined : onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Fingerprints of {name}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 0.5 }}>
          {scanner.data?.simulated && <Alert severity="warning">No scanner is connected, so a simulated one is used. It is for testing only: do not enrol real members with it.</Alert>}
          {summary.isError && <Alert severity="error">{(summary.error as Error).message}</Alert>}
          {message && <Alert severity={message.kind} onClose={() => setMessage(null)}>{message.text}</Alert>}
          {!s && !summary.isError && <CircularProgress size={22} />}
          {s && !s.consent && (
            <Box sx={{ p: 1.5, border: 1, borderColor: 'divider', borderRadius: 1 }}>
              <Typography sx={{ fontWeight: 600, mb: 0.5 }}>Member's consent</Typography>
              <Typography color="text.secondary">
                A fingerprint is sensitive personal information. It is used only to confirm this member's identity, is kept encrypted, is never shown or shared, and is erased if the member withdraws consent.
                Read this to the member and record their agreement before scanning.
              </Typography>
              {canManage
                ? <>
                    <FormControlLabel control={<Checkbox checked={agreed} onChange={(_, v) => setAgreed(v)} />} label="The member has been told this and agrees" sx={{ mt: 0.5 }} />
                    <Box><Button variant="contained" size="small" disabled={!agreed || consent.isPending} onClick={() => consent.mutate()}>Record consent</Button></Box>
                  </>
                : <Typography sx={{ mt: 1 }} color="text.secondary">No consent is on file. A person with edit permission can record it.</Typography>}
            </Box>
          )}
          {s?.consent && (
            <>
              <Typography variant="body2" color="text.secondary">Consent recorded {s.consent.givenAt?.slice(0, 16)} by {s.consent.recordedBy}. {s.fingers.length} of 10 fingers enrolled{s.fingers.length < s.recommended ? ` (enrol at least ${s.recommended} in case one is injured)` : ''}.</Typography>
              <Stack direction="row" spacing={3} sx={{ flexWrap: 'wrap', rowGap: 1.5 }}>
                <Fingers title="Right hand" ids={RIGHT} s={s} selected={finger} onPick={setFinger} />
                <Fingers title="Left hand" ids={LEFT} s={s} selected={finger} onPick={setFinger} />
              </Stack>
              {busy && <Box><LinearProgress /><Typography sx={{ mt: 0.5 }}>{busy}</Typography></Box>}
              {last && !busy && <Typography variant="body2" color="text.secondary">Last scan quality: {last.quality} (needs {s.minQuality})</Typography>}
              <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', rowGap: 1 }}>
                {canManage && <Button variant="contained" size="small" disabled={finger == null || !!busy || !scanner.data} onClick={enrol}>{enrolled ? 'Scan again' : 'Scan and enrol'}</Button>}
                <Button size="small" disabled={s.fingers.length === 0 || !!busy || !scanner.data} onClick={check}>Check a finger</Button>
                {canManage && enrolled && <Button size="small" color="error" disabled={!!busy} onClick={() => setReasonFor('finger')}>Erase this finger</Button>}
                {canManage && <Button size="small" color="error" disabled={!!busy} onClick={() => setReasonFor('consent')}>Withdraw consent</Button>}
              </Stack>
              {reasonFor && (
                <Stack direction="row" spacing={1}>
                  <TextField size="small" autoFocus label={reasonFor === 'finger' ? 'Reason for erasing this finger' : 'Reason for withdrawing consent (erases all fingers)'} value={reason} onChange={(e) => setReason(e.target.value)} sx={{ flex: 1 }} />
                  <Button size="small" color="error" variant="contained" disabled={reason.trim().length < 3 || !!busy} onClick={() => void erase()}>Erase</Button>
                  <Button size="small" onClick={() => { setReasonFor(null); setReason('') }}>Cancel</Button>
                </Stack>
              )}
            </>
          )}
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose} disabled={!!busy}>Close</Button></DialogActions>
    </Dialog>
  )
}
