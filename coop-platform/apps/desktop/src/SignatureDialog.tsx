import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, Stack, Typography } from '@mui/material'
import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'
import { FiRotateCcw, FiUpload } from 'react-icons/fi'
import { uploadMemberSignature } from './api'
import type { Id } from './clients'

// The pad is drawn at a fixed size and scaled by the page, so a saved signature looks the same on every screen.
const W = 800
const H = 280
const INK = '#111111'

/** A white page, so a signature saved as JPEG (which has no transparency) is dark ink on white. */
function blank(canvas: HTMLCanvasElement) {
  const g = canvas.getContext('2d')
  if (!g) return
  g.fillStyle = '#ffffff'
  g.fillRect(0, 0, W, H)
}

/**
 * Captures a member's signature: they sign on the pad with a mouse, finger or pen, or a scanned signature is chosen from a file and placed on the pad. Save adds it as the member's
 * newest signature (earlier ones are kept). Offered only to people granted the "edit client images" permission; the server checks it again.
 */
export default function SignatureDialog({ id, name, onClose, onDone, onPick, current = null, canCapture = true }: {
  id: Id; name: string; onClose: () => void; onDone: (msg: string) => void
  /** When given (registering a new member, who has no number yet), the signature is handed here instead of being uploaded. */
  onPick?: (signature: Blob) => void
  /** The signature on file, as a picture address, or null when there is none. */
  current?: string | null
  /** May this person capture a new one? Without it the dialog only shows what is on file. */
  canCapture?: boolean
}) {
  const qc = useQueryClient()
  const pad = useRef<HTMLCanvasElement>(null)
  const fileInput = useRef<HTMLInputElement>(null)
  const last = useRef<{ x: number; y: number } | null>(null)
  const [inked, setInked] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  // Opens on the pad when there is nothing on file to look at; otherwise shows the signature first, with a button to replace it.
  const [mode, setMode] = useState<'view' | 'pad'>(canCapture && !current ? 'pad' : 'view')

  useEffect(() => { if (mode === 'pad' && pad.current) blank(pad.current) }, [mode])

  const at = (e: ReactPointerEvent<HTMLCanvasElement>) => {
    const r = e.currentTarget.getBoundingClientRect()
    return { x: ((e.clientX - r.left) / (r.width || 1)) * W, y: ((e.clientY - r.top) / (r.height || 1)) * H }
  }
  const down = (e: ReactPointerEvent<HTMLCanvasElement>) => {
    e.currentTarget.setPointerCapture?.(e.pointerId)
    const p = at(e)
    last.current = p
    const g = e.currentTarget.getContext('2d')
    if (!g) return
    // a single tap leaves a dot
    g.fillStyle = INK
    g.beginPath()
    g.arc(p.x, p.y, 1.6, 0, Math.PI * 2)
    g.fill()
    setInked(true)
  }
  const move = (e: ReactPointerEvent<HTMLCanvasElement>) => {
    if (!last.current) return
    const g = e.currentTarget.getContext('2d')
    if (!g) return
    const p = at(e)
    g.strokeStyle = INK
    g.lineWidth = 3.2
    g.lineCap = 'round'
    g.lineJoin = 'round'
    g.beginPath()
    g.moveTo(last.current.x, last.current.y)
    g.lineTo(p.x, p.y)
    g.stroke()
    last.current = p
  }
  const up = () => { last.current = null }

  const clear = () => { if (pad.current) blank(pad.current); setInked(false); setError(null) }

  const pick = async (file: File | undefined) => {
    if (!file || !pad.current) return
    if (!/^image\/(jpeg|png|webp|bmp|gif)$/.test(file.type)) { setError('Choose a picture file (JPEG or PNG).'); return }
    try {
      const bitmap = await createImageBitmap(file)
      try {
        blank(pad.current)
        const scale = Math.min(W / bitmap.width, H / bitmap.height)
        const w = bitmap.width * scale
        const h = bitmap.height * scale
        pad.current.getContext('2d')?.drawImage(bitmap, (W - w) / 2, (H - h) / 2, w, h)
        setInked(true)
        setError(null)
      } finally { bitmap.close() }
    } catch { setError('That file could not be read as a picture.') }
  }

  const save = async () => {
    const canvas = pad.current
    if (!canvas || !inked) return
    setSaving(true)
    try {
      const blob = await new Promise<Blob>((resolve, reject) => canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('The signature could not be prepared.'))), 'image/jpeg', 0.92))
      if (onPick) { onPick(blob); onDone('Signature kept with the registration'); onClose(); return }
      await uploadMemberSignature(id.branchCode, id.clientId, blob)
      await qc.invalidateQueries({ queryKey: ['member-image', 'signature'] })
      onDone('Signature saved')
      onClose()
    } catch (e) {
      setError((e as Error).message)
      setSaving(false)
    }
  }

  return (
    <Dialog open onClose={saving ? undefined : onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Signature of {name}</DialogTitle>
      {mode === 'view' ? (
        <>
          <DialogContent>
            {/* Signatures are dark ink on a light background, so they always sit on a white surface, in both themes. */}
            <Box sx={{ bgcolor: '#fff', borderRadius: 1, border: 1, borderColor: 'divider', minHeight: 140, display: 'grid', placeItems: 'center', p: 1 }}>
              {current
                ? <Box component="img" src={current} alt={`Signature of ${name}`} sx={{ maxWidth: '100%', maxHeight: 260, objectFit: 'contain' }} />
                : <Typography sx={{ fontSize: 13, color: '#6b6b6b' }}>No signature on file</Typography>}
            </Box>
          </DialogContent>
          <DialogActions>
            <Button onClick={onClose}>Close</Button>
            {canCapture && <Button variant="contained" onClick={() => setMode('pad')}>{current ? 'Replace signature' : 'Capture signature'}</Button>}
          </DialogActions>
        </>
      ) : (
      <>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 0.5 }}>
          {error && <Alert severity="error">{error}</Alert>}
          {/* Dark ink on a white page in both themes, like the signature card the member signs. */}
          <Box sx={{ bgcolor: '#fff', borderRadius: 1, border: 1, borderColor: 'divider', overflow: 'hidden', position: 'relative' }}>
            <canvas
              ref={pad} width={W} height={H} aria-label="Signature pad"
              onPointerDown={down} onPointerMove={move} onPointerUp={up} onPointerCancel={up} onPointerLeave={up}
              style={{ display: 'block', width: '100%', height: 'auto', aspectRatio: `${W} / ${H}`, touchAction: 'none', cursor: 'crosshair' }}
            />
            {!inked && <Typography aria-hidden sx={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center', fontSize: 13, pointerEvents: 'none', color: '#6b6b6b' }}>Sign here</Typography>}
          </Box>
          <Stack direction="row" spacing={1} sx={{ justifyContent: 'center' }}>
            <Button startIcon={<FiRotateCcw />} onClick={clear} disabled={saving || !inked}>Clear</Button>
            <Button startIcon={<FiUpload />} onClick={() => fileInput.current?.click()} disabled={saving}>Use a scanned signature</Button>
            <input ref={fileInput} type="file" accept="image/jpeg,image/png" hidden onChange={(e) => { void pick(e.target.files?.[0]); e.target.value = '' }} />
          </Stack>
          <Typography sx={{ fontSize: 12 }} color="text.secondary">The new signature is added and shown from now on. Earlier signatures are kept.</Typography>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={saving}>Cancel</Button>
        <Button variant="contained" disabled={!inked || saving} onClick={() => void save()}>Save signature</Button>
      </DialogActions>
      </>
      )}
    </Dialog>
  )
}
