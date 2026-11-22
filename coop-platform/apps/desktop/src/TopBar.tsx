import { LIGHT } from './glass'
import { Badge, Box, IconButton, Tooltip, Typography, alpha, type Theme } from '@mui/material'
import { useEffect, useState } from 'react'
import { FiChevronRight, FiHome, FiPower } from 'react-icons/fi'
import { VscChromeClose, VscChromeMaximize, VscChromeMinimize, VscChromeRestore } from 'react-icons/vsc'
import type { Id } from './clients'
import type { View } from './Dashboard'
import GlobalSearch from './GlobalSearch'
import { signOut } from './api'
import { useInboxCount } from './nav/SideNav'
import { BusinessDayChip } from './BusinessDayChip'
import { useMe } from './session'
import { tileFill } from './theme'
import { ToolbarSlotTarget, useHasMenu } from './toolbarSlot'
import { nameCase } from './ui/format'

// Empty areas of the bar drag the window; interactive children are left out so they stay clickable.
const drag = { 'data-tauri-drag-region': '' }

// Toolbar icons are flat and square-cornered, like a code editor's title bar.
const flatIcon = { flexShrink: 0, borderRadius: '5px', p: 0.5, fontSize: 15, color: 'inherit', opacity: 0.8, '&:hover': { opacity: 1 }, '&.Mui-disabled': { opacity: 0.4, color: 'inherit' } } as const

const inTauri = typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window

export function Logo() {
  // The dashboard's bento motif: four rounded squares in the module colours.
  const q = [tileFill.orange, tileFill.teal, tileFill.indigo, tileFill.green]
  return (
    <Box component="svg" viewBox="0 0 20 20" role="img" aria-label="Coop Platform" sx={{ width: 20, height: 20, flexShrink: 0, pointerEvents: 'none' }}>
      {q.map((c, i) => <rect key={c} x={i % 2 ? 11 : 1} y={i > 1 ? 11 : 1} width="8" height="8" rx="2.4" fill={c} />)}
    </Box>
  )
}

function Clock() {
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 1000)
    return () => clearInterval(t)
  }, [])
  return (
    <Typography component="time" dateTime={now.toISOString()} {...drag}
      sx={{ fontSize: 12, fontWeight: 400, opacity: 0.65, fontVariantNumeric: 'tabular-nums', mr: 1, whiteSpace: 'nowrap', flexShrink: 0 }}>
      <Box component="span" sx={{ display: { xs: 'none', lg: 'inline' } }}>{now.toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric', year: 'numeric' })}{' · '}</Box>
      {now.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })}
    </Typography>
  )
}

/** Minimise / maximise / close, in the Windows style. Only shown inside the Tauri window. */
export function WindowControls() {
  const [maximized, setMaximized] = useState(false)
  const [win, setWin] = useState<import('@tauri-apps/api/window').Window | null>(null)

  useEffect(() => {
    if (!inTauri) return
    let off: (() => void) | undefined
    let live = true
    import('@tauri-apps/api/window').then(async ({ getCurrentWindow }) => {
      const w = getCurrentWindow()
      if (!live) return
      setWin(w)
      setMaximized(await w.isMaximized())
      off = await w.onResized(async () => setMaximized(await w.isMaximized()))
    })
    return () => { live = false; off?.() }
  }, [])

  if (!inTauri) return null
  // The panel contrasts with the theme: near-black in the light theme, near-white in the dark one, with the icons in the opposite colour.
  const panel = (t: Theme) => (t.palette.mode === 'dark' ? t.palette.grey[100] : t.palette.grey[900])
  const ink = (t: Theme) => (t.palette.mode === 'dark' ? t.palette.grey[900] : t.palette.grey[50])
  // The icons glow: white light on the dark panel; on the light panel (dark theme) a dark glow would read as a shadow, so it glows in the accent blue.
  const glow = (t: Theme) => (t.palette.mode === 'dark' ? t.palette.primary.main : ink(t))
  const halo = (c: string, strong: boolean) => `drop-shadow(0 0 ${strong ? 3 : 2}px ${alpha(c, strong ? 0.95 : 0.6)}) drop-shadow(0 0 ${strong ? 8 : 5}px ${alpha(c, strong ? 0.7 : 0.35)})`
  const btn = (label: string, onClick: () => void, icon: React.ReactNode, danger = false) => (
    <IconButton aria-label={label} title={label} onClick={onClick} disableRipple sx={(t) => ({
      width: 30, minWidth: 30, p: 0, height: 26, borderRadius: '6px', color: ink(t), fontSize: 13, opacity: 0.9,
      transition: 'background-color 120ms ease, color 120ms ease, opacity 120ms ease, box-shadow 160ms ease',
      '& svg': { filter: halo(glow(t), false), transition: 'filter 160ms ease' },
      '&:hover svg, &.Mui-focusVisible svg': { filter: halo(danger ? t.palette.error.main : glow(t), true) },
      '&:hover': {
        opacity: 1, bgcolor: danger ? t.palette.error.main : alpha(ink(t), 0.16), color: danger ? t.palette.error.contrastText : undefined,
        boxShadow: `0 0 10px ${alpha(danger ? t.palette.error.main : glow(t), 0.45)}`,
      },
      '&:active': { bgcolor: danger ? t.palette.error.dark : alpha(ink(t), 0.26) },
      '&.Mui-focusVisible': { outline: `2px solid ${ink(t)}`, outlineOffset: -2 },
    })}>{icon}</IconButton>
  )
  // The three sit on that panel at the right end of the bar. Its left edge is an S-shaped curve (drawn as an SVG in the same colour),
  // wider at the top and sweeping in to the bottom, so the panel meets the sign-out button with a bend instead of a hard line.
  return (
    <Box role="group" aria-label="Window" sx={(t) => ({
      position: 'relative', display: 'flex', alignItems: 'center', height: 44, flexShrink: 0, gap: '2px', ml: '30px', pl: 0.25, pr: 0.75,
      bgcolor: panel(t),
    })}>
      <Box component="svg" aria-hidden viewBox="0 0 28 44" preserveAspectRatio="none"
        sx={(t) => ({ position: 'absolute', top: 0, left: -27, width: 28, height: '100%', color: panel(t), pointerEvents: 'none' })}>
        <path d="M0 0 H28 V44 H18 C8 44 10 0 0 0 Z" fill="currentColor" />
      </Box>
      {btn('Minimize', () => win?.minimize(), <VscChromeMinimize />)}
      {btn(maximized ? 'Restore' : 'Maximize', () => win?.toggleMaximize(), maximized ? <VscChromeRestore /> : <VscChromeMaximize />)}
      {btn('Close', () => win?.close(), <VscChromeClose />, true)}
    </Box>
  )
}

export default function TopBar({ title, onHome, onNavigate, onOpenClient, onMenu, menuOpen }: {
  title: string; onHome?: () => void; onNavigate: (v: View, tab?: string) => void; onOpenClient: (id: Id) => void; onMenu: () => void; menuOpen: boolean
}) {
  const hasMenu = useHasMenu()
  const me = useMe()
  const waiting = useInboxCount()
  return (
    <Box component="header" {...drag} sx={(t) => ({
      position: 'sticky', top: 0, zIndex: 10, display: 'grid', alignItems: 'center', gap: 1.5, pl: 1.75, pr: 0, height: 44,
      gridTemplateColumns: 'minmax(0, 1fr) minmax(160px, 340px) minmax(0, 1fr)',
      userSelect: 'none', color: t.palette.mode === 'dark' ? '#CCCCCC' : '#4D4D4D',
      backdropFilter: 'saturate(180%) blur(20px)',
      backgroundColor: t.palette.mode === 'dark' ? 'rgba(28,28,30,0.72)' : LIGHT.bar,
      borderBottom: `1px solid ${t.palette.divider}`,
    })}>
      {/* Left: brand, then the current screen (or its menu), level with the window controls like a native title bar. */}
      <Box {...drag} sx={{ display: 'flex', alignItems: 'center', gap: 0.5, minWidth: 0 }}>
        {/* The logo is the menu button: it opens and closes the menu of screens. */}
        <Tooltip title="Menu (M)">
          <IconButton aria-label={waiting ? `Menu, ${waiting} waiting for you` : 'Menu'} aria-haspopup="true" aria-expanded={menuOpen} aria-keyshortcuts="M" onClick={onMenu} size="small"
            sx={(t) => ({ borderRadius: '5px', p: 0.5, mr: 0.25, bgcolor: menuOpen ? t.palette.action.selected : 'transparent', '&:hover': { bgcolor: t.palette.action.hover } })}>
            <Badge color="error" variant={waiting ? 'dot' : undefined} invisible={!waiting} overlap="circular" sx={{ '& .MuiBadge-badge': { right: -1, top: -1 } }}><Logo /></Badge>
          </IconButton>
        </Tooltip>
        {onHome && (
          <>
            <Box aria-hidden sx={{ display: 'flex', color: 'text.disabled', fontSize: 13 }}><FiChevronRight /></Box>
            <Tooltip title="Home (H)"><IconButton aria-label="Home" aria-keyshortcuts="H" onClick={onHome} size="small" sx={flatIcon}><FiHome /></IconButton></Tooltip>
            {!hasMenu && <Typography {...drag} noWrap sx={{ fontSize: 13, fontWeight: 400, color: 'inherit', opacity: 0.8 }}>{title}</Typography>}
          </>
        )}
        <ToolbarSlotTarget />
      </Box>

      {/* Centre: search for the whole app. */}
      <GlobalSearch onNavigate={onNavigate} onOpenClient={onOpenClient} />

      {/* Right: clock, session and window controls (the theme is chosen at the foot of the side menu). */}
      <Box {...drag} sx={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 0.5, minWidth: 0 }}>
        <BusinessDayChip onOpen={() => onNavigate('businessday')} />
        <Clock />
        <Typography {...drag} noWrap sx={{ fontSize: 12, opacity: 0.8, display: { xs: 'none', md: 'block' }, maxWidth: 160 }}>{nameCase(me.name)}</Typography>
        <Tooltip title={`Sign out (${nameCase(me.name)})`}>
          <IconButton aria-label={`Sign out ${nameCase(me.name)}`} size="small" sx={flatIcon} onClick={() => signOut()}><FiPower /></IconButton>
        </Tooltip>
        <WindowControls />
      </Box>
    </Box>
  )
}
