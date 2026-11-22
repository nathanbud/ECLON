import { getJson, http, send, errorFrom } from './http'

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
  if (!res.ok) throw new Error(await errorFrom(res))
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

export interface Branch { code: number; name: string | null }
export const listBranches = () => getJson<Branch[]>('/api/branches')

/** Accounts that take postings (detail, switched on), by number or name; for the account pickers. */
export const searchPostingAccounts = (branchCode: number, q: string) =>
  getJson<{ code: string; description: string }[]>(`/api/accounts/search?branchCode=${branchCode}&q=${encodeURIComponent(q)}`)

export async function getTrialBalance(branchCode: number, from: string, to: string): Promise<TrialBalance> {
  const params = new URLSearchParams({ branchCode: String(branchCode), from, to })
  const res = await http(`/api/trial-balance?${params}`)
  if (!res.ok) throw new Error(await errorFrom(res))
  return res.json()
}

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
/**
 * controlAccountReason: only for a journal that changes a control account's balance (the server refuses one otherwise): an administrator's written
 * reason, such as an opening balance or an approved correction. Audited.
 */
export const createJournal = (body: { branchCode: number; reference: string; journalDate: string; description: string; lines: JournalLineIn[]; controlAccountReason?: string | null }) =>
  send<JournalSummary>('POST', '/api/journals', body)
export const journalStep = (id: number, step: 'submit' | 'approve' | 'post') => send<JournalSummary>('POST', `/api/journals/${id}/${step}`)
export const reverseJournal = (id: number, reason: string) => send<JournalSummary>('POST', `/api/journals/${id}/reverse`, { reason })

// ---- year-end close -------------------------------------------------------------------------------------------------

export interface YearEndDepartment { dept: number; income: number; expenses: number; surplus: number; lines: number }
export interface YearEndClose {
  id: number
  branchCode: number
  year: number
  status: 'PREPARED' | 'POSTED' | 'CANCELLED'
  surplus: number
  lineCount: number
  preparedBy: string
  preparedAt: string
  decidedBy: string | null
  decidedAt: string | null
  reason: string | null
  controlNo: number | null
}
export interface YearEndPreview {
  branchCode: number
  year: number
  closingDate: string
  surplusAccount: string
  income: number
  expenses: number
  surplus: number
  lineCount: number
  departments: YearEndDepartment[]
  problems: string[]
  waiting: YearEndClose | null
}

export const listYearEndCloses = (branchCode: number) =>
  send<{ lastClosedYear: number | null; closes: YearEndClose[] }>('GET', `/api/year-end-close?branchCode=${branchCode}`)
export const previewYearEndClose = (branchCode: number, year: number) =>
  send<YearEndPreview>('GET', `/api/year-end-close/preview?branchCode=${branchCode}&year=${year}`)
export const prepareYearEndClose = (branchCode: number, year: number) => send<YearEndClose>('POST', '/api/year-end-close', { branchCode, year })
export const postYearEndClose = (id: number) => send<YearEndClose>('POST', `/api/year-end-close/${id}/post`)
export const cancelYearEndClose = (id: number, reason: string) => send<YearEndClose>('POST', `/api/year-end-close/${id}/cancel`, { reason })

export type PeriodStatus = 'OPEN' | 'SOFT_CLOSED' | 'CLOSED' | 'LOCKED'
export interface AccountingPeriod { id: number; branchCode: number; name: string; startDate: string; endDate: string; status: PeriodStatus }

export const listPeriods = (branchCode: number) => send<AccountingPeriod[]>('GET', `/api/accounting-periods?branchCode=${branchCode}`)
export const createPeriod = (body: { branchCode: number; name: string; startDate: string; endDate: string }) =>
  send<AccountingPeriod>('POST', '/api/accounting-periods', body)
export const setPeriodStatus = (id: number, status: PeriodStatus) => send<AccountingPeriod>('POST', `/api/accounting-periods/${id}/status`, { status })

export interface StatementLine { accountCode: string; description: string; amount: number }
export interface StatementGroup { name: string; lines: StatementLine[]; total: number }
export interface StatementSection { name: string; groups: StatementGroup[]; total: number }
/** The unit a statement is for (a main office, branch, satellite office or consolidation, with everything under it); null for the whole branch. */
export interface StatementUnit { id: number; name: string; kind: string; kindName: string; units: number }
export interface FinancialCondition {
  branchCode: number; asOf: string; unit: StatementUnit | null; sections: StatementSection[]
  totalAssets: number; totalLiabilitiesAndEquity: number; balanced: boolean
}
export interface OperationsStatement { branchCode: number; from: string; to: string; unit: StatementUnit | null; sections: StatementSection[]; netSurplus: number }
/** A unit to make statements for: the department tree with each unit's kind (suggested from the name while `kindChosen` is false). */
export interface UnitChoice { id: number; name: string; parent: number | null; kind: string; kindName: string; kindChosen: boolean; disabled: boolean; children: number }
export interface ConsolidatingColumn { id: number; name: string; kind: string; kindName: string; ownEntries: boolean }
export interface ConsolidatingLine { accountCode: string; description: string; amounts: number[]; total: number }
export interface ConsolidatingGroup { name: string; lines: ConsolidatingLine[]; totals: number[]; total: number }
export interface ConsolidatingSection { name: string; groups: ConsolidatingGroup[]; totals: number[]; total: number }
export interface ConsolidatingStatement {
  branchCode: number; statement: 'financial-condition' | 'operations'; asOf: string | null; from: string | null; to: string | null
  unit: ConsolidatingColumn; columns: ConsolidatingColumn[]; sections: ConsolidatingSection[]; bottomName: string; bottom: number[]; bottomTotal: number
}

const FS = '/api/financial-statements'
const unitParam = (department?: number | null) => (department != null ? `&department=${department}` : '')
export const getFinancialCondition = (branchCode: number, asOf: string, department?: number | null) =>
  send<FinancialCondition>('GET', `${FS}/financial-condition?branchCode=${branchCode}&asOf=${asOf}${unitParam(department)}`)
export const getOperations = (branchCode: number, from: string, to: string, department?: number | null) =>
  send<OperationsStatement>('GET', `${FS}/operations?branchCode=${branchCode}&from=${from}&to=${to}${unitParam(department)}`)
export const getStatementUnits = () => getJson<UnitChoice[]>(`${FS}/units`)
/** Which finished years are summed (for the whole branch and by unit), and whether a build is running ('running', 'done', 'idle' or 'failed: …'). */
export interface BalanceStatus { state: string; builtYears: number[]; builtYearsByUnit: number[] }
export const getBalanceStatus = (branchCode: number) => getJson<BalanceStatus>(`${FS}/balances/status?branchCode=${branchCode}`)
/** Starts summing every finished year not summed yet, in the background (administrators). */
export const buildBalances = (branchCode: number) => send<{ state: string }>('POST', `${FS}/balances/build?branchCode=${branchCode}`)
/** Each unit directly under `department` side by side, the consolidation's own entries, and the consolidated total. */
export const getConsolidating = (branchCode: number, department: number, q: { asOf: string } | { from: string; to: string }) =>
  getJson<ConsolidatingStatement>(`${FS}/consolidating?branchCode=${branchCode}&department=${department}&` +
    ('asOf' in q ? `statement=financial-condition&asOf=${q.asOf}` : `statement=operations&from=${q.from}&to=${q.to}`))

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

// ---- chart of accounts (administrators and accountants) -------------------------------------------------------------

export interface ChartAccount {
  code: string; name: string | null; type: number; typeName: string | null; summary: boolean; parent: string | null
  disabled: boolean; glOnly: boolean; hasChildren: boolean; inUse: boolean
}
export interface ChartUsage { code: string; entries: number; children: number; usedBy: string[] }
export interface ChartForm { code?: string; name?: string; summary?: boolean; parent?: string | null; glOnly?: boolean; disabled?: boolean; reason: string }

const CHART = '/api/accounting/chart'
export const getChart = () => getJson<ChartAccount[]>(CHART)
export const getChartUsage = (code: string) => getJson<ChartUsage>(`${CHART}/${encodeURIComponent(code)}/usage`)
export const createChartAccount = (f: ChartForm) => send<ChartAccount>('POST', CHART, f)
export const updateChartAccount = (code: string, f: ChartForm) => send<ChartAccount>('PUT', `${CHART}/${encodeURIComponent(code)}`, f)

// ---- chart of accounts: uploading a list (advanced mode, administrators) --------------------------------------------

export interface ChartImportRow { line: number; code: string; name: string; state: 'NEW' | 'EXISTS' | 'PROBLEM'; note: string | null }
export interface ChartImportPreview { fileName: string; total: number; ready: number; existing: number; errors: number; ignoredColumns: string[]; rows: ChartImportRow[] }
export interface ChartImportDone { fileName: string; added: number; existing: number; leftOut: number }

async function sendChartFile<T>(url: string, file: File, fields: Record<string, string> = {}): Promise<T> {
  const body = new FormData()
  body.append('file', file, file.name)
  for (const [k, v] of Object.entries(fields)) body.append(k, v)
  const res = await http(url, { method: 'POST', body })
  if (!res.ok) throw new Error(await errorFrom(res))
  return res.json() as Promise<T>
}
export const previewChartImport = (file: File) => sendChartFile<ChartImportPreview>(`${CHART}/import/preview`, file)
export const uploadChartImport = (file: File, reason: string, skipErrors: boolean) =>
  sendChartFile<ChartImportDone>(`${CHART}/import`, file, { reason, skipErrors: String(skipErrors) })
export async function downloadChartImportTemplate(kind: 'xlsx' | 'csv' = 'xlsx'): Promise<void> {
  const res = await http(kind === 'xlsx' ? `${CHART}/import/template` : `${CHART}/import/template.csv`)
  if (!res.ok) throw new Error(await errorFrom(res))
  const url = URL.createObjectURL(await res.blob())
  const a = document.createElement('a')
  a.href = url
  a.download = `chart-of-accounts-template.${kind}`
  a.click()
  URL.revokeObjectURL(url)
}

// ---- account types outside loans: savings, time deposit, share capital, receivable and payable types (administrators) ----

export interface AccountType {
  classId: number; code: number; name: string | null; fullName: string | null; priority: number | null; interestRate: number | null; minBalance: number | null
  maxBalance: number | null; dormantAfterMonths: number | null; disabled: boolean; accounts: number | null; links: number
}
export type AccountTypeField = 'interestRate' | 'minBalance' | 'maxBalance' | 'dormantAfterMonths'

export interface AccountTypeClass { id: number; name: string; managed: 'EDIT' | 'LOAN_SETUP' | 'CASH'; fields: AccountTypeField[]; types: AccountType[] }
export interface AccountTypeLink { event: number; what: string; number: number; statusName: string | null; account: string | null; accountName: string | null; accountActive: boolean; removable: boolean }
export interface AccountTypeForm {
  name: string; fullName: string; priority: number | null; interestRate: number | null; minBalance: number | null; maxBalance: number | null; dormantAfterMonths: number | null
  disabled: boolean; reason: string
}

const TYPES = '/api/admin/account-types'
export const getAccountTypes = () => getJson<AccountTypeClass[]>(TYPES)
export const updateAccountType = (cls: number, code: number, f: AccountTypeForm) => send<AccountType>('PUT', `${TYPES}/${cls}/${code}`, f)
export const createAccountType = (cls: number, copyFrom: number, f: AccountTypeForm) => send<AccountType>('POST', `${TYPES}/${cls}?copyFrom=${copyFrom}`, f)
export const getAccountTypeLinks = (cls: number, code: number) => getJson<AccountTypeLink[]>(`${TYPES}/${cls}/${code}/accounts`)
export const changeAccountTypeLink = (cls: number, code: number, event: number, number: number, account: string, reason: string) =>
  send<AccountTypeLink[]>('PUT', `${TYPES}/${cls}/${code}/accounts`, { event, number, account, reason })
export interface AccountTypeLinkOptions { events: { id: number; name: string }[]; statuses: { id: number; name: string }[] }
export const getAccountTypeLinkOptions = (cls: number) => getJson<AccountTypeLinkOptions>(`${TYPES}/${cls}/link-options`)
export interface LinkSuggestion { account: string; accountName: string | null; why: string }
/** Chart accounts to suggest for a ledger link, best first, with why. Leave `type` out for a type not made yet and give the name typed so far. */
export const getLinkSuggestions = (cls: number, event: number, number: number, type?: number, name?: string) =>
  getJson<LinkSuggestion[]>(`${TYPES}/${cls}/link-suggestions?event=${event}&number=${number}${type != null ? `&type=${type}` : ''}${name ? `&name=${encodeURIComponent(name)}` : ''}`)
export const addAccountTypeLink =(cls: number, code: number, event: number, number: number, account: string, reason: string) =>
  send<AccountTypeLink[]>('POST', `${TYPES}/${cls}/${code}/accounts`, { event, number, account, reason })
export const removeAccountTypeLink = (cls: number, code: number, event: number, number: number, reason: string) =>
  send<AccountTypeLink[]>('DELETE', `${TYPES}/${cls}/${code}/accounts?event=${event}&number=${number}&reason=${encodeURIComponent(reason)}`)
export interface AccountTypeDeleteCheck { deletable: boolean; problems: string[]; links: number }
/** Whether a type may be deleted: only one nothing ever used (no accounts, entries or settings point at it). */
export const checkAccountTypeDelete = (cls: number, code: number) => getJson<AccountTypeDeleteCheck>(`${TYPES}/${cls}/${code}/delete-check`)
export const deleteAccountType = (cls: number, code: number, reason: string) =>
  send<{ deleted: boolean; name: string | null; links: number }>('DELETE', `${TYPES}/${cls}/${code}?reason=${encodeURIComponent(reason)}`)
export const createFirstAccountType = (cls: number, f: { name: string; fullName: string; account: string; reason: string }) => send<AccountType>('POST', `${TYPES}/${cls}/first`, f)

export interface CashDrawer { event: number; kind: 'Cashier' | 'Teller'; number: number; account: string | null; accountName: string | null; heldBy: string[] }
export const getCashDrawers = () => getJson<CashDrawer[]>(`${TYPES}/cash/drawers`)
export const addCashDrawer = (event: number, number: number, reason: string) => send<CashDrawer[]>('POST', `${TYPES}/cash/drawers`, { event, number, reason })

// ---- dormant savings accounts ----------------------------------------------------------------------------------------

export interface DormancyRow { clientId: number; clientName: string; typeCode: number; typeName: string | null; refNo: number; accountNo: string | null; opened: string | null; lastActivity: string | null; balance: number }
export interface DormancyReview { asOf: string; types: { typeCode: number; typeName: string | null; months: number | null }[]; rows: DormancyRow[]; more: boolean }
export interface DormancyKey { clientId: number; typeCode: number; refNo: number }
const DORMANCY = '/api/savings-dormancy'
export const getDormancyReview = () => getJson<DormancyReview>(`${DORMANCY}/review`)
export const markDormant = (accounts: DormancyKey[], reason: string) => send<{ marked: number; skipped: string[] }>('POST', `${DORMANCY}/mark`, { accounts, reason })
export const findDormant = (q: string) => getJson<DormancyRow[]>(`${DORMANCY}/dormant?q=${encodeURIComponent(q)}`)
export const reactivateDormant = (k: DormancyKey, reason: string) => send<DormancyRow>('POST', `${DORMANCY}/reactivate`, { ...k, reason })

// ---- back office: the old system's transaction types ---------------------------------------------------------------

/** A back-office transaction type of the old system: entry is GJ or DCM when the Back Office window makes it, madeElsewhere says where else it is made. */
export interface BackOfficeType { id: number; name: string; code: string; entries: number; lastUsed: string | null; entry: 'GJ' | 'DCM' | 'CV' | 'CKV' | null; madeElsewhere: string | null }
/** Module 1: General Journal Transactions; module 2: Disbursement Transactions. */
export const listBackOfficeTypes = (module: 1 | 2 = 1) => send<BackOfficeType[]>('GET', `/api/backoffice/types?module=${module}`)
/** What the Disbursement Transactions window needs: the account a Cash Voucher credits, the person's department, and whether they may make each voucher. */
export interface VoucherSetup { payableAccount: string | null; payableAccountName: string | null; dept: number | null; mayCashVoucher: boolean; mayCheckVoucher: boolean; problem: string | null }
export const getVoucherSetup = () => send<VoucherSetup>('GET', '/api/backoffice/voucher-setup')
