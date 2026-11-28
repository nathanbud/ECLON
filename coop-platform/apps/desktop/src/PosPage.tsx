import { Alert, Box, Button, Checkbox, Chip, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, IconButton, MenuItem, Stack, Tab, Table, TableBody, TableCell, TableHead, TableRow, Tabs, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { FiTrash2 } from 'react-icons/fi'
import {
  addPosDiscount, addPosPromotion, createPosReturn, decidePosReturn, getPosSale, listInvProducts, listInvWarehouses, listPosDiscounts, listPosProducts, listPosPromotions, listPosReturns, listPosSales,
  sellPos, setPosPrice, setPosPromotion, updatePosDiscount,
  type PosProduct, type PosReturn, type PosSale,
} from './api'
import { appBackground, glass } from './glass'
import { ReasonDialog } from './ui/dialogs'
import { useMe } from './session'
import { useLeaveGuard } from './leaveGuard'
import { money, parseAmount } from './ui/format'
import { labelOf } from './ui/labels'
import { notifyError } from './ui/notices'
import { LoadingRow } from './ui/Loading'

const qty = (n: number) => n.toLocaleString(undefined, { maximumFractionDigits: 3 })
const today = () => new Date().toLocaleDateString('en-CA')

/** The receipt of a sale, printable. */
function ReceiptDialog({ saleId, onClose }: { saleId: number; onClose: () => void }) {
  const d = useQuery({ queryKey: ['pos-sale', saleId], queryFn: () => getPosSale(saleId) })
  const s = d.data?.summary
  return (
    <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Receipt {s?.saleNo}</DialogTitle>
      <DialogContent>
        {s && d.data && (
          <Box id="pos-receipt" sx={{ fontFamily: 'ui-monospace, Consolas, monospace', fontSize: 13 }}>
            <div>{s.saleDate} {s.saleTime.slice(11, 16)} · {s.warehouse}</div>
            <div>Cashier {s.cashier}{s.customerName ? ` · ${s.customerName}` : ''}</div>
            <Box sx={{ borderTop: '1px dashed', borderBottom: '1px dashed', my: 1, py: 1 }}>
              {d.data.lines.map((l) => (
                <Box key={l.lineNo} sx={{ display: 'flex', justifyContent: 'space-between', gap: 1 }}>
                  <span>{qty(l.quantity)} {l.unit} {l.product}{l.batchNo ? ` (${l.batchNo})` : ''}{l.promoOff > 0 ? ` -${money(l.promoOff)} promo` : ''}</span><span>{money(l.lineTotal)}</span>
                </Box>
              ))}
            </Box>
            <Box sx={{ display: 'flex', justifyContent: 'space-between' }}><span>Subtotal</span><span>{money(s.subtotal)}</span></Box>
            {s.discountTotal > 0 && <Box sx={{ display: 'flex', justifyContent: 'space-between' }}><span>{d.data.discount ?? 'Discount'}</span><span>-{money(s.discountTotal)}</span></Box>}
            <Box sx={{ display: 'flex', justifyContent: 'space-between', fontWeight: 700 }}><span>TOTAL</span><span>{money(s.total)}</span></Box>
            {d.data.payments.map((p, i) => <Box key={i} sx={{ display: 'flex', justifyContent: 'space-between' }}><span>{p.method.toLowerCase()}{p.reference ? ` ${p.reference}` : ''}</span><span>{money(p.amount)}</span></Box>)}
            {s.changeGiven > 0 && <Box sx={{ display: 'flex', justifyContent: 'space-between' }}><span>Change</span><span>{money(s.changeGiven)}</span></Box>}
            {s.returnedTotal > 0 && <Box sx={{ display: 'flex', justifyContent: 'space-between' }}><span>Returned</span><span>-{money(s.returnedTotal)}</span></Box>}
          </Box>
        )}
      </DialogContent>
      <DialogActions><Button onClick={() => window.print()}>Print</Button><Button variant="contained" onClick={onClose}>Close</Button></DialogActions>
    </Dialog>
  )
}

interface CartLine { product: PosProduct; quantity: string }

function Register() {
  const qc = useQueryClient()
  const warehouses = useQuery({ queryKey: ['inv-warehouses', 'active'], queryFn: () => listInvWarehouses(true) })
  const discounts = useQuery({ queryKey: ['pos-discounts'], queryFn: listPosDiscounts })
  const [warehouse, setWarehouse] = useState('')
  const [q, setQ] = useState('')
  const products = useQuery({ queryKey: ['pos-products', warehouse, q], queryFn: () => listPosProducts(Number(warehouse), q || undefined), enabled: !!warehouse })
  const [cart, setCart] = useState<CartLine[]>([])
  const [discountId, setDiscountId] = useState('')
  const [customer, setCustomer] = useState('')
  const [method, setMethod] = useState('CASH')
  const [tendered, setTendered] = useState('')
  const [reference, setReference] = useState('')
  const [receipt, setReceipt] = useState<number | null>(null)

  const price = (p: PosProduct) => (p.price ?? 0) * (1 - (p.promoPercent ?? 0) / 100)
  const lineTotal = (l: CartLine) => Math.round(price(l.product) * (parseAmount(l.quantity) || 0) * 100) / 100
  useLeaveGuard(cart.length > 0 ? 'The sale in progress has items that are not paid yet.' : null)
  const subtotal = cart.reduce((s, l) => s + lineTotal(l), 0)
  const d = (discounts.data ?? []).find((x) => String(x.id) === discountId)
  const off = d ? Math.min(subtotal, d.kind === 'PERCENT' ? Math.round(subtotal * d.value) / 100 : d.value) : 0
  const total = Math.round((subtotal - off) * 100) / 100
  const paid = parseAmount(tendered || String(total))
  const add = (p: PosProduct) => setCart((c) => (c.some((l) => l.product.id === p.id) ? c.map((l) => (l.product.id === p.id ? { ...l, quantity: String((parseAmount(l.quantity) || 0) + 1) } : l)) : [...c, { product: p, quantity: '1' }]))
  const sell = useMutation({
    mutationFn: () => sellPos({
      warehouseId: Number(warehouse), customerName: customer, discountId: discountId ? Number(discountId) : null,
      lines: cart.map((l) => ({ productId: l.product.id, quantity: parseAmount(l.quantity) })), payments: [{ method, amount: paid, reference }],
    }),
    onSuccess: (s) => {
      setCart([]); setDiscountId(''); setCustomer(''); setTendered(''); setReference(''); setReceipt(s.id)
      void qc.invalidateQueries({ queryKey: ['pos-products'] }); void qc.invalidateQueries({ queryKey: ['pos-sales'] }); void qc.invalidateQueries({ queryKey: ['inv-products'] })
    },
    onError: (e) => notifyError((e as Error).message),
  })
  return (
    <Box>
      <Stack direction="row" spacing={1.5} sx={{ mb: 1.5 }}>
        <TextField select size="small" label="Store" value={warehouse} onChange={(e) => { setWarehouse(e.target.value); setCart([]) }} sx={{ width: 260 }}>
          {(warehouses.data ?? []).map((w) => <MenuItem key={w.id} value={String(w.id)}>{w.name}</MenuItem>)}
        </TextField>
        <TextField size="small" label="Find a product" value={q} onChange={(e) => setQ(e.target.value)} disabled={!warehouse} sx={{ width: 260 }} />
      </Stack>
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' }, gap: 2 }}>
        <Box>
          <Table size="small">
            <TableHead><TableRow><TableCell>Product</TableCell><TableCell align="right">Price</TableCell><TableCell align="right">In stock</TableCell><TableCell /></TableRow></TableHead>
            <TableBody>
              <LoadingRow q={products} what="the products" />
              {(products.data ?? []).map((p) => (
                <TableRow key={p.id} hover>
                  <TableCell>{p.name}{p.promoPercent ? <Chip size="small" color="success" variant="outlined" label={`${p.promoPercent}% off`} sx={{ ml: 0.75 }} /> : null}</TableCell>
                  <TableCell align="right">{p.price === null ? <Typography component="span" color="text.secondary">no price</Typography> : money(p.price)}</TableCell>
                  <TableCell align="right">{qty(p.onHand)}</TableCell>
                  <TableCell align="right"><Button size="small" disabled={p.price === null || p.onHand <= 0} onClick={() => add(p)}>Add</Button></TableCell>
                </TableRow>
              ))}
              {!warehouse && <TableRow><TableCell colSpan={4}><Typography color="text.secondary">Choose the store you are selling from.</Typography></TableCell></TableRow>}
            </TableBody>
          </Table>
        </Box>
        <Box>
          <Table size="small">
            <TableHead><TableRow><TableCell>Item</TableCell><TableCell align="right" sx={{ width: 120 }}>Qty</TableCell><TableCell align="right">Amount</TableCell><TableCell /></TableRow></TableHead>
            <TableBody>
              {cart.map((l) => (
                <TableRow key={l.product.id}>
                  <TableCell>{l.product.name}</TableCell>
                  <TableCell align="right"><TextField size="small" value={l.quantity} onChange={(e) => setCart(cart.map((x) => (x.product.id === l.product.id ? { ...x, quantity: e.target.value } : x)))} sx={{ width: 90 }} /></TableCell>
                  <TableCell align="right">{money(lineTotal(l))}</TableCell>
                  <TableCell><IconButton size="small" aria-label="Remove" onClick={() => setCart(cart.filter((x) => x.product.id !== l.product.id))}><FiTrash2 /></IconButton></TableCell>
                </TableRow>
              ))}
              {cart.length === 0 && <TableRow><TableCell colSpan={4}><Typography color="text.secondary">Nothing in the sale yet.</Typography></TableCell></TableRow>}
            </TableBody>
          </Table>
          <Stack spacing={1.25} sx={{ mt: 1.5 }}>
            <Stack direction="row" spacing={1.5}>
              <TextField select size="small" label="Discount" value={discountId} onChange={(e) => setDiscountId(e.target.value)} sx={{ flex: 1 }}>
                <MenuItem value="">None</MenuItem>
                {(discounts.data ?? []).filter((x) => x.active).map((x) => <MenuItem key={x.id} value={String(x.id)}>{x.name} ({x.kind === 'PERCENT' ? `${x.value}%` : money(x.value)}){x.requiresSupervisor ? ' · supervisor' : ''}</MenuItem>)}
              </TextField>
              <TextField size="small" label="Customer (optional)" value={customer} onChange={(e) => setCustomer(e.target.value)} sx={{ flex: 1 }} />
            </Stack>
            <Typography>Subtotal {money(subtotal)}{off > 0 && <> · discount −{money(off)}</>}</Typography>
            <Typography variant="h3" component="p">Total {money(total)}</Typography>
            <Stack direction="row" spacing={1.5}>
              <TextField select size="small" label="Pay by" value={method} onChange={(e) => setMethod(e.target.value)} sx={{ width: 120 }}>
                <MenuItem value="CASH">Cash</MenuItem><MenuItem value="CARD">Card</MenuItem><MenuItem value="OTHER">Other</MenuItem>
              </TextField>
              <TextField size="small" label={method === 'CASH' ? 'Cash received' : 'Amount'} value={tendered} placeholder={money(total)} onChange={(e) => setTendered(e.target.value)} sx={{ width: 150 }} />
              {method !== 'CASH' && <TextField size="small" label="Reference" value={reference} onChange={(e) => setReference(e.target.value)} sx={{ flex: 1 }} />}
              {method === 'CASH' && paid > total && <Typography sx={{ alignSelf: 'center' }}>Change <b>{money(paid - total)}</b></Typography>}
            </Stack>
            <Button variant="contained" size="large" disabled={sell.isPending || cart.length === 0 || paid < total} onClick={() => sell.mutate()}>Complete sale</Button>
          </Stack>
        </Box>
      </Box>
      {receipt !== null && <ReceiptDialog saleId={receipt} onClose={() => setReceipt(null)} />}
    </Box>
  )
}

// ---- sales and returns ------------------------------------------------------------------------------------------------

function ReturnDialog({ sale, onClose }: { sale: PosSale; onClose: () => void }) {
  const qc = useQueryClient()
  const d = useQuery({ queryKey: ['pos-sale', sale.id], queryFn: () => getPosSale(sale.id) })
  const [back, setBack] = useState<Record<number, string>>({})
  const [reason, setReason] = useState('')
  const lines = d.data?.lines ?? []
  const save = useMutation({
    mutationFn: () => createPosReturn({ saleId: sale.id, reason, lines: lines.map((l) => ({ saleLineNo: l.lineNo, quantity: parseAmount(back[l.lineNo] ?? '0') })).filter((l) => l.quantity > 0) }),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['pos-returns'] }); onClose() },
    onError: (e) => notifyError((e as Error).message),
  })
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Return against {sale.saleNo}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          <Table size="small">
            <TableHead><TableRow><TableCell>Item</TableCell><TableCell align="right">Sold</TableCell><TableCell align="right">Back already</TableCell><TableCell align="right" sx={{ width: 110 }}>Returning</TableCell></TableRow></TableHead>
            <TableBody>
              {lines.map((l) => (
                <TableRow key={l.lineNo}>
                  <TableCell>{l.product}{l.batchNo ? ` (${l.batchNo})` : ''}</TableCell><TableCell align="right">{qty(l.quantity)}</TableCell><TableCell align="right">{qty(l.returned)}</TableCell>
                  <TableCell align="right"><TextField size="small" value={back[l.lineNo] ?? ''} disabled={l.returned >= l.quantity} onChange={(e) => setBack({ ...back, [l.lineNo]: e.target.value })} /></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <TextField size="small" label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} />
          <Typography variant="body2" color="text.secondary">A POS supervisor approves the return; the goods go back into stock and the refund is paid then.</Typography>
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={save.isPending || !reason.trim()} onClick={() => save.mutate()}>Ask for the return</Button></DialogActions>
    </Dialog>
  )
}

function Sales() {
  const [date, setDate] = useState(today())
  const list = useQuery({ queryKey: ['pos-sales', date], queryFn: () => listPosSales(date || undefined) })
  const [receipt, setReceipt] = useState<number | null>(null)
  const [returning, setReturning] = useState<PosSale | null>(null)
  const total = (list.data ?? []).reduce((s, x) => s + x.total - x.returnedTotal, 0)
  return (
    <Box>
      <Stack direction="row" spacing={1.5} sx={{ mb: 1.5, alignItems: 'center' }}>
        <TextField size="small" type="date" label="Day" value={date} onChange={(e) => setDate(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
        <Typography>{list.data?.length ?? 0} sales · net <b>{money(total)}</b></Typography>
      </Stack>
      <Table size="small">
        <TableHead><TableRow><TableCell>Receipt</TableCell><TableCell>Time</TableCell><TableCell>Store</TableCell><TableCell>Cashier</TableCell><TableCell align="right">Total</TableCell><TableCell align="right">Returned</TableCell><TableCell /></TableRow></TableHead>
        <TableBody>
          <LoadingRow q={list} what="the sales" />
          {(list.data ?? []).map((s) => (
            <TableRow key={s.id} hover>
              <TableCell>{s.saleNo}</TableCell><TableCell>{s.saleTime.slice(11, 16)}</TableCell><TableCell>{s.warehouse}</TableCell><TableCell>{s.cashier}</TableCell>
              <TableCell align="right">{money(s.total)}</TableCell><TableCell align="right">{s.returnedTotal > 0 ? money(s.returnedTotal) : ''}</TableCell>
              <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}><Button size="small" onClick={() => setReceipt(s.id)}>Receipt</Button><Button size="small" color="warning" onClick={() => setReturning(s)}>Return</Button></TableCell>
            </TableRow>
          ))}
          {list.data?.length === 0 && <TableRow><TableCell colSpan={7}><Typography color="text.secondary">No sales on this day.</Typography></TableCell></TableRow>}
        </TableBody>
      </Table>
      {receipt !== null && <ReceiptDialog saleId={receipt} onClose={() => setReceipt(null)} />}
      {returning && <ReturnDialog sale={returning} onClose={() => setReturning(null)} />}
    </Box>
  )
}

function Returns({ supervisor, user }: { supervisor: boolean; user: string }) {
  const qc = useQueryClient()
  const list = useQuery({ queryKey: ['pos-returns'], queryFn: listPosReturns })
  const [rejecting, setRejecting] = useState<PosReturn | null>(null)
  const refresh = () => { void qc.invalidateQueries({ queryKey: ['pos-returns'] }); void qc.invalidateQueries({ queryKey: ['pos-sales'] }); void qc.invalidateQueries({ queryKey: ['inv-products'] }) }
  const approve = useMutation({ meta: { done: 'Return approved.' }, mutationFn: (r: PosReturn) => decidePosReturn(r.id, 'approve'), onSuccess: () => { refresh() }, onError: (e) => notifyError((e as Error).message) })
  return (
    <Box>
      <Table size="small">
        <TableHead><TableRow><TableCell>No.</TableCell><TableCell>Sale</TableCell><TableCell>Reason</TableCell><TableCell align="right">Refund</TableCell><TableCell>By</TableCell><TableCell>Status</TableCell><TableCell /></TableRow></TableHead>
        <TableBody>
          <LoadingRow q={list} what="the returns" />
          {(list.data ?? []).map((r) => (
            <TableRow key={r.id} hover>
              <TableCell>{r.returnNo}</TableCell><TableCell>{r.saleNo}</TableCell>
              <TableCell>{r.reason}{r.decisionNote && <Typography variant="body2" component="div" color="text.secondary">{r.decisionNote}</Typography>}</TableCell>
              <TableCell align="right">{money(r.refund)}</TableCell><TableCell>{r.requestedBy}</TableCell>
              <TableCell><Chip size="small" variant="outlined" color={r.status === 'APPROVED' ? 'success' : r.status === 'PENDING' ? 'warning' : 'default'} label={labelOf(r.status)} /></TableCell>
              <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                {supervisor && r.status === 'PENDING' && r.requestedBy !== user && (<><Button size="small" disabled={approve.isPending} onClick={() => approve.mutate(r)}>Approve</Button><Button size="small" color="error" onClick={() => setRejecting(r)}>Reject</Button></>)}
              </TableCell>
            </TableRow>
          ))}
          {list.data?.length === 0 && <TableRow><TableCell colSpan={7}><Typography color="text.secondary">No returns yet.</Typography></TableCell></TableRow>}
        </TableBody>
      </Table>
      {rejecting && <ReasonDialog title={`Reject ${rejecting.returnNo}`} label="Reason" onRun={(n) => decidePosReturn(rejecting.id, 'reject', n).then(refresh)} onClose={() => setRejecting(null)} />}
    </Box>
  )
}

// ---- prices, discounts, promotions --------------------------------------------------------------------------------------

function Pricing({ supervisor }: { supervisor: boolean }) {
  const qc = useQueryClient()
  const products = useQuery({ queryKey: ['inv-products', 'active'], queryFn: () => listInvProducts(undefined, true) })
  const discounts = useQuery({ queryKey: ['pos-discounts'], queryFn: listPosDiscounts })
  const promotions = useQuery({ queryKey: ['pos-promotions'], queryFn: listPosPromotions })
  const [product, setProduct] = useState('')
  const [price, setPrice] = useState('')
  const [dc, setDc] = useState({ code: '', name: '', kind: 'PERCENT', value: '', sup: false })
  const [pr, setPr] = useState({ name: '', product: '', percent: '', from: today(), to: today() })
  const [notice, setNotice] = useState<string | null>(null)
  const fail = (e: unknown) => { setNotice(null); notifyError((e as Error).message) }
  const ok = (m: string) => { setNotice(m) }
  const setP = useMutation({ mutationFn: () => setPosPrice(Number(product), parseAmount(price)), onSuccess: () => { ok('Price set.'); setPrice(''); void qc.invalidateQueries({ queryKey: ['pos-products'] }) }, onError: fail })
  const addD = useMutation({ mutationFn: () => addPosDiscount({ code: dc.code, name: dc.name, kind: dc.kind, value: parseAmount(dc.value), requiresSupervisor: dc.sup }), onSuccess: () => { ok('Discount added.'); setDc({ ...dc, code: '', name: '', value: '' }); void qc.invalidateQueries({ queryKey: ['pos-discounts'] }) }, onError: fail })
  const toggleD = useMutation({ mutationFn: (x: { id: number; active: boolean }) => updatePosDiscount(x.id, { active: x.active }), onSuccess: () => { void qc.invalidateQueries({ queryKey: ['pos-discounts'] }) }, onError: fail })
  const addP = useMutation({ mutationFn: () => addPosPromotion({ name: pr.name, productId: Number(pr.product), percentOff: parseAmount(pr.percent), startDate: pr.from, endDate: pr.to }), onSuccess: () => { ok('Promotion added.'); setPr({ ...pr, name: '', percent: '' }); void qc.invalidateQueries({ queryKey: ['pos-promotions'] }); void qc.invalidateQueries({ queryKey: ['pos-products'] }) }, onError: fail })
  const toggleP = useMutation({ mutationFn: (x: { id: number; active: boolean }) => setPosPromotion(x.id, x.active), onSuccess: () => { void qc.invalidateQueries({ queryKey: ['pos-promotions'] }) }, onError: fail })
  const label = (p: { sku: string; name: string }) => `${p.sku} · ${p.name}`
  return (
    <Box>
      {notice && <Alert severity="success" sx={{ mb: 1 }} onClose={() => setNotice(null)}>{notice}</Alert>}
      {!supervisor && <Alert severity="info" sx={{ mb: 1 }}>Only a POS supervisor changes prices, discounts and promotions.</Alert>}
      <Typography variant="overline" sx={{ display: 'block', mb: 1 }} color="text.secondary">Selling price</Typography>
      {supervisor && (
        <Stack direction="row" spacing={1.5} sx={{ mb: 3 }}>
          <TextField select size="small" label="Product" value={product} onChange={(e) => setProduct(e.target.value)} sx={{ width: 320 }}>{(products.data ?? []).map((p) => <MenuItem key={p.id} value={String(p.id)}>{label(p)}</MenuItem>)}</TextField>
          <TextField size="small" label="New price" value={price} onChange={(e) => setPrice(e.target.value)} sx={{ width: 140 }} />
          <Button variant="contained" disabled={setP.isPending || !product || !price} onClick={() => setP.mutate()}>Set price</Button>
        </Stack>
      )}
      <Typography variant="overline" sx={{ display: 'block', mb: 1 }} color="text.secondary">Discounts</Typography>
      {supervisor && (
        <Stack direction="row" spacing={1.5} sx={{ mb: 1.5, alignItems: 'center' }}>
          <TextField size="small" label="Code" value={dc.code} onChange={(e) => setDc({ ...dc, code: e.target.value })} sx={{ width: 110 }} />
          <TextField size="small" label="Name" value={dc.name} onChange={(e) => setDc({ ...dc, name: e.target.value })} sx={{ width: 200 }} />
          <TextField select size="small" label="Kind" value={dc.kind} onChange={(e) => setDc({ ...dc, kind: e.target.value })} sx={{ width: 120 }}><MenuItem value="PERCENT">Percent</MenuItem><MenuItem value="AMOUNT">Amount</MenuItem></TextField>
          <TextField size="small" label="Value" value={dc.value} onChange={(e) => setDc({ ...dc, value: e.target.value })} sx={{ width: 100 }} />
          <FormControlLabel control={<Checkbox checked={dc.sup} onChange={(_, v) => setDc({ ...dc, sup: v })} />} label="Supervisor only" />
          <Button variant="contained" disabled={addD.isPending || !dc.code || !dc.name || !dc.value} onClick={() => addD.mutate()}>Add</Button>
        </Stack>
      )}
      <Table size="small" sx={{ mb: 3 }}>
        <TableBody>
          {(discounts.data ?? []).map((x) => (
            <TableRow key={x.id}><TableCell>{x.code}</TableCell><TableCell>{x.name}</TableCell><TableCell>{x.kind === 'PERCENT' ? `${x.value}%` : money(x.value)}</TableCell><TableCell>{x.requiresSupervisor ? 'supervisor only' : ''}</TableCell>
              <TableCell><Chip size="small" variant="outlined" color={x.active ? 'success' : 'default'} label={x.active ? 'active' : 'off'} /></TableCell>
              <TableCell align="right">{supervisor && <Button size="small" onClick={() => toggleD.mutate({ id: x.id, active: !x.active })}>{x.active ? 'Switch off' : 'Switch on'}</Button>}</TableCell></TableRow>
          ))}
        </TableBody>
      </Table>
      <Typography variant="overline" sx={{ display: 'block', mb: 1 }} color="text.secondary">Promotions</Typography>
      {supervisor && (
        <Stack direction="row" spacing={1.5} sx={{ mb: 1.5 }}>
          <TextField size="small" label="Name" value={pr.name} onChange={(e) => setPr({ ...pr, name: e.target.value })} sx={{ width: 180 }} />
          <TextField select size="small" label="Product" value={pr.product} onChange={(e) => setPr({ ...pr, product: e.target.value })} sx={{ width: 240 }}>{(products.data ?? []).map((p) => <MenuItem key={p.id} value={String(p.id)}>{label(p)}</MenuItem>)}</TextField>
          <TextField size="small" label="% off" value={pr.percent} onChange={(e) => setPr({ ...pr, percent: e.target.value })} sx={{ width: 90 }} />
          <TextField size="small" type="date" label="From" value={pr.from} onChange={(e) => setPr({ ...pr, from: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} />
          <TextField size="small" type="date" label="To" value={pr.to} onChange={(e) => setPr({ ...pr, to: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} />
          <Button variant="contained" disabled={addP.isPending || !pr.name || !pr.product || !pr.percent} onClick={() => addP.mutate()}>Add</Button>
        </Stack>
      )}
      <Table size="small">
        <TableBody>
          {(promotions.data ?? []).map((x) => (
            <TableRow key={x.id}><TableCell>{x.name}</TableCell><TableCell>{x.product}</TableCell><TableCell>{x.percentOff}% off</TableCell><TableCell>{x.startDate} to {x.endDate}</TableCell>
              <TableCell><Chip size="small" variant="outlined" color={x.active ? 'success' : 'default'} label={x.active ? 'active' : 'off'} /></TableCell>
              <TableCell align="right">{supervisor && <Button size="small" onClick={() => toggleP.mutate({ id: x.id, active: !x.active })}>{x.active ? 'Switch off' : 'Switch on'}</Button>}</TableCell></TableRow>
          ))}
        </TableBody>
      </Table>
    </Box>
  )
}

/** Point of sale: ring up and take payment, today's sales and receipts, returns approved by a supervisor, and the prices, discounts and promotions. */
export default function PosPage() {
  const me = useMe()
  const role = (r: string) => me.roles.some((x) => x.replace('ROLE_', '') === r)
  const supervisor = me.admin || role('POS_SUPERVISOR')
  const seller = supervisor || role('POS_CASHIER')
  const tabs = [...(seller ? ['Sell'] : []), 'Sales', 'Returns', 'Prices']
  const [tab, setTab] = useState(0)
  const name = tabs[tab]
  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: 2, background: appBackground(t) })}>
      <Box sx={(t) => ({ maxWidth: 1250, mx: 'auto', p: 2, ...glass(t) })}>
        <Typography component="h1" sx={{ fontSize: 20, fontWeight: 700, letterSpacing: '-0.02em', mb: 1 }}>Point of sale</Typography>
        <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}>{tabs.map((t) => <Tab key={t} label={t} />)}</Tabs>
        {name === 'Sell' && <Register />}
        {name === 'Sales' && <Sales />}
        {name === 'Returns' && <Returns supervisor={supervisor} user={me.username} />}
        {name === 'Prices' && <Pricing supervisor={supervisor} />}
      </Box>
    </Box>
  )
}

