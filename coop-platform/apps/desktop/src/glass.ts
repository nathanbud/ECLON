import type { Theme } from '@mui/material'
import { RADIUS } from './theme'

/** The soft colour wash behind glass surfaces, so the glass has something to refract. */
export const appBackground = (t: Theme) =>
  t.palette.mode === 'dark'
    ? 'radial-gradient(1200px 600px at 15% 0%, #1b3a6b 0%, transparent 60%), radial-gradient(900px 600px at 100% 100%, #3b1f5e 0%, transparent 55%), #0b0b0f'
    : 'radial-gradient(1200px 600px at 15% 0%, #bfe0ff 0%, transparent 60%), radial-gradient(900px 600px at 100% 100%, #e6d4ff 0%, transparent 55%), #f2f2f7'

/**
 * Frosted-glass surface: translucent fill, background blur, a lit rim and layered shadows for depth.
 * `elevated` adds the deep shadow used for the main card; the default suits smaller cards.
 */
export function glass(t: Theme, { elevated = false }: { elevated?: boolean } = {}) {
  const dark = t.palette.mode === 'dark'
  return {
    background: dark
      ? 'linear-gradient(145deg, rgba(58,58,64,0.62), rgba(28,28,32,0.5))'
      : 'linear-gradient(145deg, rgba(255,255,255,0.78), rgba(255,255,255,0.5))',
    backdropFilter: 'blur(24px) saturate(180%)',
    border: `1px solid ${dark ? 'rgba(255,255,255,0.16)' : 'rgba(255,255,255,0.7)'}`,
    borderRadius: `${RADIUS}px`,
    boxShadow: dark
      ? `inset 0 1px 0 rgba(255,255,255,0.12), 0 1px 2px rgba(0,0,0,0.3)${elevated ? ', 0 8px 24px rgba(0,0,0,0.3)' : ', 0 2px 8px rgba(0,0,0,0.18)'}`
      : `inset 0 1px 0 rgba(255,255,255,0.8), 0 1px 2px rgba(20,40,90,0.06)${elevated ? ', 0 8px 24px rgba(20,40,90,0.10)' : ', 0 2px 8px rgba(20,40,90,0.05)'}`,
  } as const
}
