import { Box, ButtonBase, Drawer, Tooltip, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { FiChevronDown, FiChevronRight } from 'react-icons/fi'
import { getInboxCount } from '../api'
import type { View } from '../Dashboard'
import { useMe } from '../session'
import { tileFill } from '../theme'
import { byDomain, HOME, screenFor, type Screen } from './screens'

const EASE = 'cubic-bezier(.2,.8,.2,1)'
const WIDTH = 252
/** Height of the title bar the menu opens under. */
const BAR = 44

const SHUT = 'coop.nav.shut'
const shutStored = (): string[] => { try { return JSON.parse(localStorage.getItem(SHUT) ?? '[]') as string[] } catch { return [] } }

/** The number waiting on the signed-in person, for the badge. Refreshed every half minute; a failure just shows no number. */
export function useInboxCount(): number {
  const { data } = useQuery({ queryKey: ['inbox-count'], queryFn: getInboxCount, refetchInterval: 30_000, retry: false })
  return data?.count ?? 0
}

/**
 * The menu, built from the screen registry: what this person may open, grouped by what they are doing. It is hidden until the logo in the title bar is pressed (or the M key),
 * opens as a panel under the title bar, and closes when a screen is chosen, on Esc, or when the screen behind it is pressed.
 * Entries are links (they take you somewhere); the tile on the home page is the button.
 */
export default function SideNav({ view, tab, onNavigate, open, onClose }: { view: View; tab?: string; onNavigate: (v: View, tab?: string) => void; open: boolean; onClose: () => void }) {
  const me = useMe()
  const waiting = useInboxCount()
  const [shut, setShut] = useState<string[]>(shutStored)
  const fold = (id: string) => setShut((c) => { const n = c.includes(id) ? c.filter((x) => x !== id) : [...c, id]; try { localStorage.setItem(SHUT, JSON.stringify(n)) } catch { /* storage unavailable */ } return n })
  const here = view === 'home' ? 'home' : screenFor(view, tab)?.id
  const groups = byDomain(me)

  const item = (key: string, label: string, note: string, icon: React.ReactNode, color: string, active: boolean, go: () => void, count = 0, sub = false) => (
    <Box key={key} component="li" sx={{ listStyle: 'none' }}>
      <Tooltip title={note} placement="right" enterDelay={700}>
        <ButtonBase
          role="link" component="a" aria-current={active ? 'page' : undefined} aria-label={count ? `${label}, ${count} waiting` : label} onClick={(e) => { e.preventDefault(); go(); onClose() }}
          sx={(t) => ({
            width: '100%', display: 'flex', alignItems: 'center', gap: 1.25, justifyContent: 'flex-start', textAlign: 'left', borderRadius: 0,
            px: 1, pl: sub ? 3.5 : undefined, py: sub ? 0.4 : 0.65, minHeight: sub ? 28 : 34,
            fontSize: sub ? 13 : 14, fontWeight: active ? 600 : 500, color: 'text.primary',
            bgcolor: active ? (t.palette.mode === 'dark' ? 'rgba(10,132,255,0.28)' : 'rgba(0,122,255,0.14)') : 'transparent',
            boxShadow: active ? `inset 2px 0 0 ${t.palette.primary.main}` : 'none',
            transition: `background-color 140ms ${EASE}`,
            '&:hover': { bgcolor: active ? undefined : t.palette.action.hover },
            '&.Mui-focusVisible': { outline: `2px solid ${t.palette.primary.main}`, outlineOffset: -1 },
          })}
        >
          <Box aria-hidden sx={{ display: 'flex', fontSize: sub ? 14 : 17, color: sub ? 'text.secondary' : color, flexShrink: 0, width: sub ? 14 : 20, justifyContent: 'center' }}>{icon}</Box>
          <Box component="span" sx={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{label}</Box>
          {count > 0 && (
            <Box component="span" aria-hidden sx={{ minWidth: 20, height: 20, px: 0.75, borderRadius: '5px', bgcolor: 'error.main', color: '#fff', fontSize: 12, fontWeight: 700, display: 'grid', placeItems: 'center' }}>{count > 99 ? '99+' : count}</Box>
          )}
        </ButtonBase>
      </Tooltip>
    </Box>
  )

  return (
    <Drawer
      open={open} onClose={onClose} anchor="left"
      // The title bar stays usable (the logo closes the menu again, and the window can still be dragged and closed), so the panel and the dimmed screen start below it.
      sx={{ top: BAR }}
      // The menu is not a trap: the logo that opened it must still work while it is open.
      ModalProps={{ disableEnforceFocus: true, disableScrollLock: true }}
      slotProps={{
        backdrop: { sx: (t) => ({ backgroundColor: t.palette.mode === 'dark' ? 'rgba(0,0,0,0.4)' : 'rgba(30,40,70,0.18)' }) },
        paper: {
          sx: (t) => ({
            top: BAR, height: `calc(100dvh - ${BAR}px)`, width: WIDTH, maxWidth: '86vw', boxSizing: 'border-box', backgroundImage: 'none', overflowY: 'auto', overflowX: 'hidden',
            px: 0, py: 0.5, borderRight: `1px solid ${t.palette.divider}`, backgroundColor: t.palette.background.paper,
            // thin scrollbar as in VS Code: invisible until the pointer is over the menu, square thumb, darker when grabbed
            scrollbarWidth: 'thin', scrollbarColor: 'transparent transparent',
            '&:hover': { scrollbarColor: `${t.palette.mode === 'dark' ? 'rgba(121,121,121,0.4)' : 'rgba(100,100,100,0.4)'} transparent` },
            '&::-webkit-scrollbar': { width: 10, height: 10 },
            '&::-webkit-scrollbar-track': { background: 'transparent' },
            '&::-webkit-scrollbar-thumb': { backgroundColor: 'transparent', borderRadius: 0 },
            '&:hover::-webkit-scrollbar-thumb': { backgroundColor: t.palette.mode === 'dark' ? 'rgba(121,121,121,0.4)' : 'rgba(100,100,100,0.4)' },
            '&:hover::-webkit-scrollbar-thumb:hover': { backgroundColor: 'rgba(100,100,100,0.7)' },
            '&:hover::-webkit-scrollbar-thumb:active': { backgroundColor: t.palette.mode === 'dark' ? 'rgba(191,191,191,0.4)' : 'rgba(0,0,0,0.6)' },
          }),
        },
      }}
      transitionDuration={{ enter: 180, exit: 140 }}
    >
      <Box component="nav" aria-label="Main">
        <Box component="ul" sx={{ m: 0, p: 0, display: 'grid', gap: 0 }}>
          {item('home', HOME.label, HOME.note, HOME.icon, tileFill.gray, here === 'home', () => onNavigate('home'))}
        </Box>
        {groups.map((g) => {
          const expanded = !shut.includes(g.id)
          return (
            <Box key={g.id} component="section" aria-label={g.label} sx={(t) => ({ mt: 0.5, borderTop: `1px solid ${t.palette.divider}` })}>
              <ButtonBase
                onClick={() => fold(g.id)} aria-expanded={expanded} aria-label={`${g.label} section`}
                sx={(t) => ({ width: '100%', justifyContent: 'flex-start', gap: 0.5, px: 0.75, py: 0.5, '&:hover': { bgcolor: t.palette.action.hover } })}
              >
                <Box aria-hidden sx={{ display: 'flex', fontSize: 13, color: 'text.secondary' }}>{expanded ? <FiChevronDown /> : <FiChevronRight />}</Box>
                <Typography component="h2" sx={{ fontSize: 11, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase' }} color="text.secondary">{g.label}</Typography>
              </ButtonBase>
              {expanded && (
                <Box component="ul" sx={{ m: 0, p: 0, display: 'grid', gap: 0 }}>
                  {g.screens.map((s: Screen) => item(s.id, s.label, s.note, s.icon, tileFill[g.color], here === s.id, () => onNavigate(s.view, s.tab), s.id === 'waiting' ? waiting : 0, !!s.tab))}
                </Box>
              )}
            </Box>
          )
        })}
      </Box>
    </Drawer>
  )
}
