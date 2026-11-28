import { Box, Button, ButtonBase, Skeleton, Typography, alpha } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useState, type ReactNode } from 'react'
import { getInbox, getTdMaturityCount } from './api'
import type { InboxItem } from './api/inbox'
import { inboxFamily } from './inboxKinds'
import { appBackground, glass } from './glass'
import { byDomain, type Screen } from './nav/screens'
import { useMe } from './session'
import { SURFACE_RADIUS, tileFill, tint, type Tint } from './theme'
import { nameCase } from './ui/format'

export type View = 'home' | 'productroles' | 'members' | 'accounts' | 'accounting' | 'backoffice' | 'approvals' | 'users' | 'audit' | 'loansetup' | 'amortization' | 'settings' | 'frontoffice' | 'loanapp' | 'loans' | 'procurement' | 'inventory' | 'pos' | 'assets' | 'reports' | 'documents' | 'workflows' | 'membershippolicy' | 'documentation' | 'branding' | 'connections' | 'backup' | 'privacy' | 'cic' | 'dividends' | 'kycreview' | 'accounttypes' | 'dormancy' | 'businessday' | 'slcontrols' | 'errors' | 'startersetup' | 'offices' | 'departments' | 'memberdefaults' | 'features'

const EASE = 'cubic-bezier(.2,.8,.2,1)'

/**
 * The board shows whole and at once. It used to fade each tile in 30 ms after the one before, on every visit, so after signing in the board took most of a second to fill
 * and looked unsettled. Now only the first view after the window opens fades in, all together and briefly; coming back home shows it straight away.
 */
let shownOnce = false
const boardIn = {
  animation: `boardIn 180ms ${EASE} both`,
  '@keyframes boardIn': { from: { opacity: 0 }, to: { opacity: 1 } },
  '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
}

/**
 * The board (arrangement after `pics/dashboard.png`): the time and date at the top left, the greeting at the top right, then the areas of work side by side. Each area is a light
 * label over square tiles in its colour, filled top to bottom, at most two to a column, so an area with one screen is one tile wide and a larger one grows sideways.
 * The tiles are a quarter smaller than the full size (84 px against 112), except the ones in LARGE (Client Profile, the screen used most), which keep the full size and stand
 * first in their area. The waiting area's rows are sized from the full tile, so five stay readable.
 */
const TILE = 112
const SMALL = 84
const LARGE = new Set(['members'])
const GAP = 6
const ROWS = 2
const tileRadius = `${SURFACE_RADIUS}px`
const focusRing = { outline: '3px solid rgba(10,108,220,0.55)', outlineOffset: '2px' }

/** One screen: a square in the area's colour, its icon in the middle, its name at the bottom left and a count, when there is one, at the top right. */
function Tile({ screen, color, onOpen, badge, large = false }: { screen: Screen; color: Tint; onOpen: () => void; badge?: number; large?: boolean }) {
  const c = tileFill[color]
  const size = large ? TILE : SMALL
  return (
    <ButtonBase
      onClick={onOpen} aria-label={badge ? `${screen.label}, ${badge} waiting` : screen.label} title={screen.note}
      sx={{
        position: 'relative', width: size, height: size, borderRadius: tileRadius, color: '#fff', overflow: 'hidden',
        display: 'grid', placeItems: 'center',
        background: `linear-gradient(160deg, color-mix(in srgb, ${c} 86%, white) 0%, ${c} 100%)`,
        boxShadow: `0 1px 2px ${c}40`,
        transition: `transform 200ms ${EASE}, box-shadow 200ms ${EASE}, filter 200ms ${EASE}`,
        '&:hover': { filter: 'brightness(1.08)', transform: 'translateY(-2px)', boxShadow: `0 8px 18px ${c}55` },
        '&:active': { transform: 'scale(0.97)', transitionDuration: '90ms' },
        '&.Mui-focusVisible': focusRing,
      }}
    >
      <Box aria-hidden sx={{ fontSize: large ? 34 : 26, lineHeight: 0, mb: large ? 2 : 1.75, opacity: 0.96 }}>{screen.icon}</Box>
      {!!badge && (
        <Box aria-hidden sx={{ position: 'absolute', top: 0, right: 0, minWidth: 22, height: 20, px: 0.75, borderBottomLeftRadius: '6px', bgcolor: 'rgba(0,0,0,0.3)', fontSize: 11, fontWeight: 600, display: 'grid', placeItems: 'center' }}>{badge}</Box>
      )}
      <Typography sx={{
        position: 'absolute', left: large ? 8 : 7, right: large ? 8 : 7, bottom: large ? 6 : 5, textAlign: 'left', fontWeight: 500, fontSize: large ? 13 : 12, lineHeight: 1.2,
        display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden', wordBreak: 'break-word',
      }}>{screen.label}</Typography>
    </ButtonBase>
  )
}

/** The label above an area. */
function AreaLabel({ children }: { children: string }) {
  return <Typography variant="h5" component="h2" color="text.secondary" sx={{ fontWeight: 400, lineHeight: 1.3, mb: 0.75, whiteSpace: 'nowrap' }}>{children}</Typography>
}

const when = (at: string | null) => (at ? new Date(at.replace(' ', 'T')).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' }) : '')

/**
 * The waiting area's footprint: three tiles wide and two high, whatever it shows, so the areas after it never move when its answer comes or changes. Inside, the waiting work
 * is a list of thin rows, five of them (the rest under See all), each in the colour of its area of work (`inboxFamily`), the same colour as that area's tiles.
 */
const WIDE = 3 * TILE + 2 * GAP
const TALL = ROWS * TILE + (ROWS - 1) * GAP
const SHOWN = 5
const ROW_GAP = 4

/** A plain panel filling the waiting area, for a message instead of items. */
function WaitingPanel({ children }: { children: ReactNode }) {
  return (
    <Box sx={(t) => ({ ...glass(t), borderRadius: tileRadius, height: TALL, px: 1.5, display: 'grid', alignItems: 'end', pb: 1 })}>
      <Typography color="text.secondary">{children}</Typography>
    </Box>
  )
}

/** One waiting item as a thin row: a bar and a light wash in its area's colour; the title and when on the first line, the area and what it is on the second. */
function WaitingRow({ item, onOpen }: { item: InboxItem; onOpen: () => void }) {
  const fam = inboxFamily(item.kind)
  const dated = when(item.at)
  return (
    <ButtonBase
      onClick={onOpen} title={[item.summary, item.by && `From ${item.by}`, dated].filter(Boolean).join(' · ')}
      aria-label={`${fam.area}: ${item.label}. ${item.summary}${item.by ? `. From ${item.by}` : ''}${dated ? `, ${dated}` : ''}`}
      sx={(t) => {
        const c = t.palette.mode === 'dark' ? tint[fam.color][1] : tileFill[fam.color]
        return {
          position: 'relative', display: 'flex', flexDirection: 'column', alignItems: 'stretch', justifyContent: 'center', width: '100%', height: '100%', minWidth: 0,
          textAlign: 'left', pl: 1.5, pr: 1, borderRadius: `${SURFACE_RADIUS - 2}px`, overflow: 'hidden',
          bgcolor: alpha(c, t.palette.mode === 'dark' ? 0.2 : 0.1), border: 1, borderColor: alpha(c, 0.22),
          transition: `background-color 160ms ${EASE}, transform 160ms ${EASE}`,
          '&::before': { content: '""', position: 'absolute', left: 0, top: 0, bottom: 0, width: 4, bgcolor: c },
          '&:hover': { bgcolor: alpha(c, t.palette.mode === 'dark' ? 0.3 : 0.17), transform: 'translateX(2px)' },
          '&.Mui-focusVisible': focusRing,
          '@media (prefers-reduced-motion: reduce)': { transition: 'none', '&:hover': { transform: 'none' } },
        }
      }}
    >
      <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1, minWidth: 0 }}>
        <Typography variant="subtitle2" component="span" sx={{ flex: 1, minWidth: 0, lineHeight: 1.25 }} noWrap>{item.label}</Typography>
        {dated && <Typography variant="caption" component="span" color="text.secondary" sx={{ lineHeight: 1.25, flexShrink: 0, fontVariantNumeric: 'tabular-nums' }}>{dated}</Typography>}
      </Box>
      <Typography variant="body2" component="span" color="text.secondary" sx={{ lineHeight: 1.25 }} noWrap>
        <Box component="span" sx={(t) => ({ fontWeight: 600, color: t.palette.mode === 'dark' ? tint[fam.color][1] : tileFill[fam.color] })}>{fam.area}</Box>
        {' · '}{item.summary}
      </Typography>
    </ButtonBase>
  )
}

/**
 * What is waiting on this person: the first five items as thin colour-coded rows, each one a link to the screen that decides it. It is drawn from the first frame at its full
 * size (placeholder rows while the inbox is asked), because it is the first area on the board: arriving late, or changing height, used to push every area after it along.
 */
function WaitingArea({ onOpen }: { onOpen: (v: View, tab?: string) => void }) {
  const { data, error } = useQuery({ queryKey: ['inbox'], queryFn: getInbox, refetchInterval: 30_000, retry: false })
  const loading = !data && !error
  const items = data ? data.items.slice(0, SHOWN) : []
  const more = data ? data.total - items.length : 0
  const rows = { display: 'grid', gap: `${ROW_GAP}px`, height: TALL, gridTemplateRows: `repeat(${SHOWN}, minmax(0, 1fr))` }
  return (
    <Box component="section" aria-label="Waiting for you" aria-busy={loading} sx={{ width: WIDE, maxWidth: '100%' }}>
      <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1 }}>
        <Box sx={{ flex: 1, minWidth: 0 }}><AreaLabel>{`Waiting for you${data?.total ? ` (${data.total})` : ''}`}</AreaLabel></Box>
        {!!data?.total && (
          <Button size="small" onClick={() => onOpen('approvals')} sx={{ minHeight: 22, py: 0, px: 0.75, mr: -0.75 }}>{more > 0 ? `See all (${more} more)` : 'See all'}</Button>
        )}
      </Box>
      {loading && (
        <Box sx={rows} aria-label="Checking what is waiting">
          {Array.from({ length: SHOWN }, (_, n) => <Skeleton key={n} variant="rounded" sx={{ height: '100%', borderRadius: `${SURFACE_RADIUS - 2}px` }} />)}
        </Box>
      )}
      {/* a failed check is said as such, never shown as an empty list */}
      {!data && error && <WaitingPanel>Couldn&apos;t check what is waiting for you. It is tried again in a moment.</WaitingPanel>}
      {data && items.length === 0 && <WaitingPanel>Nothing is waiting for you.</WaitingPanel>}
      {items.length > 0 && (
        <Box sx={rows}>
          {items.map((i, n) => <WaitingRow key={`${i.kind}-${n}`} item={i} onOpen={() => onOpen(i.view as View, i.tab ?? undefined)} />)}
        </Box>
      )}
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
  const { data: inbox, error: inboxError } = useQuery({ queryKey: ['inbox'], queryFn: getInbox, refetchInterval: 30_000, retry: false })
  // "All caught up" only when the inbox answered with nothing; nothing while it is being asked, and a plain word when it could not be asked
  const waiting = inbox ? (inbox.total ? `${inbox.total} waiting for you` : 'All caught up') : inboxError ? "Couldn't check what's waiting" : '\u00a0'
  // What waits is its own area at the start of the board, so its tile is not repeated
  const groups = byDomain(me, (s) => !!s.tile).map((g) => ({ ...g, screens: g.screens.filter((s) => s.id !== 'waiting') })).filter((g) => g.screens.length > 0)
  const [clock, half] = now.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' }).split(/\s+/)
  const [fadeIn] = useState(() => !shownOnce)
  useEffect(() => { shownOnce = true }, [])

  return (
    <Box component="main" sx={(t) => ({ height: 'calc(100dvh - 44px)', overflowY: 'auto', overflowX: 'hidden', px: { xs: 2, md: 5 }, py: { xs: 2.5, md: 3 }, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 1240, mx: 'auto', ...(fadeIn ? boardIn : {}) }}>
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
              Good {hour < 12 ? 'morning' : hour < 18 ? 'afternoon' : 'evening'}, {nameCase(me.name)}
            </Typography>
            <Typography variant="h6" component="p" color="text.secondary" sx={{ lineHeight: 1.35 }}>
              {waiting}
            </Typography>
          </Box>
        </Box>

        <Box sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'flex-start', columnGap: 4, rowGap: 3 }}>
          <WaitingArea onOpen={onOpen} />
          {groups.map((g) => {
            const large = g.screens.filter((s) => LARGE.has(s.id))
            const small = g.screens.filter((s) => !LARGE.has(s.id))
            const tile = (s: Screen, big: boolean) => (
              <Tile key={s.id} screen={s} color={g.color} large={big} onOpen={() => onOpen(s.view, s.tab)} badge={s.view === 'frontoffice' ? tdCount?.[0] : undefined} />
            )
            return (
              <Box key={g.id} component="section" aria-label={g.label} sx={{ maxWidth: '100%' }}>
                <AreaLabel>{g.label}</AreaLabel>
                <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: `${GAP}px` }}>
                  {large.map((s) => tile(s, true))}
                  {small.length > 0 && (
                    <Box sx={{ display: 'grid', gap: `${GAP}px`, gridAutoFlow: 'column', gridAutoColumns: `${SMALL}px`, gridTemplateRows: `repeat(${Math.min(small.length, ROWS)}, ${SMALL}px)` }}>
                      {small.map((s) => tile(s, false))}
                    </Box>
                  )}
                </Box>
              </Box>
            )
          })}
        </Box>
      </Box>
    </Box>
  )
}
