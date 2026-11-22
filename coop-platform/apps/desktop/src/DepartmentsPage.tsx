import { Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Tooltip, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { FiEdit2, FiPlus, FiPower, FiTrash2 } from 'react-icons/fi'
import { addDepartment, changeDepartment, deleteDepartment, getDepartments, switchDepartment, type DepartmentRow, type Departments } from './api/departments'
import { DatabaseChangeNotice } from './DatabaseChangeNotice'
import { appBackground, glass } from './glass'
import { LoadingRow } from './ui/Loading'
import { Hinted, ScreenTitle } from './appleUi'

const KEY = ['departments']
const MIN_REASON = 3

/** Why the screen will not offer Delete, or null (the server also checks the books: ledger entries, cheques, loan applications and the rest). */
export function cannotDelete(d: DepartmentRow, defaultForMembers: number): string | null {
  if (d.id === defaultForMembers) return `Department ${defaultForMembers} is given to a new member when none is chosen, so it stays. Rename it instead.`
  const who = [
    d.members ? `${d.members} member${d.members === 1 ? '' : 's'}` : '',
    d.users ? `${d.users} user${d.users === 1 ? '' : 's'}` : '',
    d.children ? `${d.children} department${d.children === 1 ? '' : 's'} under it` : '',
  ].filter(Boolean)
  return who.length ? `${who.join(', ')} still point to this department. Switch it off instead.` : null
}

type Open = { kind: 'add' } | { kind: 'change' | 'switch' | 'delete'; dept: DepartmentRow } | null

/**
 * Departments (Settings > Organization, administrators): the cooperative's departments (the old table dept), such as the main office, satellites and
 * buildings, optionally under a consolidating one. Members, users and the books point to them; a new member is given department 2 when none is chosen.
 * Add, change, switch off or on, and delete one nothing has used. Every change asks for a reason, kept in the audit log.
 */
export default function DepartmentsPage() {
  const q = useQuery({ queryKey: KEY, queryFn: getDepartments })
  const [open, setOpen] = useState<Open>(null)
  const data = q.data
  const editable = !!data?.editable
  const cols = editable ? 6 : 5
  const lacksDefault = !!data && !data.departments.some((d) => d.id === data.defaultForMembers)

  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: 2, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 1000, mx: 'auto' }}>
        <Stack direction={{ xs: 'column', sm: 'row' }} sx={{ gap: 1, alignItems: { sm: 'flex-start' }, justifyContent: 'space-between', mb: 2 }}>
          <Box>
            <ScreenTitle title="Departments">
              The cooperative&apos;s departments: the main office, satellite offices and the like, each optionally under a consolidating one. Members, users and the
              books are kept by department, and a new member is given department {data?.defaultForMembers ?? 2} when none is chosen. A department keeps its number for
              good; one that was used is switched off rather than deleted.
            </ScreenTitle>
          </Box>
          {editable && <Button variant="contained" startIcon={<FiPlus />} onClick={() => setOpen({ kind: 'add' })} sx={{ flexShrink: 0 }}>Add department</Button>}
        </Stack>

        {data && !editable && (
          <DatabaseChangeNotice>This database does not let the application add or change departments yet, so the list can only be read for now.</DatabaseChangeNotice>
        )}
        {lacksDefault && (
          <Alert severity="warning" sx={{ mb: 2 }}>
            There is no department {data.defaultForMembers}, so new members cannot be registered: each one is given department {data.defaultForMembers} when none is
            chosen. Add department {data.defaultForMembers} (usually the main office).
          </Alert>
        )}

        <Box component="section" aria-label="Departments" sx={(t) => ({ ...glass(t), p: 0, overflow: 'hidden' })}>
          <Box sx={{ overflowX: 'auto' }}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell sx={{ fontWeight: 600, width: 64 }}>No.</TableCell>
                  <TableCell sx={{ fontWeight: 600 }}>Department</TableCell>
                  <TableCell sx={{ fontWeight: 600 }}>Under</TableCell>
                  <TableCell align="right" sx={{ fontWeight: 600 }}>Members</TableCell>
                  <TableCell align="right" sx={{ fontWeight: 600 }}>Users</TableCell>
                  {editable && <TableCell align="right" sx={{ fontWeight: 600 }}><Box component="span" sx={{ position: 'absolute', width: 1, height: 1, overflow: 'hidden', clip: 'rect(0 0 0 0)' }}>Actions</Box></TableCell>}
                </TableRow>
              </TableHead>
              <TableBody>
                <LoadingRow q={q} what="the departments" />
                {data && data.departments.length === 0 && (
                  <TableRow><TableCell colSpan={cols}><Typography sx={{ fontSize: 13 }} color="text.secondary">No departments yet.</Typography></TableCell></TableRow>
                )}
                {data?.departments.map((d) => {
                  const blocked = cannotDelete(d, data.defaultForMembers)
                  return (
                    <TableRow key={d.id} hover>
                      <TableCell sx={{ fontVariantNumeric: 'tabular-nums' }}>{d.id}</TableCell>
                      <TableCell>
                        <Stack direction="row" sx={{ alignItems: 'center', gap: 1 }}>
                          <Typography sx={{ fontSize: 13, color: d.disabled ? 'text.disabled' : undefined }}>{d.name}</Typography>
                          {d.disabled && <Chip size="small" label="Switched off" />}
                        </Stack>
                        {(d.tin || d.address) && (
                          <Typography sx={{ fontSize: 11 }} color="text.secondary">{[d.tin && `TIN ${d.tin}`, d.address].filter(Boolean).join(' · ')}</Typography>
                        )}
                      </TableCell>
                      <TableCell>{d.parentName ?? '–'}</TableCell>
                      <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{d.members.toLocaleString()}</TableCell>
                      <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{d.users.toLocaleString()}</TableCell>
                      {editable && (
                        <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                          <Button size="small" startIcon={<FiEdit2 />} onClick={() => setOpen({ kind: 'change', dept: d })} aria-label={`Change ${d.name}`}>Change</Button>
                          <Button size="small" startIcon={<FiPower />} onClick={() => setOpen({ kind: 'switch', dept: d })} aria-label={`${d.disabled ? 'Switch on' : 'Switch off'} ${d.name}`}>
                            {d.disabled ? 'Switch on' : 'Switch off'}
                          </Button>
                          <Tooltip title={blocked ?? ''}>
                            <span>
                              <Button size="small" color="error" startIcon={<FiTrash2 />} disabled={!!blocked} onClick={() => setOpen({ kind: 'delete', dept: d })} aria-label={`Delete ${d.name}`}>Delete</Button>
                            </span>
                          </Tooltip>
                        </TableCell>
                      )}
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
          </Box>
        </Box>
      </Box>
      {open && data && <DepartmentDialog open={open} data={data} onClose={() => setOpen(null)} />}
    </Box>
  )
}

function DepartmentDialog({ open, data, onClose }: { open: NonNullable<Open>; data: Departments; onClose: () => void }) {
  const qc = useQueryClient()
  const dept = open.kind === 'add' ? null : open.dept
  const editing = open.kind === 'add' || open.kind === 'change'
  const firstMain = open.kind === 'add' && !data.departments.some((d) => d.id === data.defaultForMembers)
  const [number, setNumber] = useState(String(firstMain ? data.defaultForMembers : data.nextNumber))
  const [name, setName] = useState(dept?.name ?? (firstMain ? 'MAIN OFFICE' : ''))
  const [parent, setParent] = useState<string>(dept?.parent != null ? String(dept.parent) : '')
  const [tin, setTin] = useState(dept?.tin ?? '')
  const [address, setAddress] = useState(dept?.address ?? '')
  const [reason, setReason] = useState('')
  const id = Number(number)
  const maxName = data.lengths.DeptDesc ?? 30

  const save = useMutation({
    mutationFn: () => {
      const body = { name: name.trim(), parent: parent ? Number(parent) : null, tin: tin.trim(), address: address.trim(), reason: reason.trim() }
      if (open.kind === 'add') return addDepartment({ ...body, id })
      if (open.kind === 'change') return changeDepartment(open.dept.id, body)
      if (open.kind === 'switch') return switchDepartment(open.dept.id, !open.dept.disabled, reason.trim())
      return deleteDepartment(open.dept.id, reason.trim())
    },
    meta: {
      done: open.kind === 'add' ? `Department ${id} added.` : open.kind === 'change' ? 'Department saved.'
        : open.kind === 'switch' ? `${dept?.name} switched ${dept?.disabled ? 'on' : 'off'}.` : `${dept?.name} deleted.`,
    },
    onSuccess: (fresh) => {
      qc.setQueryData(KEY, fresh)
      void qc.invalidateQueries({ queryKey: ['lookups'] })
      onClose()
    },
  })

  const nameProblem = !editing ? null
    : !name.trim() ? 'Give the department a name.'
    : name.trim().length > maxName ? `At most ${maxName} characters.`
    : data.departments.some((d) => d.id !== dept?.id && d.name.trim().toLowerCase() === name.trim().toLowerCase()) ? 'Another department already has this name.'
    : null
  const numberProblem = open.kind !== 'add' ? null
    : !Number.isInteger(id) || id < 1 || id > 32767 ? 'A number from 1 to 32767.'
    : data.departments.some((d) => d.id === id) ? `Already used; the next free number is ${data.nextNumber}.`
    : null
  const ready = !nameProblem && !numberProblem && reason.trim().length >= MIN_REASON && !save.isPending
  const title = open.kind === 'add' ? 'Add a department' : open.kind === 'change' ? `Change department ${dept?.id}`
    : open.kind === 'switch' ? `${dept?.disabled ? 'Switch on' : 'Switch off'} ${dept?.name}` : `Delete ${dept?.name}`
  const parents = data.departments.filter((d) => d.id !== dept?.id)

  return (
    <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          {save.error && <Alert severity="error">{(save.error as Error).message}</Alert>}
          {open.kind === 'switch' && (
            <Typography sx={{ fontSize: 13 }}>
              {dept?.disabled
                ? `${dept.name} can be chosen again for members and users.`
                : `${dept?.name} will no longer be offered for members and users. The records that already use it keep it.`}
            </Typography>
          )}
          {open.kind === 'delete' && (
            <Typography sx={{ fontSize: 13 }}>
              Department {dept?.id}, {dept?.name}, will be removed. If anything in the books still uses it, nothing is deleted and you will be told to switch it off.
            </Typography>
          )}
          {editing && (
            <>
              {open.kind === 'add' && (
                <Hinted tip="Stays with the department for good."><TextField label="Department number" value={number} onChange={(e) => setNumber(e.target.value.replace(/\D/g, ''))} required
                  error={!!numberProblem} helperText={numberProblem} slotProps={{ htmlInput: { inputMode: 'numeric' } }} /></Hinted>
              )}
              <TextField label="Department name" value={name} onChange={(e) => setName(e.target.value)} required autoFocus
                error={!!nameProblem && name.length > 0} helperText={(name.length > 0 && nameProblem) || `Such as MAIN OFFICE. At most ${maxName} characters.`} />
              <Hinted tip="The consolidating department it belongs to, if any."><TextField select label="Under" value={parent} onChange={(e) => setParent(e.target.value)}>
                <MenuItem value="">None</MenuItem>
                {parents.map((d) => <MenuItem key={d.id} value={String(d.id)}>{d.id} · {d.name}</MenuItem>)}
              </TextField></Hinted>
              <TextField label="TIN" value={tin} onChange={(e) => setTin(e.target.value)} />
              <TextField label="Address" value={address} onChange={(e) => setAddress(e.target.value)} />
            </>
          )}
          <Hinted tip="Kept in the audit log with the change."><TextField label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} required autoFocus={!editing} /></Hinted>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" color={open.kind === 'delete' ? 'error' : 'primary'} disabled={!ready} onClick={() => save.mutate()}>
          {open.kind === 'add' ? 'Add department' : open.kind === 'change' ? 'Save' : open.kind === 'switch' ? (dept?.disabled ? 'Switch on' : 'Switch off') : 'Delete department'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
