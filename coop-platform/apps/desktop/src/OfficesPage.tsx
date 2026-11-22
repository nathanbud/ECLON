import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Tooltip, Typography } from '@mui/material'
import { DatabaseChangeNotice } from './DatabaseChangeNotice'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { FiEdit2, FiPlus, FiTrash2 } from 'react-icons/fi'
import { addOffice, deleteOffice, getOffices, renameOffice, type OfficeRow, type Offices } from './api/offices'
import { appBackground, glass } from './glass'
import { LoadingRow } from './ui/Loading'
import { ScreenTitle } from './appleUi'

const KEY = ['offices']
const MIN_REASON = 3

/** Why an office cannot be deleted, or null when it can (the server checks the same). */
export function cannotDelete(o: OfficeRow): string | null {
  if (o.id === 1) return 'Office 1 is given to a new member when no office is chosen, so it stays. Rename it instead.'
  const who = [o.members ? `${o.members} member${o.members === 1 ? '' : 's'}` : '', o.users ? `${o.users} user${o.users === 1 ? '' : 's'}` : ''].filter(Boolean)
  return who.length ? `${who.join(' and ')} still belong to this office. Rename it instead, or move them first.` : null
}

type Open = { kind: 'add' } | { kind: 'rename'; office: OfficeRow } | { kind: 'delete'; office: OfficeRow } | null

/**
 * Offices (Settings > Organization, administrators): the cooperative's offices (the old table restrik). Each member belongs to one (a new member is given
 * office 1 when none is chosen, so registration needs it) and each user has a list of them. Add an office, rename it, or delete one nothing uses; the number
 * never changes. Every change asks for a reason, kept in the audit log.
 */
export default function OfficesPage() {
  const q = useQuery({ queryKey: KEY, queryFn: getOffices })
  const [open, setOpen] = useState<Open>(null)
  const data = q.data
  const editable = !!data?.editable
  const noOffices = !!data && data.offices.length === 0
  const noOfficeOne = !!data && data.offices.length > 0 && !data.offices.some((o) => o.id === 1)

  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: 2, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 860, mx: 'auto' }}>
        <Stack direction={{ xs: 'column', sm: 'row' }} sx={{ gap: 1, alignItems: { sm: 'flex-start' }, justifyContent: 'space-between', mb: 2 }}>
          <Box>
            <ScreenTitle title="Offices">
              The cooperative&apos;s offices. Every member belongs to one, and a new member is given office 1 when none is chosen. Each user is given the offices
              they work for in Settings &gt; Users. An office keeps its number for good; it can be renamed, and deleted only while no member or user belongs to it.
            </ScreenTitle>
          </Box>
          {editable && (
            <Button variant="contained" startIcon={<FiPlus />} onClick={() => setOpen({ kind: 'add' })} sx={{ flexShrink: 0 }}>Add office</Button>
          )}
        </Stack>

        {data && !editable && (
          <DatabaseChangeNotice>This database does not let the application add, rename or delete offices yet, so the list can only be read for now.</DatabaseChangeNotice>
        )}
        {noOffices && (
          <Alert severity="warning" sx={{ mb: 2 }}>
            This database has no offices yet, so new members cannot be registered: each one is given office 1. Add office 1 first (the main office).
          </Alert>
        )}
        {noOfficeOne && (
          <Alert severity="warning" sx={{ mb: 2 }}>
            There is no office 1. A new member is given office 1 when none is chosen, so add an office with number 1.
          </Alert>
        )}

        <Box component="section" aria-label="Offices" sx={(t) => ({ ...glass(t), p: 0, overflow: 'hidden' })}>
          <Box sx={{ overflowX: 'auto' }}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell sx={{ fontWeight: 600, width: 72 }}>No.</TableCell>
                  <TableCell sx={{ fontWeight: 600 }}>Office</TableCell>
                  <TableCell align="right" sx={{ fontWeight: 600 }}>Members</TableCell>
                  <TableCell align="right" sx={{ fontWeight: 600 }}>Users</TableCell>
                  {editable && <TableCell align="right" sx={{ fontWeight: 600 }}><Box component="span" sx={{ position: 'absolute', width: 1, height: 1, overflow: 'hidden', clip: 'rect(0 0 0 0)' }}>Actions</Box></TableCell>}
                </TableRow>
              </TableHead>
              <TableBody>
                <LoadingRow q={q} what="the offices" />
                {noOffices && (
                  <TableRow><TableCell colSpan={editable ? 5 : 4}><Typography sx={{ fontSize: 13 }} color="text.secondary">No offices yet.</Typography></TableCell></TableRow>
                )}
                {data?.offices.map((o) => {
                  const blocked = cannotDelete(o)
                  return (
                    <TableRow key={o.id} hover>
                      <TableCell sx={{ fontVariantNumeric: 'tabular-nums' }}>{o.id}</TableCell>
                      <TableCell>{o.name}</TableCell>
                      <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{o.members.toLocaleString()}</TableCell>
                      <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{o.users.toLocaleString()}</TableCell>
                      {editable && (
                        <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                          <Button size="small" startIcon={<FiEdit2 />} onClick={() => setOpen({ kind: 'rename', office: o })} aria-label={`Rename ${o.name}`}>Rename</Button>
                          <Tooltip title={blocked ?? ''}>
                            <span>
                              <Button size="small" color="error" startIcon={<FiTrash2 />} disabled={!!blocked} onClick={() => setOpen({ kind: 'delete', office: o })} aria-label={`Delete ${o.name}`}>Delete</Button>
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
      {open && data && <OfficeDialog open={open} data={data} onClose={() => setOpen(null)} />}
    </Box>
  )
}

function OfficeDialog({ open, data, onClose }: { open: NonNullable<Open>; data: Offices; onClose: () => void }) {
  const qc = useQueryClient()
  const office = open.kind === 'add' ? null : open.office
  const firstOffice = open.kind === 'add' && !data.offices.some((o) => o.id === 1)
  const [number, setNumber] = useState(String(firstOffice ? 1 : data.nextNumber))
  const [name, setName] = useState(office?.name ?? (firstOffice ? 'MAIN OFFICE' : ''))
  const [reason, setReason] = useState('')
  const id = Number(number)

  const save = useMutation({
    mutationFn: () => {
      if (open.kind === 'add') return addOffice(id, name.trim(), reason.trim())
      if (open.kind === 'rename') return renameOffice(open.office.id, name.trim(), reason.trim())
      return deleteOffice(open.office.id, reason.trim())
    },
    meta: { done: open.kind === 'add' ? `Office ${id} added.` : open.kind === 'rename' ? 'Office renamed.' : `${office?.name} deleted.` },
    onSuccess: (fresh) => {
      qc.setQueryData(KEY, fresh)
      qc.invalidateQueries({ queryKey: ['lookups'] })
      onClose()
    },
  })

  const nameProblem = open.kind === 'delete' ? null
    : !name.trim() ? 'Give the office a name.'
    : name.trim().length > data.maxNameLength ? `At most ${data.maxNameLength} characters.`
    : data.offices.some((o) => o.id !== office?.id && o.name.trim().toLowerCase() === name.trim().toLowerCase()) ? 'Another office already has this name.'
    : open.kind === 'rename' && name.trim() === office?.name ? 'Type the new name.'
    : null
  const numberProblem = open.kind !== 'add' ? null
    : !Number.isInteger(id) || id < 1 || id > 32767 ? 'A number from 1 to 32767.'
    : data.offices.some((o) => o.id === id) ? `Already used; the next free number is ${data.nextNumber}.`
    : null
  const ready = !nameProblem && !numberProblem && reason.trim().length >= MIN_REASON && !save.isPending
  const title = open.kind === 'add' ? 'Add an office' : open.kind === 'rename' ? `Rename office ${office?.id}` : `Delete ${office?.name}`

  return (
    <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          {save.error && <Alert severity="error">{(save.error as Error).message}</Alert>}
          {open.kind === 'delete' ? (
            <Typography sx={{ fontSize: 13 }}>
              Office {office?.id}, {office?.name}, will be removed. No member or user belongs to it. Its number can be given to a new office later.
            </Typography>
          ) : (
            <>
              {open.kind === 'add' && (
                <TextField label="Office number" value={number} onChange={(e) => setNumber(e.target.value.replace(/\D/g, ''))} required
                  error={!!numberProblem} helperText={numberProblem ?? 'Stays with the office for good.'} slotProps={{ htmlInput: { inputMode: 'numeric' } }} />
              )}
              <TextField label="Office name" value={name} onChange={(e) => setName(e.target.value)} required autoFocus
                error={!!nameProblem && name.length > 0} helperText={(name.length > 0 && nameProblem) || 'Such as MAIN OFFICE, or the town of a satellite office.'} />
            </>
          )}
          <TextField label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} required autoFocus={open.kind === 'delete'}
            helperText="Kept in the audit log with the change." />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" color={open.kind === 'delete' ? 'error' : 'primary'} disabled={!ready} onClick={() => save.mutate()}>
          {open.kind === 'add' ? 'Add office' : open.kind === 'rename' ? 'Save name' : 'Delete office'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
