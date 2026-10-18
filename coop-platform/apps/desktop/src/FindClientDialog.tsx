import { Alert, Avatar, Box, Chip, CircularProgress, Dialog, InputAdornment, TextField, Typography, type Theme } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { FiSearch } from 'react-icons/fi'
import { searchMembers, type Lookups } from './api'
import { displayName, initials, memberNo, statusOf, type Id } from './clients'
import { tileFill } from './theme'

const MAX_RESULTS = 8

// A name needs two letters; a member ID can be a single digit ("1" is not useful, but "33189" is).
const searchable = (q: string) => q.length >= 2 || /^\d+$/.test(q)

const dark = (t: Theme) => t.palette.mode === 'dark'

/** A physical-looking key cap for the shortcut hints. */
const Key = ({ children }: { children: ReactNode }) => (
  <Box component="kbd" sx={(t) => ({
    fontFamily: 'inherit', fontSize: 11.5, fontWeight: 600, lineHeight: 1, px: 0.9, py: 0.5, borderRadius: '5px',
    color: 'text.primary',
    backgroundColor: dark(t) ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.04)',
    border: `1px solid ${dark(t) ? 'rgba(255,255,255,0.16)' : 'rgba(0,0,0,0.12)'}`,
  })}>{children}</Box>
)

/** The search field and results. Used inside the pop-up, and directly on the page when no client is open. */
export function FindBody({ onPick, lk, inline = false }: { onPick: (id: Id) => void; lk?: Lookups; inline?: boolean }) {
  const [input, setInput] = useState('')
  const [q, setQ] = useState('')
  const [active, setActive] = useState(0)
  const activeRef = useRef<HTMLLIElement>(null)

  // Debounce typing so each keystroke doesn't hit the large member table.
  useEffect(() => {
    const t = setTimeout(() => setQ(input.trim()), 200)
    return () => clearTimeout(t)
  }, [input])

  const enabled = searchable(q)
  const { data, error, isFetching } = useQuery({
    queryKey: ['members', q, 'find'],
    queryFn: () => searchMembers(q, 0, MAX_RESULTS),
    enabled,
    placeholderData: (prev) => prev,
  })
  const results = enabled ? data?.content ?? [] : []
  const more = enabled && data ? data.totalElements - results.length : 0

  useEffect(() => { activeRef.current?.scrollIntoView({ block: 'nearest' }) }, [active, results.length])

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); setActive((a) => Math.min(a + 1, Math.max(results.length - 1, 0))) }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setActive((a) => Math.max(a - 1, 0)) }
    else if (e.key === 'Enter' && results[active]) { e.preventDefault(); onPick(results[active].id) }
  }

  return (
    <Box sx={{ position: 'relative', isolation: 'isolate' }}>
      {/* Soft colour behind the glass gives it something to refract. Decorative only. */}
      <Box aria-hidden sx={{ position: 'absolute', inset: 0, zIndex: -1, overflow: 'hidden', pointerEvents: 'none' }}>
        <Box sx={{ position: 'absolute', width: 260, height: 260, top: -90, left: -60, borderRadius: '50%', filter: 'blur(60px)', opacity: 0.3, bgcolor: tileFill.orange }} />
        <Box sx={{ position: 'absolute', width: 300, height: 300, bottom: -130, right: -80, borderRadius: '50%', filter: 'blur(70px)', opacity: 0.25, bgcolor: tileFill.indigo }} />
      </Box>

      <Box sx={{ p: 2.5, pb: 2 }}>
        <Typography component="h2" sx={{ fontSize: 18, fontWeight: 700, letterSpacing: '-0.02em', mb: 1.5 }}>Find Client</Typography>
        <TextField
          id="find-client-input" fullWidth autoFocus value={input}
          onChange={(e) => { setInput(e.target.value); setActive(0) }}
          onKeyDown={onKeyDown}
          label="Client name or member ID"
          placeholder="e.g. Budikey, or 0001-0033189"
          slotProps={{
            htmlInput: {
              role: 'combobox', 'aria-expanded': results.length > 0,
              'aria-controls': 'find-client-results', 'aria-activedescendant': results[active] ? `find-client-${active}` : undefined,
            },
            input: {
              endAdornment: isFetching && enabled
                ? <InputAdornment position="end"><CircularProgress size={16} /></InputAdornment>
                : <InputAdornment position="end"><FiSearch /></InputAdornment>,
            },
          }}
          sx={(t) => ({
            // The field looks pressed into the glass: inset shadow below the top edge, a lit rim at the bottom.
            '& .MuiOutlinedInput-root': {
              borderRadius: '5px', fontSize: 15.5, backgroundColor: dark(t) ? 'rgba(0,0,0,0.28)' : 'rgba(255,255,255,0.55)',
              boxShadow: dark(t) ? 'inset 0 1px 2px rgba(0,0,0,0.35)' : 'inset 0 1px 2px rgba(0,0,0,0.08)',
              transition: 'box-shadow 160ms ease-out',
              '& .MuiOutlinedInput-notchedOutline': { borderColor: dark(t) ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.08)' },
              '&.Mui-focused': { boxShadow: `0 0 0 3px ${dark(t) ? 'rgba(10,132,255,0.3)' : 'rgba(0,122,255,0.2)'}` },
            },
          })}
        />
      </Box>

      <Box sx={(t) => ({ borderTop: `1px solid ${dark(t) ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.06)'}`, minHeight: 120, maxHeight: 360, overflow: 'auto' })}>
        {error && <Alert severity="error" sx={{ m: 1.5 }}>Search failed. Try again.</Alert>}
        {!error && !enabled && (
          <Typography sx={{ p: 2.5, fontSize: 13.5 }} color="text.secondary">
            Type a name, or a member ID such as 0001-0033189 or 33189. A reference ID such as A010352 works too.
          </Typography>
        )}
        {!error && enabled && !isFetching && results.length === 0 && (
          <Typography sx={{ p: 2.5, fontSize: 13.5 }} color="text.secondary">No clients found for “{q}”.</Typography>
        )}
        <Box component="ul" id="find-client-results" role="listbox" aria-label="Matching clients" sx={{ listStyle: 'none', m: 0, p: 1, display: 'grid', gap: 0.75 }}>
          {results.map((m, i) => {
            const st = statusOf(m, lk)
            const on = i === active
            return (
              <Box
                component="li" key={`${m.id.branchCode}-${m.id.clientId}`} id={`find-client-${i}`} role="option" aria-selected={on}
                ref={on ? activeRef : undefined}
                onClick={() => onPick(m.id)} onMouseMove={() => setActive(i)}
                sx={(t) => ({
                  display: 'flex', alignItems: 'center', gap: 1.5, px: 1.5, py: 1.1, borderRadius: '5px', cursor: 'pointer',
                  border: '1px solid transparent',
                  // The highlighted row is just a soft tint.
                  backgroundColor: on ? (dark(t) ? 'rgba(255,255,255,0.1)' : 'rgba(0,100,220,0.09)') : 'transparent',
                  transition: 'background-color 120ms ease-out',
                  '@media (prefers-reduced-motion: reduce)': { transition: 'none' },
                })}
              >
                <Avatar sx={{
                  width: 38, height: 38, fontSize: 13, fontWeight: 700, color: '#fff',
                  backgroundColor: tileFill.blue,
                }}>{initials(m)}</Avatar>
                <Box sx={{ minWidth: 0, flex: 1 }}>
                  <Typography noWrap sx={{ fontSize: 14.5, fontWeight: 600 }}>{displayName(m)}</Typography>
                  <Typography noWrap sx={{ fontSize: 12.5, fontVariantNumeric: 'tabular-nums' }} color="text.secondary">
                    {memberNo(m.id)}{m.referenceId ? ` · Ref ${m.referenceId}` : ''}{m.city ? ` · ${m.city}` : ''}
                  </Typography>
                </Box>
                <Chip size="small" variant="outlined" label={st.label} color={st.kind} sx={{ height: 22, fontSize: 11.5, backdropFilter: 'blur(6px)' }} />
              </Box>
            )
          })}
        </Box>
        {more > 0 && (
          <Typography sx={{ px: 2.5, pb: 1.5, fontSize: 12.5 }} color="text.secondary">
            {more.toLocaleString()} more match{more === 1 ? '' : 'es'}. Keep typing to narrow the list.
          </Typography>
        )}
      </Box>

      <Box sx={(t) => ({
        px: 2.5, py: 1.25, display: 'flex', gap: 2.5, flexWrap: 'wrap', alignItems: 'center',
        borderTop: `1px solid ${dark(t) ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.06)'}`,
        backgroundColor: dark(t) ? 'rgba(0,0,0,0.18)' : 'rgba(255,255,255,0.35)',
      })}>
        {[['↑↓', 'move'], ['Enter', 'open'], ...(inline ? [] : [['Esc', 'close']])].map(([k, label]) => (
          <Box key={k} sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
            <Key>{k}</Key>
            <Typography sx={{ fontSize: 12 }} color="text.secondary">{label}</Typography>
          </Box>
        ))}
      </Box>
    </Box>
  )
}

/** A pop-up for finding a client by name or member ID. Its contents are mounted only while open, so each use starts fresh. */
export default function FindClientDialog({ open, onClose, onPick, lk }: {
  open: boolean; onClose: () => void; onPick: (id: Id) => void; lk?: Lookups
}) {
  return (
    <Dialog
      open={open} onClose={onClose} fullWidth maxWidth="sm" aria-label="Find Client"
      slotProps={{
        // The scrim blurs the app behind, so the panel reads as a floating sheet of glass.
        backdrop: { sx: (t: Theme) => ({
          backdropFilter: 'blur(14px) saturate(140%)',
          backgroundColor: dark(t) ? 'rgba(0,0,0,0.5)' : 'rgba(40,50,80,0.28)',
        }) },
        paper: { sx: (t: Theme) => ({
          alignSelf: 'flex-start', mt: '12vh', borderRadius: '5px', overflow: 'hidden',
          backgroundImage: 'none',
          background: dark(t)
            ? 'linear-gradient(145deg, rgba(58,58,64,0.62), rgba(28,28,32,0.5))'
            : 'linear-gradient(145deg, rgba(255,255,255,0.78), rgba(255,255,255,0.5))',
          backdropFilter: 'blur(30px) saturate(180%)',
          border: `1px solid ${dark(t) ? 'rgba(255,255,255,0.16)' : 'rgba(255,255,255,0.7)'}`,
          // A lit top edge, a tight contact shadow and one soft ambient shadow.
          boxShadow: dark(t)
            ? 'inset 0 1px 0 rgba(255,255,255,0.12), 0 1px 2px rgba(0,0,0,0.4), 0 16px 40px rgba(0,0,0,0.5)'
            : 'inset 0 1px 0 rgba(255,255,255,0.8), 0 1px 2px rgba(20,40,90,0.08), 0 16px 40px rgba(20,40,90,0.16)',
          // Fades in with a small drop.
          transformOrigin: '50% 0%',
          animation: 'findIn 220ms cubic-bezier(.2,.8,.2,1) both',
          '@keyframes findIn': {
            from: { opacity: 0, transform: 'translateY(-6px) scale(0.985)' },
            to: { opacity: 1, transform: 'none' },
          },
          '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
        }) },
      }}
    >
      <FindBody onPick={onPick} lk={lk} />
    </Dialog>
  )
}
