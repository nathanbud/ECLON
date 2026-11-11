import { Box, Button, ButtonBase, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { getInbox, getTdMaturityCount } from './api'
import { appBackground, glass } from './glass'
import { byDomain, type Screen } from './nav/screens'
import { useMe } from './session'
import { SURFACE_RADIUS, tileFill, type Tint } from './theme'

export type View = 'home' | 'members' | 'accounts' | 'accounting' | 'approvals' | 'users' | 'audit' | 'loansetup' | 'amortization' | 'settings' | 'frontoffice' | 'loanapp' | 'procurement' | 'inventory' | 'pos' | 'assets' | 'reports' | 'documents' | 'workflows' | 'membershippolicy' | 'documentation' | 'branding' | 'connections' | 'backup' | 'privacy' | 'cic' | 'dividends'

// One shared motion rhythm: ease-out, items staggered 30ms apart.
const EASE = 'cubic-bezier(.2,.8,.2,1)'
const enter = (i: number) => ({
  animation: `tileIn 380ms ${EASE} both`,
  animationDelay: `${Math.min(i, 16) * 30}ms`,
  '@keyframes tileIn': { from: { opacity: 0, transform: 'translateY(8px)' }, to: { opacity: 1, transform: 'none' } },
  '@media (prefers-reduced-motion: reduce)': { animation: 'none', transition: 'none' },
})

/**
 * The board (arrangement after `pics/dashboard.png`): the time and date at the top left, the greeting at the top right, then the areas of work side by side. Each area is a light
 * label over square tiles in its colour, filled top to bottom, at most two to a column, so an area with one screen is one tile wide and a larger one grows sideways.
 */
const TILE = 112
const GAP = 6
const ROWS = 2
const tileRadius = `${SURFACE_RADIUS}px`
const focusRing = { outline: '3px solid rgba(10,108,220,0.55)', outlineOffset: '2px' }

/** One screen: a square in the area's colour, its icon in the middle, its name at the bottom left and a count, when there is one, at the top right. */
function Tile({ screen, color, index, onOpen, badge }: { screen: Screen; color: Tint; index: number; onOpen: () => void; badge?: number }) {
  const c = tileFill[color]
  return (
    <ButtonBase
      onClick={onOpen} aria-label={badge ? `${screen.label}, ${badge} waiting` : screen.label} title={screen.note}
      sx={{
        position: 'relative', width: TILE, height: TILE, borderRadius: tileRadius, color: '#fff', overflow: 'hidden',
        display: 'grid', placeItems: 'center',
        background: `linear-gradient(160deg, color-mix(in srgb, ${c} 86%, white) 0%, ${c} 100%)`,
        boxShadow: `0 1px 2px ${c}40`,
        transition: `transform 200ms ${EASE}, box-shadow 200ms ${EASE}, filter 200ms ${EASE}`,
        '&:hover': { filter: 'brightness(1.08)', transform: 'translateY(-2px)', boxShadow: `0 8px 18px ${c}55` },
        '&:active': { transform: 'scale(0.97)', transitionDuration: '90ms' },
        '&.Mui-focusVisible': focusRing,
        ...enter(index),
      }}
    >
      <Box aria-hidden sx={{ fontSize: 34, lineHeight: 0, mb: 2, opacity: 0.96 }}>{screen.icon}</Box>
      {!!badge && (
        <Box aria-hidden sx={{ position: 'absolute', top: 0, right: 0, minWidth: 22, height: 20, px: 0.75, borderBottomLeftRadius: '6px', bgcolor: 'rgba(0,0,0,0.3)', fontSize: 11, fontWeight: 600, display: 'grid', placeItems: 'center' }}>{badge}</Box>
      )}
      <Typography sx={{
        position: 'absolute', left: 8, right: 8, bottom: 6, textAlign: 'left', fontWeight: 500, fontSize: 13, lineHeight: 1.2,
        display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden', wordBreak: 'break-word',
      }}>{screen.label}</Typography>
    </ButtonBase>
  )
}

/** The label above an area. */
function AreaLabel({ children }: { children: string }) {
  return <Typography component="h2" color="text.secondary" sx={{ fontSize: 17, fontWeight: 400, lineHeight: 1.3, mb: 0.75, whiteSpace: 'nowrap' }}>{children}</Typography>
}

const when = (at: string | null) => (at ? new Date(at.replace(' ', 'T')).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' }) : '')

/** What is waiting on this person: the first few items as wide tiles, each one a link to the screen that decides it. */
function WaitingArea({ onOpen }: { onOpen: (v: View, tab?: string) => void }) {
  const { data } = useQuery({ queryKey: ['inbox'], queryFn: getInbox, refetchInterval: 30_000, retry: false })
  if (!data) return null
  const items = data.items.slice(0, ROWS)
  const wide = 2 * TILE + GAP
  return (
    <Box component="section" aria-label="Waiting for you" sx={{ width: wide, maxWidth: '100%' }}>
      <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1 }}>
        <Box sx={{ flex: 1, minWidth: 0 }}><AreaLabel>{`Waiting for you${data.total ? ` (${data.total})` : ''}`}</AreaLabel></Box>
        {data.total > 0 && <Button size="small" onClick={() => onOpen('approvals')} sx={{ minHeight: 22, py: 0, px: 0.75, mr: -0.75 }}>See all</Button>}
      </Box>
      {items.length === 0 && (
        <Box sx={(t) => ({ ...glass(t), borderRadius: tileRadius, height: TILE, px: 1.5, display: 'grid', alignItems: 'end', pb: 1 })}>
          <Typography sx={{ fontSize: 13 }} color="text.secondary">Nothing is waiting for you.</Typography>
        </Box>
      )}
      <Box sx={{ display: 'grid', gap: `${GAP}px` }}>
        {items.map((i, n) => (
          <ButtonBase
            key={`${i.kind}-${n}`} onClick={() => onOpen(i.view as View, i.tab ?? undefined)}
            sx={{
              display: 'flex', flexDirection: 'column', alignItems: 'stretch', justifyContent: 'flex-start', width: '100%', height: TILE, textAlign: 'left', px: 1.25, py: 1, borderRadius: tileRadius, color: '#fff', overflow: 'hidden',
              background: `linear-gradient(160deg, color-mix(in srgb, ${tileFill.orange} 86%, white) 0%, ${tileFill.orange} 100%)`,
              transition: `transform 200ms ${EASE}, filter 200ms ${EASE}`,
              '&:hover': { filter: 'brightness(1.08)', transform: 'translateY(-2px)' },
              '&.Mui-focusVisible': focusRing,
              ...enter(n),
            }}
          >
            <Typography component="span" sx={{ display: 'block', fontSize: 13, fontWeight: 600 }} noWrap>{i.label}</Typography>
            <Typography component="span" sx={{ fontSize: 13, lineHeight: 1.3, mt: 0.25, display: '-webkit-box', WebkitLineClamp: 3, flex: '0 1 auto', WebkitBoxOrient: 'vertical', overflow: 'hidden', wordBreak: 'break-word' }}>{i.summary}</Typography>
            <Typography component="span" sx={{ position: 'absolute', left: 10, right: 10, bottom: 6, fontSize: 11, opacity: 0.9 }} noWrap>{i.by ? `${i.by} · ` : ''}{when(i.at)}</Typography>
          </ButtonBase>
        ))}
      </Box>
    </Box>
  )
}

export default function Dashboard({ onOpen }: { onOpen: (v: View, tab?: string) => void }) {
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const tick = setInterval(() => setNow(new Date()), 20_000)
    return () => clearInterval(tick)
  }, [])
  const hour = now.getHours()
  const me = useMe()
  const { data: tdCount } = useQuery({ queryKey: ['td-maturity-count'], queryFn: getTdMaturityCount, refetchInterval: 60_000, retry: false })
  const { data: inbox } = useQuery({ queryKey: ['inbox'], queryFn: getInbox, refetchInterval: 30_000, retry: false })
  // What waits is its own area at the start of the board, so its tile is not repeated
  const groups = byDomain(me, (s) => !!s.tile).map((g) => ({ ...g, screens: g.screens.filter((s) => s.id !== 'waiting') })).filter((g) => g.screens.length > 0)
  const [clock, half] = now.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' }).split(/\s+/)
  let index = 0

  return (
    <Box component="main" sx={(t) => ({ height: 'calc(100dvh - 44px)', overflowY: 'auto', overflowX: 'hidden', px: { xs: 2, md: 5 }, py: { xs: 2.5, md: 3 }, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 1240, mx: 'auto' }}>
        <Box sx={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', flexWrap: 'wrap', columnGap: 4, rowGap: 1, mb: 2.5 }}>
          <Box>
            <Typography sx={{ fontSize: 34, fontWeight: 300, lineHeight: 1.1, letterSpacing: '-0.01em', fontVariantNumeric: 'tabular-nums' }}>
              {clock}{half && <Box component="span" sx={{ fontSize: 17, fontWeight: 400, ml: 0.75, letterSpacing: 0 }}>{half}</Box>}
            </Typography>
            <Typography sx={{ fontSize: 20, fontWeight: 400, lineHeight: 1.3 }}>
              {now.toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric' })}
            </Typography>
          </Box>
          <Box sx={{ textAlign: { xs: 'left', sm: 'right' }, minWidth: 0, pt: 0.5 }}>
            <Typography component="h1" sx={{ fontSize: 20, fontWeight: 600, letterSpacing: '-0.01em', lineHeight: 1.3, overflowWrap: 'anywhere' }}>
              Good {hour < 12 ? 'morning' : hour < 18 ? 'afternoon' : 'evening'}, {me.name}
            </Typography>
            <Typography color="text.secondary" sx={{ fontSize: 15, lineHeight: 1.35 }}>
              {inbox?.total ? `${inbox.total} waiting for you` : 'All caught up'}
            </Typography>
          </Box>
        </Box>

        <Box sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'flex-start', columnGap: 4, rowGap: 3 }}>
          <WaitingArea onOpen={onOpen} />
          {groups.map((g) => (
            <Box key={g.id} component="section" aria-label={g.label} sx={{ maxWidth: '100%' }}>
              <AreaLabel>{g.label}</AreaLabel>
              <Box sx={{ display: 'grid', gap: `${GAP}px`, gridAutoFlow: 'column', gridAutoColumns: `${TILE}px`, gridTemplateRows: `repeat(${Math.min(g.screens.length, ROWS)}, ${TILE}px)` }}>
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
