export interface Member {
  id: { branchCode: number; clientId: number }
  firstName: string | null
  lastName: string | null
  accountName: string | null
  dateOpened: string | null
  accountStatus: number | null
  cellNumber: string | null
  city: string | null
  closedDate: string | null
  updatedAt: string | null
  middleName: string | null
  suffix: string | null
  clientType: number | null
  dateOfBirth: string | null
  gender: number | null
  civilStatus: number | null
  email: string | null
  street: string | null
  barangay: string | null
  province: string | null
  referenceId: string | null
  accountType: number | null
  alertLevel: number | null
  department: number | null
  profession: string | null
  religion: number | null
  mailingAddress: number | null
  dosriTag: number | null
  includeHealthCare: number | null
  monthlyIncome: string | null
  zipCode: string | null
  residenceSince: number | null
  residenceType: number | null
  provStreet: string | null
  provBarangay: string | null
  provCity: string | null
  provProvince: string | null
}

export interface Page<T> {
  content: T[]
  totalElements: number
  number: number
  size: number
}

// ---- sign-in ------------------------------------------------------------------------------------------------------
// HTTP Basic until Keycloak arrives. The credentials are kept for this window's session only (cleared when it closes).

const SESSION_KEY = 'coop.session'
let credentials: string | null = (() => {
  try { return sessionStorage.getItem(SESSION_KEY) } catch { return null }
})()

/** Fired when the server rejects the saved sign-in, or the person signs out. */
export const SIGNED_OUT = 'coop:signed-out'

const authHeader = () => (credentials ? `Basic ${credentials}` : '')

export function signOut() {
  credentials = null
  try { sessionStorage.removeItem(SESSION_KEY) } catch { /* storage unavailable */ }
  window.dispatchEvent(new Event(SIGNED_OUT))
}

/** Every request goes through here: it adds the sign-in, and signs out if the server no longer accepts it. */
async function http(url: string, init: RequestInit = {}): Promise<Response> {
  const res = await fetch(url, { ...init, headers: { Authorization: authHeader(), ...init.headers } })
  if (res.status === 401 && credentials) signOut()
  return res
}

export interface Me {
  username: string
  name: string
  roles: string[]
  canEdit: boolean
  canApprove: boolean
  admin: boolean
}

export async function signIn(username: string, password: string): Promise<Me> {
  const token = btoa(unescape(encodeURIComponent(`${username}:${password}`)))
  const res = await fetch('/api/me', { headers: { Authorization: `Basic ${token}` } })
  if (res.status === 401) throw new Error('The username or password is wrong.')
  if (!res.ok) throw new Error(`Sign-in failed (${res.status}).`)
  credentials = token
  try { sessionStorage.setItem(SESSION_KEY, token) } catch { /* storage unavailable */ }
  return res.json()
}

/** The signed-in person, or null when there is no saved sign-in or the server no longer accepts it. */
export async function restoreSession(): Promise<Me | null> {
  if (!credentials) return null
  const res = await http('/api/me')
  return res.ok ? res.json() : null
}

export async function searchMembers(q: string, page: number, size: number): Promise<Page<Member>> {
  const params = new URLSearchParams({ q, page: String(page), size: String(size) })
  const res = await http(`/api/members?${params}`)
  if (!res.ok) throw new Error(`Request failed (${res.status})`)
  return res.json()
}

export interface Account {
  id: { branchCode: number; code: string }
  description: string | null
  accountType: number | null
  level: string | null
  summaryAccount: string | null
  balanceType: number | null
  disabled: number | null
}

export async function listAccounts(branchCode: number): Promise<Account[]> {
  const res = await http(`/api/accounts?branchCode=${branchCode}`)
  if (!res.ok) throw new Error(`Request failed (${res.status})`)
  return res.json()
}

export async function getMember(branchCode: number, clientId: number): Promise<Member> {
  const res = await http(`/api/members/${branchCode}/${clientId}`)
  if (!res.ok) throw new Error(`Request failed (${res.status})`)
  return res.json()
}

export interface TrialBalanceLine {
  accountCode: string
  description: string | null
  debit: number
  credit: number
}

export interface TrialBalance {
  branchCode: number
  from: string
  to: string
  lines: TrialBalanceLine[]
  totalDebit: number
  totalCredit: number
  balanced: boolean
}

export async function getTrialBalance(branchCode: number, from: string, to: string): Promise<TrialBalance> {
  const params = new URLSearchParams({ branchCode: String(branchCode), from, to })
  const res = await http(`/api/trial-balance?${params}`)
  if (!res.ok) throw new Error(`Request failed (${res.status})`)
  return res.json()
}

/** Descriptions for the numeric codes on a member, keyed by lookup name then code. */
export type Lookups = Record<string, Record<string, string>>

export async function getLookups(): Promise<Lookups> {
  const res = await http('/api/lookups')
  if (!res.ok) throw new Error(`Request failed (${res.status})`)
  return res.json()
}

/** Fetches a member's photo or signature as an object URL, or null when none is on file. */
export async function getMemberImage(kind: 'photo' | 'signature', branchCode: number, clientId: number): Promise<string | null> {
  const res = await http(`/api/members/${branchCode}/${clientId}/${kind}`)
  if (res.status === 404) return null
  if (!res.ok) throw new Error(`Request failed (${res.status})`)
  return URL.createObjectURL(await res.blob())
}

async function getJson<T>(url: string): Promise<T> {
  const res = await http(url)
  if (!res.ok) throw new Error(`Request failed (${res.status})`)
  return res.json()
}

/** One labelled value on a profile tab. `kind` says how to format it. */
export interface DetailField {
  label: string
  value: string | null
  kind: 'text' | 'date' | 'money' | 'year'
  /** For coded fields: the lookup list to choose from and the stored code. */
  lookup?: string | null
  code?: string | null
  /** The column this field writes to, and whether it may be edited. */
  key: string
  editable: boolean
}
export interface DetailSection { title: string; fields: DetailField[] }
/** Sections for each profile tab, keyed by tab name (business, education, contacts, spouse, groups, closed, pledges, others). */
export type MemberDetails = Record<string, DetailSection[]>

export const getMemberDetails = (branchCode: number, clientId: number) =>
  getJson<MemberDetails>(`/api/members/${branchCode}/${clientId}/details`)

export interface HistoryEntry { at: string; user: string | null; activity: string | null; info: string | null; from: string | null; to: string | null }
export const HISTORY_PAGE = 25
export const getMemberHistory = (branchCode: number, clientId: number, page: number) =>
  getJson<HistoryEntry[]>(`/api/members/${branchCode}/${clientId}/history?page=${page}`)

export interface BillingEntry { reference: string | null; date: string | null; amount: string | null; type: string | null; status: string | null; policy: string | null }
export const getMemberBilling = (branchCode: number, clientId: number) =>
  getJson<BillingEntry[]>(`/api/members/${branchCode}/${clientId}/billing`)

/** What this person may do: edit (propose changes) and approve (decide them, Membership Officers only). */
export const getCapabilities = () => getJson<{ edit: boolean; approve: boolean }>('/api/capabilities')

// ---- member changes (maker-checker) --------------------------------------------------------------------------------

export interface ChangeItem {
  column: string
  label: string
  from: string | null
  to: string | null
  fromText: string | null
  toText: string | null
}

export interface Change {
  id: number
  branchCode: number
  clientId: number
  clientName: string | null
  requestedBy: string
  requestedByName: string
  requestedAt: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN'
  items: ChangeItem[]
  reason: string | null
  decidedBy: string | null
  decidedAt: string | null
  decisionNote: string | null
}

async function send<T>(method: string, url: string, body?: unknown): Promise<T> {
  const res = await http(url, {
    method,
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!res.ok) {
    let message = `Request failed (${res.status})`
    try {
      const j = await res.json()
      if (j.message) message = j.message
    } catch { /* keep the generic message */ }
    throw new Error(message)
  }
  const text = await res.text() // some replies have no body
  return (text ? JSON.parse(text) : undefined) as T
}

/**
 * Proposes changed fields, keyed by column name. Nothing changes until a Membership Officer approves.
 * `expectedUpdatedAt` is the record's stamp when it was opened, so the server can refuse a request made on old data.
 */
export const submitChange = (branchCode: number, clientId: number, body: { changes: Record<string, string>; expectedUpdatedAt: string | null }) =>
  send<Change>('POST', `/api/members/${branchCode}/${clientId}/changes`, body)

/** The change waiting for approval on this client, or null. */
export async function getPendingChange(branchCode: number, clientId: number): Promise<Change | null> {
  const res = await http(`/api/members/${branchCode}/${clientId}/changes/pending`)
  if (res.status === 204) return null
  if (!res.ok) throw new Error(`Request failed (${res.status})`)
  return res.json()
}

export const listChanges = (status = 'PENDING') => getJson<Change[]>(`/api/changes?status=${status}`)
export const getPendingCount = () => getJson<{ pending: number }>('/api/changes/count')
export const approveChange = (id: number, note?: string) => send<Change>('POST', `/api/changes/${id}/approve`, { note })
export const rejectChange = (id: number, note: string) => send<Change>('POST', `/api/changes/${id}/reject`, { note })
export const withdrawChange = (id: number) => send<Change>('POST', `/api/changes/${id}/withdraw`)

// ---- user administration (administrators only) --------------------------------------------------------------------

export interface AdminUser {
  username: string
  name: string | null
  level: string | null
  enabled: boolean
  canEdit: boolean
  hasPassword: boolean
  roles: string[]
}

export const listUsers = () => getJson<AdminUser[]>('/api/admin/users')
export const setUserPassword = (username: string, password: string) =>
  send<void>('PUT', `/api/admin/users/${encodeURIComponent(username)}/password`, { password })
export const grantRole = (username: string, role: string) => send<void>('PUT', `/api/admin/users/${encodeURIComponent(username)}/roles/${role}`)
export const revokeRole = (username: string, role: string) => send<void>('DELETE', `/api/admin/users/${encodeURIComponent(username)}/roles/${role}`)

// ---- a person's legacy account setup (administrators only, read-only) ---------------------------------------------

export interface UserOption { value: string; label: string }
export interface UserField {
  label: string
  /** What to show; for choices and lists, the descriptions. */
  value: string | null
  kind: 'text' | 'date' | 'money' | 'number' | 'bool' | 'list'
  /** The column this field writes to, whether it may be edited now, the stored value, and the choices if any. */
  key: string
  editable: boolean
  raw?: string | null
  options?: UserOption[]
}
export interface UserSection { title: string; fields: UserField[] }
export interface UserProfile {
  username: string
  name: string
  enabled: boolean
  hasPassword: boolean
  roles: string[]
  /** Whether this person's account can be edited by you right now, and if not, why. */
  canEdit: boolean
  editNote?: string | null
  /** Sections for each tab: general, transactions, pos, client, receivables, deposits, others. */
  tabs: Record<string, UserSection[]>
}
export interface TransactionPermissions {
  columns: string[]
  keys: string[]
  canEdit: boolean
  editNote?: string | null
  rows: { code: number; name: string; allowed: boolean[] }[]
}

export const getUserProfile = (username: string) => getJson<UserProfile>(`/api/admin/users/${encodeURIComponent(username)}/profile`)
export const getUserTransactionTypes = (username: string) =>
  getJson<TransactionPermissions>(`/api/admin/users/${encodeURIComponent(username)}/transaction-types`)

/** Saves changed fields of a person's account setup. Each change carries the value it started from, so a clash is caught. */
export const saveUserProfile = (username: string, changes: Record<string, { from: string | null; to: string | null }>) =>
  send<{ changed: number }>('PATCH', `/api/admin/users/${encodeURIComponent(username)}/profile`, { changes })

export const saveUserTransactionTypes = (username: string, changes: { code: number; column: string; allowed: boolean }[]) =>
  send<{ changed: number }>('PUT', `/api/admin/users/${encodeURIComponent(username)}/transaction-types`, { changes })

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
}
export interface FoHandoverMine { ready: boolean; notReady: string | null; drawers: FoDrawer[]; headCashier: boolean; incoming: FoHandover[]; outgoing: FoHandover[]; today: FoHandover[] }
export interface FoHandoverIssue {
  fromEvent: number; toEvent: number; toNumber: number | null; amount: number; counts: Record<number, number>; remarks: string
  /** Made once per attempt so a repeated click or retry cannot post twice; the reason is needed to return cash to the vault before the day is ready to close. */
  requestKey: string; overrideReason?: string
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
export const acceptFoHandover = (id: number, counts: Record<number, number>) => send<FoHandover>('POST', `/api/front-office/handover/${id}/accept`, { counts })
export const cancelFoHandover = (id: number) => send<FoHandover>('POST', `/api/front-office/handover/${id}/cancel`)
export const getFoDuplicates = (clientId: number, date: string, amount: string) =>
  getJson<FoSummary[]>(`/api/front-office/duplicates?clientId=${clientId}&date=${date}&amount=${encodeURIComponent(amount)}`)

// ---- accounting: journals, periods, statements ---------------------------------------------------------------------

export interface JournalSummary {
  id: number
  branchCode: number
  reference: string
  journalDate: string
  description: string | null
  status: 'DRAFT' | 'FOR_REVIEW' | 'APPROVED' | 'POSTED'
  createdBy: string
  approvedBy: string | null
  total: number
}
export interface JournalLineIn { accountCode: string; debit: number; credit: number }
export interface JournalDetail { summary: JournalSummary; lines: JournalLineIn[] }

export const listJournals = (branchCode: number) => send<JournalSummary[]>('GET', `/api/journals?branchCode=${branchCode}`)
export const getJournal = (id: number) => send<JournalDetail>('GET', `/api/journals/${id}`)
export const createJournal = (body: { branchCode: number; reference: string; journalDate: string; description: string; lines: JournalLineIn[] }) =>
  send<JournalSummary>('POST', '/api/journals', body)
export const journalStep = (id: number, step: 'submit' | 'approve' | 'post') => send<JournalSummary>('POST', `/api/journals/${id}/${step}`)
export const reverseJournal = (id: number, reason: string) => send<JournalSummary>('POST', `/api/journals/${id}/reverse`, { reason })

export type PeriodStatus = 'OPEN' | 'SOFT_CLOSED' | 'CLOSED' | 'LOCKED'
export interface AccountingPeriod { id: number; branchCode: number; name: string; startDate: string; endDate: string; status: PeriodStatus }

export const listPeriods = (branchCode: number) => send<AccountingPeriod[]>('GET', `/api/accounting-periods?branchCode=${branchCode}`)
export const createPeriod = (body: { branchCode: number; name: string; startDate: string; endDate: string }) =>
  send<AccountingPeriod>('POST', '/api/accounting-periods', body)
export const setPeriodStatus = (id: number, status: PeriodStatus) => send<AccountingPeriod>('POST', `/api/accounting-periods/${id}/status`, { status })

export interface StatementLine { accountCode: string; description: string; amount: number }
export interface StatementGroup { name: string; lines: StatementLine[]; total: number }
export interface StatementSection { name: string; groups: StatementGroup[]; total: number }
export interface FinancialCondition {
  branchCode: number; asOf: string; sections: StatementSection[]
  totalAssets: number; totalLiabilitiesAndEquity: number; balanced: boolean
}
export interface OperationsStatement { branchCode: number; from: string; to: string; sections: StatementSection[]; netSurplus: number }

export const getFinancialCondition = (branchCode: number, asOf: string) =>
  send<FinancialCondition>('GET', `/api/financial-statements/financial-condition?branchCode=${branchCode}&asOf=${asOf}`)
export const getOperations = (branchCode: number, from: string, to: string) =>
  send<OperationsStatement>('GET', `/api/financial-statements/operations?branchCode=${branchCode}&from=${from}&to=${to}`)

// ---- share capital (read-only) -------------------------------------------------------------------------------------

export interface ShareAccount {
  typeCode: number; type: string | null; refNo: number; refNoText: string | null; opened: string | null
  status: number; statusName: string; minimumBalance: number | null; balance: number; lastActivity: string | null
}
export interface ShareSummary { accounts: ShareAccount[]; totalBalance: number }
export interface ShareEntry { date: string; glDate: string | null; transactionCode: number; transaction: string | null; documentNo: string | null; amount: number; balanceAfter: number }
export interface ShareHistory { account: ShareAccount; entries: ShareEntry[]; page: number; size: number; totalEntries: number }

export const getShares = (branchCode: number, clientId: number) => send<ShareSummary>('GET', `/api/members/${branchCode}/${clientId}/shares`)
export const getShareHistory = (branchCode: number, clientId: number, typeCode: number, refNo: number, page: number, size: number) =>
  send<ShareHistory>('GET', `/api/members/${branchCode}/${clientId}/shares/${typeCode}/${refNo}?page=${page}&size=${size}`)

export interface SharePaymentIn { clientId: number; typeCode: number; refNo: number; amount: number; explanation?: string; receiptNo?: string; confirmDuplicate?: boolean }
export interface SharePaymentReceipt {
  branchCode: number; year: number; controlNo: number; documentNo: string; date: string; clientId: number; clientName: string
  account: string; amount: number; balanceAfter: number; cashier: string
}
/** Posts a cash payment into a share capital account (a Cash Receipt in the legacy ledger). */
export const postSharePayment = (body: SharePaymentIn) => send<SharePaymentReceipt>('POST', '/api/front-office/share-payments', body)

export interface ShareWithdrawalIn { clientId: number; typeCode: number; refNo: number; amount: number; explanation?: string }
export interface ShareWithdrawalOut { branchCode: number; year: number; controlNo: number; documentNo: string; clientId: number; clientName: string; amount: number; availableAfter: number }
/** Encodes a share capital withdrawal as a Cash Voucher; it is approved and posted on the Disburse tab. */
export const postShareWithdrawal = (body: ShareWithdrawalIn) => send<ShareWithdrawalOut>('POST', '/api/front-office/share-withdrawals', body)

export interface BankCashBank { bankId: number; name: string; ledgerAccount: string; event: number }
export interface BankCashIn { bankId: number; amount: number; counts: Record<number, number>; remarks?: string }
export interface BankCashOut { year: number; controlNo: number; documentNo: string; bank: string; amount: number; vaultAfter: number }
export const getBankCashBanks = () => send<BankCashBank[]>('GET', '/api/front-office/bank-cash/banks')
export const postBankDeposit = (body: BankCashIn) => send<BankCashOut>('POST', '/api/front-office/bank-cash/deposits', body)
export const postBankWithdrawal = (body: BankCashIn) => send<BankCashOut>('POST', '/api/front-office/bank-cash/withdrawals', body)

// ---- savings deposits (read-only) ----------------------------------------------------------------------------------

export interface SavingsAccount {
  typeCode: number; type: string | null; refNo: number; refNoText: string | null; opened: string | null; status: number; statusName: string
  interestRate: number | null; minimumBalance: number | null; balance: number; held: number; available: number; lastActivity: string | null
}
export interface SavingsSummary { accounts: SavingsAccount[]; totalBalance: number; totalHeld: number }
export interface SavingsHold { reason: string | null; description: string | null; amount: number; date: string | null; reference: string | null }
export interface SavingsHistory { account: SavingsAccount; holds: SavingsHold[]; entries: ShareEntry[]; page: number; size: number; totalEntries: number }

export const getSavings = (branchCode: number, clientId: number) => send<SavingsSummary>('GET', `/api/members/${branchCode}/${clientId}/savings`)
export const getSavingsHistory = (branchCode: number, clientId: number, typeCode: number, refNo: number, page: number, size: number) =>
  send<SavingsHistory>('GET', `/api/members/${branchCode}/${clientId}/savings/${typeCode}/${refNo}?page=${page}&size=${size}`)

export interface SavingsCashIn { clientId: number; typeCode: number; refNo: number; amount: number; explanation?: string; receiptNo?: string; confirmDuplicate?: boolean }
export interface SavingsReceipt {
  kind: 'DEPOSIT' | 'WITHDRAWAL'; branchCode: number; year: number; controlNo: number; documentNo: string; date: string; clientId: number; clientName: string
  account: string; amount: number; balanceAfter: number; cashier: string
}
export interface SavingsNeed { kind: string; title: string; description: string; amount: number; limit: number | null; status: 'NONE' | 'PENDING' | 'APPROVED' | 'REJECTED' }
export interface SavingsWithdrawalCheck { problems: string[]; needs: SavingsNeed[]; ready: boolean }

export const postSavingsDeposit = (b: SavingsCashIn) => send<SavingsReceipt>('POST', '/api/front-office/savings/deposits', b)
export const checkSavingsWithdrawal = (b: SavingsCashIn) => send<SavingsWithdrawalCheck>('POST', '/api/front-office/savings/withdrawals/check', b)
export const postSavingsWithdrawal = (b: SavingsCashIn) => send<SavingsReceipt>('POST', '/api/front-office/savings/withdrawals', b)

// ---- banks and reconciliation --------------------------------------------------------------------------------------

export interface BankAccount {
  id: number; name: string; accountNo: string | null; ledgerAccount: string; bookBalance: number; lastActivity: string | null
  reconciledThrough: string | null; lastStatementBalance: number | null
}
export interface BankKey { year: number; module: number; code: number; ctlNo: number; seq: number }
export interface BankEntry { key: BankKey; date: string; transaction: string | null; documentNo: string | null; explanation: string | null; reference: string | null; amount: number; balanceAfter: number }
export interface BankStatement { bank: BankAccount; from: string; to: string; opening: number; entries: BankEntry[]; closing: number; truncated: boolean }
export interface BankSheetItem { key: BankKey; date: string; transaction: string | null; documentNo: string | null; explanation: string | null; reference: string | null; amount: number; outstanding: boolean }
export interface BankSheet {
  bank: BankAccount; statementDate: string; bookBalance: number; from: string; previousId: number | null; previousDate: string | null
  draftId: number | null; draftStatementBalance: number | null; items: BankSheetItem[]
}
export interface BankRecon {
  id: number; bankId: number; bank: string | null; statementDate: string; statementBalance: number; bookBalance: number; outstandingTotal: number; difference: number
  reconciled: boolean; status: 'DRAFT' | 'FINAL'; note: string | null; preparedBy: string; preparedAt: string; finalizedBy: string | null; finalizedAt: string | null; outstandingCount: number
}

export const listBanks = (branchCode: number) => send<BankAccount[]>('GET', `/api/banks?branchCode=${branchCode}`)
export const getBankEntries = (branchCode: number, bankId: number, from: string, to: string) =>
  send<BankStatement>('GET', `/api/banks/${bankId}/entries?branchCode=${branchCode}&from=${from}&to=${to}`)
export const getBankSheet = (branchCode: number, bankId: number, statementDate: string) =>
  send<BankSheet>('GET', `/api/banks/${bankId}/reconciliation-sheet?branchCode=${branchCode}&statementDate=${statementDate}`)
export const saveBankRecon = (branchCode: number, bankId: number, body: { statementDate: string; statementBalance: number; outstanding: BankKey[]; note?: string }) =>
  send<BankRecon>('POST', `/api/banks/${bankId}/reconciliations?branchCode=${branchCode}`, body)
export const listBankRecons = (branchCode: number, bankId: number) => send<BankRecon[]>('GET', `/api/banks/${bankId}/reconciliations?branchCode=${branchCode}`)
export const finalizeBankRecon = (id: number) => send<BankRecon>('POST', `/api/banks/reconciliations/${id}/finalize`)

// ---- loans (read-only) ---------------------------------------------------------------------------------------------

export interface LoanItem {
  typeCode: number; type: string | null; refNo: number; status: number; statusName: string; amount: number; date: string | null; terms: number; termUnit: string | null; paymentMode: string | null
  interestRate: number | null; amortization: string | null; firstDue: string | null; maturity: string | null; purpose: string | null; collateral: string | null
  collateralAmount: number | null; restructured: boolean; renewals: number; outstanding: number; principalPaid: number; interestPaid: number
  pastDuePrincipal: number; daysPastDue: number; nextDueDate: string | null; nextDuePrincipal: number | null; nextDueInterest: number | null; lastPayment: string | null
}
export interface LoanSummary { loans: LoanItem[]; totalOutstanding: number; totalPastDue: number; asOf: string }
export interface LoanScheduleRow { dueDate: string; principalDue: number; interestDue: number; principalBalance: number; state: 'PAID' | 'PARTIAL' | 'PAST_DUE' | 'UPCOMING'; principalPaid: number }
export interface LoanLedgerEntry { date: string; event: number; eventName: string | null; transactionCode: number; transaction: string | null; documentNo: string | null; debit: number; credit: number; principalBalance: number | null }
export interface LoanDetail {
  loan: LoanItem; remarks: string | null; coMakers: { clientId: number | null; name: string | null; address: string | null }[]
  schedule: LoanScheduleRow[]; entries: LoanLedgerEntry[]; entriesTruncated: boolean
}

export const getLoans = (branchCode: number, clientId: number, includePaid: boolean) =>
  send<LoanSummary>('GET', `/api/members/${branchCode}/${clientId}/loans?includePaid=${includePaid}`)
export const getLoanDetail = (branchCode: number, clientId: number, typeCode: number, refNo: number) =>
  send<LoanDetail>('GET', `/api/members/${branchCode}/${clientId}/loans/${typeCode}/${refNo}`)

export interface LoanPaymentIn { clientId: number; typeCode: number; refNo: number; amount: number; explanation?: string; receiptNo?: string; confirmDuplicate?: boolean }
export interface LoanPaymentBreakdown {
  outstanding: number; interestFrom: string; days: number; interestDue: number; payoff: number; pastDuePrincipal: number; daysPastDue: number; graceDays: number
  interest: number; principal: number; balanceAfter: number; problems: string[]; ready: boolean
}
export interface LoanPaymentReceipt {
  branchCode: number; year: number; controlNo: number; documentNo: string; date: string; clientId: number; clientName: string; loan: string
  amount: number; interest: number; principal: number; balanceAfter: number; cashier: string
}
export const checkLoanPayment = (b: LoanPaymentIn) => send<LoanPaymentBreakdown>('POST', '/api/front-office/loan-payments/check', b)
export const postLoanPayment = (b: LoanPaymentIn) => send<LoanPaymentReceipt>('POST', '/api/front-office/loan-payments', b)

export interface LoanProfile {
  header: { clientNo: string; lastName: string | null; firstName: string | null; middleName: string | null; suffix: string | null; dateOpened: string | null; accountType: string | null; jointType: string; clientType: string | null; accountStatus: string | null }
  setup: {
    loanType: string | null; classification: string | null; refNo: number; principal: number; balance: number; loanDate: string | null; termGraceDays: number; terms: number; termUnit: string | null; paymentMode: string | null
    status: string; interestRate: number; basedOn: string | null; diminish: string; principalGraceDue: number; prepaidPayments: number; penaltyRate: number | null; penaltyAfterMaturity: number | null
    penaltyComputed: string | null; penaltyGraceDays: number; insurance: number | null; dueDateOption: string | null; firstInterestDue: string | null; firstPrincipalDue: string | null; maturity: string | null
    collectionType: string | null; loanClass: string | null; purpose: string | null
  }
  coMakers: { clientId: number | null; name: string | null; address: string | null }[]
  collateral: { type: string | null; amount: number | null; description: string | null } | null
  pledges: { profile: string; amount: number | null; clientPledge: number | null }[]
  remarks: string | null
  scheduleCheck: { checked: boolean; matches: boolean; note: string }
}
export const getLoanProfile = (branchCode: number, clientId: number, typeCode: number, refNo: number) =>
  getJson<LoanProfile>(`/api/members/${branchCode}/${clientId}/loans/${typeCode}/${refNo}/profile`)

export interface LoanReleaseVoucher {
  branchCode: number; year: number; controlNo: number; documentNo: string; date: string; clientId: number; explanation: string; loanAmount: number
  encodedBy: number; approvedBy: number | null; status: 'WAITING FOR APPROVAL' | 'APPROVED' | 'POSTED'; encodedByName: string | null; approvedByName: string | null; clientName: string | null
}
export interface LoanReleaseLine { seq: number; account: string; slc: number | null; slt: number | null; refNo: number | null; event: number | null; cts: number | null; amount: number; dept: number }
export interface LoanReleaseDetail { voucher: LoanReleaseVoucher; lines: LoanReleaseLine[]; problems: string[] }
export const getLoanReleases = () => getJson<LoanReleaseVoucher[]>('/api/front-office/loan-releases/pending')
export const getLoanRelease = (year: number, ctl: number) => getJson<LoanReleaseDetail>(`/api/front-office/loan-releases/${year}/${ctl}`)
export const approveLoanRelease = (year: number, ctl: number) => send<LoanReleaseDetail>('POST', `/api/front-office/loan-releases/${year}/${ctl}/approve`)
export const postLoanRelease = (year: number, ctl: number) => send<LoanReleaseDetail>('POST', `/api/front-office/loan-releases/${year}/${ctl}/post`)

export interface LoanAppTemplate {
  typeCode: number; typeName: string; terms: number; termUnit: number; paymentMode: number; interestRate: number; hasVouchersPayable: boolean; nextRefNo: number
  deductions: { id: number; description: string; behavior: number; formula: string | null; needsMemberAccount: boolean; hasMemberAccount: boolean }[]
  purposes: { id: number; name: string }[]; collateralTypes: { id: number; name: string }[]
  payoffs: { typeCode: number; typeName: string | null; refNo: number; status: number; outstanding: number; interestDue: number; daysPastDue: number }[]
}
export interface LoanAppIn {
  typeCode: number; amount: number; terms: number | null; purpose: number | null; loanClass: number | null; borrowerClass: number | null; collateralType: number | null
  collateralAmount: number | null; collateralDescription: string | null; remarks: string | null; coMakers: number[]; deductions: { id: number; amount: number }[]
  payoffs: { typeCode: number; refNo: number; principal: number; interest: number; penalty: number }[]
}
export interface LoanAppCreated { applicationId: number; documentNo: string; refNo: number; firstDue: string; maturity: string; totalInterest: number; totalDeductions: number; netProceeds: number; scheduleRows: number }
export const getLoanAppTemplate = (b: number, c: number, type: number) => getJson<LoanAppTemplate>(`/api/members/${b}/${c}/loan-applications/template?type=${type}`)
export const createLoanApplication = (b: number, c: number, body: LoanAppIn) => send<LoanAppCreated>('POST', `/api/members/${b}/${c}/loan-applications`, body)

export interface LoanApplicationItem { id: number; documentNo: string; clientId: number; clientName: string | null; typeCode: number; typeName: string | null; refNo: number; amount: number; terms: number; status: number; statusName: string; encodedByName: string | null; date: string }
export interface LoanApplicationDetail { item: LoanApplicationItem; lines: { account: string | null; description: string; amount: number }[]; totalDeductions: number; netProceeds: number; problems: string[] }
export const getLoanApplications = () => getJson<LoanApplicationItem[]>('/api/loan-applications/pending')
export const getLoanApplication = (id: number) => getJson<LoanApplicationDetail>(`/api/loan-applications/${id}`)
export const verifyLoanApplication = (id: number) => send<LoanApplicationItem>('POST', `/api/loan-applications/${id}/verify`)
export const approveLoanApplication = (id: number) => send<{ applicationId: number; year: number; voucherControlNo: number; voucherDocumentNo: string }>('POST', `/api/loan-applications/${id}/approve`)

export interface SingleHandGrant { username: string; reason: string; grantedBy: string; grantedAt: string }
export const listSingleHand = () => getJson<SingleHandGrant[]>('/api/admin/single-hand-loans')
export const grantSingleHand = (username: string, reason: string) => send<SingleHandGrant>('PUT', `/api/admin/single-hand-loans/${encodeURIComponent(username)}`, { reason })
export const revokeSingleHand = (username: string) => send<void>('DELETE', `/api/admin/single-hand-loans/${encodeURIComponent(username)}`)
export const cancelLoanApplication = (id: number, reason: string) => send<LoanApplicationItem>('POST', `/api/loan-applications/${id}/cancel`, { reason })
export const disapproveLoanApplication = (id: number, reason: string) => send<LoanApplicationItem>('POST', `/api/loan-applications/${id}/disapprove`, { reason })
