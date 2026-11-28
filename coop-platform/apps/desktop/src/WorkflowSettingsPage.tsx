import { Alert, Box, Button, Checkbox, FormControlLabel, IconButton, MenuItem, Stack, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { FiPlus, FiTrash2 } from 'react-icons/fi'
import { getWorkflowDefinitions, getWorkflowOptions, saveWorkflowDefinition, type WorkflowDefinition } from './api'
import { appBackground, glass } from './glass'
import { useLeaveGuard } from './leaveGuard'
import { useMe } from './session'
import { parseAmount } from './ui/format'
import { notifyError } from './ui/notices'
import { AboutNote, ScreenTitle } from './appleUi'

interface Row { name: string; role: string; minAmount: string }
const roleName = (r: string) => r.toLowerCase().replace(/_/g, ' ')

function Editor({ def, roles, max, canEdit }: { def: WorkflowDefinition; roles: string[]; max: number; canEdit: boolean }) {
  const qc = useQueryClient()
  const [active, setActive] = useState(def.active)
  const [rows, setRows] = useState<Row[]>(def.steps.map((s) => ({ name: s.name, role: s.role, minAmount: String(s.minAmount) })))
  const [saved, setSaved] = useState(false)
  const initial = JSON.stringify([def.active, def.steps.map((s) => ({ name: s.name, role: s.role, minAmount: String(s.minAmount) }))])
  useLeaveGuard(canEdit && JSON.stringify([active, rows]) !== initial ? `The approval steps for ${def.label} have changes that are not saved.` : null)
  const set = (i: number, k: keyof Row, v: string) => { setSaved(false); setRows(rows.map((r, j) => (j === i ? { ...r, [k]: v } : r))) }
  const save = useMutation({
    mutationFn: () => saveWorkflowDefinition(def.entityType, active, rows.map((r) => ({ name: r.name, role: r.role, minAmount: parseAmount(r.minAmount || '0') }))),
    onSuccess: () => { setSaved(true); void qc.invalidateQueries({ queryKey: ['workflow-definitions'] }) },
    onError: (e) => notifyError((e as Error).message),
  })
  return (
    <Box sx={(t) => ({ p: 2, mb: 2, ...glass(t) })}>
      <Stack direction="row" sx={{ alignItems: 'center', mb: 0.5 }}>
        <Typography variant="h6" component="p" sx={{ flex: 1 }}>{def.label}</Typography>
        <FormControlLabel control={<Checkbox checked={active} disabled={!canEdit} onChange={(_, v) => { setActive(v); setSaved(false) }} />} label="Extra approvals on" />
      </Stack>
      <AboutNote sx={{ mb: 1.5 }}>
        The usual approver approves first, as always. Then each step below that applies to the amount also has to approve, in this order, by a different person each time.
      </AboutNote>
      {rows.length === 0 && <Typography sx={{ mb: 1 }} color="text.secondary">No extra steps: the usual approver is enough at any amount.</Typography>}
      {rows.map((r, i) => (
        <Stack key={i} direction="row" spacing={1.5} sx={{ mb: 1, alignItems: 'center' }}>
          <Typography sx={{ width: 22, color: 'text.secondary' }}>{i + 1}.</Typography>
          <TextField size="small" label="Step" value={r.name} disabled={!canEdit} onChange={(e) => set(i, 'name', e.target.value)} sx={{ flex: 1 }} />
          <TextField select size="small" label="Approved by" value={r.role} disabled={!canEdit} onChange={(e) => set(i, 'role', e.target.value)} sx={{ width: 210 }}>
            {roles.map((x) => <MenuItem key={x} value={x}>{roleName(x)}</MenuItem>)}
          </TextField>
          <TextField size="small" label="When the amount is at least" value={r.minAmount} disabled={!canEdit} onChange={(e) => set(i, 'minAmount', e.target.value)} sx={{ width: 210 }} />
          {canEdit && <IconButton aria-label="Remove step" onClick={() => { setSaved(false); setRows(rows.filter((_, j) => j !== i)) }}><FiTrash2 /></IconButton>}
        </Stack>
      ))}
      {canEdit && (
        <Stack direction="row" spacing={1.5} sx={{ mt: 1.5, alignItems: 'center' }}>
          <Button size="small" startIcon={<FiPlus />} disabled={rows.length >= max} onClick={() => { setSaved(false); setRows([...rows, { name: '', role: roles[0] ?? 'ACCOUNTANT', minAmount: '0' }]) }}>Add a step</Button>
          <Button variant="contained" disabled={save.isPending} onClick={() => save.mutate()}>Save</Button>
          {saved && <Typography color="success.main">Saved. Items already under way keep the steps they started with.</Typography>}
        </Stack>
      )}
    </Box>
  )
}

/** Who must approve what, from which amount (guide phase 17: approval limits are configurable). Administrators change it; accountants may read it. */
export default function WorkflowSettingsPage() {
  const me = useMe()
  const defs = useQuery({ queryKey: ['workflow-definitions'], queryFn: getWorkflowDefinitions })
  const opts = useQuery({ queryKey: ['workflow-options'], queryFn: getWorkflowOptions })
  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: 2, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 900, mx: 'auto' }}>
        <ScreenTitle title="Approval workflows" sx={{ mb: me.admin ? 2 : 0.5 }}>
          Set extra approvals and the amounts they start at. A purchase order of 100,000 or more can, for example, also need the accountant, and one of 1,000,000 or more the manager as well.
        </ScreenTitle>
        {!me.admin && <Typography sx={{ mb: 2 }} color="text.secondary">Only an administrator can change these.</Typography>}
        {defs.isError && <Alert severity="error">{(defs.error as Error).message}</Alert>}
        {defs.data && opts.data && defs.data.map((d) => <Editor key={d.entityType + JSON.stringify(d)} def={d} roles={opts.data.roles} max={opts.data.maxSteps} canEdit={me.admin} />)}
        <Typography variant="body2" color="text.secondary">Loans, journals, membership changes, cash adjustments and CIC submissions keep their own approvals for now.</Typography>
      </Box>
    </Box>
  )
}
