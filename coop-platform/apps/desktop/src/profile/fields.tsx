import { fieldSx, type Bind, type Value, useBound } from './editing'
import { Box, MenuItem, TextField, Typography } from '@mui/material'
import { type ReactNode, useId } from 'react'
import { glass } from '../glass'
import { type Lookups } from '../api'

export function Field({ label, value, col, raw, kind = 'text' }: { label: string; value?: Value } & Bind) {
  const { live, current, change } = useBound({ col, raw: raw === undefined ? value : raw, kind })
  const text = live ? current : value == null ? '' : String(value)
  return (
    <TextField
      label={label} value={text} placeholder="—" fullWidth
      multiline={!live || kind === 'text'} maxRows={4}
      type={live && kind === 'date' ? 'date' : undefined}
      onChange={live ? (e) => change(e.target.value) : undefined}
      slotProps={{
        input: { readOnly: !live },
        inputLabel: { shrink: true },
        htmlInput: live && (kind === 'year' || kind === 'money') ? { inputMode: kind === 'year' ? 'numeric' : 'decimal' } : {},
      }}
      sx={fieldSx(live)}
    />
  )
}

/** A field with its label beside it, for compact lists such as the header. */
export function InlineField({ label, value }: { label: string; value?: Value }) {
  const id = useId()
  return (
    <>
      <Typography component="label" htmlFor={id} sx={{ fontSize: 11.5, fontWeight: 500 }} color="text.secondary">{label}</Typography>
      <TextField
        id={id} hiddenLabel size="small" value={value == null ? '' : String(value)} placeholder="—" fullWidth
        slotProps={{ input: { readOnly: true }, htmlInput: { title: value == null ? undefined : String(value) } }}
        sx={{ ...fieldSx(false), '& .MuiOutlinedInput-input': { fontSize: 13 } }}
      />
    </>
  )
}

export function Section({ title, children, action, columns }: { title: string; children: ReactNode; action?: ReactNode; columns?: number }) {
  return (
    <Box sx={(t) => ({ p: 1.25, mb: 1, breakInside: 'avoid', ...glass(t) })}>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 0.75, minHeight: 22 }}>
        <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase' }} color="text.secondary">
          {title}
        </Typography>
        {action}
      </Box>
      <Box sx={{ display: 'grid', gap: '8px 12px', gridTemplateColumns: columns ? `repeat(${columns}, minmax(0, 1fr))` : 'repeat(auto-fill, minmax(132px, 1fr))' }}>{children}</Box>
    </Box>
  )
}

export function EmptyState({ icon, title, hint, action }: { icon: ReactNode; title: string; hint: string; action?: ReactNode }) {
  return (
    <Box sx={{ height: '100%', minHeight: 260, display: 'grid', placeItems: 'center', textAlign: 'center', p: 4 }}>
      <Box>
        <Box sx={{ fontSize: 36, color: 'text.disabled', display: 'flex', justifyContent: 'center', mb: 1 }}>{icon}</Box>
        <Typography sx={{ fontWeight: 600 }}>{title}</Typography>
        <Typography sx={{ fontSize: 13 }} color="text.secondary">{hint}</Typography>
        {action && <Box sx={{ mt: 2 }}>{action}</Box>}
      </Box>
    </Box>
  )
}

// ---- profile ------------------------------------------------------------------------------------------------------

/**
 * A coded field shown as a dropdown of its lookup list. Locked until edit mode, when it opens. A stored code with no
 * description still shows, as "Code n".
 */
export function SelectField({ label, lookup, code, lk, inline = false, col }: {
  label: string; lookup: string; code: number | string | null | undefined; lk?: Lookups; inline?: boolean; col?: string
}) {
  const id = useId()
  const { live, current, change } = useBound({ col, raw: code })
  const options = Object.entries(lk?.[lookup] ?? {})
  const value = current
  const known = value === '' || options.some(([k]) => k === value)
  const sx = {
    ...fieldSx(live),
    '& .MuiSelect-icon': { opacity: live ? 1 : 0.35 },
    ...(inline ? { '& .MuiSelect-select': { fontSize: 13 } } : {}),
  }
  const items = [
    <MenuItem key="" value=""><em>—</em></MenuItem>,
    ...options.map(([k, text]) => <MenuItem key={k} value={k}>{text}</MenuItem>),
    ...(known ? [] : [<MenuItem key={value} value={value}>{`Code ${value}`}</MenuItem>]),
  ]
  const select = { displayEmpty: true, renderValue: (v: unknown) => (v === '' ? <span style={{ opacity: 0.5 }}>—</span> : options.find(([k]) => k === v)?.[1] ?? `Code ${String(v)}`) }
  const onChange = live ? (e: React.ChangeEvent<HTMLInputElement>) => change(e.target.value) : undefined
  const field = inline
    ? <TextField id={id} select hiddenLabel size="small" value={value} fullWidth sx={sx} onChange={onChange} slotProps={{ input: { readOnly: !live }, select }}>{items}</TextField>
    : <TextField select label={label} value={value} fullWidth sx={sx} onChange={onChange} slotProps={{ input: { readOnly: !live }, inputLabel: { shrink: true }, select }}>{items}</TextField>
  if (!inline) return field
  return (
    <>
      <Typography component="label" htmlFor={id} sx={{ fontSize: 11.5, fontWeight: 500 }} color="text.secondary">{label}</Typography>
      {field}
    </>
  )
}

/** Small, low-contrast tag: short, thin outline, regular weight. */
export const quietChip = {
  height: 20, fontSize: 11.5, fontWeight: 400, opacity: 0.9,
  '& .MuiChip-icon': { fontSize: 12, ml: 0.75, mr: -0.25 },
  '& .MuiChip-label': { px: 0.9 },
} as const

/** Glass panel holding a table, so the list tabs match the section cards. */
export function TableCard({ children }: { children: ReactNode }) {
  return <Box sx={(t) => ({ overflow: 'auto', ...glass(t) })}>{children}</Box>
}
