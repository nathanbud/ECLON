import { Box, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { getBranding, type Branding } from './api'
import { brand } from './brand'
import { Logo } from './TopBar'

/** The sign-in screen's wording: what the SUPERADMIN set, or the defaults in brand.ts where nothing is set (or the server cannot be asked). */
export function useBranding() {
  const q = useQuery({ queryKey: ['branding'], queryFn: getBranding, staleTime: 60_000, retry: false })
  const b: Branding | undefined = q.data
  return {
    name: b?.coopName || brand.product,
    custom: !!b?.coopName,
    tagline: b?.tagline || null,
    logo: b?.hasLogo ? `/api/public/branding/logo?v=${b.version}` : null,
  }
}

/** The cooperative's mark: its own logo when one is set, otherwise the Coop Platform mark. */
export function Mark({ size, logo, alt }: { size: number; logo?: string | null; alt: string }) {
  if (logo) return <Box component="img" src={logo} alt={`${alt} logo`} sx={{ width: size, height: size, objectFit: 'contain' }} />
  return <Box sx={{ display: 'flex', '& svg': { width: size, height: size } }}><Logo /></Box>
}

/** The logo, name and tagline as the sign-in screen shows them; the settings form shows the same block as a live preview. */
export function BrandBlock({ name, tagline, logo, custom, size = 104, scale = 1 }: { name: string; tagline: string | null; logo: string | null; custom: boolean; size?: number; scale?: number }) {
  // A long name is set a little smaller so it keeps to two or three lines.
  const fit = name.length > 56 ? 0.68 : name.length > 36 ? 0.82 : 1
  return (
    <Box sx={{ textAlign: 'center' }}>
      <Box sx={{ display: 'inline-flex', mb: 3 * scale }}><Mark size={size} logo={logo} alt={name} /></Box>
      <Typography component="h1" sx={{ fontSize: { xs: 36 * scale * fit, lg: 48 * scale * fit }, fontWeight: 700, letterSpacing: '-0.035em', lineHeight: 1.1, overflowWrap: 'anywhere' }}>{name}</Typography>
      {tagline
        ? <Typography sx={{ mt: 1.5 * scale, fontSize: 19 * scale, lineHeight: 1.4, maxWidth: 440, mx: 'auto' }} color="text.secondary">{tagline}</Typography>
        : !custom && <Typography sx={{ mt: 1.25, fontSize: 14 * scale, letterSpacing: '0.14em', textTransform: 'uppercase', fontWeight: 600 }} color="text.secondary">{brand.coop}</Typography>}
      <Box aria-hidden sx={(t) => ({ width: 40, height: 2, borderRadius: 1, mx: 'auto', my: 3 * scale, bgcolor: t.palette.mode === 'dark' ? 'rgba(255,255,255,0.25)' : 'rgba(0,0,0,0.18)' })} />
      <Typography sx={{ fontSize: 15 * scale }} color="text.secondary">{custom ? `${brand.product} · ${brand.system}` : brand.system}</Typography>
    </Box>
  )
}
