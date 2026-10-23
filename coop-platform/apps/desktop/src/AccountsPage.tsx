import {
  Alert, Autocomplete, Box, Button, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, MenuItem, Paper, Stack, Switch,
  Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { FiPlus } from 'react-icons/fi'
import { createChartAccount, getChart, getChartUsage, listAccounts, updateChartAccount, type ChartAccount } from './api'
import { useMe } from './session'

const TYPES = ['All types', 'Assets', 'Liabilities', 'Equity and Reserves', 'Revenues', 'Expenses', 'Net Income']

/** The chart of accounts. Everyone signed in can look; administrators and accountants can add accounts and change them. */
export default function AccountsPage() {
  const me = useMe()
  const canEdit = me.admin || me.roles.some((r) => r.replace('ROLE_', '') === 'ACCOUNTANT')
  const qc = useQueryClient()
  const [filter, setFilter] = useState('')
  const [type, setType] = useState(0)
  const [showOff, setShowOff] = useState(false)
  const [dialog, setDialog] = useState<ChartAccount | 'new' | null>(null)
  const [done, setDone] = useState<string | null>(null)

  const { data, error, isFetching } = useQuery({ queryKey: ['chart'], queryFn: getChart, enabled: canEdit })

  const rows = useMemo(() => {
    const f = filter.trim().toLowerCase()
    return (data ?? []).filter((a) =>
      (type === 0 || a.type === type) && (showOff || !a.disabled) &&
      (!f || a.code.toLowerCase().includes(f) || (a.name ?? '').toLowerCase().includes(f)))
  }, [data, filter, type, showOff])

  const saved = (msg: string) => { setDialog(null); setDone(msg); void qc.invalidateQueries({ queryKey: ['chart'] }); void qc.invalidateQueries({ queryKey: ['accounts'] }) }

  if (!canEdit) return <ReadOnlyChart />

  return (
    <Container sx={{ py: 3 }}>
      <Stack direction="row" spacing={2} sx={{ mb: 2, alignItems: 'center', flexWrap: 'wrap', rowGap: 1 }}>
        <TextField size="small" label="Find by number or name" value={filter} onChange={(e) => setFilter(e.target.value)} sx={{ width: 300 }} />
        <TextField size="small" select label="Type" value={type} onChange={(e) => setType(Number(e.target.value))} sx={{ width: 200 }}>
          {TYPES.map((t, i) => <MenuItem key={t} value={i}>{t}</MenuItem>)}
        </TextField>
        <FormControlLabel control={<Switch checked={showOff} onChange={(e) => setShowOff(e.target.checked)} />} label="Show switched-off accounts" />
        <Box sx={{ flex: 1 }} />
        <Button variant="contained" startIcon={<FiPlus />} onClick={() => { setDone(null); setDialog('new') }}>New account</Button>
      </Stack>
      {done && <Alert severity="success" onClose={() => setDone(null)} sx={{ mb: 2 }}>{done}</Alert>}
      {error && <Alert severity="error" sx={{ mb: 2 }}>{(error as Error).message}</Alert>}
      <TableContainer component={Paper} sx={{ opacity: isFetching ? 0.6 : 1, maxHeight: 'calc(100dvh - 200px)' }}>
        <Table size="small" stickyHeader>
          <TableHead>
            <TableRow>
              <TableCell>Number</TableCell><TableCell>Name</TableCell><TableCell>Type</TableCell><TableCell>Kind</TableCell>
              <TableCell>Under</TableCell><TableCell>Status</TableCell><TableCell />
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.map((a) => (
              <TableRow key={a.code} hover sx={{ opacity: a.disabled ? 0.55 : 1 }}>
                <TableCell sx={{ fontVariantNumeric: 'tabular-nums' }}>{a.code}</TableCell>
                <TableCell sx={{ pl: 1 + Math.min(a.code.length, 8) * 0.6, fontWeight: a.summary ? 600 : 400 }}>{a.name}</TableCell>
                <TableCell>{a.typeName}</TableCell>
                <TableCell>{a.summary ? 'Summary' : a.glOnly ? 'Detail' : 'Detail (ledger-controlled)'}</TableCell>
                <TableCell>{a.parent}</TableCell>
                <TableCell>{a.disabled ? <Chip size="small" label="Switched off" /> : a.inUse ? <Chip size="small" color="primary" variant="outlined" label="Used by the system" /> : 'Active'}</TableCell>
                <TableCell align="right"><Button size="small" onClick={() => { setDone(null); setDialog(a) }}>Edit</Button></TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
      {dialog && <AccountDialog key={dialog === 'new' ? 'new' : dialog.code} target={dialog} chart={data ?? []} onClose={() => setDialog(null)} onSaved={saved} />}
    </Container>
  )
}

/** People without the right see the list as before. */
function ReadOnlyChart() {
  const { data, error } = useQuery({ queryKey: ['accounts', 1], queryFn: () => listAccounts(1) })
  const [filter, setFilter] = useState('')
  const rows = (data ?? []).filter((a) => !filter.trim() || a.id.code.includes(filter.trim()) || (a.description ?? '').toLowerCase().includes(filter.trim().toLowerCase()))
  return (
    <Container sx={{ py: 3 }}>
      <TextField size="small" label="Find by number or name" value={filter} onChange={(e) => setFilter(e.target.value)} sx={{ mb: 2, width: 300 }} />
      {error && <Alert severity="error" sx={{ mb: 2 }}>{(error as Error).message}</Alert>}
      <TableContainer component={Paper}>
        <Table size="small">
          <TableHead><TableRow><TableCell>Number</TableCell><TableCell>Name</TableCell><TableCell>Under</TableCell><TableCell>Status</TableCell></TableRow></TableHead>
          <TableBody>
            {rows.map((a) => (
              <TableRow key={a.id.code} hover>
                <TableCell>{a.id.code}</TableCell><TableCell>{a.description}</TableCell><TableCell>{a.summaryAccount}</TableCell><TableCell>{a.disabled ? 'Switched off' : 'Active'}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
    </Container>
  )
}

function AccountDialog({ target, chart, onClose, onSaved }: { target: ChartAccount | 'new'; chart: ChartAccount[]; onClose: () => void; onSaved: (msg: string) => void }) {
  const editing = target === 'new' ? null : target
  const [code, setCode] = useState('')
  const [name, setName] = useState(editing?.name ?? '')
  const [summary, setSummary] = useState(false)
  const [parent, setParent] = useState<string | null>(editing?.parent ?? null)
  const [glOnly, setGlOnly] = useState(editing?.glOnly ?? true)
  const [disabled, setDisabled] = useState(editing?.disabled ?? false)
  const [reason, setReason] = useState('')

  const usage = useQuery({ queryKey: ['chart-usage', editing?.code], queryFn: () => getChartUsage(editing!.code), enabled: editing != null })
  // only enabled summary accounts can hold others; for a new account, those whose number is a prefix of the typed one
  const parents = useMemo(() => chart.filter((a) => a.summary && !a.disabled && (editing ? editing.code.startsWith(a.code) && a.code !== editing.code : code.startsWith(a.code) && a.code !== code)), [chart, code, editing])
  const parentRow = parents.find((p) => p.code === parent) ?? null

  const save = useMutation({
    mutationFn: () => editing
      ? updateChartAccount(editing.code, { name, parent, glOnly, disabled, reason })
      : createChartAccount({ code, name, summary, parent, glOnly, reason }),
    onSuccess: (a) => onSaved(`${a.code} ${a.name} was saved`),
  })

  const typeOf = (c: string) => TYPES[Number(c.charAt(0))] ?? ''
  const ready = name.trim() !== '' && reason.trim() !== '' && parent != null && (editing != null || /^[1-6][0-9]{0,9}$/.test(code))

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <form onSubmit={(e) => { e.preventDefault(); if (ready) save.mutate() }}>
        <DialogTitle>{editing ? `${editing.code} ${editing.name ?? ''}` : 'New account'}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            {editing == null ? (
              <TextField required label="Account number" value={code} onChange={(e) => { setCode(e.target.value.replace(/\D/g, '')); setParent(null) }}
                helperText={code ? `${typeOf(code) || 'The first digit must be 1 to 6'}. It must start with the number of the summary account it goes under.` : '1 assets, 2 liabilities, 3 equity, 4 revenues, 5 expenses, 6 net income'}
                slotProps={{ htmlInput: { maxLength: 10, inputMode: 'numeric' } }} />
            ) : (
              <Typography variant="body2" color="text.secondary">
                {editing.typeName} · {editing.summary ? 'Summary account' : 'Detail account'} · the number cannot be changed.
              </Typography>
            )}
            <TextField required label="Account name" value={name} onChange={(e) => setName(e.target.value)} slotProps={{ htmlInput: { maxLength: 50 } }} />
            <Autocomplete
              options={parents} value={parentRow} onChange={(_, v) => setParent(v?.code ?? null)}
              getOptionLabel={(p) => `${p.code} ${p.name ?? ''}`} isOptionEqualToValue={(a, b) => a.code === b.code}
              disabled={editing != null && (usage.data?.entries ?? 0) > 0}
              noOptionsText={editing == null && !code ? 'Type the account number first' : 'No summary account matches this number'}
              renderInput={(p) => <TextField {...p} required label="Under summary account" helperText={editing != null && (usage.data?.entries ?? 0) > 0 ? 'It has ledger entries, so it stays where it is.' : undefined} />}
            />
            {editing == null && (
              <FormControlLabel control={<Switch checked={summary} onChange={(e) => setSummary(e.target.checked)} />} label="Summary account (a heading that totals the accounts under it)" />
            )}
            {(editing == null ? !summary : !editing.summary) && (
              <FormControlLabel control={<Switch checked={glOnly} onChange={(e) => setGlOnly(e.target.checked)} />}
                label="Takes journal entries from the general ledger (switch off for an account that a loan, savings or share capital ledger controls)" />
            )}
            {editing && (
              <>
                <FormControlLabel control={<Switch checked={!disabled} onChange={(e) => setDisabled(!e.target.checked)} />} label="In use" />
                {usage.data && (
                  <Typography variant="body2" color="text.secondary">
                    {usage.data.entries.toLocaleString()} ledger {usage.data.entries === 1 ? 'entry' : 'entries'}
                    {usage.data.children > 0 && ` · ${usage.data.children} account${usage.data.children === 1 ? '' : 's'} under it`}
                    {usage.data.usedBy.length > 0 && ` · used by the system as ${usage.data.usedBy.join(' and ')}, so it cannot be switched off`}
                  </Typography>
                )}
              </>
            )}
            <TextField required label="Reason for this change" value={reason} onChange={(e) => setReason(e.target.value)} slotProps={{ htmlInput: { maxLength: 255 } }} />
            {save.error && <Alert severity="error">{(save.error as Error).message}</Alert>}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="contained" disabled={!ready || save.isPending}>Save</Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}
