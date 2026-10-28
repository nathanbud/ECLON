import { getJson, send } from './http'

// ---- procurement (guide phase 12): suppliers, purchase requests, purchase orders --------------------------------------
export interface Supplier { id: number; code: string; name: string; tin: string | null; address: string | null; phone: string | null; email: string | null; contact: string | null; termsDays: number; taxable: boolean; active: boolean }
export type SupplierIn = Partial<Omit<Supplier, 'id'>>
export interface PrLineIn { description: string; unit: string; quantity: number; estCost: number }
export interface PrSummary { id: number; requestNo: string; requestDate: string; neededBy: string | null; purpose: string; status: 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'ORDERED' | 'CANCELLED'; requestedBy: string; decidedBy: string | null; decisionNote: string | null; total: number; waiting: string | null }
export interface PrDetail { summary: PrSummary; lines: PrLineIn[] }
export interface PoLineIn { description: string; unit: string; quantity: number; unitCost: number; productId?: number | null }
export interface PoLineOut extends PoLineIn { received: number; productId: number | null; trackBatch: boolean; trackExpiry: boolean }
export interface PoSummary { id: number; orderNo: string; supplierId: number; supplier: string; requestId: number | null; requestNo: string | null; orderDate: string; expectedDate: string | null; termsDays: number; remarks: string | null; status: 'DRAFT' | 'APPROVED' | 'PARTIAL' | 'RECEIVED' | 'CANCELLED'; total: number; createdBy: string; approvedBy: string | null; waiting: string | null }
export interface PoDetail { summary: PoSummary; lines: PoLineOut[] }
export const listSuppliers = (q?: string, activeOnly = false) => getJson<Supplier[]>(`/api/procurement/suppliers?activeOnly=${activeOnly}${q ? `&q=${encodeURIComponent(q)}` : ''}`)
export const createSupplier = (b: SupplierIn) => send<Supplier>('POST', '/api/procurement/suppliers', b)
export const updateSupplier = (id: number, b: SupplierIn) => send<Supplier>('PUT', `/api/procurement/suppliers/${id}`, b)
export const listRequests = (status?: string) => getJson<PrSummary[]>(`/api/procurement/requests${status ? `?status=${status}` : ''}`)
export const getRequest = (id: number) => getJson<PrDetail>(`/api/procurement/requests/${id}`)
export const createRequest = (b: { purpose: string; neededBy?: string | null; lines: PrLineIn[] }) => send<PrSummary>('POST', '/api/procurement/requests', b)
export const decideRequest = (id: number, kind: 'approve' | 'reject' | 'cancel', note?: string) => send<PrSummary>('POST', `/api/procurement/requests/${id}/${kind}`, { note })
export const listOrders = (status?: string) => getJson<PoSummary[]>(`/api/procurement/orders${status ? `?status=${status}` : ''}`)
export const createOrder = (b: { supplierId: number; requestId?: number | null; expectedDate?: string | null; remarks?: string; lines: PoLineIn[] }) => send<PoSummary>('POST', '/api/procurement/orders', b)
export const approveOrder = (id: number) => send<PoSummary>('POST', `/api/procurement/orders/${id}/approve`)
export const cancelOrder = (id: number, note: string) => send<PoSummary>('POST', `/api/procurement/orders/${id}/cancel`, { note })
export const getOrder = (id: number) => getJson<PoDetail>(`/api/procurement/orders/${id}`)
export interface GrSummary { id: number; receiptNo: string; orderId: number; orderNo: string; supplier: string; receiptDate: string; deliveryNote: string | null; remarks: string | null; status: 'RECEIVED' | 'REVERSED'; receivedBy: string; reversedBy: string | null; reverseNote: string | null }
export const listReceipts = (orderId?: number) => getJson<GrSummary[]>(`/api/procurement/receipts${orderId ? `?orderId=${orderId}` : ''}`)
export const createReceipt = (b: { orderId: number; warehouseId?: number | null; deliveryNote?: string; remarks?: string; lines: { lineNo: number; quantity: number; batchNo?: string; expiryDate?: string | null }[] }) => send<GrSummary>('POST', '/api/procurement/receipts', b)
export const reverseReceipt = (id: number, note: string) => send<GrSummary>('POST', `/api/procurement/receipts/${id}/reverse`, { note })

// ---- inventory (guide phase 13) ----------------------------------------------------------------------------------------
export interface InvUnit { code: string; name: string }
export interface InvCategory { id: number; name: string }
export interface InvWarehouse { id: number; code: string; name: string; active: boolean }
export interface InvProduct { id: number; sku: string; name: string; categoryId: number | null; category: string | null; unit: string; cost: number; reorderLevel: number; trackBatch: boolean; trackExpiry: boolean; active: boolean; onHand: number; low: boolean }
export interface InvProductIn { sku?: string; name?: string; categoryId?: number | null; unit?: string; reorderLevel?: number | null; trackBatch?: boolean; trackExpiry?: boolean; active?: boolean }
export interface InvStockRow { warehouseId: number; warehouse: string; batchId: number; batchNo: string; expiry: string | null; quantity: number }
export interface InvLedgerRow { id: number; txnNo: string; date: string; type: string; productId: number; sku: string; product: string; warehouseId: number; warehouse: string; batchNo: string; quantity: number; unitCost: number; refType: string | null; refNo: string | null; remarks: string | null; by: string }
export interface InvMoveIn { type: 'RECEIVE' | 'ISSUE' | 'RETURN'; productId: number; warehouseId: number; batchNo?: string; expiryDate?: string | null; quantity: number; unitCost?: number | null; reference?: string; remarks?: string }
export interface InvAdjSummary { id: number; adjustNo: string; warehouseId: number; warehouse: string; type: string; reason: string; status: 'SUBMITTED' | 'APPROVED' | 'REJECTED'; requestedBy: string; decidedBy: string | null; decisionNote: string | null; waiting: string | null }
export interface InvAdjLine { productId: number; sku: string; product: string; batchNo: string; quantity: number }
export interface InvCountSummary { id: number; countNo: string; warehouseId: number; warehouse: string; countDate: string; status: 'SUBMITTED' | 'APPROVED' | 'REJECTED'; countedBy: string; decidedBy: string | null; decisionNote: string | null; differences: number }
export interface InvCountLine { productId: number; sku: string; product: string; batchNo: string; systemQty: number; countedQty: number; variance: number }
export const listInvUnits = () => getJson<InvUnit[]>('/api/inventory/units')
export const listInvCategories = () => getJson<InvCategory[]>('/api/inventory/categories')
export const addInvCategory = (name: string) => send<InvCategory>('POST', '/api/inventory/categories', { name })
export const listInvWarehouses = (activeOnly = false) => getJson<InvWarehouse[]>(`/api/inventory/warehouses?activeOnly=${activeOnly}`)
export const addInvWarehouse = (b: { code: string; name: string }) => send<InvWarehouse>('POST', '/api/inventory/warehouses', b)
export const updateInvWarehouse = (id: number, b: { name?: string; active?: boolean }) => send<InvWarehouse>('PUT', `/api/inventory/warehouses/${id}`, b)
export const listInvProducts = (q?: string, activeOnly = false) => getJson<InvProduct[]>(`/api/inventory/products?activeOnly=${activeOnly}${q ? `&q=${encodeURIComponent(q)}` : ''}`)
export const addInvProduct = (b: InvProductIn) => send<InvProduct>('POST', '/api/inventory/products', b)
export const updateInvProduct = (id: number, b: InvProductIn) => send<InvProduct>('PUT', `/api/inventory/products/${id}`, b)
export const getInvStock = (id: number) => getJson<InvStockRow[]>(`/api/inventory/products/${id}/stock`)
export const getInvLedger = (productId?: number, warehouseId?: number) => getJson<InvLedgerRow[]>(`/api/inventory/ledger?limit=200${productId ? `&productId=${productId}` : ''}${warehouseId ? `&warehouseId=${warehouseId}` : ''}`)
export const moveInvStock = (b: InvMoveIn) => send<{ txnNo: string; unitCost: number }>('POST', '/api/inventory/movements', b)
export const transferInvStock = (b: { productId: number; fromWarehouseId: number; toWarehouseId: number; batchNo?: string; quantity: number; remarks?: string }) => send<{ outTxnNo: string; inTxnNo: string }>('POST', '/api/inventory/transfers', b)
export const listInvAdjustments = () => getJson<InvAdjSummary[]>('/api/inventory/adjustments')
export const getInvAdjustment = (id: number) => getJson<{ summary: InvAdjSummary; lines: InvAdjLine[] }>(`/api/inventory/adjustments/${id}`)
export const createInvAdjustment = (b: { warehouseId: number; type: string; reason: string; lines: { productId: number; batchNo?: string; quantity: number }[] }) => send<InvAdjSummary>('POST', '/api/inventory/adjustments', b)
export const decideInvAdjustment = (id: number, kind: 'approve' | 'reject', note?: string) => send<InvAdjSummary>('POST', `/api/inventory/adjustments/${id}/${kind}`, { note })
export const listInvCounts = () => getJson<InvCountSummary[]>('/api/inventory/counts')
export const getInvCount = (id: number) => getJson<{ summary: InvCountSummary; lines: InvCountLine[] }>(`/api/inventory/counts/${id}`)
export const createInvCount = (b: { warehouseId: number; lines: { productId: number; batchNo?: string; countedQty: number }[] }) => send<InvCountSummary>('POST', '/api/inventory/counts', b)
export const decideInvCount = (id: number, kind: 'approve' | 'reject', note?: string) => send<InvCountSummary>('POST', `/api/inventory/counts/${id}/${kind}`, { note })

// ---- point of sale (guide phase 14) -----------------------------------------------------------------------------------
export interface PosProduct { id: number; sku: string; name: string; unit: string; price: number | null; onHand: number; promoPercent: number | null; promoName: string | null; trackBatch: boolean }
export interface PosDiscount { id: number; code: string; name: string; kind: 'PERCENT' | 'AMOUNT'; value: number; requiresSupervisor: boolean; active: boolean }
export interface PosPromotion { id: number; name: string; productId: number; product: string; percentOff: number; startDate: string; endDate: string; active: boolean }
export interface PosSale { id: number; saleNo: string; saleDate: string; saleTime: string; warehouse: string; cashier: string; clientId: number | null; customerName: string | null; subtotal: number; discountTotal: number; total: number; paid: number; changeGiven: number; returnedTotal: number; journaled: boolean }
export interface PosSaleLine { lineNo: number; productId: number; sku: string; product: string; unit: string; batchNo: string; quantity: number; unitPrice: number; promoOff: number; lineTotal: number; returned: number }
export interface PosSaleDetail { summary: PosSale; discount: string | null; lines: PosSaleLine[]; payments: { method: string; amount: number; reference: string | null }[] }
export interface PosReturn { id: number; returnNo: string; saleId: number; saleNo: string; reason: string; status: 'PENDING' | 'APPROVED' | 'REJECTED'; refund: number; requestedBy: string; decidedBy: string | null; decisionNote: string | null }
export const listPosProducts = (warehouseId: number, q?: string) => getJson<PosProduct[]>(`/api/pos/products?warehouseId=${warehouseId}${q ? `&q=${encodeURIComponent(q)}` : ''}`)
export const setPosPrice = (productId: number, price: number) => send<{ price: number }>('POST', '/api/pos/prices', { productId, price })
export const listPosDiscounts = () => getJson<PosDiscount[]>('/api/pos/discounts')
export const addPosDiscount = (b: { code: string; name: string; kind: string; value: number; requiresSupervisor: boolean }) => send<PosDiscount>('POST', '/api/pos/discounts', b)
export const updatePosDiscount = (id: number, b: { value?: number; active?: boolean; requiresSupervisor?: boolean }) => send<PosDiscount>('PUT', `/api/pos/discounts/${id}`, b)
export const listPosPromotions = () => getJson<PosPromotion[]>('/api/pos/promotions')
export const addPosPromotion = (b: { name: string; productId: number; percentOff: number; startDate: string; endDate: string }) => send<PosPromotion>('POST', '/api/pos/promotions', b)
export const setPosPromotion = (id: number, active: boolean) => send<PosPromotion>('PUT', `/api/pos/promotions/${id}`, { active })
export const sellPos = (b: { warehouseId: number; customerName?: string; discountId?: number | null; lines: { productId: number; quantity: number }[]; payments: { method: string; amount: number; reference?: string }[] }) => send<PosSale>('POST', '/api/pos/sales', b)
export const listPosSales = (date?: string) => getJson<PosSale[]>(`/api/pos/sales${date ? `?date=${date}` : ''}`)
export const getPosSale = (id: number) => getJson<PosSaleDetail>(`/api/pos/sales/${id}`)
export const listPosReturns = () => getJson<PosReturn[]>('/api/pos/returns')
export const createPosReturn = (b: { saleId: number; reason: string; lines: { saleLineNo: number; quantity: number }[] }) => send<PosReturn>('POST', '/api/pos/returns', b)
export const decidePosReturn = (id: number, kind: 'approve' | 'reject', note?: string) => send<PosReturn>('POST', `/api/pos/returns/${id}/${kind}`, { note })

// ---- fixed assets (guide phase 15) --------------------------------------------------------------------------------------
export interface AssetCategory { id: number; name: string; assetAccount: string; accumAccount: string; expenseAccount: string; lifeMonths: number; salvagePercent: number; active: boolean }
export interface AssetSummary { id: number; assetNo: string; tag: string; name: string; categoryId: number; category: string; location: string; acquisitionDate: string; inServiceDate: string; cost: number; salvageValue: number; lifeMonths: number; status: 'ACTIVE' | 'DISPOSED'; disposedOn: string | null; booked: number; posted: number; netBookValue: number }
export interface AssetTransfer { id: number; transferNo: string; from: string; to: string; reason: string; status: 'PENDING' | 'APPROVED' | 'REJECTED'; requestedBy: string; decidedBy: string | null; decisionNote: string | null }
export interface AssetMaint { id: number; date: string; description: string; cost: number; vendor: string | null; nextDue: string | null; recordedBy: string }
export interface AssetDetail { summary: AssetSummary; serialNo: string | null; reference: string | null; supplierId: number | null; depreciation: { periodEnd: string; amount: number; journal: string | null; journalStatus: string | null }[]; transfers: AssetTransfer[]; maintenance: AssetMaint[] }
export interface AssetDisposal { id: number; disposalNo: string; assetId: number; assetNo: string; asset: string; disposalDate: string; kind: string; proceeds: number; bookValue: number; gainLoss: number; reason: string; status: 'PENDING' | 'APPROVED' | 'REJECTED'; requestedBy: string; decidedBy: string | null; decisionNote: string | null; journal: string | null }
export interface DepPreview { through: string; periods: { periodEnd: string; reference: string; assets: number; total: number; referenceUsed: boolean }[]; assets: number; total: number }
export interface DepRun { periodEnd: string; reference: string; journalId: number; assets: number; total: number }
export interface DepHistory { periodEnd: string; assets: number; total: number; journal: string | null; journalStatus: string | null }
export type NewAsset = { tag: string; name: string; categoryId: number; serialNo?: string; location: string; acquisitionDate: string; inServiceDate?: string | null; cost: number; salvageValue?: number | null; lifeMonths?: number | null; supplierId?: number | null; reference?: string }
export const listAssetCategories = () => getJson<AssetCategory[]>('/api/assets/categories')
export const saveAssetCategory = (id: number | null, b: Partial<Omit<AssetCategory, 'id'>>) => (id ? send<AssetCategory>('PUT', `/api/assets/categories/${id}`, b) : send<AssetCategory>('POST', '/api/assets/categories', b))
export const listAssets = (q?: string, status?: string) => getJson<AssetSummary[]>(`/api/assets?${q ? `q=${encodeURIComponent(q)}&` : ''}${status ? `status=${status}` : ''}`)
export const getAsset = (id: number) => getJson<AssetDetail>(`/api/assets/${id}`)
export const registerAsset = (b: NewAsset) => send<AssetSummary>('POST', '/api/assets', b)
export const askAssetTransfer = (id: number, toLocation: string, reason: string) => send<AssetTransfer>('POST', `/api/assets/${id}/transfers`, { toLocation, reason })
export const listAssetTransfers = (status?: string) => getJson<AssetTransfer[]>(`/api/assets/transfers${status ? `?status=${status}` : ''}`)
export const decideAssetTransfer = (id: number, kind: 'approve' | 'reject', note?: string) => send<AssetTransfer>('POST', `/api/assets/transfers/${id}/${kind}`, { note })
export const addAssetMaintenance = (id: number, b: { date?: string; description: string; cost?: number; vendor?: string; nextDue?: string | null }) => send<AssetMaint>('POST', `/api/assets/${id}/maintenance`, b)
export const listAssetMaintenanceDue = (days = 30) => getJson<{ assetId: number; assetNo: string; name: string; location: string; nextDue: string; lastWork: string }[]>(`/api/assets/maintenance/due?days=${days}`)
export const listAssetDisposals = () => getJson<AssetDisposal[]>('/api/assets/disposals')
export const askAssetDisposal = (assetId: number, b: { disposalDate: string; kind: string; proceeds: number; proceedsAccount?: string; gainAccount?: string; reason: string }) => send<AssetDisposal>('POST', `/api/assets/disposals/for/${assetId}`, b)
export const decideAssetDisposal = (id: number, kind: 'approve' | 'reject', note?: string) => send<AssetDisposal>('POST', `/api/assets/disposals/${id}/${kind}`, { note })
export const previewDepreciation = (periodEnd: string) => send<DepPreview>('POST', '/api/assets/depreciation/preview', { periodEnd })
export const runDepreciation = (periodEnd: string) => send<DepRun[]>('POST', '/api/assets/depreciation/run', { periodEnd })
export const listDepreciationRuns = () => getJson<DepHistory[]>('/api/assets/depreciation')
