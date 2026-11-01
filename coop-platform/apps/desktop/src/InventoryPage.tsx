import { Alert, Box, Button, Checkbox, Chip, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, IconButton, MenuItem, Stack, Tab, Table, TableBody, TableCell, TableHead, TableRow, Tabs, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { FiPlus, FiTrash2 } from 'react-icons/fi'
import {
  addInvCategory, addInvProduct, addInvWarehouse, createInvAdjustment, createInvCount, decideInvAdjustment, decideInvCount, getInvLedger, getInvStock, listInvAdjustments, listInvCategories, listInvCounts,
  listInvProducts, listInvUnits, listInvWarehouses, moveInvStock, transferInvStock, updateInvProduct, updateInvWarehouse,
  type InvAdjSummary, type InvCountSummary, type InvMoveIn, type InvProduct,
} from './api'
import { appBackground, glass } from './glass'
import { ReasonDialog } from './ui/dialogs'
import { useMe } from './session'
import { parseAmount } from './ui/format'

const qty = (n: number) => n.toLocaleString(undefined, { maximumFractionDigits: 3 })
const money = (n: number) => n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 4 })
const statusColor = (s: string) => (s === 'APPROVED' ? 'success' : s === 'SUBMITTED' ? 'warning' : 'default') as 'success' | 'warning' | 'default'

function useLists() {
  const products = useQuery({ queryKey: ['inv-products', 'active'], queryFn: () => listInvProducts(undefined, true) })
  const warehouses = useQuery({ queryKey: ['inv-warehouses', 'active'], queryFn: () => listInvWarehouses(true) })
  return { products: products.data ?? [], warehouses: warehouses.data ?? [] }
}

function ProductPicker({ value, onChange, products }: { value: string; onChange: (v: string) => void; products: InvProduct[] }) {
  return (
    <TextField select size="small" label="Product" value={value} onChange={(e) => onChange(e.target.value)} sx={{ minWidth: 260 }}>
      {products.map((p) => <MenuItem key={p.id} value={String(p.id)}>{p.sku} · {p.name}</MenuItem>)}
    </TextField>
  )
}

// ---- products -------------------------------------------------------------------------------------------------------

function ProductDialog({ product, onClose }: { product?: InvProduct; onClose: () => void }) {
  const qc = useQueryClient()
  const units = useQuery({ queryKey: ['inv-units'], queryFn: listInvUnits })
  const cats = useQuery({ queryKey: ['inv-categories'], queryFn: listInvCategories })
  const [f, setF] = useState({ sku: product?.sku ?? '', name: product?.name ?? '', categoryId: product?.categoryId ? String(product.categoryId) : '', unit: product?.unit ?? 'pc', reorder: String(product?.reorderLevel ?? 0), trackBatch: product?.trackBatch ?? false, trackExpiry: product?.trackExpiry ?? false, active: product?.active ?? true })
  const [error, setError] = useState<string | null>(null)
  const save = useMutation({
    mutationFn: () => {
      const body = { name: f.name, categoryId: f.categoryId ? Number(f.categoryId) : null, unit: f.unit, reorderLevel: parseAmount(f.reorder || '0'), trackBatch: f.trackBatch, trackExpiry: f.trackExpiry, active: f.active }
      return product ? updateInvProduct(product.id, body) : addInvProduct({ ...body, sku: f.sku })
    },
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['inv-products'] }); onClose() },
    onError: (e) => setError((e as Error).message),
  })
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{product ? `Edit ${product.name}` : 'New product'}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <Stack direction="row" spacing={1.5}>
            <TextField size="small" label="SKU" value={f.sku} disabled={!!product} onChange={(e) => setF({ ...f, sku: e.target.value })} sx={{ width: 160 }} />
            <TextField size="small" label="Name" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} sx={{ flex: 1 }} />
          </Stack>
          <Stack direction="row" spacing={1.5}>
            <TextField select size="small" label="Category" value={f.categoryId} onChange={(e) => setF({ ...f, categoryId: e.target.value })} sx={{ flex: 1 }}>
              <MenuItem value="">None</MenuItem>
              {(cats.data ?? []).map((c) => <MenuItem key={c.id} value={String(c.id)}>{c.name}</MenuItem>)}
            </TextField>
            <TextField select size="small" label="Unit" value={f.unit} onChange={(e) => setF({ ...f, unit: e.target.value })} sx={{ width: 130 }}>
              {(units.data ?? []).map((u) => <MenuItem key={u.code} value={u.code}>{u.code} · {u.name}</MenuItem>)}
            </TextField>
            <TextField size="small" label="Reorder at" value={f.reorder} onChange={(e) => setF({ ...f, reorder: e.target.value })} sx={{ width: 110 }} />
          </Stack>
          <Stack direction="row">
            <FormControlLabel control={<Checkbox checked={f.trackBatch} onChange={(_, v) => setF({ ...f, trackBatch: v, trackExpiry: v ? f.trackExpiry : false })} />} label="Track by batch" />
            <FormControlLabel control={<Checkbox checked={f.trackExpiry} disabled={!f.trackBatch} onChange={(_, v) => setF({ ...f, trackExpiry: v })} />} label="Has an expiry date" />
            {product && <FormControlLabel control={<Checkbox checked={f.active} onChange={(_, v) => setF({ ...f, active: v })} />} label="Active" />}
          </Stack>
          {product && <Typography sx={{ fontSize: 12.5 }} color="text.secondary">Once there is stock history the unit and batch tracking cannot change.</Typography>}
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={save.isPending} onClick={() => save.mutate()}>Save</Button></DialogActions>
    </Dialog>
  )
}

function StockDialog({ product, onClose }: { product: InvProduct; onClose: () => void }) {
  const stock = useQuery({ queryKey: ['inv-stock', product.id], queryFn: () => getInvStock(product.id) })
  const ledger = useQuery({ queryKey: ['inv-ledger', product.id], queryFn: () => getInvLedger(product.id) })
  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>{product.sku} · {product.name}: {qty(product.onHand)} {product.unit} at {money(product.cost)} each</DialogTitle>
      <DialogContent>
        <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', my: 1 }} color="text.secondary">Where it is</Typography>
        <Table size="small">
          <TableHead><TableRow><TableCell>Warehouse</TableCell><TableCell>Batch</TableCell><TableCell>Expires</TableCell><TableCell align="right">On hand</TableCell></TableRow></TableHead>
          <TableBody>
            {(stock.data ?? []).map((s) => <TableRow key={`${s.warehouseId}-${s.batchId}`}><TableCell>{s.warehouse}</TableCell><TableCell>{s.batchNo}</TableCell><TableCell>{s.expiry ?? ''}</TableCell><TableCell align="right">{qty(s.quantity)}</TableCell></TableRow>)}
            {stock.data?.length === 0 && <TableRow><TableCell colSpan={4}><Typography color="text.secondary">No stock.</Typography></TableCell></TableRow>}
          </TableBody>
        </Table>
        <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', my: 1 }} color="text.secondary">Movements</Typography>
        <Table size="small">
          <TableHead><TableRow><TableCell>No.</TableCell><TableCell>Date</TableCell><TableCell>Type</TableCell><TableCell>Warehouse</TableCell><TableCell>Batch</TableCell><TableCell align="right">Qty</TableCell><TableCell align="right">Cost</TableCell></TableRow></TableHead>
          <TableBody>
            {(ledger.data ?? []).map((l) => <TableRow key={l.id}><TableCell>{l.txnNo}</TableCell><TableCell>{l.date}</TableCell><TableCell>{l.type.toLowerCase()}</TableCell><TableCell>{l.warehouse}</TableCell><TableCell>{l.batchNo}</TableCell><TableCell align="right">{qty(l.quantity)}</TableCell><TableCell align="right">{money(l.unitCost)}</TableCell></TableRow>)}
          </TableBody>
        </Table>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Close</Button></DialogActions>
    </Dialog>
  )
}

function Products({ keeper }: { keeper: boolean }) {
  const [q, setQ] = useState('')
  const list = useQuery({ queryKey: ['inv-products', q], queryFn: () => listInvProducts(q || undefined) })
  const [editing, setEditing] = useState<InvProduct | 'new' | null>(null)
  const [viewing, setViewing] = useState<InvProduct | null>(null)
  return (
    <Box>
      <Stack direction="row" spacing={1.5} sx={{ mb: 1.5 }}>
        <TextField size="small" label="Search" value={q} onChange={(e) => setQ(e.target.value)} sx={{ width: 260 }} />
        {keeper && <Button variant="contained" startIcon={<FiPlus />} onClick={() => setEditing('new')}>New product</Button>}
      </Stack>
      <Table size="small">
        <TableHead><TableRow><TableCell>SKU</TableCell><TableCell>Name</TableCell><TableCell>Category</TableCell><TableCell>Unit</TableCell><TableCell align="right">On hand</TableCell><TableCell align="right">Avg cost</TableCell><TableCell>Status</TableCell><TableCell /></TableRow></TableHead>
        <TableBody>
          {(list.data ?? []).map((p) => (
            <TableRow key={p.id} hover>
              <TableCell>{p.sku}</TableCell><TableCell>{p.name}</TableCell><TableCell>{p.category ?? ''}</TableCell><TableCell>{p.unit}</TableCell>
              <TableCell align="right">{qty(p.onHand)} {p.low && <Chip size="small" color="warning" variant="outlined" label="low" sx={{ ml: 0.5 }} />}</TableCell>
              <TableCell align="right">{money(p.cost)}</TableCell>
              <TableCell><Chip size="small" variant="outlined" color={p.active ? 'success' : 'default'} label={p.active ? 'active' : 'off'} /></TableCell>
              <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}><Button size="small" onClick={() => setViewing(p)}>Stock</Button>{keeper && <Button size="small" onClick={() => setEditing(p)}>Edit</Button>}</TableCell>
            </TableRow>
          ))}
          {list.data?.length === 0 && <TableRow><TableCell colSpan={8}><Typography color="text.secondary">No products yet.</Typography></TableCell></TableRow>}
        </TableBody>
      </Table>
      {editing && <ProductDialog product={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />}
      {viewing && <StockDialog product={viewing} onClose={() => setViewing(null)} />}
    </Box>
  )
}

// ---- movements ------------------------------------------------------------------------------------------------------

function MoveDialog({ kind, onClose }: { kind: InvMoveIn['type'] | 'TRANSFER'; onClose: () => void }) {
  const qc = useQueryClient()
  const { products, warehouses } = useLists()
  const [product, setProduct] = useState('')
  const [wh, setWh] = useState('')
  const [toWh, setToWh] = useState('')
  const [batch, setBatch] = useState('')
  const [expiry, setExpiry] = useState('')
  const [quantity, setQuantity] = useState('')
  const [cost, setCost] = useState('')
  const [ref, setRef] = useState('')
  const [remarks, setRemarks] = useState('')
  const [error, setError] = useState<string | null>(null)
  const p = products.find((x) => String(x.id) === product)
  const save = useMutation({
    mutationFn: (): Promise<unknown> => kind === 'TRANSFER'
      ? transferInvStock({ productId: Number(product), fromWarehouseId: Number(wh), toWarehouseId: Number(toWh), batchNo: batch || undefined, quantity: parseAmount(quantity), remarks })
      : moveInvStock({ type: kind, productId: Number(product), warehouseId: Number(wh), batchNo: batch || undefined, expiryDate: expiry || null, quantity: parseAmount(quantity), unitCost: kind === 'RECEIVE' ? parseAmount(cost || '0') : null, reference: ref, remarks }),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['inv-products'] }); void qc.invalidateQueries({ queryKey: ['inv-ledger'] }); void qc.invalidateQueries({ queryKey: ['inv-stock'] }); onClose() },
    onError: (e) => setError((e as Error).message),
  })
  const title = { RECEIVE: 'Receive stock', ISSUE: 'Issue stock', RETURN: 'Return to stock', TRANSFER: 'Transfer between warehouses' }[kind]
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <ProductPicker value={product} onChange={setProduct} products={products} />
          <Stack direction="row" spacing={1.5}>
            <TextField select size="small" label={kind === 'TRANSFER' ? 'From' : 'Warehouse'} value={wh} onChange={(e) => setWh(e.target.value)} sx={{ flex: 1 }}>
              {warehouses.map((w) => <MenuItem key={w.id} value={String(w.id)}>{w.name}</MenuItem>)}
            </TextField>
            {kind === 'TRANSFER' && (
              <TextField select size="small" label="To" value={toWh} onChange={(e) => setToWh(e.target.value)} sx={{ flex: 1 }}>
                {warehouses.map((w) => <MenuItem key={w.id} value={String(w.id)}>{w.name}</MenuItem>)}
              </TextField>
            )}
          </Stack>
          {p?.trackBatch && (
            <Stack direction="row" spacing={1.5}>
              <TextField size="small" label="Batch no." value={batch} onChange={(e) => setBatch(e.target.value)} sx={{ flex: 1 }} />
              {p.trackExpiry && kind === 'RECEIVE' && <TextField size="small" type="date" label="Expiry (new batch)" value={expiry} onChange={(e) => setExpiry(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} sx={{ flex: 1 }} />}
            </Stack>
          )}
          <Stack direction="row" spacing={1.5}>
            <TextField size="small" label={`Quantity${p ? ` (${p.unit})` : ''}`} value={quantity} onChange={(e) => setQuantity(e.target.value)} sx={{ flex: 1 }} />
            {kind === 'RECEIVE' && <TextField size="small" label="Cost per unit" value={cost} onChange={(e) => setCost(e.target.value)} sx={{ flex: 1 }} />}
          </Stack>
          {kind !== 'TRANSFER' && <TextField size="small" label="Reference (delivery receipt, request ...)" value={ref} onChange={(e) => setRef(e.target.value)} />}
          <TextField size="small" label="Remarks" value={remarks} onChange={(e) => setRemarks(e.target.value)} />
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={save.isPending || !product || !wh} onClick={() => save.mutate()}>Post</Button></DialogActions>
    </Dialog>
  )
}

function Movements({ keeper }: { keeper: boolean }) {
  const ledger = useQuery({ queryKey: ['inv-ledger', 'all'], queryFn: () => getInvLedger() })
  const [kind, setKind] = useState<InvMoveIn['type'] | 'TRANSFER' | null>(null)
  return (
    <Box>
      {keeper && (
        <Stack direction="row" spacing={1} sx={{ mb: 1.5 }}>
          {(['RECEIVE', 'ISSUE', 'RETURN', 'TRANSFER'] as const).map((k) => <Button key={k} variant="outlined" onClick={() => setKind(k)}>{k === 'RECEIVE' ? 'Receive' : k === 'ISSUE' ? 'Issue' : k === 'RETURN' ? 'Return' : 'Transfer'}</Button>)}
        </Stack>
      )}
      <Table size="small">
        <TableHead><TableRow><TableCell>No.</TableCell><TableCell>Date</TableCell><TableCell>Type</TableCell><TableCell>Product</TableCell><TableCell>Warehouse</TableCell><TableCell>Batch</TableCell><TableCell align="right">Qty</TableCell><TableCell align="right">Cost</TableCell><TableCell>Reference</TableCell></TableRow></TableHead>
        <TableBody>
          {(ledger.data ?? []).map((l) => (
            <TableRow key={l.id} hover>
              <TableCell>{l.txnNo}</TableCell><TableCell>{l.date}</TableCell><TableCell>{l.type.toLowerCase()}</TableCell><TableCell>{l.sku} · {l.product}</TableCell><TableCell>{l.warehouse}</TableCell><TableCell>{l.batchNo}</TableCell>
              <TableCell align="right" sx={{ color: l.quantity < 0 ? 'error.main' : undefined }}>{qty(l.quantity)}</TableCell><TableCell align="right">{money(l.unitCost)}</TableCell><TableCell>{l.refNo ?? l.remarks ?? ''}</TableCell>
            </TableRow>
          ))}
          {ledger.data?.length === 0 && <TableRow><TableCell colSpan={9}><Typography color="text.secondary">No stock movements yet.</Typography></TableCell></TableRow>}
        </TableBody>
      </Table>
      {kind && <MoveDialog kind={kind} onClose={() => setKind(null)} />}
    </Box>
  )
}

// ---- adjustments and counts -----------------------------------------------------------------------------------------

interface LineRow { productId: string; batch: string; quantity: string }

function LineRows({ rows, onChange, products, label }: { rows: LineRow[]; onChange: (r: LineRow[]) => void; products: InvProduct[]; label: string }) {
  const set = (i: number, k: keyof LineRow, v: string) => onChange(rows.map((r, j) => (j === i ? { ...r, [k]: v } : r)))
  return (
    <Box>
      {rows.map((r, i) => {
        const p = products.find((x) => String(x.id) === r.productId)
        return (
          <Stack key={i} direction="row" spacing={1} sx={{ mb: 1 }}>
            <ProductPicker value={r.productId} onChange={(v) => set(i, 'productId', v)} products={products} />
            {p?.trackBatch && <TextField size="small" label="Batch" value={r.batch} onChange={(e) => set(i, 'batch', e.target.value)} sx={{ width: 120 }} />}
            <TextField size="small" label={label} value={r.quantity} onChange={(e) => set(i, 'quantity', e.target.value)} sx={{ width: 140 }} />
            <IconButton aria-label="Remove line" disabled={rows.length === 1} onClick={() => onChange(rows.filter((_, j) => j !== i))}><FiTrash2 /></IconButton>
          </Stack>
        )
      })}
      <Button size="small" startIcon={<FiPlus />} onClick={() => onChange([...rows, { productId: '', batch: '', quantity: '' }])}>Add line</Button>
    </Box>
  )
}

function NewAdjustmentDialog({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient()
  const { products, warehouses } = useLists()
  const [wh, setWh] = useState('')
  const [type, setType] = useState('ADJUSTMENT')
  const [reason, setReason] = useState('')
  const [rows, setRows] = useState<LineRow[]>([{ productId: '', batch: '', quantity: '' }])
  const [error, setError] = useState<string | null>(null)
  const save = useMutation({
    mutationFn: () => createInvAdjustment({ warehouseId: Number(wh), type, reason, lines: rows.map((r) => ({ productId: Number(r.productId), batchNo: r.batch || undefined, quantity: type === 'ADJUSTMENT' ? parseAmount(r.quantity) : -Math.abs(parseAmount(r.quantity)) })) }),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['inv-adjustments'] }); onClose() },
    onError: (e) => setError((e as Error).message),
  })
  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>New stock adjustment</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <Stack direction="row" spacing={1.5}>
            <TextField select size="small" label="Warehouse" value={wh} onChange={(e) => setWh(e.target.value)} sx={{ flex: 1 }}>{warehouses.map((w) => <MenuItem key={w.id} value={String(w.id)}>{w.name}</MenuItem>)}</TextField>
            <TextField select size="small" label="Kind" value={type} onChange={(e) => setType(e.target.value)} sx={{ width: 190 }}>
              <MenuItem value="ADJUSTMENT">Adjustment (+ or −)</MenuItem><MenuItem value="DAMAGE">Damage (out)</MenuItem><MenuItem value="EXPIRY">Expiry (out)</MenuItem>
            </TextField>
          </Stack>
          <TextField size="small" label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} />
          <LineRows rows={rows} onChange={setRows} products={products} label={type === 'ADJUSTMENT' ? 'Change (+/−)' : 'Quantity lost'} />
          <Typography sx={{ fontSize: 12.5 }} color="text.secondary">Nothing changes in stock until a different stock approver approves it.</Typography>
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={save.isPending || !wh} onClick={() => save.mutate()}>Submit</Button></DialogActions>
    </Dialog>
  )
}

function Adjustments({ keeper, approver, user }: { keeper: boolean; approver: boolean; user: string }) {
  const qc = useQueryClient()
  const list = useQuery({ queryKey: ['inv-adjustments'], queryFn: listInvAdjustments })
  const [creating, setCreating] = useState(false)
  const [rejecting, setRejecting] = useState<InvAdjSummary | null>(null)
  const [error, setError] = useState<string | null>(null)
  const refresh = () => { void qc.invalidateQueries({ queryKey: ['inv-adjustments'] }); void qc.invalidateQueries({ queryKey: ['inv-products'] }); void qc.invalidateQueries({ queryKey: ['inv-ledger'] }) }
  const approve = useMutation({ mutationFn: (a: InvAdjSummary) => decideInvAdjustment(a.id, 'approve'), onSuccess: () => { setError(null); refresh() }, onError: (e) => setError((e as Error).message) })
  return (
    <Box>
      {error && <Alert severity="error" sx={{ mb: 1 }} onClose={() => setError(null)}>{error}</Alert>}
      {keeper && <Button variant="contained" startIcon={<FiPlus />} sx={{ mb: 1.5 }} onClick={() => setCreating(true)}>New adjustment</Button>}
      <Table size="small">
        <TableHead><TableRow><TableCell>No.</TableCell><TableCell>Warehouse</TableCell><TableCell>Kind</TableCell><TableCell>Reason</TableCell><TableCell>By</TableCell><TableCell>Status</TableCell><TableCell /></TableRow></TableHead>
        <TableBody>
          {(list.data ?? []).map((a) => (
            <TableRow key={a.id} hover>
              <TableCell>{a.adjustNo}</TableCell><TableCell>{a.warehouse}</TableCell><TableCell>{a.type.toLowerCase()}</TableCell>
              <TableCell>{a.reason}{a.decisionNote && <Typography component="div" sx={{ fontSize: 12 }} color="text.secondary">{a.decisionNote}</Typography>}</TableCell><TableCell>{a.requestedBy}</TableCell>
              <TableCell><Chip size="small" variant="outlined" color={statusColor(a.status)} label={a.status.toLowerCase()} />{a.waiting && <Typography component="div" sx={{ fontSize: 12 }} color="text.secondary">waiting for {a.waiting}</Typography>}</TableCell>
              <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                {approver && a.status === 'SUBMITTED' && a.requestedBy !== user && (<>{!a.waiting && <Button size="small" disabled={approve.isPending} onClick={() => approve.mutate(a)}>Approve</Button>}<Button size="small" color="error" onClick={() => setRejecting(a)}>Reject</Button></>)}
              </TableCell>
            </TableRow>
          ))}
          {list.data?.length === 0 && <TableRow><TableCell colSpan={7}><Typography color="text.secondary">No stock adjustments yet.</Typography></TableCell></TableRow>}
        </TableBody>
      </Table>
      {creating && <NewAdjustmentDialog onClose={() => setCreating(false)} />}
      {rejecting && <ReasonDialog title={`Reject ${rejecting.adjustNo}`} label="Reason" onRun={(n) => decideInvAdjustment(rejecting.id, 'reject', n).then(refresh)} onClose={() => setRejecting(null)} />}
    </Box>
  )
}

function NewCountDialog({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient()
  const { products, warehouses } = useLists()
  const [wh, setWh] = useState('')
  const [rows, setRows] = useState<LineRow[]>([{ productId: '', batch: '', quantity: '' }])
  const [error, setError] = useState<string | null>(null)
  const save = useMutation({
    mutationFn: () => createInvCount({ warehouseId: Number(wh), lines: rows.map((r) => ({ productId: Number(r.productId), batchNo: r.batch || undefined, countedQty: parseAmount(r.quantity) })) }),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['inv-counts'] }); onClose() },
    onError: (e) => setError((e as Error).message),
  })
  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>New stock count</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <TextField select size="small" label="Warehouse" value={wh} onChange={(e) => setWh(e.target.value)} sx={{ width: 320 }}>{warehouses.map((w) => <MenuItem key={w.id} value={String(w.id)}>{w.name}</MenuItem>)}</TextField>
          <LineRows rows={rows} onChange={setRows} products={products} label="Counted" />
          <Typography sx={{ fontSize: 12.5 }} color="text.secondary">The books are read when you submit. A different stock approver approves the count, and only the differences are posted.</Typography>
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={save.isPending || !wh} onClick={() => save.mutate()}>Submit count</Button></DialogActions>
    </Dialog>
  )
}

function Counts({ keeper, approver, user }: { keeper: boolean; approver: boolean; user: string }) {
  const qc = useQueryClient()
  const list = useQuery({ queryKey: ['inv-counts'], queryFn: listInvCounts })
  const [creating, setCreating] = useState(false)
  const [rejecting, setRejecting] = useState<InvCountSummary | null>(null)
  const [error, setError] = useState<string | null>(null)
  const refresh = () => { void qc.invalidateQueries({ queryKey: ['inv-counts'] }); void qc.invalidateQueries({ queryKey: ['inv-products'] }); void qc.invalidateQueries({ queryKey: ['inv-ledger'] }) }
  const approve = useMutation({ mutationFn: (c: InvCountSummary) => decideInvCount(c.id, 'approve'), onSuccess: () => { setError(null); refresh() }, onError: (e) => setError((e as Error).message) })
  return (
    <Box>
      {error && <Alert severity="error" sx={{ mb: 1 }} onClose={() => setError(null)}>{error}</Alert>}
      {keeper && <Button variant="contained" startIcon={<FiPlus />} sx={{ mb: 1.5 }} onClick={() => setCreating(true)}>New count</Button>}
      <Table size="small">
        <TableHead><TableRow><TableCell>No.</TableCell><TableCell>Date</TableCell><TableCell>Warehouse</TableCell><TableCell align="right">Differences</TableCell><TableCell>By</TableCell><TableCell>Status</TableCell><TableCell /></TableRow></TableHead>
        <TableBody>
          {(list.data ?? []).map((c) => (
            <TableRow key={c.id} hover>
              <TableCell>{c.countNo}</TableCell><TableCell>{c.countDate}</TableCell><TableCell>{c.warehouse}</TableCell><TableCell align="right">{c.differences}</TableCell><TableCell>{c.countedBy}</TableCell>
              <TableCell><Chip size="small" variant="outlined" color={statusColor(c.status)} label={c.status.toLowerCase()} />{c.decisionNote && <Typography component="div" sx={{ fontSize: 12 }} color="text.secondary">{c.decisionNote}</Typography>}</TableCell>
              <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                {approver && c.status === 'SUBMITTED' && c.countedBy !== user && (<><Button size="small" disabled={approve.isPending} onClick={() => approve.mutate(c)}>Approve</Button><Button size="small" color="error" onClick={() => setRejecting(c)}>Reject</Button></>)}
              </TableCell>
            </TableRow>
          ))}
          {list.data?.length === 0 && <TableRow><TableCell colSpan={7}><Typography color="text.secondary">No stock counts yet.</Typography></TableCell></TableRow>}
        </TableBody>
      </Table>
      {creating && <NewCountDialog onClose={() => setCreating(false)} />}
      {rejecting && <ReasonDialog title={`Reject ${rejecting.countNo}`} label="Reason" onRun={(n) => decideInvCount(rejecting.id, 'reject', n).then(refresh)} onClose={() => setRejecting(null)} />}
    </Box>
  )
}

// ---- setup ----------------------------------------------------------------------------------------------------------

function Setup({ keeper }: { keeper: boolean }) {
  const qc = useQueryClient()
  const warehouses = useQuery({ queryKey: ['inv-warehouses', 'all'], queryFn: () => listInvWarehouses() })
  const cats = useQuery({ queryKey: ['inv-categories'], queryFn: listInvCategories })
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [cat, setCat] = useState('')
  const [error, setError] = useState<string | null>(null)
  const fail = (e: unknown) => setError((e as Error).message)
  const refreshWh = () => { void qc.invalidateQueries({ queryKey: ['inv-warehouses'] }) }
  const addWh = useMutation({ mutationFn: () => addInvWarehouse({ code, name }), onSuccess: () => { setError(null); setCode(''); setName(''); refreshWh() }, onError: fail })
  const toggle = useMutation({ mutationFn: (w: { id: number; active: boolean }) => updateInvWarehouse(w.id, { active: w.active }), onSuccess: () => { setError(null); refreshWh() }, onError: fail })
  const addCat = useMutation({ mutationFn: () => addInvCategory(cat), onSuccess: () => { setError(null); setCat(''); void qc.invalidateQueries({ queryKey: ['inv-categories'] }) }, onError: fail })
  return (
    <Box>
      {error && <Alert severity="error" sx={{ mb: 1 }} onClose={() => setError(null)}>{error}</Alert>}
      <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', mb: 1 }} color="text.secondary">Warehouses</Typography>
      {keeper && (
        <Stack direction="row" spacing={1.5} sx={{ mb: 1.5 }}>
          <TextField size="small" label="Code" value={code} onChange={(e) => setCode(e.target.value)} sx={{ width: 140 }} />
          <TextField size="small" label="Name" value={name} onChange={(e) => setName(e.target.value)} sx={{ width: 260 }} />
          <Button variant="contained" disabled={addWh.isPending || !code || !name} onClick={() => addWh.mutate()}>Add warehouse</Button>
        </Stack>
      )}
      <Table size="small" sx={{ mb: 3 }}>
        <TableBody>
          {(warehouses.data ?? []).map((w) => (
            <TableRow key={w.id}><TableCell>{w.code}</TableCell><TableCell>{w.name}</TableCell>
              <TableCell><Chip size="small" variant="outlined" color={w.active ? 'success' : 'default'} label={w.active ? 'active' : 'off'} /></TableCell>
              <TableCell align="right">{keeper && <Button size="small" disabled={toggle.isPending} onClick={() => toggle.mutate({ id: w.id, active: !w.active })}>{w.active ? 'Switch off' : 'Switch on'}</Button>}</TableCell></TableRow>
          ))}
        </TableBody>
      </Table>
      <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', mb: 1 }} color="text.secondary">Categories</Typography>
      {keeper && (
        <Stack direction="row" spacing={1.5} sx={{ mb: 1.5 }}>
          <TextField size="small" label="New category" value={cat} onChange={(e) => setCat(e.target.value)} sx={{ width: 260 }} />
          <Button variant="contained" disabled={addCat.isPending || !cat} onClick={() => addCat.mutate()}>Add category</Button>
        </Stack>
      )}
      <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap' }}>{(cats.data ?? []).map((c) => <Chip key={c.id} label={c.name} variant="outlined" />)}</Stack>
    </Box>
  )
}

/** Inventory: products and stock, movements, approved adjustments and counts, warehouses. Storekeepers move stock, stock approvers (never the maker) decide adjustments and counts. */
const TABS = ['Products', 'Movements', 'Adjustments', 'Counts', 'Setup']

export default function InventoryPage({ initialTab }: { initialTab?: string }) {
  const me = useMe()
  const role = (r: string) => me.roles.some((x) => x.replace('ROLE_', '') === r)
  const keeper = me.admin || role('STOREKEEPER')
  const approver = me.admin || role('STOCK_APPROVER')
  const [tab, setTab] = useState(() => Math.max(0, TABS.indexOf(initialTab ?? '')))
  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: 2, background: appBackground(t) })}>
      <Box sx={(t) => ({ maxWidth: 1200, mx: 'auto', p: 2, ...glass(t) })}>
        <Typography component="h1" sx={{ fontSize: 20, fontWeight: 700, letterSpacing: '-0.02em', mb: 1 }}>Inventory</Typography>
        <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}>{TABS.map((t) => <Tab key={t} label={t} />)}</Tabs>
        {tab === 0 && <Products keeper={keeper} />}
        {tab === 1 && <Movements keeper={keeper} />}
        {tab === 2 && <Adjustments keeper={keeper} approver={approver} user={me.username} />}
        {tab === 3 && <Counts keeper={keeper} approver={approver} user={me.username} />}
        {tab === 4 && <Setup keeper={keeper} />}
      </Box>
    </Box>
  )
}
