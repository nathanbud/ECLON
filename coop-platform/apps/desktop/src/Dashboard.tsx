import { Box, ButtonBase, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import {
  FiBarChart2, FiBookOpen, FiDollarSign, FiDownloadCloud, FiFileText, FiInbox, FiList,
  FiCheckSquare, FiMonitor, FiPlusSquare, FiSettings, FiShoppingCart, FiUserCheck, FiUsers,
} from 'react-icons/fi'
import { getPendingCount } from './api'
import { useMe } from './session'
import { tileFill, useThemeMode, type Tint } from './theme'

export type View = 'home' | 'members' | 'accounts' | 'accounting' | 'approvals' | 'users' | 'frontoffice'

// Every item is a square of 1x1 or 2x2 cells on a landscape 5 x 4 grid (2 x 4 + 12 = 20 cells, no gaps).
type Size = 'md' | 'sm'
type Group = 'members' | 'operations' | 'accounting' | 'commerce' | 'admin'
interface TileSpec { label: string; icon: ReactNode; group: Group; area: string; size: Size; view?: View }

// Tiles are grouped by function; a group shares one colour (explained by the legend under the grid).
const groups: Record<Group, { label: string; color: Tint }> = {
  members: { label: 'Members', color: 'orange' },
  operations: { label: 'Operations', color: 'green' },
  accounting: { label: 'Accounting', color: 'indigo' },
  commerce: { label: 'Commerce', color: 'blue' },
  admin: { label: 'Administration', color: 'gray' },
}

// Tiles without a view are modules that are not built yet.
const tiles: TileSpec[] = [
  { label: 'Client Profile', icon: <FiUsers />, group: 'members', area: 'cp', size: 'md', view: 'members' },
  { label: 'Front Office', icon: <FiDollarSign />, group: 'operations', area: 'fo', size: 'sm', view: 'frontoffice' },
  { label: 'Receivables', icon: <FiInbox />, group: 'operations', area: 'rec', size: 'sm' },
  { label: 'Payables', icon: <FiBarChart2 />, group: 'operations', area: 'pay', size: 'sm' },
  { label: 'Inventory', icon: <FiList />, group: 'commerce', area: 'inv', size: 'sm' },
  { label: 'POS', icon: <FiShoppingCart />, group: 'commerce', area: 'pos', size: 'sm' },
  { label: 'Back Office', icon: <FiBookOpen />, group: 'accounting', area: 'bo', size: 'sm', view: 'accounts' },
  { label: 'Reports', icon: <FiFileText />, group: 'accounting', area: 'rep', size: 'sm', view: 'accounting' },
  { label: 'Settings', icon: <FiSettings />, group: 'admin', area: 'set', size: 'sm' },
  { label: 'System', icon: <FiMonitor />, group: 'admin', area: 'sys', size: 'sm' },
  { label: 'Backup', icon: <FiDownloadCloud />, group: 'admin', area: 'bak', size: 'sm' },
  { label: 'Users', icon: <FiUserCheck />, group: 'admin', area: 'usr', size: 'sm', view: 'users' },
  { label: 'Others', icon: <FiPlusSquare />, group: 'admin', area: 'oth', size: 'sm' },
  { label: 'Approvals', icon: <FiCheckSquare />, group: 'admin', area: 'apr', size: 'md', view: 'approvals' },
]

const areas = `
  "cp   cp   fo   rec  pay"
  "cp   cp   bo   rep  inv"
  "apr  apr  usr  set  pos"
  "apr  apr  sys  bak  oth"
`

// One shared motion rhythm: 220ms ease-out, items staggered 35ms apart.
const EASE = 'cubic-bezier(.2,.8,.2,1)'
const enter = (i: number) => ({
  animation: `tileIn 360ms ${EASE} both`,
  animationDelay: `${i * 35}ms`,
  '@keyframes tileIn': { from: { opacity: 0, transform: 'translateY(8px) scale(0.97)' }, to: { opacity: 1, transform: 'none' } },
  '@media (prefers-reduced-motion: reduce)': { animation: 'none', transition: 'none' },
})

function Tile({ spec, index, onOpen, badge }: { spec: TileSpec; index: number; onOpen: (v: View) => void; badge?: number }) {
  const { resolved } = useThemeMode()
  const dark = resolved === 'dark'
  const c = tileFill[groups[spec.group].color]
  const enabled = !!spec.view
  const md = spec.size === 'md'
  return (
    <ButtonBase
      disabled={!enabled}
      onClick={() => spec.view && onOpen(spec.view)}
      aria-label={enabled ? spec.label : `${spec.label} (coming soon)`}
      sx={{
        gridArea: spec.area, minWidth: 0, borderRadius: '5px', overflow: 'hidden', color: '#fff', textAlign: 'left',
        p: md ? 1.75 : 1.25,
        display: 'flex', flexDirection: 'column', alignItems: 'flex-start', justifyContent: 'space-between',
        // Subtle top-light gradient plus an inner hairline gives depth without adding colour noise.
        background: `linear-gradient(160deg, ${c}F2 0%, ${c} 100%)`,
        boxShadow: `inset 0 0 0 1px rgba(255,255,255,${dark ? 0.12 : 0.18}), 0 1px 2px rgba(0,0,0,0.12), 0 3px 8px rgba(0,0,0,0.08)`,
        transition: `transform 220ms ${EASE}, box-shadow 220ms ${EASE}`,
        '&:hover': enabled ? { transform: 'translateY(-1px)', boxShadow: `inset 0 0 0 1px rgba(255,255,255,0.22), 0 6px 14px rgba(0,0,0,0.16)` } : undefined,
        '&:active': enabled ? { transform: 'scale(0.97)', transitionDuration: '90ms' } : undefined,
        '&.Mui-focusVisible': { outline: '3px solid #fff', outlineOffset: '-5px' },
        '&.Mui-disabled': { opacity: 0.62, color: '#fff' },
        ...enter(index),
      }}
    >
      <Box sx={{ fontSize: md ? 'calc(var(--cell) * 0.34)' : 'calc(var(--cell) * 0.24)', lineHeight: 0, display: 'flex', opacity: 0.95 }}>{spec.icon}</Box>
      {!!badge && (
        <Box aria-label={`${badge} waiting`} sx={{ position: 'absolute', top: 8, right: 8, minWidth: 20, height: 20, px: 0.75, borderRadius: '5px', bgcolor: '#fff', color: '#1d1d1f', fontSize: 12, fontWeight: 700, display: 'grid', placeItems: 'center' }}>
          {badge}
        </Box>
      )}
      <Box sx={{ minWidth: 0, maxWidth: '100%' }}>
        <Typography noWrap sx={{ fontWeight: 600, fontSize: md ? 16 : 13, lineHeight: 1.25, letterSpacing: '-0.01em' }}>{spec.label}</Typography>
        {md && !enabled && <Typography sx={{ fontSize: 11, opacity: 0.85, lineHeight: 1.2 }}>Coming soon</Typography>}
      </Box>
    </ButtonBase>
  )
}

export default function Dashboard({ onOpen }: { onOpen: (v: View) => void }) {
  const { resolved } = useThemeMode()
  const dark = resolved === 'dark'
  const [hour] = useState(() => new Date().getHours())
  const user = useMe().name
  const { data: count } = useQuery({ queryKey: ['pending-count'], queryFn: getPendingCount, refetchInterval: 30_000 })
  const pending = count?.pending

  return (
    <Box component="main" sx={{
      height: 'calc(100dvh - 44px)', overflow: 'hidden', p: '24px',
      background: dark
        ? 'radial-gradient(1200px 600px at 15% 0%, #1b3a6b 0%, transparent 60%), radial-gradient(900px 600px at 100% 100%, #3b1f5e 0%, transparent 55%), #0b0b0f'
        : 'radial-gradient(1200px 600px at 15% 0%, #bfe0ff 0%, transparent 60%), radial-gradient(900px 600px at 100% 100%, #e6d4ff 0%, transparent 55%), #f2f2f7',
    }}>
      {/* --cell is the side of one grid square. It is the smaller of what fits the width and the height
          (226px = padding, heading, legend and row gaps), so the dashboard never scrolls. */}
      <Box sx={{ '--gap': '12px', '--cell': 'clamp(48px, min(calc((100vw - 48px - 4 * var(--gap)) / 5), calc((100dvh - 44px - 226px) / 4)), 160px)', mx: 'auto', width: 'fit-content' }}>
        <Typography component="h1" variant="h5" sx={{ mb: 0.25, fontWeight: 700, letterSpacing: '-0.02em' }}>
          Good {hour < 12 ? 'morning' : hour < 18 ? 'afternoon' : 'evening'}, {user}
        </Typography>
        <Typography color="text.secondary" sx={{ mb: 2, fontSize: 13 }}>Choose a module to get started.</Typography>

        <Box sx={{ display: 'grid', gap: 'var(--gap)', gridTemplateColumns: 'repeat(5, var(--cell))', gridAutoRows: 'var(--cell)', gridTemplateAreas: areas }}>
          {tiles.map((t, i) => <Tile key={t.label} spec={t} index={i} onOpen={onOpen} badge={t.view === 'approvals' ? pending : undefined} />)}
        </Box>

        <Box component="ul" aria-label="Colour key" sx={{ listStyle: 'none', m: 0, mt: 2, p: 0, display: 'flex', flexWrap: 'wrap', gap: '6px 16px' }}>
          {Object.values(groups).map((g) => (
            <Box component="li" key={g.label} sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
              <Box aria-hidden sx={{ width: 10, height: 10, borderRadius: '50%', bgcolor: tileFill[g.color] }} />
              <Typography sx={{ fontSize: 12 }} color="text.secondary">{g.label}</Typography>
            </Box>
          ))}
        </Box>
      </Box>
    </Box>
  )
}
