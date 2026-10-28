import { Alert, Avatar, Box, Chip, CircularProgress, Dialog, IconButton, InputAdornment, Skeleton, TextField, Typography, type Theme } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { FiCornerDownLeft, FiSearch, FiUser, FiX } from 'react-icons/fi'
import { searchMembers, type Lookups } from './api'
import { displayName, initials, memberNo, statusOf, type Id } from './clients'
import { LIGHT } from './glass'
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

const AVATAR_TINTS = [tileFill.blue, tileFill.orange, tileFill.indigo]

/** The part of the text the person typed, set in a heavier weight, so it is clear why a row matched. */
function Highlight({ text, q }: { text: string; q: string }) {
  const needle = q.trim()
  if (needle.length < 2) return <>{text}</>
  const at = text.toLowerCase().indexOf(needle.toLowerCase())
  if (at < 0) return <>{text}</>
  return <>{text.slice(0, at)}<Box component="span" sx={{ fontWeight: 800 }}>{text.slice(at, at + needle.length)}</Box>{text.slice(at + needle.length)}</>
}

/** Placeholder rows shown while the first results load, so the list does not jump. */
function LoadingRows() {
  return (
    <Box aria-hidden sx={{ p: 1, display: 'grid', gap: 0.5 }}>
      {[0, 1, 2].map((i) => (
        <Box key={i} sx={{ display: 'flex', alignItems: 'center', gap: 1.75, px: 1.5, py: 1.1 }}>
          <Skeleton variant="circular" width={40} height={40} />
          <Box sx={{ flex: 1 }}><Skeleton width={`${55 - i * 8}%`} height={18} /><Skeleton width="38%" height={14} /></Box>
        </Box>
      ))}
    </Box>
  )
}

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
  const total = enabled && data ? data.totalElements : 0
  const more = total - results.length

  useEffect(() => { activeRef.current?.scrollIntoView({ block: 'nearest' }) }, [active, results.length])

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); setActive((a) => Math.min(a + 1, Math.max(results.length - 1, 0))) }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setActive((a) => Math.max(a - 1, 0)) }
    else if (e.key === 'Enter' && results[active]) { e.preventDefault(); onPick(results[active].id) }
    else if (e.key === 'Escape' && input) { e.preventDefault(); e.stopPropagation(); setInput(''); setActive(0) }
  }

  const rule = (t: Theme) => (dark(t) ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.06)')
  const busy = isFetching && enabled
  return (
    <Box sx={{ position: 'relative', isolation: 'isolate' }}>
      {/* Soft colour behind the glass gives it something to refract. Decorative only. */}
      <Box aria-hidden sx={{ position: 'absolute', inset: 0, zIndex: -1, overflow: 'hidden', pointerEvents: 'none' }}>
        <Box sx={{ position: 'absolute', width: 260, height: 260, top: -90, left: -60, borderRadius: '50%', filter: 'blur(60px)', opacity: 0.3, bgcolor: tileFill.orange }} />
        <Box sx={{ position: 'absolute', width: 300, height: 300, bottom: -130, right: -80, borderRadius: '50%', filter: 'blur(70px)', opacity: 0.25, bgcolor: tileFill.indigo }} />
      </Box>

      <Box sx={{ px: 2.5, pt: 2.25, pb: 2 }}>
        <Typography component="h2" sx={{ fontSize: 12, fontWeight: 600, letterSpacing: '0.06em', textTransform: 'uppercase', mb: 1 }} color="text.secondary">Find Client</Typography>
        <TextField
          id="find-client-input" fullWidth autoFocus value={input}
          onChange={(e) => { setInput(e.target.value); setActive(0) }}
          onKeyDown={onKeyDown}
          placeholder="Search by name or member ID"
          slotProps={{
            htmlInput: {
              role: 'combobox', 'aria-label': 'Client name or member ID', 'aria-expanded': results.length > 0, autoComplete: 'off', spellCheck: false,
              'aria-controls': 'find-client-results', 'aria-activedescendant': results[active] ? `find-client-${active}` : undefined,
            },
            input: {
              startAdornment: <InputAdornment position="start" sx={{ color: 'text.secondary' }}><FiSearch size={18} aria-hidden /></InputAdornment>,
              endAdornment: busy
                ? <InputAdornment position="end"><CircularProgress size={16} aria-label="Searching" /></InputAdornment>
                : input
                  ? <InputAdornment position="end"><IconButton size="small" edge="end" aria-label="Clear search" onClick={() => { setInput(''); setActive(0); document.getElementById('find-client-input')?.focus() }}><FiX size={16} /></IconButton></InputAdornment>
                  : undefined,
            },
          }}
          sx={(t) => ({
            // A taller field with the icon inside and no floating label: the placeholder says what to type. The corner radius is the app's own.
            '& .MuiOutlinedInput-root': {
              fontSize: 17, minHeight: 50, backgroundColor: dark(t) ? 'rgba(0,0,0,0.28)' : LIGHT.field,
              boxShadow: dark(t) ? 'inset 0 1px 2px rgba(0,0,0,0.35)' : 'inset 0 1px 2px rgba(0,0,0,0.08)',
              transition: 'box-shadow 160ms ease-out',
              '& .MuiOutlinedInput-notchedOutline': { borderColor: dark(t) ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.08)' },
              '&.Mui-focused': { boxShadow: `0 0 0 3px ${dark(t) ? 'rgba(10,132,255,0.3)' : 'rgba(0,122,255,0.2)'}` },
            },
            '& .MuiOutlinedInput-input::placeholder': { opacity: 0.55 },
          })}
        />
      </Box>

      <Box sx={(t) => ({ borderTop: `1px solid ${rule(t)}`, minHeight: 132, maxHeight: 380, overflow: 'auto' })}>
        {error && <Alert severity="error" sx={{ m: 1.5 }}>Search failed. Try again.</Alert>}

        {!error && !enabled && (
          <Box sx={{ px: 2.5, py: 3, textAlign: 'center' }}>
            <Typography sx={{ fontSize: 14.5, fontWeight: 600, mb: 0.5 }}>Who are you looking for?</Typography>
            <Typography sx={{ fontSize: 13, mb: 1.5 }} color="text.secondary">Type at least two letters of a name, or a member ID.</Typography>
            <Box sx={{ display: 'flex', gap: 0.75, justifyContent: 'center', flexWrap: 'wrap' }}>
              {['Budikey', '0001-0033189', '33189', 'A010352'].map((e) => (
                <Chip key={e} size="small" variant="outlined" label={e} clickable onClick={() => { setInput(e); setActive(0); document.getElementById('find-client-input')?.focus() }}
                  sx={{ fontVariantNumeric: 'tabular-nums', fontSize: 12 }} />
              ))}
            </Box>
          </Box>
        )}

        {!error && enabled && !data && isFetching && <LoadingRows />}

        {!error && enabled && !isFetching && results.length === 0 && (
          <Box sx={{ px: 2.5, py: 3, textAlign: 'center' }}>
            <Typography sx={{ fontSize: 14.5, fontWeight: 600, mb: 0.5 }}>No clients found</Typography>
            <Typography sx={{ fontSize: 13 }} color="text.secondary">Nothing matches “{q}”. Check the spelling, or try the member ID.</Typography>
          </Box>
        )}

        {results.length > 0 && (
          <>
            <Typography sx={{ px: 2.5, pt: 1.5, pb: 0.5, fontSize: 11.5, fontWeight: 600, letterSpacing: '0.06em', textTransform: 'uppercase' }} color="text.secondary">
              {total > results.length ? `Top ${results.length} of ${total.toLocaleString()} clients` : `${results.length} client${results.length === 1 ? '' : 's'}`}
            </Typography>
            <Box component="ul" id="find-client-results" role="listbox" aria-label="Matching clients" sx={{ listStyle: 'none', m: 0, px: 1, pb: 1, display: 'grid', gap: 0.25 }}>
              {results.map((m, i) => {
                const st = statusOf(m, lk)
                const on = i === active
                const name = displayName(m)
                const dot = st.kind === 'success' ? 'success.main' : st.kind === 'warning' ? 'warning.main' : st.kind === 'info' ? 'info.main' : 'text.disabled'
                return (
                  <Box
                    component="li" key={`${m.id.branchCode}-${m.id.clientId}`} id={`find-client-${i}`} role="option" aria-selected={on}
                    ref={on ? activeRef : undefined}
                    onClick={() => onPick(m.id)} onMouseMove={() => setActive(i)}
                    sx={(t) => ({
                      display: 'flex', alignItems: 'center', gap: 1.75, px: 1.5, py: 1.15, borderRadius: '5px', cursor: 'pointer',
                      backgroundColor: on ? (dark(t) ? 'rgba(255,255,255,0.1)' : 'rgba(0,100,220,0.09)') : 'transparent',
                      transition: 'background-color 120ms ease-out',
                      '@media (prefers-reduced-motion: reduce)': { transition: 'none' },
                    })}
                  >
                    <Avatar aria-hidden sx={{ width: 40, height: 40, fontSize: 14, fontWeight: 700, color: '#fff', backgroundColor: AVATAR_TINTS[m.id.clientId % AVATAR_TINTS.length] }}>
                      {initials(m) === '?' ? <FiUser size={20} /> : initials(m)}
                    </Avatar>
                    <Box sx={{ minWidth: 0, flex: 1 }}>
                      <Typography noWrap sx={{ fontSize: 15, fontWeight: 500, lineHeight: 1.3 }}><Highlight text={name} q={q} /></Typography>
                      <Typography noWrap sx={{ fontSize: 12.5, fontVariantNumeric: 'tabular-nums', lineHeight: 1.4 }} color="text.secondary">
                        <Highlight text={memberNo(m.id)} q={q} />{m.referenceId ? ` · Ref ${m.referenceId}` : ''}{m.city ? ` · ${m.city}` : ''}
                      </Typography>
                    </Box>
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75, flexShrink: 0 }}>
                      <Box aria-hidden sx={{ width: 7, height: 7, borderRadius: '50%', bgcolor: dot }} />
                      <Typography sx={{ fontSize: 12.5 }} color="text.secondary">{st.label}</Typography>
                    </Box>
                    <Box aria-hidden sx={{ width: 18, display: 'flex', justifyContent: 'flex-end', color: 'text.secondary', opacity: on ? 1 : 0, transition: 'opacity 120ms ease-out' }}><FiCornerDownLeft size={15} /></Box>
                  </Box>
                )
              })}
            </Box>
          </>
        )}
        {more > 0 && (
          <Typography sx={{ px: 2.5, pb: 1.5, fontSize: 12.5 }} color="text.secondary">
            {more.toLocaleString()} more match{more === 1 ? '' : 'es'}. Keep typing to narrow the list.
          </Typography>
        )}
      </Box>

      <Box sx={(t) => ({
        px: 2.5, py: 1.1, display: 'flex', gap: 2.5, flexWrap: 'wrap', alignItems: 'center',
        borderTop: `1px solid ${rule(t)}`,
        backgroundColor: dark(t) ? 'rgba(0,0,0,0.18)' : 'rgba(244,245,247,0.55)',
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
            : 'linear-gradient(145deg, rgba(251,251,252,0.82), rgba(247,248,250,0.66))',
          backdropFilter: 'blur(30px) saturate(180%)',
          border: `1px solid ${dark(t) ? 'rgba(255,255,255,0.16)' : LIGHT.hairline}`,
          // A lit top edge, a tight contact shadow and one soft ambient shadow.
          boxShadow: dark(t)
            ? 'inset 0 1px 0 rgba(255,255,255,0.12), 0 1px 2px rgba(0,0,0,0.4), 0 16px 40px rgba(0,0,0,0.5)'
            : 'inset 0 1px 0 rgba(255,255,255,0.55), 0 1px 2px rgba(20,40,90,0.08), 0 16px 40px rgba(20,40,90,0.14)',
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
