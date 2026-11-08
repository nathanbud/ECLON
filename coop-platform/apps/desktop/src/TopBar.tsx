import { LIGHT } from './glass'
import { Badge, Box, IconButton, Tooltip, Typography } from '@mui/material'
import { useEffect, useState } from 'react'
import { FiChevronRight, FiHome, FiMoon, FiMonitor, FiPower, FiSun } from 'react-icons/fi'
import { VscChromeClose, VscChromeMaximize, VscChromeMinimize, VscChromeRestore } from 'react-icons/vsc'
import type { Id } from './clients'
import type { View } from './Dashboard'
import GlobalSearch from './GlobalSearch'
import { signOut } from './api'
import { useInboxCount } from './nav/SideNav'
import { VoiceControl, type Handled } from './voice/VoiceControl'
import { useMe } from './session'
import { tileFill, useThemeMode, type ThemeMode } from './theme'
import { ToolbarSlotTarget, useHasMenu } from './toolbarSlot'

const order: ThemeMode[] = ['light', 'dark', 'system']
const icons = { light: <FiSun />, dark: <FiMoon />, system: <FiMonitor /> }

// Empty areas of the bar drag the window; interactive children are left out so they stay clickable.
const drag = { 'data-tauri-drag-region': '' }

// Toolbar icons are flat and square-cornered, like a code editor's title bar.
const flatIcon = { borderRadius: '5px', p: 0.5, fontSize: 16, color: 'inherit', opacity: 0.8, '&:hover': { opacity: 1 }, '&.Mui-disabled': { opacity: 0.4, color: 'inherit' } } as const

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
      sx={{ fontSize: 12, fontWeight: 400, opacity: 0.65, fontVariantNumeric: 'tabular-nums', mr: 1, whiteSpace: 'nowrap' }}>
      {now.toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric', year: 'numeric' })}
      {' · '}
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
  const btn = (label: string, onClick: () => void, icon: React.ReactNode, danger = false) => (
    <IconButton aria-label={label} title={label} onClick={onClick} disableRipple sx={{
      width: 46, height: 44, borderRadius: 0, color: 'inherit', fontSize: 15,
      '&:hover': { bgcolor: danger ? '#C42B1C' : 'action.hover', color: danger ? '#fff' : undefined },
    }}>{icon}</IconButton>
  )
  return (
    <Box sx={{ display: 'flex', ml: 0.5 }}>
      {btn('Minimize', () => win?.minimize(), <VscChromeMinimize />)}
      {btn(maximized ? 'Restore' : 'Maximize', () => win?.toggleMaximize(), maximized ? <VscChromeRestore /> : <VscChromeMaximize />)}
      {btn('Close', () => win?.close(), <VscChromeClose />, true)}
    </Box>
  )
}

export default function TopBar({ title, onHome, onNavigate, onOpenClient, onMenu, menuOpen, onVoice }: {
  title: string; onHome?: () => void; onNavigate: (v: View, tab?: string) => void; onOpenClient: (id: Id) => void; onMenu: () => void; menuOpen: boolean
  /** Carries out a spoken sentence; without it there is no microphone button. */
  onVoice?: (heard: string) => Handled
}) {
  const { mode, setMode } = useThemeMode()
  const hasMenu = useHasMenu()
  const me = useMe()
  const waiting = useInboxCount()
  const next = order[(order.indexOf(mode) + 1) % order.length]
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

      {/* Right: clock, theme, session and window controls. */}
      <Box {...drag} sx={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 0.5, minWidth: 0 }}>
        <Clock />
        {onVoice && <VoiceControl handle={onVoice} />}
        <Tooltip title={`Theme: ${mode} (switch to ${next})`}>
          <IconButton aria-label={`Theme ${mode}, switch to ${next}`} onClick={() => setMode(next)} size="small" sx={flatIcon}>{icons[mode]}</IconButton>
        </Tooltip>
        <Typography {...drag} noWrap sx={{ fontSize: 12, opacity: 0.8, display: { xs: 'none', md: 'block' }, maxWidth: 160 }}>{me.name}</Typography>
        <Tooltip title={`Sign out (${me.name})`}>
          <IconButton aria-label={`Sign out ${me.name}`} size="small" sx={flatIcon} onClick={signOut}><FiPower /></IconButton>
        </Tooltip>
        <WindowControls />
      </Box>
    </Box>
  )
}
