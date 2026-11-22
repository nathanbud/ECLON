import { getJson, send } from './http'

// ---- front office (teller) window ---------------------------------------------------------------------------------

export interface FoTypeInfo { id: number; desc: string; canFind: boolean; canNew: boolean }
export interface FoTab { key: string; label: string; types: FoTypeInfo[] }
export interface FoDenom { id: number; kind: string; description: string; amount: number }
export interface FoSetup {
  user: string; systemDate: string; canAccess: boolean; branch: number; tabs: FoTab[]; denominations: FoDenom[]
  /** The person's own limits and default department from the legacy setup. */
  maxWithdrawal: number | null; disbursementLimit: number | null; defaultDept: number | null
  /** The ledger account a teller's cash goes through, and its name. */
  cashAccount: string; cashAccountDesc: string
}

export interface FoSummary {
  year: number; module: number; code: number; ctlNo: number; docNo: string | null; type: string; date: string; time: string | null
  clientId: number | null; clientName: string | null; batchNo: string | null; explanation: string | null; total: number; refNo: string | null
}
export interface FoEntry { seq: number; dept: number | null; accountCode: string; description: string | null; accountNo: string | null; debit: number; credit: number; reference: string | null }
export interface FoCheque { seq: number; type: string; checkNo: string | null; bank: number | null; checkDate: string | null; amount: number; payee: string | null; cleared: boolean }
export interface FoCash { kind: string; description: string; denomination: number; qty: number; amount: number }
export interface FoAllocation { label: string; amount: number }
export interface FoDetail {
  header: FoSummary; dept: number | null; entries: FoEntry[]; cheques: FoCheque[]; cash: FoCash[]; allocations: FoAllocation[]
  debit: number; credit: number; difference: number
}

export interface FoDraftLine { accountCode: string; accountDesc: string; acctNum: string; debit: string; credit: string; dept: number | null; slc?: number | null; slt?: number | null }
export interface FoDraftPayload {
  trCode: number; date: string; batchNo: string; dept: number | null; clientId: number | null; clientName: string; refNo: string
  explanation: string; lines: FoDraftLine[]; denominations: { typeId: number; qty: string }[]
}
export interface FoTotals { debit: number; credit: number; difference: number; balanced: boolean }
export interface FoDraft { id: number; payload: FoDraftPayload; totals: FoTotals; updatedAt: string }
export interface FoDraftRow { id: number; trCode: number; type: string; date: string; clientName: string | null; debit: number; balanced: boolean; updatedAt: string }

export const getFoSetup = () => getJson<FoSetup>('/api/front-office/setup')
export const listFoTransactions = (date: string, types: number[], q: string) => {
  const params = new URLSearchParams({ date, q })
  types.forEach((t) => params.append('types', String(t)))
  return getJson<FoSummary[]>(`/api/front-office/transactions?${params}`)
}
export const getFoTransaction = (year: number, module: number, code: number, ctlNo: number) =>
  getJson<FoDetail>(`/api/front-office/transactions/${year}/${module}/${code}/${ctlNo}`)
export const searchFoAccounts = (q: string) => getJson<{ code: string; description: string }[]>(`/api/front-office/accounts?q=${encodeURIComponent(q)}`)
export const listFoDrafts = () => getJson<FoDraftRow[]>('/api/front-office/drafts')
export const getFoDraft = (id: number) => getJson<FoDraft>(`/api/front-office/drafts/${id}`)
export const createFoDraft = (p: FoDraftPayload) => send<FoDraft>('POST', '/api/front-office/drafts', p)
export const updateFoDraft = (id: number, p: FoDraftPayload) => send<FoDraft>('PUT', `/api/front-office/drafts/${id}`, p)
export const deleteFoDraft = (id: number) => send<void>('DELETE', `/api/front-office/drafts/${id}`)

/** A member's account and the ledger account it posts to. */
export interface FoClientAccount { slc: number; slt: number; refNo: number; number: string | null; type: string; kind: string; glCode: string | null; glDesc: string | null }
/** One column of the teller's totals. */
export interface FoColumn { beginning: number; in: number; out: number; pendingIn: number; pendingOut: number; ending: number }
export interface FoTellerTotals { known: boolean; reason: string | null; number: number | null; role: string | null; person: string | null; date: string; cash: FoColumn; coci: FoColumn; total: FoColumn }
export const getFoClientAccounts = (clientId: number) => getJson<FoClientAccount[]>(`/api/front-office/clients/${clientId}/accounts`)
export const getFoTellerTotals = (date: string) => getJson<FoTellerTotals>(`/api/front-office/teller-totals?date=${date}`)

/** Cash handover: vault -> head cashier -> tellers and back. Moves with people wait to be counted; vault moves post at once. */
export interface FoDrawer {
  event: number; number: number; label: string; who: string; balance: number | null; pendingIn: number | null; pendingOut: number | null
  /** The most cash this drawer should hold (null for none), today's count (time and difference) when there was one, and whether it is the head cashier's. */
  limit: number | null; closedAt: string | null; variance: number | null; head: boolean
  /** Cheques held in this drawer (an amount only; cheques travel with the cash on a hand-off). */
  cheques: number | null
}
export interface FoLimit { event: number; number: number; maxCash: number }
export interface FoCloseResult { expected: number; counted: number; variance: number; closedAt: string }
export interface FoPulse { incoming: number; latestId: number; latestFrom: string | null; latestAmount: number | null; disputed: number; overrides: number }
export interface FoOverride {
  id: number; draftId: number | null; kind: string; description: string; amount: number; limitAmount: number | null; requestedBy: string
  requestedAt: string; status: 'PENDING' | 'APPROVED' | 'REJECTED'; decidedBy: string | null; note: string | null
}
export const getFoLimits = () => getJson<FoLimit[]>('/api/front-office/handover/limits')
export const setFoLimit = (l: FoLimit) => send<FoLimit[]>('PUT', '/api/front-office/handover/limits', l)
export const closeFoDrawer = (event: number, counts: Record<number, number>, reason: string) => send<FoCloseResult>('POST', '/api/front-office/handover/close', { event, counts, reason })
export const getFoPulse = () => getJson<FoPulse>('/api/front-office/handover/pulse')
export const refreshFoBalances = () => send<{ drawers: number }>('POST', '/api/front-office/handover/snapshot/refresh')
export const askFoOverride = (b: { draftId: number | null; kind: string; description: string; amount: number; limitAmount: number | null }) => send<FoOverride>('POST', '/api/front-office/overrides', b)
export const getFoOverrideForDraft = (draftId: number) => getJson<FoOverride | null>(`/api/front-office/overrides/for-draft?draftId=${draftId}`)
export const getFoOverridesPending = () => getJson<FoOverride[]>('/api/front-office/overrides/pending')
export const decideFoOverride = (id: number, approve: boolean, note: string) => send<FoOverride>('POST', `/api/front-office/overrides/${id}/decide`, { approve, note })
export interface FoHandover {
  id: number; trCode: number; from: string; to: string; amount: number; status: 'PENDING' | 'POSTED' | 'DISPUTED' | 'CANCELLED'; issuedBy: string; issuedAt: string
  remarks: string | null; counted: number | null; note: string | null; ctlNo: number | null; incoming: boolean; outgoing: boolean
  /** Cheques given together with the cash. */
  cheques: number
}
export interface FoHandoverMine { ready: boolean; notReady: string | null; drawers: FoDrawer[]; headCashier: boolean; incoming: FoHandover[]; outgoing: FoHandover[]; today: FoHandover[] }
export interface FoHandoverIssue {
  fromEvent: number; toEvent: number; toNumber: number | null; amount: number; counts: Record<number, number>; remarks: string
  /** Made once per attempt so a repeated click or retry cannot post twice; the reason is needed to return cash to the vault before the day is ready to close. */
  requestKey: string; overrideReason?: string
  /** Cheques given with (or instead of) the cash; the cash may then be zero. */
  cheques?: number
}
export interface FoSlipLine { kind: string; description: string; face: number; qty: number; amount: number }
export interface FoSlip {
  id: number; trCode: number; type: string; docNo: string | null; date: string; time: string; from: string; fromWho: string; to: string; toWho: string
  amount: number; status: FoHandover['status']; remarks: string | null; issuedBy: string; acceptedBy: string | null; counted: number | null; note: string | null; lines: FoSlipLine[]
}
export const getFoHandoverSlip = (id: number) => getJson<FoSlip>(`/api/front-office/handover/${id}/slip`)
export const getFoHandoverMine =() => getJson<FoHandoverMine>('/api/front-office/handover/mine')
export const getFoHandoverDrawers = () => getJson<FoDrawer[]>('/api/front-office/handover/drawers')
export const issueFoHandover = (b: FoHandoverIssue) => send<FoHandover>('POST', '/api/front-office/handover', b)
export const acceptFoHandover = (id: number, counts: Record<number, number>, cheques?: number) => send<FoHandover>('POST', `/api/front-office/handover/${id}/accept`, { counts, cheques })
export const cancelFoHandover = (id: number) => send<FoHandover>('POST', `/api/front-office/handover/${id}/cancel`)
export const getFoDuplicates = (clientId: number, date: string, amount: string) =>
  getJson<FoSummary[]>(`/api/front-office/duplicates?clientId=${clientId}&date=${date}&amount=${encodeURIComponent(amount)}`)

// ---- posting drafts (documentation/general-voucher-map.md) ------------------------------------------------------------

export type DraftPostKind = 'OTHER_RECEIPT' | 'MIXED_PAYMENT' | 'CASH_VOUCHER' | 'CHECK_VOUCHER'
export interface DraftPostNeed { kind: string; title: string; description: string; amount: number; limit: number | null; status: 'NONE' | 'PENDING' | 'APPROVED' | 'REJECTED' }
export interface DraftPostPlan {
  kind: DraftPostKind | null; kindWords: string | null; postsHow: string | null
  lines: { account: string; description: string; amount: number }[]; amount: number; drawerCash: number | null
  problems: string[]; needs: DraftPostNeed[]; ready: boolean
}
/** The cooperative's own cheque on a Check Voucher: the bank and its ledger account, number, date, payee. */
export interface OwnChequeIn { bankId: number | null; account: string | null; number: string; date: string; payee: string }
export interface DraftPostIn {
  cheques?: { number: string; bankCode: number | null; date: string | null; kind: number | null; amount: number | null }[]
  cheque?: OwnChequeIn | null; receiptNo?: string; confirmDuplicate?: boolean
}
export interface DraftPosted { kind: DraftPostKind; year: number; code: number; controlNo: number; documentNo: string; date: string; amount: number; status: string }
export const checkFoDraftPost = (id: number, b: DraftPostIn) => send<DraftPostPlan>('POST', `/api/front-office/drafts/${id}/check`, b)
export const postFoDraft = (id: number, b: DraftPostIn, key?: string) => send<DraftPosted>('POST', `/api/front-office/drafts/${id}/post`, b, key)

// ---- Cash and Check Vouchers waiting for approval ----------------------------------------------------------------------

export interface WaitingVoucher {
  year: number; code: 21 | 22; type: string; controlNo: number; documentNo: string; date: string; clientId: number; clientName: string | null; explanation: string
  amount: number; status: 'WAITING FOR APPROVAL' | 'APPROVED' | 'POSTED'; encodedBy: number; encodedByName: string | null; approvedBy: number | null; approvedByName: string | null
  cheque: { bankCode: number; bank: string; number: string; date: string; payee: string } | null
}
export interface WaitingVoucherDetail {
  voucher: WaitingVoucher; lines: { seq: number; account: string; description: string; slc: number | null; slt: number | null; refNo: number | null; amount: number }[]
  mayApprove: boolean; mayPost: boolean; problems: string[]
}
export const getWaitingVouchers = () => getJson<WaitingVoucher[]>('/api/front-office/vouchers/waiting')
export const getWaitingVoucher = (code: number, year: number, ctl: number) => getJson<WaitingVoucherDetail>(`/api/front-office/vouchers/${code}/${year}/${ctl}`)
export const approveWaitingVoucher = (code: number, year: number, ctl: number) => send<WaitingVoucherDetail>('POST', `/api/front-office/vouchers/${code}/${year}/${ctl}/approve`)
export const postWaitingVoucher = (code: number, year: number, ctl: number) => send<WaitingVoucherDetail>('POST', `/api/front-office/vouchers/${code}/${year}/${ctl}/post`)

// ---- opening the drawer at the start of the day ----------------------------------------------------------------------------

export interface DrawerOpenState { event: number; number: number; label: string; opened: boolean; openedAt: string | null; counted: number | null; difference: number | null; reason: string | null }
export interface DrawerOpenMine { required: boolean; policyOn: boolean; available: boolean; businessDate: string; drawers: DrawerOpenState[] }
export interface DrawerOpened { label: string; expected: number; counted: number; difference: number; openedAt: string }
export interface DrawerOpenRow { label: string; person: string; openedAt: string; expected: number; counted: number; difference: number; reason: string | null }
export const getDrawerOpen = () => getJson<DrawerOpenMine>('/api/front-office/drawer-open')
export const openDrawer = (event: number, counts: Record<number, number>, reason: string) => send<DrawerOpened>('POST', '/api/front-office/drawer-open', { event, counts, reason })
export const getDrawersOpenedToday = () => getJson<DrawerOpenRow[]>('/api/front-office/drawer-open/day')
export const setDrawerOpenPolicy = (on: boolean, reason: string) => send<DrawerOpenMine>('PUT', '/api/front-office/drawer-open/policy', { on, reason })
