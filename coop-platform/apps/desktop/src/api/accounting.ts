import { getJson, http, send } from './http'

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
