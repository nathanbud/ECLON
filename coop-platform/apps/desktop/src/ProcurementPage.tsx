import { Alert, Box, Button, Checkbox, Chip, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, IconButton, MenuItem, Stack, Tab, Table, TableBody, TableCell, TableHead, TableRow, Tabs, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { FiPlus, FiTrash2 } from 'react-icons/fi'
import {
  approveOrder, cancelOrder, createOrder, createRequest, createSupplier, decideRequest, getRequest, listOrders, listRequests, listSuppliers, updateSupplier,
  type PoSummary, type PrSummary, type Supplier, type SupplierIn,
} from './api'
import { appBackground, glass } from './glass'
import { useMe } from './session'

const money = (n: number) => n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const color = (s: string) => (s === 'APPROVED' ? 'success' : s === 'SUBMITTED' || s === 'DRAFT' ? 'warning' : s === 'ORDERED' ? 'info' : 'default') as 'success' | 'warning' | 'info' | 'default'

interface Row { description: string; unit: string; quantity: string; price: string }
const blank = (): Row => ({ description: '', unit: 'pc', quantity: '1', price: '' })

/** Lines of a request or an order: description, unit, quantity and the price per unit. */
function LinesEditor({ rows, onChange, priceLabel }: { rows: Row[]; onChange: (r: Row[]) => void; priceLabel: string }) {
  const set = (i: number, k: keyof Row, v: string) => onChange(rows.map((r, j) => (j === i ? { ...r, [k]: v } : r)))
  const total = rows.reduce((s, r) => s + (Number(r.quantity) || 0) * (Number(r.price) || 0), 0)
  return (
    <Box>
      {rows.map((r, i) => (
        <Stack key={i} direction="row" spacing={1} sx={{ mb: 1 }}>
          <TextField size="small" label="Description" value={r.description} onChange={(e) => set(i, 'description', e.target.value)} sx={{ flex: 1 }} />
          <TextField size="small" label="Unit" value={r.unit} onChange={(e) => set(i, 'unit', e.target.value)} sx={{ width: 80 }} />
          <TextField size="small" label="Qty" value={r.quantity} onChange={(e) => set(i, 'quantity', e.target.value)} sx={{ width: 90 }} />
          <TextField size="small" label={priceLabel} value={r.price} onChange={(e) => set(i, 'price', e.target.value)} sx={{ width: 130 }} />
          <IconButton aria-label="Remove line" disabled={rows.length === 1} onClick={() => onChange(rows.filter((_, j) => j !== i))}><FiTrash2 /></IconButton>
        </Stack>
      ))}
      <Stack direction="row" sx={{ alignItems: 'center' }}>
        <Button size="small" startIcon={<FiPlus />} onClick={() => onChange([...rows, blank()])}>Add line</Button>
        <Typography sx={{ flex: 1, textAlign: 'right', fontSize: 14 }}>Total <b>{money(total)}</b></Typography>
      </Stack>
    </Box>
  )
}

const num = (s: string) => Number(s.replace(/,/g, ''))

function NewRequestDialog({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient()
  const [purpose, setPurpose] = useState('')
  const [neededBy, setNeededBy] = useState('')
  const [rows, setRows] = useState<Row[]>([blank()])
  const [error, setError] = useState<string | null>(null)
  const save = useMutation({
    mutationFn: () => createRequest({ purpose, neededBy: neededBy || null, lines: rows.map((r) => ({ description: r.description, unit: r.unit, quantity: num(r.quantity), estCost: num(r.price || '0') })) }),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['pr-list'] }); onClose() },
    onError: (e) => setError((e as Error).message),
  })
  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>New purchase request</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <TextField size="small" label="What is it for?" value={purpose} onChange={(e) => setPurpose(e.target.value)} />
          <TextField size="small" type="date" label="Needed by (optional)" value={neededBy} onChange={(e) => setNeededBy(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} sx={{ width: 200 }} />
          <LinesEditor rows={rows} onChange={setRows} priceLabel="Est. cost each" />
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={save.isPending} onClick={() => save.mutate()}>Submit request</Button></DialogActions>
    </Dialog>
  )
}

/** A new order, optionally answering an approved request (its lines are the starting point, the buyer sets the real cost). */
function NewOrderDialog({ request, onClose }: { request?: PrSummary; onClose: () => void }) {
  const qc = useQueryClient()
  const suppliers = useQuery({ queryKey: ['suppliers', 'active'], queryFn: () => listSuppliers(undefined, true) })
  const detail = useQuery({ queryKey: ['pr-detail', request?.id], queryFn: () => getRequest(request!.id), enabled: !!request })
  const [supplierId, setSupplierId] = useState('')
  const [expected, setExpected] = useState('')
  const [remarks, setRemarks] = useState('')
  const [edited, setEdited] = useState<Row[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const rows = edited ?? (detail.data?.lines.map((l) => ({ description: l.description, unit: l.unit, quantity: String(l.quantity), price: String(l.estCost) })) ?? [blank()])
  const save = useMutation({
    mutationFn: () => createOrder({ supplierId: Number(supplierId), requestId: request?.id ?? null, expectedDate: expected || null, remarks, lines: rows.map((r) => ({ description: r.description, unit: r.unit, quantity: num(r.quantity), unitCost: num(r.price || '0') })) }),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['po-list'] }); void qc.invalidateQueries({ queryKey: ['pr-list'] }); onClose() },
    onError: (e) => setError((e as Error).message),
  })
  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>New purchase order{request ? ` for ${request.requestNo}` : ''}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <TextField select size="small" label="Supplier" value={supplierId} onChange={(e) => setSupplierId(e.target.value)}>
            {(suppliers.data ?? []).map((s) => <MenuItem key={s.id} value={String(s.id)}>{s.name} ({s.code})</MenuItem>)}
          </TextField>
          <Stack direction="row" spacing={1.5}>
            <TextField size="small" type="date" label="Expected on (optional)" value={expected} onChange={(e) => setExpected(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} sx={{ width: 200 }} />
            <TextField size="small" label="Remarks" value={remarks} onChange={(e) => setRemarks(e.target.value)} sx={{ flex: 1 }} />
          </Stack>
          <LinesEditor rows={rows} onChange={setEdited} priceLabel="Cost each" />
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={save.isPending || !supplierId} onClick={() => save.mutate()}>Save order</Button></DialogActions>
    </Dialog>
  )
}

/** Asks for a reason, then runs the action. */
function ReasonDialog({ title, label, onRun, onClose }: { title: string; label: string; onRun: (note: string) => Promise<unknown>; onClose: () => void }) {
  const [note, setNote] = useState('')
  const [error, setError] = useState<string | null>(null)
  return (
    <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <TextField size="small" autoFocus label={label} value={note} onChange={(e) => setNote(e.target.value)} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Back</Button>
        <Button variant="contained" disabled={!note.trim()} onClick={() => onRun(note.trim()).then(onClose, (e: Error) => setError(e.message))}>Confirm</Button>
      </DialogActions>
    </Dialog>
  )
}

function Requests({ buyer, approver, user }: { buyer: boolean; approver: boolean; user: string }) {
  const qc = useQueryClient()
  const list = useQuery({ queryKey: ['pr-list'], queryFn: () => listRequests() })
  const [creating, setCreating] = useState(false)
  const [ordering, setOrdering] = useState<PrSummary | null>(null)
  const [reason, setReason] = useState<{ r: PrSummary; kind: 'reject' | 'cancel' } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const approve = useMutation({
    mutationFn: (r: PrSummary) => decideRequest(r.id, 'approve'),
    onSuccess: () => { setError(null); void qc.invalidateQueries({ queryKey: ['pr-list'] }) },
    onError: (e) => setError((e as Error).message),
  })
  return (
    <Box>
      {error && <Alert severity="error" sx={{ mb: 1 }} onClose={() => setError(null)}>{error}</Alert>}
      {buyer && <Button variant="contained" startIcon={<FiPlus />} sx={{ mb: 1.5 }} onClick={() => setCreating(true)}>New request</Button>}
      <Table size="small">
        <TableHead><TableRow><TableCell>No.</TableCell><TableCell>Date</TableCell><TableCell>Purpose</TableCell><TableCell>By</TableCell><TableCell align="right">Estimate</TableCell><TableCell>Status</TableCell><TableCell /></TableRow></TableHead>
        <TableBody>
          {(list.data ?? []).map((r) => (
            <TableRow key={r.id} hover>
              <TableCell>{r.requestNo}</TableCell><TableCell>{r.requestDate}</TableCell>
              <TableCell>{r.purpose}{r.decisionNote && <Typography component="div" sx={{ fontSize: 12 }} color="text.secondary">{r.decisionNote}</Typography>}</TableCell>
              <TableCell>{r.requestedBy}</TableCell><TableCell align="right">{money(r.total)}</TableCell>
              <TableCell><Chip size="small" color={color(r.status)} variant="outlined" label={r.status.toLowerCase()} /></TableCell>
              <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                {approver && r.status === 'SUBMITTED' && r.requestedBy !== user && (<>
                  <Button size="small" disabled={approve.isPending} onClick={() => approve.mutate(r)}>Approve</Button>
                  <Button size="small" color="error" onClick={() => setReason({ r, kind: 'reject' })}>Reject</Button>
                </>)}
                {buyer && r.status === 'APPROVED' && <Button size="small" onClick={() => setOrdering(r)}>Order</Button>}
                {r.requestedBy === user && (r.status === 'SUBMITTED' || r.status === 'APPROVED') && <Button size="small" color="warning" onClick={() => setReason({ r, kind: 'cancel' })}>Withdraw</Button>}
              </TableCell>
            </TableRow>
          ))}
          {list.data?.length === 0 && <TableRow><TableCell colSpan={7}><Typography color="text.secondary">No purchase requests yet.</Typography></TableCell></TableRow>}
        </TableBody>
      </Table>
      {creating && <NewRequestDialog onClose={() => setCreating(false)} />}
      {ordering && <NewOrderDialog request={ordering} onClose={() => setOrdering(null)} />}
      {reason && (
        <ReasonDialog title={reason.kind === 'reject' ? `Reject ${reason.r.requestNo}` : `Withdraw ${reason.r.requestNo}`} label="Reason"
          onRun={(n) => decideRequest(reason.r.id, reason.kind, n).then(() => qc.invalidateQueries({ queryKey: ['pr-list'] }))} onClose={() => setReason(null)} />
      )}
    </Box>
  )
}

function Orders({ buyer, approver, user }: { buyer: boolean; approver: boolean; user: string }) {
  const qc = useQueryClient()
  const list = useQuery({ queryKey: ['po-list'], queryFn: () => listOrders() })
  const [creating, setCreating] = useState(false)
  const [cancelling, setCancelling] = useState<PoSummary | null>(null)
  const [error, setError] = useState<string | null>(null)
  const approve = useMutation({
    mutationFn: (o: PoSummary) => approveOrder(o.id),
    onSuccess: () => { setError(null); void qc.invalidateQueries({ queryKey: ['po-list'] }) },
    onError: (e) => setError((e as Error).message),
  })
  return (
    <Box>
      {error && <Alert severity="error" sx={{ mb: 1 }} onClose={() => setError(null)}>{error}</Alert>}
      {buyer && <Button variant="contained" startIcon={<FiPlus />} sx={{ mb: 1.5 }} onClick={() => setCreating(true)}>New order</Button>}
      <Table size="small">
        <TableHead><TableRow><TableCell>No.</TableCell><TableCell>Date</TableCell><TableCell>Supplier</TableCell><TableCell>Request</TableCell><TableCell align="right">Total</TableCell><TableCell>Status</TableCell><TableCell /></TableRow></TableHead>
        <TableBody>
          {(list.data ?? []).map((o) => (
            <TableRow key={o.id} hover>
              <TableCell>{o.orderNo}</TableCell><TableCell>{o.orderDate}</TableCell><TableCell>{o.supplier}</TableCell><TableCell>{o.requestNo ?? ''}</TableCell>
              <TableCell align="right">{money(o.total)}</TableCell>
              <TableCell><Chip size="small" color={color(o.status)} variant="outlined" label={o.status.toLowerCase()} /></TableCell>
              <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                {approver && o.status === 'DRAFT' && o.createdBy !== user && <Button size="small" disabled={approve.isPending} onClick={() => approve.mutate(o)}>Approve</Button>}
                {buyer && (o.status === 'DRAFT' || o.status === 'APPROVED') && <Button size="small" color="warning" onClick={() => setCancelling(o)}>Cancel</Button>}
              </TableCell>
            </TableRow>
          ))}
          {list.data?.length === 0 && <TableRow><TableCell colSpan={7}><Typography color="text.secondary">No purchase orders yet.</Typography></TableCell></TableRow>}
        </TableBody>
      </Table>
      {creating && <NewOrderDialog onClose={() => setCreating(false)} />}
      {cancelling && <ReasonDialog title={`Cancel ${cancelling.orderNo}`} label="Reason" onRun={(n) => cancelOrder(cancelling.id, n).then(() => { void qc.invalidateQueries({ queryKey: ['po-list'] }); void qc.invalidateQueries({ queryKey: ['pr-list'] }) })} onClose={() => setCancelling(null)} />}
    </Box>
  )
}

function SupplierDialog({ supplier, onClose }: { supplier?: Supplier; onClose: () => void }) {
  const qc = useQueryClient()
  const [f, setF] = useState<SupplierIn & { termsText: string }>({
    code: supplier?.code ?? '', name: supplier?.name ?? '', tin: supplier?.tin ?? '', address: supplier?.address ?? '', phone: supplier?.phone ?? '', email: supplier?.email ?? '',
    contact: supplier?.contact ?? '', taxable: supplier?.taxable ?? true, active: supplier?.active ?? true, termsText: String(supplier?.termsDays ?? 0),
  })
  const [error, setError] = useState<string | null>(null)
  const set = (k: keyof SupplierIn) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value })
  const save = useMutation({
    mutationFn: () => { const { termsText, ...rest } = f; const body = { ...rest, termsDays: Number(termsText) || 0 }; return supplier ? updateSupplier(supplier.id, body) : createSupplier(body) },
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['suppliers'] }); onClose() },
    onError: (e) => setError((e as Error).message),
  })
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{supplier ? `Edit ${supplier.name}` : 'New supplier'}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <Stack direction="row" spacing={1.5}>
            <TextField size="small" label="Code" value={f.code} disabled={!!supplier} onChange={set('code')} sx={{ width: 140 }} />
            <TextField size="small" label="Name" value={f.name} onChange={set('name')} sx={{ flex: 1 }} />
          </Stack>
          <Stack direction="row" spacing={1.5}>
            <TextField size="small" label="TIN" value={f.tin ?? ''} onChange={set('tin')} sx={{ width: 180 }} />
            <TextField size="small" label="Contact person" value={f.contact ?? ''} onChange={set('contact')} sx={{ flex: 1 }} />
          </Stack>
          <TextField size="small" label="Address" value={f.address ?? ''} onChange={set('address')} />
          <Stack direction="row" spacing={1.5}>
            <TextField size="small" label="Phone" value={f.phone ?? ''} onChange={set('phone')} sx={{ width: 180 }} />
            <TextField size="small" label="Email" value={f.email ?? ''} onChange={set('email')} sx={{ flex: 1 }} />
          </Stack>
          <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
            <TextField size="small" label="Payment terms (days)" value={f.termsText} onChange={(e) => setF({ ...f, termsText: e.target.value })} sx={{ width: 180 }} />
            <FormControlLabel control={<Checkbox checked={!!f.taxable} onChange={(_, v) => setF({ ...f, taxable: v })} />} label="Taxable" />
            {supplier && <FormControlLabel control={<Checkbox checked={!!f.active} onChange={(_, v) => setF({ ...f, active: v })} />} label="Active" />}
          </Stack>
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={save.isPending} onClick={() => save.mutate()}>Save</Button></DialogActions>
    </Dialog>
  )
}

function Suppliers({ buyer }: { buyer: boolean }) {
  const [q, setQ] = useState('')
  const list = useQuery({ queryKey: ['suppliers', q], queryFn: () => listSuppliers(q || undefined) })
  const [editing, setEditing] = useState<Supplier | 'new' | null>(null)
  return (
    <Box>
      <Stack direction="row" spacing={1.5} sx={{ mb: 1.5 }}>
        <TextField size="small" label="Search" value={q} onChange={(e) => setQ(e.target.value)} sx={{ width: 260 }} />
        {buyer && <Button variant="contained" startIcon={<FiPlus />} onClick={() => setEditing('new')}>New supplier</Button>}
      </Stack>
      <Table size="small">
        <TableHead><TableRow><TableCell>Code</TableCell><TableCell>Name</TableCell><TableCell>Contact</TableCell><TableCell>Phone</TableCell><TableCell align="right">Terms</TableCell><TableCell>Status</TableCell><TableCell /></TableRow></TableHead>
        <TableBody>
          {(list.data ?? []).map((s) => (
            <TableRow key={s.id} hover>
              <TableCell>{s.code}</TableCell><TableCell>{s.name}</TableCell><TableCell>{s.contact ?? ''}</TableCell><TableCell>{s.phone ?? ''}</TableCell>
              <TableCell align="right">{s.termsDays} days</TableCell>
              <TableCell><Chip size="small" variant="outlined" color={s.active ? 'success' : 'default'} label={s.active ? 'active' : 'off'} /></TableCell>
              <TableCell align="right">{buyer && <Button size="small" onClick={() => setEditing(s)}>Edit</Button>}</TableCell>
            </TableRow>
          ))}
          {list.data?.length === 0 && <TableRow><TableCell colSpan={7}><Typography color="text.secondary">No suppliers yet.</Typography></TableCell></TableRow>}
        </TableBody>
      </Table>
      {editing && <SupplierDialog supplier={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />}
    </Box>
  )
}

/** Purchasing: requests, orders and suppliers. Purchasers write them, approvers (never the same person) decide. Receiving, invoices and payment come next. */
export default function ProcurementPage() {
  const me = useMe()
  const role = (r: string) => me.roles.some((x) => x.replace('ROLE_', '') === r)
  const buyer = me.admin || role('PURCHASER')
  const approver = me.admin || role('PURCHASE_APPROVER')
  const [tab, setTab] = useState(0)
  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: 2, background: appBackground(t) })}>
      <Box sx={(t) => ({ maxWidth: 1100, mx: 'auto', p: 2, ...glass(t) })}>
        <Typography component="h1" sx={{ fontSize: 20, fontWeight: 700, letterSpacing: '-0.02em', mb: 1 }}>Purchasing</Typography>
        <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}>{['Requests', 'Orders', 'Suppliers'].map((t) => <Tab key={t} label={t} />)}</Tabs>
        {tab === 0 && <Requests buyer={buyer} approver={approver} user={me.username} />}
        {tab === 1 && <Orders buyer={buyer} approver={approver} user={me.username} />}
        {tab === 2 && <Suppliers buyer={buyer} />}
      </Box>
    </Box>
  )
}
