import { Box, CircularProgress, ClickAwayListener, FormControlLabel, IconButton, InputAdornment, Paper, Switch, TextField, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useId, useState } from 'react'
import { FiSearch, FiX } from 'react-icons/fi'
import { searchMembers, type Lookups } from './api'
import { RADIUS } from './theme'
import { displayName, memberNo, statusOf, type Id } from './clients'

const MAX_RESULTS = 8
// A name needs two letters; a member ID can be a single digit.
const searchable = (q: string) => q.length >= 2 || /^\d+$/.test(q)

/**
 * A search field that finds the member where it stands: type a name or member ID and the matches drop down under the field; Enter or a click picks one. Used on the teller
 * window so no second search window opens. Active members only unless the switch is on, as in Find Client.
 */
export default function MemberSearchBox({ onPick, lk, width = '100%' }: { onPick: (id: Id) => void; lk?: Lookups; width?: string | number | object }) {
  const listId = useId()
  const [input, setInput] = useState('')
  const [q, setQ] = useState('')
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)
  const [inactive, setInactive] = useState(false)

  // Debounce typing so each keystroke does not hit the large member table.
  useEffect(() => {
    const t = setTimeout(() => setQ(input.trim()), 200)
    return () => clearTimeout(t)
  }, [input])

  const enabled = searchable(q)
  const { data, error, isFetching } = useQuery({
    queryKey: ['members', q, 'teller-search', inactive],
    queryFn: () => searchMembers(q, 0, MAX_RESULTS, inactive),
    enabled,
    placeholderData: (prev) => prev,
  })
  const results = enabled ? data?.content ?? [] : []
  const showList = open && enabled

  const pick = (id: Id) => { setOpen(false); setInput(''); setQ(''); setActive(0); onPick(id) }

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); setOpen(true); setActive((a) => Math.min(a + 1, Math.max(results.length - 1, 0))) }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setActive((a) => Math.max(a - 1, 0)) }
    else if (e.key === 'Enter' && showList && results[active]) { e.preventDefault(); pick(results[active].id) }
    else if (e.key === 'Escape') { e.stopPropagation(); if (open) setOpen(false); else setInput('') }
  }

  return (
    <ClickAwayListener onClickAway={() => setOpen(false)}>
      <Box sx={{ position: 'relative', width }}>
        <TextField
          size="small" fullWidth value={input} placeholder="Search member by name or member number…"
          // the teller's main way in: taller and with larger type than an ordinary field, the corner radius unchanged
          sx={{ '& .MuiOutlinedInput-root': { minHeight: 42, fontSize: 15, pl: 1.5 }, '& .MuiInputAdornment-root svg': { fontSize: 17 } }}
          onChange={(e) => { setInput(e.target.value); setActive(0); setOpen(true) }} onFocus={() => setOpen(true)} onKeyDown={onKeyDown}
          slotProps={{
            htmlInput: {
              role: 'combobox', 'aria-label': 'Search member', 'aria-expanded': showList, 'aria-controls': listId, autoComplete: 'off', spellCheck: false,
              'aria-activedescendant': showList && results[active] ? `${listId}-${active}` : undefined,
            },
            input: {
              startAdornment: <InputAdornment position="start"><FiSearch aria-hidden /></InputAdornment>,
              endAdornment: isFetching && enabled
                ? <InputAdornment position="end"><CircularProgress size={16} aria-label="Searching" /></InputAdornment>
                : input ? <InputAdornment position="end"><IconButton size="small" edge="end" aria-label="Clear search" onClick={() => { setInput(''); setQ(''); setActive(0) }}><FiX size={16} /></IconButton></InputAdornment> : undefined,
            },
          }}
        />
        {showList && (
          <Paper elevation={6} sx={{ position: 'absolute', zIndex: 20, top: '100%', left: 0, mt: 0.5, width: { xs: '100%', md: 'max(100%, 420px)' }, borderRadius: `${RADIUS}px`, overflow: 'hidden' }}>
            <Box component="ul" id={listId} role="listbox" aria-label="Matching members" sx={{ listStyle: 'none', m: 0, p: 0.5, maxHeight: 340, overflow: 'auto' }}>
              {error && <Typography sx={{ p: 1.5 }} color="error">Search failed. Try again.</Typography>}
              {!error && !isFetching && results.length === 0 && (
                <Typography sx={{ p: 1.5 }} color="text.secondary">No members match “{q}”.{!inactive && ' Only active members are listed.'}</Typography>
              )}
              {results.map((m, i) => (
                <Box
                  component="li" key={`${m.id.branchCode}-${m.id.clientId}`} id={`${listId}-${i}`} role="option" aria-selected={i === active}
                  onClick={() => pick(m.id)} onMouseMove={() => setActive(i)}
                  sx={(t) => ({ display: 'flex', alignItems: 'center', gap: 1.5, px: 1.25, py: 0.75, borderRadius: `${RADIUS}px`, cursor: 'pointer', bgcolor: i === active ? t.palette.action.selected : 'transparent' })}
                >
                  <Box sx={{ minWidth: 0, flex: 1 }}>
                    <Typography noWrap sx={{ fontWeight: 500 }}>{displayName(m)}</Typography>
                    <Typography variant="body2" noWrap sx={{ fontVariantNumeric: 'tabular-nums' }} color="text.secondary">{memberNo(m.id)}{m.city ? ` · ${m.city}` : ''}</Typography>
                  </Box>
                  <Typography variant="body2" sx={{ flexShrink: 0 }} color="text.secondary">{statusOf(m, lk).label}</Typography>
                </Box>
              ))}
            </Box>
            <Box sx={{ px: 1.25, py: 0.25, borderTop: 1, borderColor: 'divider' }}>
              <FormControlLabel sx={{ m: 0, '& .MuiFormControlLabel-label': { fontSize: 12 } }} label="Include inactive"
                control={<Switch size="small" checked={inactive} onChange={(e) => { setInactive(e.target.checked); setActive(0) }} />} />
            </Box>
          </Paper>
        )}
      </Box>
    </ClickAwayListener>
  )
}
