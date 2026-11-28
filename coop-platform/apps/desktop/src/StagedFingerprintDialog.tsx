import { Alert, Box, Button, Checkbox, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, LinearProgress, Stack, TextField, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { checkFingerprintDuplicate, type FingerprintSummary, type StagedFinger } from './api'
import { Fingers, LEFT, RIGHT } from './FingerprintDialog'
import { findScanner } from './fingerprintScanner'

const MIN_QUALITY = 50

/**
 * Fingerprints for a member who is still being registered. The member's consent is recorded, fingers are scanned and kept with the registration (not yet with a member), and a
 * finger already enrolled for someone else, or held for another registration, is refused at once. They become the member's fingerprints when the registration is approved.
 */
export default function StagedFingerprintDialog({ memberKey, name, consent, prints, onChange, onClose }: {
  memberKey: string; name: string; consent: boolean; prints: StagedFinger[]; onChange: (consent: boolean, prints: StagedFinger[]) => void; onClose: () => void
}) {
  const scanner = useQuery({ queryKey: ['scanner'], queryFn: findScanner, staleTime: 60_000 })
  const [finger, setFinger] = useState<number | null>(null)
  const [agreed, setAgreed] = useState(false)
  const [busy, setBusy] = useState<string | null>(null)
  const [message, setMessage] = useState<{ kind: 'success' | 'error'; text: string } | null>(null)
  const [confirmDrop, setConfirmDrop] = useState(false)

  // The same chooser as for a member on file, fed from the scans held here.
  const summary: FingerprintSummary = {
    consent: consent ? { version: '', givenAt: '', recordedBy: '' } : null,
    fingers: prints.map((p) => ({ finger: p.finger, name: '', quality: p.quality, device: p.device, capturedAt: '', capturedBy: '' })),
    minQuality: MIN_QUALITY, threshold: 0, recommended: 2, simulated: !!scanner.data?.simulated, consentVersion: '',
  }

  const scan = async () => {
    const sc = scanner.data
    if (!sc || finger == null) return
    setMessage(null)
    setBusy('Place the finger on the scanner…')
    try {
      const r = await sc.capture(memberKey, finger)
      if (r.quality < MIN_QUALITY) { setMessage({ kind: 'error', text: `The scan quality is ${r.quality} and at least ${MIN_QUALITY} is needed. Clean the finger and the glass and scan again.` }); return }
      const others = prints.filter((p) => p.finger !== finger)
      const check = await checkFingerprintDuplicate({ template: r.template, format: r.format, staged: others })
      if (check.duplicate) { setMessage({ kind: 'error', text: check.message ?? 'This fingerprint is already enrolled.' }); return }
      onChange(true, [...others, { finger, template: r.template, format: r.format, quality: r.quality, device: r.device }])
      setMessage({ kind: 'success', text: `${RIGHT.includes(finger) ? 'Right' : 'Left'} hand finger kept with the registration.` })
    } catch (e) {
      setMessage({ kind: 'error', text: (e as Error).message })
    } finally {
      setBusy(null)
    }
  }

  const enrolled = finger != null && prints.some((p) => p.finger === finger)
  return (
    <Dialog open onClose={busy ? undefined : onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Fingerprints of {name}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 0.5 }}>
          {scanner.data?.simulated && <Alert severity="warning">No scanner is connected, so a simulated one is used. It is for testing only: do not enrol real members with it.</Alert>}
          {message && <Alert severity={message.kind} onClose={() => setMessage(null)}>{message.text}</Alert>}
          {!consent ? (
            <Box sx={{ p: 1.5, border: 1, borderColor: 'divider', borderRadius: 1 }}>
              <Typography sx={{ fontWeight: 600, mb: 0.5 }}>Member's consent</Typography>
              <Typography color="text.secondary">
                A fingerprint is sensitive personal information. It is used only to confirm this member's identity, is kept encrypted, is never shown or shared, and is erased if the member withdraws consent.
                Read this to the member and record their agreement before scanning.
              </Typography>
              <FormControlLabel control={<Checkbox checked={agreed} onChange={(_, v) => setAgreed(v)} />} label="The member has been told this and agrees" sx={{ mt: 0.5 }} />
              <Box><Button variant="contained" size="small" disabled={!agreed} onClick={() => onChange(true, prints)}>Record consent</Button></Box>
            </Box>
          ) : (
            <>
              <Typography variant="body2" color="text.secondary">
                Kept with the registration until a Membership Officer approves it. {prints.length} of 10 fingers scanned{prints.length < 2 ? ' (scan at least 2 in case one is injured)' : ''}.
              </Typography>
              <Stack direction="row" spacing={3} sx={{ flexWrap: 'wrap', rowGap: 1.5 }}>
                <Fingers title="Right hand" ids={RIGHT} s={summary} selected={finger} onPick={setFinger} />
                <Fingers title="Left hand" ids={LEFT} s={summary} selected={finger} onPick={setFinger} />
              </Stack>
              {busy && <Box><LinearProgress /><Typography sx={{ mt: 0.5 }}>{busy}</Typography></Box>}
              <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', rowGap: 1 }}>
                <Button variant="contained" size="small" disabled={finger == null || !!busy || !scanner.data} onClick={() => void scan()}>{enrolled ? 'Scan again' : 'Scan finger'}</Button>
                {enrolled && <Button size="small" color="error" disabled={!!busy} onClick={() => onChange(true, prints.filter((p) => p.finger !== finger))}>Remove this finger</Button>}
                <Button size="small" color="error" disabled={!!busy} onClick={() => setConfirmDrop(true)}>Withdraw consent</Button>
              </Stack>
              {confirmDrop && (
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                  <TextField size="small" disabled value="Remove consent and every scanned finger?" sx={{ flex: 1 }} />
                  <Button size="small" color="error" variant="contained" onClick={() => { onChange(false, []); setConfirmDrop(false); setFinger(null) }}>Remove</Button>
                  <Button size="small" onClick={() => setConfirmDrop(false)}>Keep</Button>
                </Stack>
              )}
            </>
          )}
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose} disabled={!!busy}>Done</Button></DialogActions>
    </Dialog>
  )
}
