import { Alert, Box, Button, Checkbox, Chip, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, IconButton, MenuItem, Stack, Tab, Table, TableBody, TableCell, TableHead, TableRow, Tabs, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { FiPlus, FiTrash2 } from 'react-icons/fi'
import {
  approveOrder, cancelOrder, createOrder, createReceipt, createRequest, createSupplier, decideRequest, getOrder, getRequest, listOrders, listReceipts, listRequests, listSuppliers, reverseReceipt, updateSupplier,
  listInvProducts, listInvWarehouses, type GrSummary, type InvProduct, type PoSummary, type PrSummary, type Supplier, type SupplierIn,
} from './api'
import { appBackground, glass } from './glass'
import { useMe } from './session'
import { money, parseAmount } from './ui/format'
import { ReasonDialog } from './ui/dialogs'
import { labelOf } from './ui/labels'
import { LoadingRow } from './ui/Loading'

const color = (s: string) => (s === 'APPROVED' || s === 'RECEIVED' ? 'success' : s === 'SUBMITTED' || s === 'DRAFT' ? 'warning' : s === 'ORDERED' || s === 'PARTIAL' ? 'info' : 'default') as 'success' | 'warning' | 'info' | 'default'

interface Row { description: string; unit: string; quantity: string; price: string; productId: string }
const blank = (): Row => ({ description: '', unit: 'pc', quantity: '1', price: '', productId: '' })

/** Lines of a request or an order: description, unit, quantity and the price per unit. With {@code products}, a line can name a stock product (it takes the product's unit and is put into stock when received). */
function LinesEditor({ rows, onChange, priceLabel, products }: { rows: Row[]; onChange: (r: Row[]) => void; priceLabel: string; products?: InvProduct[] }) {
  const set = (i: number, k: keyof Row, v: string) => onChange(rows.map((r, j) => (j === i ? { ...r, [k]: v } : r)))
  const pick = (i: number, v: string) => {
    const p = products?.find((x) => String(x.id) === v)
    onChange(rows.map((r, j) => (j === i ? { ...r, productId: v, description: p && !r.description ? p.name : r.description, unit: p ? p.unit : r.unit } : r)))
  }
  const total = rows.reduce((s, r) => s + (Number(r.quantity) || 0) * (Number(r.price) || 0), 0)
  return (
    <Box>
      {rows.map((r, i) => (
        <Stack key={i} direction="row" spacing={1} sx={{ mb: 1 }}>
          {products && (
            <TextField select size="small" label="Stock product" value={r.productId} onChange={(e) => pick(i, e.target.value)} sx={{ width: 200 }}>
              <MenuItem value="">Not stocked</MenuItem>
              {products.map((p) => <MenuItem key={p.id} value={String(p.id)}>{p.sku} · {p.name}</MenuItem>)}
            </TextField>
          )}
          <TextField size="small" label="Description" value={r.description} onChange={(e) => set(i, 'description', e.target.value)} sx={{ flex: 1 }} />
          <TextField size="small" label="Unit" value={r.unit} disabled={!!r.productId} onChange={(e) => set(i, 'unit', e.target.value)} sx={{ width: 80 }} />
          <TextField size="small" label="Qty" value={r.quantity} onChange={(e) => set(i, 'quantity', e.target.value)} sx={{ width: 90 }} />
          <TextField size="small" label={priceLabel} value={r.price} onChange={(e) => set(i, 'price', e.target.value)} sx={{ width: 130 }} />
          <IconButton aria-label="Remove line" disabled={rows.length === 1} onClick={() => onChange(rows.filter((_, j) => j !== i))}><FiTrash2 /></IconButton>
        </Stack>
      ))}
      <Stack direction="row" sx={{ alignItems: 'center' }}>
        <Button size="small" startIcon={<FiPlus />} onClick={() => onChange([...rows, blank()])}>Add line</Button>
        <Typography sx={{ flex: 1, textAlign: 'right', fontSize: 13 }}>Total <b>{money(total)}</b></Typography>
      </Stack>
    </Box>
  )
}


function NewRequestDialog({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient()
  const [purpose, setPurpose] = useState('')
  const [neededBy, setNeededBy] = useState('')
  const [rows, setRows] = useState<Row[]>([blank()])
  const [error, setError] = useState<string | null>(null)
  const save = useMutation({
    mutationFn: () => createRequest({ purpose, neededBy: neededBy || null, lines: rows.map((r) => ({ description: r.description, unit: r.unit, quantity: parseAmount(r.quantity), estCost: parseAmount(r.price || '0') })) }),
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
  const products = useQuery({ queryKey: ['inv-products', 'active'], queryFn: () => listInvProducts(undefined, true), retry: false })
  const rows = edited ?? (detail.data?.lines.map((l) => ({ description: l.description, unit: l.unit, quantity: String(l.quantity), price: String(l.estCost), productId: '' })) ?? [blank()])
  const save = useMutation({
    mutationFn: () => createOrder({ supplierId: Number(supplierId), requestId: request?.id ?? null, expectedDate: expected || null, remarks, lines: rows.map((r) => ({ description: r.description, unit: r.unit, quantity: parseAmount(r.quantity), unitCost: parseAmount(r.price || '0'), productId: r.productId ? Number(r.productId) : null })) }),
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
          <LinesEditor rows={rows} onChange={setEdited} priceLabel="Cost each" products={products.data} />
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={save.isPending || !supplierId} onClick={() => save.mutate()}>Save order</Button></DialogActions>
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
  const approve = useMutation({ meta: { done: 'Purchase request approved.' },
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
          <LoadingRow q={list} what="the purchase requests" />
          {(list.data ?? []).map((r) => (
            <TableRow key={r.id} hover>
              <TableCell>{r.requestNo}</TableCell><TableCell>{r.requestDate}</TableCell>
              <TableCell>{r.purpose}{r.decisionNote && <Typography component="div" sx={{ fontSize: 12 }} color="text.secondary">{r.decisionNote}</Typography>}</TableCell>
              <TableCell>{r.requestedBy}</TableCell><TableCell align="right">{money(r.total)}</TableCell>
              <TableCell><Chip size="small" color={color(r.status)} variant="outlined" label={labelOf(r.status)} />{r.waiting && <Typography component="div" sx={{ fontSize: 12 }} color="text.secondary">waiting for {r.waiting}</Typography>}</TableCell>
              <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                {approver && r.status === 'SUBMITTED' && r.requestedBy !== user && (<>
                  {!r.waiting && <Button size="small" disabled={approve.isPending} onClick={() => approve.mutate(r)}>Approve</Button>}
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

function Orders({ buyer, approver, receiver, user }: { buyer: boolean; approver: boolean; receiver: boolean; user: string }) {
  const qc = useQueryClient()
  const list = useQuery({ queryKey: ['po-list'], queryFn: () => listOrders() })
  const [creating, setCreating] = useState(false)
  const [cancelling, setCancelling] = useState<PoSummary | null>(null)
  const [receiving, setReceiving] = useState<PoSummary | null>(null)
  const [error, setError] = useState<string | null>(null)
  const approve = useMutation({ meta: { done: 'Purchase order approved.' },
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
          <LoadingRow q={list} what="the purchase orders" />
          {(list.data ?? []).map((o) => (
            <TableRow key={o.id} hover>
              <TableCell>{o.orderNo}</TableCell><TableCell>{o.orderDate}</TableCell><TableCell>{o.supplier}</TableCell><TableCell>{o.requestNo ?? ''}</TableCell>
              <TableCell align="right">{money(o.total)}</TableCell>
              <TableCell><Chip size="small" color={color(o.status)} variant="outlined" label={labelOf(o.status)} />{o.waiting && <Typography component="div" sx={{ fontSize: 12 }} color="text.secondary">waiting for {o.waiting}</Typography>}</TableCell>
              <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                {approver && o.status === 'DRAFT' && o.createdBy !== user && !o.waiting && <Button size="small" disabled={approve.isPending} onClick={() => approve.mutate(o)}>Approve</Button>}
                {receiver && (o.status === 'APPROVED' || o.status === 'PARTIAL') && o.createdBy !== user && <Button size="small" onClick={() => setReceiving(o)}>Receive</Button>}
                {buyer && (o.status === 'DRAFT' || o.status === 'APPROVED') && <Button size="small" color="warning" onClick={() => setCancelling(o)}>Cancel</Button>}
              </TableCell>
            </TableRow>
          ))}
          {list.data?.length === 0 && <TableRow><TableCell colSpan={7}><Typography color="text.secondary">No purchase orders yet.</Typography></TableCell></TableRow>}
        </TableBody>
      </Table>
      {creating && <NewOrderDialog onClose={() => setCreating(false)} />}
      {receiving && <ReceiveDialog order={receiving} onClose={() => setReceiving(null)} />}
      {cancelling && <ReasonDialog title={`Cancel ${cancelling.orderNo}`} label="Reason" onRun={(n) => cancelOrder(cancelling.id, n).then(() => { void qc.invalidateQueries({ queryKey: ['po-list'] }); void qc.invalidateQueries({ queryKey: ['pr-list'] }) })} onClose={() => setCancelling(null)} />}
    </Box>
  )
}

/** Goods received on an order: the quantity that came in on each line, up to what is still expected. */
function ReceiveDialog({ order, onClose }: { order: PoSummary; onClose: () => void }) {
  const qc = useQueryClient()
  const detail = useQuery({ queryKey: ['po-detail', order.id], queryFn: () => getOrder(order.id) })
  const warehouses = useQuery({ queryKey: ['inv-warehouses', 'active'], queryFn: () => listInvWarehouses(true), retry: false })
  const [qty, setQty] = useState<Record<number, string>>({})
  const [batch, setBatch] = useState<Record<number, string>>({})
  const [expiry, setExpiry] = useState<Record<number, string>>({})
  const [warehouseId, setWarehouseId] = useState('')
  const [note, setNote] = useState('')
  const [remarks, setRemarks] = useState('')
  const [error, setError] = useState<string | null>(null)
  const lines = detail.data?.lines ?? []
  const left = (i: number) => lines[i].quantity - lines[i].received
  const value = (i: number) => qty[i] ?? String(left(i))
  const stocking = lines.some((l, i) => l.productId !== null && parseAmount(value(i)) > 0)
  const save = useMutation({
    mutationFn: () => createReceipt({
      orderId: order.id, warehouseId: warehouseId ? Number(warehouseId) : null, deliveryNote: note, remarks,
      lines: lines.map((_, i) => ({ lineNo: i + 1, quantity: parseAmount(value(i)), batchNo: batch[i] || undefined, expiryDate: expiry[i] || null })).filter((l) => l.quantity > 0),
    }),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['po-list'] }); void qc.invalidateQueries({ queryKey: ['gr-list'] }); onClose() },
    onError: (e) => setError((e as Error).message),
  })
  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>Receive goods on {order.orderNo} from {order.supplier}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <Stack direction="row" spacing={1.5}>
            <TextField size="small" label="Supplier delivery receipt no." value={note} onChange={(e) => setNote(e.target.value)} sx={{ width: 240 }} />
            <TextField size="small" label="Remarks" value={remarks} onChange={(e) => setRemarks(e.target.value)} sx={{ flex: 1 }} />
          </Stack>
          {lines.some((l) => l.productId !== null) && (
            <TextField select size="small" label="Put the stock into warehouse" value={warehouseId} onChange={(e) => setWarehouseId(e.target.value)} sx={{ width: 320 }}
              helperText={stocking ? 'Lines that name a stock product are added to this warehouse at the order price.' : undefined}>
              {(warehouses.data ?? []).map((w) => <MenuItem key={w.id} value={String(w.id)}>{w.name}</MenuItem>)}
            </TextField>
          )}
          <Table size="small">
            <TableHead><TableRow><TableCell>Item</TableCell><TableCell>Unit</TableCell><TableCell align="right">Ordered</TableCell><TableCell align="right">Already in</TableCell><TableCell align="right" sx={{ width: 130 }}>Received now</TableCell><TableCell sx={{ width: 230 }}>Batch / expiry</TableCell></TableRow></TableHead>
            <TableBody>
              {lines.map((l, i) => (
                <TableRow key={i}>
                  <TableCell>{l.description}{l.productId !== null && <Chip size="small" variant="outlined" label="stocked" sx={{ ml: 0.75 }} />}</TableCell><TableCell>{l.unit}</TableCell><TableCell align="right">{l.quantity}</TableCell><TableCell align="right">{l.received}</TableCell>
                  <TableCell align="right"><TextField size="small" value={value(i)} disabled={left(i) <= 0} onChange={(e) => setQty({ ...qty, [i]: e.target.value })} /></TableCell>
                  <TableCell>
                    {l.trackBatch && (
                      <Stack direction="row" spacing={0.5}>
                        <TextField size="small" label="Batch" value={batch[i] ?? ''} onChange={(e) => setBatch({ ...batch, [i]: e.target.value })} sx={{ width: 100 }} />
                        {l.trackExpiry && <TextField size="small" type="date" label="Expiry" value={expiry[i] ?? ''} onChange={(e) => setExpiry({ ...expiry, [i]: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} sx={{ width: 130 }} />}
                      </Stack>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={save.isPending || detail.isLoading} onClick={() => save.mutate()}>Record receipt</Button></DialogActions>
    </Dialog>
  )
}

function Receipts({ approver, user }: { approver: boolean; user: string }) {
  const qc = useQueryClient()
  const list = useQuery({ queryKey: ['gr-list'], queryFn: () => listReceipts() })
  const [reversing, setReversing] = useState<GrSummary | null>(null)
  return (
    <Box>
      <Table size="small">
        <TableHead><TableRow><TableCell>No.</TableCell><TableCell>Date</TableCell><TableCell>Order</TableCell><TableCell>Supplier</TableCell><TableCell>Delivery receipt</TableCell><TableCell>By</TableCell><TableCell>Status</TableCell><TableCell /></TableRow></TableHead>
        <TableBody>
          <LoadingRow q={list} what="the goods received" />
          {(list.data ?? []).map((g) => (
            <TableRow key={g.id} hover>
              <TableCell>{g.receiptNo}</TableCell><TableCell>{g.receiptDate}</TableCell><TableCell>{g.orderNo}</TableCell><TableCell>{g.supplier}</TableCell><TableCell>{g.deliveryNote ?? ''}</TableCell><TableCell>{g.receivedBy}</TableCell>
              <TableCell><Chip size="small" variant="outlined" color={g.status === 'RECEIVED' ? 'success' : 'default'} label={labelOf(g.status)} />{g.reverseNote && <Typography component="div" sx={{ fontSize: 12 }} color="text.secondary">{g.reverseNote}</Typography>}</TableCell>
              <TableCell align="right">{approver && g.status === 'RECEIVED' && g.receivedBy !== user && <Button size="small" color="warning" onClick={() => setReversing(g)}>Reverse</Button>}</TableCell>
            </TableRow>
          ))}
          {list.data?.length === 0 && <TableRow><TableCell colSpan={8}><Typography color="text.secondary">No goods received yet.</Typography></TableCell></TableRow>}
        </TableBody>
      </Table>
      {reversing && <ReasonDialog title={`Reverse ${reversing.receiptNo}`} label="Reason" onRun={(n) => reverseReceipt(reversing.id, n).then(() => { void qc.invalidateQueries({ queryKey: ['gr-list'] }); void qc.invalidateQueries({ queryKey: ['po-list'] }) })} onClose={() => setReversing(null)} />}
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
          <LoadingRow q={list} what="the suppliers" />
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

/** Purchasing: requests, orders and suppliers. Purchasers write them, approvers (never the same person) decide, receivers record the goods that arrive. Invoices and payment come next. */
const TABS = ['Requests', 'Orders', 'Receipts', 'Suppliers']

export default function ProcurementPage({ initialTab }: { initialTab?: string }) {
  const me = useMe()
  const role = (r: string) => me.roles.some((x) => x.replace('ROLE_', '') === r)
  const buyer = me.admin || role('PURCHASER')
  const approver = me.admin || role('PURCHASE_APPROVER')
  const receiver = me.admin || role('RECEIVER')
  const [tab, setTab] = useState(() => Math.max(0, TABS.indexOf(initialTab ?? '')))
  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: 2, background: appBackground(t) })}>
      <Box sx={(t) => ({ maxWidth: 1100, mx: 'auto', p: 2, ...glass(t) })}>
        <Typography component="h1" sx={{ fontSize: 20, fontWeight: 700, letterSpacing: '-0.02em', mb: 1 }}>Purchasing</Typography>
        <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}>{TABS.map((t) => <Tab key={t} label={t} />)}</Tabs>
        {tab === 0 && <Requests buyer={buyer} approver={approver} user={me.username} />}
        {tab === 1 && <Orders buyer={buyer} approver={approver} receiver={receiver} user={me.username} />}
        {tab === 2 && <Receipts approver={approver} user={me.username} />}
        {tab === 3 && <Suppliers buyer={buyer} />}
      </Box>
    </Box>
  )
}
