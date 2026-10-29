import { Alert, Box, Button, Snackbar, Stack, TextField, Typography, type Theme } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { FiTrash2, FiUpload } from 'react-icons/fi'
import { getBranding, removeBrandingLogo, setBrandingText, uploadBrandingLogo } from './api'
import { BrandBlock, SystemLine } from './BrandBlock'
import { brand } from './brand'
import { appBackground, glass, LIGHT } from './glass'
import { tileFill } from './theme'
import { useLeaveGuard } from './leaveGuard'
import { useMe } from './session'

const NAME_MAX = 80
const TAGLINE_MAX = 140

/**
 * Settings > Sign-in screen (SUPERADMIN only): the cooperative's logo, name and tagline as the sign-in screen shows them, with a live preview. The wording is saved with the Save
 * button; a logo is uploaded or removed at once. Leave the name empty to show "Coop Platform" and its acronym again.
 */
export default function BrandingPage() {
  const me = useMe()
  const qc = useQueryClient()
  const superadmin = me.roles.some((r) => r.replace('ROLE_', '') === 'SUPERADMIN')
  const saved = useQuery({ queryKey: ['branding'], queryFn: getBranding })
  const [name, setName] = useState('')
  const [tagline, setTagline] = useState('')
  const [loaded, setLoaded] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const file = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (saved.data && !loaded) { setName(saved.data.coopName ?? ''); setTagline(saved.data.tagline ?? ''); setLoaded(true) }
  }, [saved.data, loaded])

  const changed = !!saved.data && (name.trim() !== (saved.data.coopName ?? '') || tagline.trim() !== (saved.data.tagline ?? ''))
  useLeaveGuard(changed ? 'The sign-in screen wording has changes that are not saved.' : null)

  const done = (msg: string) => (b: Awaited<ReturnType<typeof getBranding>>) => {
    qc.setQueryData(['branding'], b)
    setError(null)
    setNotice(msg)
  }
  const saveText = useMutation({ mutationFn: () => setBrandingText(name, tagline), onSuccess: done('Saved. The sign-in screen shows the new wording.'), onError: (e) => setError((e as Error).message) })
  const upload = useMutation({ mutationFn: (f: File) => uploadBrandingLogo(f), onSuccess: done('Logo saved.'), onError: (e) => setError((e as Error).message) })
  const remove = useMutation({ mutationFn: removeBrandingLogo, onSuccess: done('Logo removed. The Coop Platform mark is shown again.'), onError: (e) => setError((e as Error).message) })

  const logo = saved.data?.hasLogo ? `/api/public/branding/logo?v=${saved.data.version}` : null
  const shownName = name.trim() || brand.product
  const busy = saveText.isPending || upload.isPending || remove.isPending
  const tint = (t: Theme) => (t.palette.mode === 'dark' ? 'linear-gradient(160deg, #15233d 0%, #101827 55%, #1d1630 100%)' : `linear-gradient(160deg, ${LIGHT.washBlue} 0%, #E9EDF4 55%, ${LIGHT.washLilac} 100%)`)

  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: 2, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 1000, mx: 'auto' }}>
        <Typography component="h1" sx={{ fontSize: 20, fontWeight: 700, letterSpacing: '-0.02em', mb: 0.5 }}>Sign-in screen</Typography>
        <Typography sx={{ fontSize: 13.5, mb: 2 }} color="text.secondary">
          The logo, name and tagline on the left of the sign-in screen. Everyone sees them, including people who have not signed in yet.
        </Typography>
        {!superadmin && <Alert severity="info" sx={{ mb: 2 }}>Only a SUPERADMIN can change this. You can see how it looks.</Alert>}
        {error && <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>{error}</Alert>}
        {saved.isError && <Alert severity="error" sx={{ mb: 2 }}>The current settings could not be read.</Alert>}

        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'minmax(0, 1fr) minmax(0, 1fr)' }, gap: 2, alignItems: 'start' }}>
          <Box sx={(t) => ({ p: 2.5, ...glass(t) })}>
            <Stack spacing={2.5}>
              <Box>
                <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', mb: 1 }} color="text.secondary">Logo</Typography>
                <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
                  <Box sx={(t) => ({ width: 88, height: 88, flexShrink: 0, display: 'grid', placeItems: 'center', border: 1, borderColor: 'divider', borderRadius: '5px', bgcolor: t.palette.mode === 'dark' ? 'rgba(255,255,255,0.05)' : LIGHT.field })}>
                    {logo ? <Box component="img" src={logo} alt="Current logo" sx={{ maxWidth: 76, maxHeight: 76, objectFit: 'contain' }} /> : <Typography sx={{ fontSize: 12, textAlign: 'center', px: 1 }} color="text.secondary">No logo</Typography>}
                  </Box>
                  <Box>
                    <Stack direction="row" spacing={1}>
                      <Button size="small" variant="outlined" startIcon={<FiUpload />} disabled={!superadmin || busy} onClick={() => file.current?.click()}>{logo ? 'Replace logo' : 'Upload logo'}</Button>
                      {logo && <Button size="small" color="error" startIcon={<FiTrash2 />} disabled={!superadmin || busy} onClick={() => remove.mutate()}>Remove</Button>}
                    </Stack>
                    <input ref={file} type="file" hidden accept="image/png,image/jpeg,image/webp,image/svg+xml,.svg" onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ''; if (f) upload.mutate(f) }} />
                    <Typography sx={{ fontSize: 12, mt: 1 }} color="text.secondary">PNG, JPEG, WebP or SVG, up to 1 MB. A square picture at least 256 px wide looks best.</Typography>
                  </Box>
                </Stack>
              </Box>

              <TextField label="Cooperative name" value={name} onChange={(e) => setName(e.target.value)} fullWidth disabled={!superadmin}
                placeholder={brand.product} helperText={`${name.trim().length}/${NAME_MAX}. Empty shows “${brand.product}”.`} slotProps={{ htmlInput: { maxLength: NAME_MAX } }} />
              <TextField label="Tagline" value={tagline} onChange={(e) => setTagline(e.target.value)} fullWidth multiline minRows={2} disabled={!superadmin}
                helperText={`${tagline.trim().length}/${TAGLINE_MAX}. A short line under the name. Optional.`} slotProps={{ htmlInput: { maxLength: TAGLINE_MAX } }} />

              <Box sx={{ display: 'flex', gap: 1.5, alignItems: 'center' }}>
                <Button variant="contained" disabled={!superadmin || !changed || busy} onClick={() => saveText.mutate()}>Save</Button>
                {changed && <Button color="inherit" disabled={busy} onClick={() => { setName(saved.data?.coopName ?? ''); setTagline(saved.data?.tagline ?? '') }}>Undo</Button>}
              </Box>
            </Stack>
          </Box>

          <Box aria-label="Preview of the sign-in screen's left side" role="img" sx={(t) => ({ position: 'relative', overflow: 'hidden', minHeight: 360, display: 'grid', placeItems: 'center', p: 3, borderRadius: '5px', border: `1px solid ${t.palette.mode === 'dark' ? 'rgba(255,255,255,0.1)' : LIGHT.hairline}`, background: tint(t) })}>
            <Box aria-hidden sx={{ position: 'absolute', width: 300, height: 300, top: -110, left: -90, borderRadius: '50%', filter: 'blur(80px)', opacity: 0.28, bgcolor: tileFill.indigo }} />
            <Box sx={{ position: 'relative' }}>
              <BrandBlock name={shownName} tagline={tagline.trim() || null} logo={logo} custom={!!name.trim()} size={72} scale={0.62} />
            </Box>
            <Box sx={{ position: 'absolute', left: 16, bottom: 10 }}><SystemLine custom={!!name.trim()} size={11} /></Box>
            <Typography sx={{ position: 'absolute', bottom: 10, right: 16, fontSize: 11 }} color="text.secondary">Preview</Typography>
          </Box>
        </Box>
      </Box>
      <Snackbar open={!!notice} autoHideDuration={4000} onClose={() => setNotice(null)} message={notice} />
    </Box>
  )
}
