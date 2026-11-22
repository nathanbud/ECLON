import {
  Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, IconButton, InputAdornment, TextField, ToggleButton, ToggleButtonGroup, Tooltip, Typography,
  type SxProps, type Theme,
} from '@mui/material'
import { useState, type ReactNode } from 'react'
import { FiInfo, FiSearch } from 'react-icons/fi'
import { glass } from './glass'

/** Small building blocks shared by the settings-style screens, so they look and move the same way. */

export const EASE = 'cubic-bezier(.2,.8,.2,1)'

const fill = (t: Theme) => (t.palette.mode === 'dark' ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.05)')

/** A small rounded square in a colour with an icon in it, as on the cards' headings and the quick actions. */
export const IconChip = ({ icon, color, size = 28 }: { icon: React.ReactNode; color: string; size?: number }) => (
  <Box aria-hidden sx={{ width: size, height: size, flexShrink: 0, borderRadius: `${Math.round(size * 0.28)}px`, display: 'grid', placeItems: 'center', color, bgcolor: `${color}1F`, fontSize: Math.round(size * 0.55), lineHeight: 0 }}>{icon}</Box>
)

/**
 * A small ⓘ that shows an explanation (what a screen is for, how it works) on hover, on keyboard focus or on a tap, instead of a paragraph on the screen. Only for text a
 * person can do without: an error, a warning, a limit to keep to or what an action is about to do stays on the screen.
 */
export function InfoTip({ children, label = 'About this screen' }: { children: ReactNode; label?: string }) {
  const [open, setOpen] = useState(false)
  return (
    <Tooltip describeChild open={open} onOpen={() => setOpen(true)} onClose={() => setOpen(false)}
      title={<Box sx={{ fontSize: 13, lineHeight: 1.45, py: 0.25 }}>{children}</Box>} slotProps={{ tooltip: { sx: { maxWidth: 400 } } }}>
      <IconButton aria-label={label} onClick={() => setOpen((o) => !o)} sx={{ color: 'text.secondary', fontSize: 15, flexShrink: 0 }}><FiInfo /></IconButton>
    </Tooltip>
  )
}

/** A screen's title, with its explanation behind an ⓘ beside it (see {@link InfoTip}). */
export function ScreenTitle({ title, children, sx }: { title: ReactNode; children?: ReactNode; sx?: SxProps<Theme> }) {
  return (
    <Box sx={[{ display: 'flex', alignItems: 'center', gap: 0.25, minWidth: 0 }, ...(Array.isArray(sx) ? sx : [sx])]}>
      <Typography component="h1" sx={{ fontSize: 20, fontWeight: 700, letterSpacing: '-0.02em', lineHeight: 1.25 }}>{title}</Typography>
      {children && <InfoTip>{children}</InfoTip>}
    </Box>
  )
}

/** A large title, with its explanation behind an ⓘ. */
export function PageTitle({ title, children }: { title: string; children?: ReactNode }) {
  return <ScreenTitle title={title} sx={{ mb: 2.5, px: 0.5 }}>{children}</ScreenTitle>
}

/** The rounded surface a list or table sits on. */
export function Surface({ children, label }: { children: ReactNode; label?: string }) {
  return <Box role={label ? 'group' : undefined} aria-label={label} sx={(t) => ({ ...glass(t), overflow: 'hidden' })}>{children}</Box>
}

/** A small heading above a surface. */
export function SectionLabel({ children }: { children: ReactNode }) {
  return <Typography component="h2" color="text.secondary" sx={{ fontSize: 12, fontWeight: 600, letterSpacing: '0.05em', textTransform: 'uppercase', mb: 0.75, px: 0.5 }}>{children}</Typography>
}

export function SearchField({ value, onChange, placeholder = 'Search', width = 260 }: { value: string; onChange: (v: string) => void; placeholder?: string; width?: number }) {
  return (
    <TextField
      size="small" value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder} sx={{ width }}
      slotProps={{
        htmlInput: { 'aria-label': placeholder },
        input: { startAdornment: <InputAdornment position="start"><FiSearch /></InputAdornment>, sx: (t: Theme) => ({ borderRadius: '10px', bgcolor: fill(t), '& fieldset': { border: 'none' } }) },
      }}
    />
  )
}

/** A segmented control, as in iOS: one pill holds the choices and the chosen one lifts. */
export function Segmented<T extends string | number>({ value, onChange, options, label }: {
  value: T; onChange: (v: T) => void; options: { value: T; label: string }[]; label: string
}) {
  return (
    <ToggleButtonGroup
      exclusive value={value} onChange={(_, v) => v != null && onChange(v as T)} aria-label={label} size="small"
      sx={(t) => ({
        bgcolor: fill(t), borderRadius: '7px', p: '2px', gap: '2px', flexWrap: 'wrap',
        '& .MuiToggleButtonGroup-grouped': { border: 0, borderRadius: '5px !important', minHeight: 24, px: 1.75, py: 0, textTransform: 'none', fontSize: 13, fontWeight: 500, color: t.palette.text.secondary, transition: `background-color 180ms ${EASE}, color 180ms` },
        '& .Mui-selected': {
          bgcolor: `${t.palette.mode === 'dark' ? 'rgba(255,255,255,0.16)' : '#FBFBFC'} !important`, color: `${t.palette.text.primary} !important`,
          boxShadow: t.palette.mode === 'dark' ? '0 1px 2px rgba(0,0,0,0.4)' : '0 1px 3px rgba(0,0,0,0.12), 0 0 0 0.5px rgba(0,0,0,0.04)',
        },
      })}
    >
      {options.map((o) => <ToggleButton key={String(o.value)} value={o.value} disableRipple>{o.label}</ToggleButton>)}
    </ToggleButtonGroup>
  )
}

/** A status shown as a coloured dot and a word, the way system lists do. */
export function Dot({ color, children }: { color: 'success' | 'warning' | 'error' | 'grey'; children: ReactNode }) {
  return (
    <Box component="span" sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.75, fontSize: 13, whiteSpace: 'nowrap' }}>
      <Box aria-hidden component="span" sx={(t) => ({ width: 8, height: 8, borderRadius: '50%', bgcolor: color === 'grey' ? t.palette.text.disabled : t.palette[color].main })} />
      {children}
    </Box>
  )
}

/** A hairline table style: no heavy header, thin dividers, rows that react to the pointer. */
export const listTable = (t: Theme) => ({
  '& .MuiTableCell-root': { borderBottom: `0.5px solid ${t.palette.divider}`, py: 1.1 },
  '& .MuiTableHead-root .MuiTableCell-root': { fontSize: 11, fontWeight: 600, letterSpacing: '0.05em', textTransform: 'uppercase', color: t.palette.text.secondary, py: 0.9, bgcolor: 'transparent' },
  '& .MuiTableBody-root .MuiTableRow-root:last-of-type .MuiTableCell-root': { borderBottom: 0 },
  '& .MuiTableBody-root .MuiTableRow-root': { transition: `background-color 160ms ${EASE}` },
})

/**
 * A short form that asks for a reason before it saves, used for every audited change. Extra fields go in as children.
 * It stays mounted so it can fade out; the reason is cleared once it has closed.
 */
export function ReasonDialog({ open, title, intro, confirm, danger, busy, error, canSubmit = true, onCancel, onConfirm, children }: {
  open: boolean; title: string; intro?: string; confirm: string; danger?: boolean; busy: boolean; error: string | null; canSubmit?: boolean
  onCancel: () => void; onConfirm: (reason: string) => void; children?: ReactNode
}) {
  const [reason, setReason] = useState('')
  const ready = reason.trim() !== '' && canSubmit && !busy
  return (
    <Dialog open={open} onClose={busy ? undefined : onCancel} fullWidth maxWidth="xs" aria-labelledby="reason-title" slotProps={{ transition: { onExited: () => setReason('') } }}>
      <DialogTitle id="reason-title" sx={{ pb: 0.5 }}>{title}</DialogTitle>
      <form onSubmit={(e) => { e.preventDefault(); if (ready) onConfirm(reason.trim()) }}>
        <DialogContent sx={{ display: 'grid', gap: 1.75, pt: 1 }}>
          {intro && <Typography color="text.secondary" sx={{ fontSize: 13 }}>{intro}</Typography>}
          {error && <Alert severity="error">{error}</Alert>}
          {children}
          <TextField required autoFocus={!children} label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} slotProps={{ htmlInput: { maxLength: 255 } }} helperText="Kept in the audit log" />
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button color="inherit" onClick={onCancel} disabled={busy}>Cancel</Button>
          <Button type="submit" variant="contained" color={danger ? 'error' : 'primary'} disabled={!ready}>{confirm}</Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}
