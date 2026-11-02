import { Box, Button, ButtonBase, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { FiInbox } from 'react-icons/fi'
import { getInbox, getTdMaturityCount } from './api'
import { glass } from './glass'
import { byDomain, type Screen } from './nav/screens'
import { useMe } from './session'
import { tileFill, useThemeMode, type Tint } from './theme'

export type View = 'home' | 'members' | 'accounts' | 'accounting' | 'approvals' | 'users' | 'audit' | 'loansetup' | 'amortization' | 'settings' | 'frontoffice' | 'loanapp' | 'procurement' | 'inventory' | 'pos' | 'assets' | 'reports' | 'documents' | 'workflows' | 'membershippolicy' | 'documentation' | 'branding' | 'privacy' | 'cic' | 'dividends'

// One shared motion rhythm: 220ms ease-out, items staggered 35ms apart.
const EASE = 'cubic-bezier(.2,.8,.2,1)'
const enter = (i: number) => ({
  animation: `tileIn 360ms ${EASE} both`,
  animationDelay: `${Math.min(i, 14) * 35}ms`,
  '@keyframes tileIn': { from: { opacity: 0, transform: 'translateY(8px) scale(0.97)' }, to: { opacity: 1, transform: 'none' } },
  '@media (prefers-reduced-motion: reduce)': { animation: 'none', transition: 'none' },
})

function Tile({ screen, color, index, onOpen, badge }: { screen: Screen; color: Tint; index: number; onOpen: () => void; badge?: number }) {
  const { resolved } = useThemeMode()
  const dark = resolved === 'dark'
  const c = tileFill[color]
  return (
    <ButtonBase
      onClick={onOpen} aria-label={badge ? `${screen.label}, ${badge} waiting` : screen.label} title={screen.note}
      sx={{
        minWidth: 0, aspectRatio: '1 / 1', borderRadius: '5px', overflow: 'hidden', color: '#fff', textAlign: 'center', position: 'relative', p: 0.75,
        display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 0.75,
        background: `linear-gradient(160deg, ${c}F2 0%, ${c} 100%)`,
        boxShadow: `inset 0 0 0 1px rgba(255,255,255,${dark ? 0.12 : 0.18}), 0 1px 2px rgba(0,0,0,0.12)`,
        transition: `transform 220ms ${EASE}, box-shadow 220ms ${EASE}`,
        '&:hover': { transform: 'translateY(-1px)', boxShadow: 'inset 0 0 0 1px rgba(255,255,255,0.22), 0 4px 10px rgba(0,0,0,0.16)' },
        '&:active': { transform: 'scale(0.97)', transitionDuration: '90ms' },
        '&.Mui-focusVisible': { outline: '3px solid #fff', outlineOffset: '-4px' },
        ...enter(index),
      }}
    >
      <Box aria-hidden sx={{ fontSize: 26, lineHeight: 0, display: 'flex', opacity: 0.95, flexShrink: 0 }}>{screen.icon}</Box>
      <Typography sx={{ fontWeight: 600, fontSize: 11.5, lineHeight: 1.2, width: '100%', display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden', wordBreak: 'break-word' }}>{screen.label}</Typography>
      {!!badge && (
        <Box aria-hidden sx={{ position: 'absolute', top: 4, right: 4, minWidth: 18, height: 18, px: 0.5, borderRadius: '5px', bgcolor: '#fff', color: '#1d1d1f', fontSize: 11, fontWeight: 700, display: 'grid', placeItems: 'center', flexShrink: 0 }}>{badge}</Box>
      )}
    </ButtonBase>
  )
}

const when = (at: string | null) => (at ? new Date(at.replace(' ', 'T')).toLocaleDateString(undefined, { month: '2-digit', day: '2-digit', year: 'numeric' }) : '')

/** What is waiting on this person, newest work last: the first few items, each one a link to the screen that decides it. */
function WaitingCard({ onOpen }: { onOpen: (v: View, tab?: string) => void }) {
  const { data } = useQuery({ queryKey: ['inbox'], queryFn: getInbox, refetchInterval: 30_000, retry: false })
  if (!data) return null
  const items = data.items.slice(0, 3)
  return (
    <Box component="section" aria-label="Waiting for you" sx={(t) => ({ p: 1.25, mb: 1.5, ...glass(t) })}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: items.length ? 1 : 0 }}>
        <Box aria-hidden sx={{ display: 'flex', color: 'text.secondary' }}><FiInbox /></Box>
        <Typography component="h2" sx={{ flex: 1, fontSize: 14.5, fontWeight: 700 }}>Waiting for you{data.total ? ` (${data.total})` : ''}</Typography>
        {data.total > 0 && <Button size="small" onClick={() => onOpen('approvals')}>See all</Button>}
      </Box>
      {items.length === 0 && <Typography sx={{ fontSize: 13 }} color="text.secondary">Nothing is waiting for you.</Typography>}
      {items.map((i, n) => (
        <ButtonBase
          key={`${i.kind}-${n}`} onClick={() => onOpen(i.view as View, i.tab ?? undefined)}
          sx={(t) => ({ display: 'flex', width: '100%', alignItems: 'center', gap: 1.5, textAlign: 'left', py: 0.6, px: 0.5, borderRadius: '5px', '&:hover': { bgcolor: t.palette.action.hover } })}
        >
          <Typography component="span" sx={{ fontSize: 12.5, fontWeight: 600, width: 150, flexShrink: 0 }} noWrap>{i.label}</Typography>
          <Typography component="span" sx={{ fontSize: 13, flex: 1, minWidth: 0 }} noWrap>{i.summary}</Typography>
          <Typography component="span" sx={{ fontSize: 12, flexShrink: 0 }} color="text.secondary">{i.by ? `${i.by} · ` : ''}{when(i.at)}</Typography>
        </ButtonBase>
      ))}
    </Box>
  )
}

/** The start page: what is waiting on you, then a tile for each area of work you may open, grouped by what you are doing. */
export default function Dashboard({ onOpen }: { onOpen: (v: View, tab?: string) => void }) {
  const { resolved } = useThemeMode()
  const dark = resolved === 'dark'
  const [hour] = useState(() => new Date().getHours())
  const me = useMe()
  const { data: tdCount } = useQuery({ queryKey: ['td-maturity-count'], queryFn: getTdMaturityCount, refetchInterval: 60_000, retry: false })
  // "My work" is the card above the tiles, so its own tile is not repeated
  const groups = byDomain(me, (s) => !!s.tile).map((g) => ({ ...g, screens: g.screens.filter((s) => s.id !== 'waiting') })).filter((g) => g.screens.length > 0)
  let index = 0

  return (
    <Box component="main" sx={{
      height: 'calc(100dvh - 44px)', overflow: 'auto', p: { xs: 1.5, md: 2 },
      background: dark
        ? 'radial-gradient(1200px 600px at 15% 0%, #1b3a6b 0%, transparent 60%), radial-gradient(900px 600px at 100% 100%, #3b1f5e 0%, transparent 55%), #0b0b0f'
        : 'radial-gradient(1200px 600px at 15% 0%, #DCE6F2 0%, transparent 60%), radial-gradient(900px 600px at 100% 100%, #E5E1F0 0%, transparent 55%), #F3F4F6',
    }}>
      <Box sx={{ maxWidth: 1400, mx: 'auto' }}>
        <Typography component="h1" variant="h5" sx={{ mb: 0, fontWeight: 700, letterSpacing: '-0.02em', fontSize: 20 }}>
          Good {hour < 12 ? 'morning' : hour < 18 ? 'afternoon' : 'evening'}, {me.name}
        </Typography>
        <Typography color="text.secondary" sx={{ mb: 1, fontSize: 12.5 }}>Choose where to work. The menu on the left is always there.</Typography>

        <WaitingCard onOpen={onOpen} />

        <Box sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'flex-start', columnGap: 3, rowGap: 1.5 }}>
        {groups.map((g) => (
          <Box key={g.id} component="section" aria-label={g.label}>
            <Typography component="h2" sx={{ fontSize: 11, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', mb: 0.5 }} color="text.secondary">{g.label}</Typography>
            <Box sx={{ display: 'grid', gap: 0.75, gridTemplateColumns: 'repeat(3, 92px)' }}>
              {g.screens.map((s) => (
                <Tile
                  key={s.id} screen={s} color={g.color} index={index++} onOpen={() => onOpen(s.view, s.tab)}
                  badge={s.view === 'frontoffice' ? tdCount?.[0] : undefined}
                />
              ))}
            </Box>
          </Box>
        ))}
        </Box>
      </Box>
    </Box>
  )
}
