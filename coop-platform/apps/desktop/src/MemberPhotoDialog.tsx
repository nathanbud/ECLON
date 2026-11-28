import { Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, Stack, Typography } from '@mui/material'
import { useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useRef, useState } from 'react'
import { FiCamera, FiRefreshCw, FiUpload } from 'react-icons/fi'
import { uploadMemberPhoto } from './api'
import { notifyError } from './ui/notices'
import type { Id } from './clients'

const MAX_SIDE = 640

/** Scales any picture source down to at most 640 px on its long side and encodes it as JPEG, so the stored photo stays small. */
function toJpeg(source: CanvasImageSource, width: number, height: number): Promise<Blob> {
  const scale = Math.min(1, MAX_SIDE / Math.max(width, height))
  const canvas = document.createElement('canvas')
  canvas.width = Math.round(width * scale)
  canvas.height = Math.round(height * scale)
  canvas.getContext('2d')!.drawImage(source, 0, 0, canvas.width, canvas.height)
  return new Promise((resolve, reject) => canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('The picture could not be prepared.'))), 'image/jpeg', 0.88))
}

async function fileToJpeg(file: File): Promise<Blob> {
  const bitmap = await createImageBitmap(file)
  try {
    return await toJpeg(bitmap, bitmap.width, bitmap.height)
  } finally {
    bitmap.close()
  }
}

/**
 * Takes a picture with the camera or uploads one from a file, shows it, and saves it as the member's photo on confirmation.
 * Offered only to people granted the "edit client images" permission; the server checks it again.
 */
export default function MemberPhotoDialog({ id, name, onClose, onDone, onPick }: { id: Id; name: string; onClose: () => void; onDone: (msg: string) => void; onPick?: (photo: Blob) => void }) {
  const qc = useQueryClient()
  const video = useRef<HTMLVideoElement>(null)
  const stream = useRef<MediaStream | null>(null)
  const fileInput = useRef<HTMLInputElement>(null)
  const [camera, setCamera] = useState(false)
  const [blob, setBlob] = useState<Blob | null>(null)
  const [preview, setPreview] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const stopCamera = useCallback(() => {
    stream.current?.getTracks().forEach((t) => t.stop())
    stream.current = null
    setCamera(false)
  }, [])
  useEffect(() => stopCamera, [stopCamera])
  useEffect(() => () => { if (preview) URL.revokeObjectURL(preview) }, [preview])

  const keep = (b: Blob) => { setBlob(b); setPreview(URL.createObjectURL(b)) }

  const startCamera = async () => {
    setBlob(null)
    setPreview(null)
    if (!navigator.mediaDevices?.getUserMedia) { notifyError('This window cannot use a camera. Upload a picture instead.'); return }
    try {
      const s = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'user', width: { ideal: 1280 }, height: { ideal: 960 } }, audio: false })
      stream.current = s
      setCamera(true)
    } catch {
      notifyError('The camera could not be opened. Check that it is connected and that this app may use it, or upload a picture instead.')
    }
  }
  // The video element exists only once the camera view is showing.
  useEffect(() => {
    if (camera && video.current && stream.current) { video.current.srcObject = stream.current; void video.current.play() }
  }, [camera])

  const snap = async () => {
    const v = video.current
    if (!v || !v.videoWidth) return
    try { keep(await toJpeg(v, v.videoWidth, v.videoHeight)); stopCamera() } catch (e) { notifyError((e as Error).message) }
  }

  const pick = async (file: File | undefined) => {
    if (!file) return
    stopCamera()
    if (!/^image\/(jpeg|png|webp|bmp|gif)$/.test(file.type)) { notifyError('Choose a picture file (JPEG or PNG).'); return }
    try { keep(await fileToJpeg(file)) } catch { notifyError('That file could not be read as a picture.') }
  }

  const save = async () => {
    if (!blob) return
    setSaving(true)
    if (onPick) { onPick(blob); onDone('Photo kept with the registration'); onClose(); return }
    try {
      await uploadMemberPhoto(id.branchCode, id.clientId, blob)
      await qc.invalidateQueries({ queryKey: ['member-image', 'photo'] })
      onDone('Photo saved')
      onClose()
    } catch (e) {
      notifyError((e as Error).message)
      setSaving(false)
    }
  }

  return (
    <Dialog open onClose={saving ? undefined : onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Photo of {name}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 0.5 }}>
          <Box sx={{ aspectRatio: '4 / 3', bgcolor: 'action.hover', borderRadius: 1, overflow: 'hidden', display: 'grid', placeItems: 'center' }}>
            {camera
              ? <video ref={video} muted playsInline style={{ width: '100%', height: '100%', objectFit: 'cover', transform: 'scaleX(-1)' }} />
              : preview
                ? <Box component="img" src={preview} alt="New photo" sx={{ width: '100%', height: '100%', objectFit: 'contain' }} />
                : <Typography color="text.secondary">Take a picture or choose a file.</Typography>}
          </Box>
          <Stack direction="row" spacing={1} sx={{ justifyContent: 'center' }}>
            {camera
              ? <Button variant="contained" startIcon={<FiCamera />} onClick={() => void snap()}>Capture</Button>
              : <Button startIcon={preview ? <FiRefreshCw /> : <FiCamera />} onClick={() => void startCamera()}>{preview ? 'Retake' : 'Take photo'}</Button>}
            <Button startIcon={<FiUpload />} onClick={() => fileInput.current?.click()}>Upload</Button>
            <input ref={fileInput} type="file" accept="image/jpeg,image/png" hidden onChange={(e) => { void pick(e.target.files?.[0]); e.target.value = '' }} />
          </Stack>
          <Typography variant="body2" color="text.secondary">The new picture is added and shown from now on. Earlier photos are kept.</Typography>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={saving}>Cancel</Button>
        <Button variant="contained" disabled={!blob || saving} onClick={() => void save()}>Save photo</Button>
      </DialogActions>
    </Dialog>
  )
}
