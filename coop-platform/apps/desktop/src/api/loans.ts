import { type ChequeIn } from './deposits'
import { getJson, http, send, errorFrom } from './http'

// ---- loans (read-only) ---------------------------------------------------------------------------------------------

export interface LoanItem {
  typeCode: number; type: string | null; refNo: number; status: number; statusName: string; amount: number; date: string | null; terms: number; termUnit: string | null; paymentMode: string | null
  interestRate: number | null; amortization: string | null; firstDue: string | null; maturity: string | null; purpose: string | null; collateral: string | null
  collateralAmount: number | null; restructured: boolean; renewals: number; outstanding: number; principalPaid: number; interestPaid: number
  pastDuePrincipal: number; daysPastDue: number; nextDueDate: string | null; nextDuePrincipal: number | null; nextDueInterest: number | null; lastPayment: string | null
}
export interface LoanSummary { loans: LoanItem[]; totalOutstanding: number; totalPastDue: number; asOf: string }
export interface LoanScheduleRow { dueDate: string; principalDue: number; interestDue: number; principalBalance: number; state: 'PAID' | 'PARTIAL' | 'PAST_DUE' | 'UPCOMING'; principalPaid: number }
export interface LoanLedgerEntry { date: string; event: number; eventName: string | null; transactionCode: number; transaction: string | null; documentNo: string | null; debit: number; credit: number; principalBalance: number | null
  /** A payment made by cheque: the cheque, whether it cleared and the day it cleared when that was recorded. */
  chequeNo?: string | null; chequeCleared?: boolean | null; clearedOn?: string | null
  /** The control number of the transaction behind the row; with the year of the date and the transaction code it opens the transaction. */
  controlNo?: number | null }
export interface LoanDetail {
  loan: LoanItem; remarks: string | null; coMakers: { clientId: number | null; name: string | null; address: string | null }[]
  schedule: LoanScheduleRow[]; entries: LoanLedgerEntry[]; entriesTruncated: boolean
}

export const getLoans = (branchCode: number, clientId: number, includePaid: boolean) =>
  send<LoanSummary>('GET', `/api/members/${branchCode}/${clientId}/loans?includePaid=${includePaid}`)
export const getLoanDetail = (branchCode: number, clientId: number, typeCode: number, refNo: number) =>
  send<LoanDetail>('GET', `/api/members/${branchCode}/${clientId}/loans/${typeCode}/${refNo}`)

export interface LoanPaymentIn { clientId: number; typeCode: number; refNo: number; amount: number; explanation?: string; receiptNo?: string; confirmDuplicate?: boolean; penalty?: number; cheques?: ChequeIn[] }
export interface LoanPaymentBreakdown {
  outstanding: number; interestFrom: string; days: number; interestDue: number; payoff: number; pastDuePrincipal: number; daysPastDue: number; graceDays: number
  suggestedPenalty: number | null; penalty: number; approval: { kind: string; title: string; description: string; amount: number; status: 'NONE' | 'PENDING' | 'APPROVED' | 'REJECTED' } | null
  interest: number; principal: number; balanceAfter: number; problems: string[]; ready: boolean
}
export interface LoanPaymentReceipt {
  branchCode: number; year: number; controlNo: number; documentNo: string; date: string; clientId: number; clientName: string; loan: string
  amount: number; penalty: number; interest: number; principal: number; balanceAfter: number; cashier: string
}
export const checkLoanPayment = (b: LoanPaymentIn) => send<LoanPaymentBreakdown>('POST', '/api/front-office/loan-payments/check', b)
export const postLoanPayment = (b: LoanPaymentIn, key?: string) => send<LoanPaymentReceipt>('POST', '/api/front-office/loan-payments', b, key)

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
  setup: { termUnit: string | null; principalPayment: string | null; interestPayment: string | null; basedOn: string | null; diminish: string | null; penaltyRate: number | null; yearDays: number | null; collateralRequired: boolean; unsupported: string | null }
}
export interface LoanAppIn {
  typeCode: number; amount: number; terms: number | null; termUnit: number | null; purpose: number | null; loanClass: number | null; borrowerClass: number | null; collateralType: number | null
  collateralAmount: number | null; collateralDescription: string | null; remarks: string | null; coMakers: number[]; deductions: { id: number; amount: number }[]
  payoffs: { typeCode: number; refNo: number; principal: number; interest: number; penalty: number }[]
  coMakerHolds: { clientId: number; amount: number; slc: number; slt: number; refNo: number }[]
}
export interface LoanAppCreated { applicationId: number; documentNo: string; refNo: number; firstDue: string; maturity: string; totalInterest: number; totalDeductions: number; netProceeds: number; scheduleRows: number
  /** Saved by someone who may do Profiling only: someone who may do Deduction fills in the deductions before it can be verified. */
  waitingForDeductions: boolean }
export interface LoanTypeOption { code: number; name: string; terms: number | null; rate: number | null; loans: number; available: boolean }
export const getLoanTypes = (b: number, c: number) => getJson<LoanTypeOption[]>(`/api/members/${b}/${c}/loan-applications/types`)
export interface CoMakerAccount { slc: number; slt: number; refNo: number; type: string | null; balance: number; held: number; available: number; preferred: boolean }
export const getCoMakerAccounts = (b: number, c: number, comaker: number) => getJson<CoMakerAccount[]>(`/api/members/${b}/${c}/loan-applications/comaker-accounts?comaker=${comaker}`)
export const getLoanAppTemplate = (b: number, c: number, type: number) => getJson<LoanAppTemplate>(`/api/members/${b}/${c}/loan-applications/template?type=${type}`)
/** The Deduction step of an application saved without it. */
export const saveLoanDeductions = (b: number, c: number, applicationId: number, body: { deductions: { id: number; amount: number }[]; payoffs: { typeCode: number; refNo: number; principal: number; interest: number; penalty: number }[] }) =>
  send<{ applicationId: number; totalDeductions: number; netProceeds: number }>('POST', `/api/members/${b}/${c}/loan-applications/${applicationId}/deductions`, body)
export const createLoanApplication = (b: number, c: number, body: LoanAppIn) => send<LoanAppCreated>('POST', `/api/members/${b}/${c}/loan-applications`, body)

/**
 * The amortization schedule of the application on the form as a PDF (laid out like the old system's printout, with a page for the member to sign).
 * Nothing is saved; the server logs the print. refNo: the loan number once the application is saved, so it is printed on it.
 */
export async function getAmortizationPdf(b: number, c: number, body: LoanAppIn, refNo?: number): Promise<Blob> {
  const res = await http(`/api/members/${b}/${c}/loan-applications/amortization/pdf${refNo ? `?refNo=${refNo}` : ''}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  })
  if (!res.ok) throw new Error(await errorFrom(res))
  return res.blob()
}

export interface LoanApplicationItem { id: number; documentNo: string; clientId: number; clientName: string | null; typeCode: number; typeName: string | null; refNo: number; amount: number; terms: number; status: number; statusName: string; encodedByName: string | null; date: string; voucherYear: number | null; voucherControlNo: number | null; branchCode: number; waitingForDeductions: boolean }
export interface CoMakerHoldSaved { clientId: number; name: string | null; amount: number; slc: number; slt: number; refNo: number; accountType: string | null; placed: boolean }
export interface LoanApplicationDetail { item: LoanApplicationItem; lines: { account: string | null; description: string; amount: number }[]; totalDeductions: number; netProceeds: number; problems: string[]; coMakerHolds: CoMakerHoldSaved[] }
/** canAdd is 1 · Profiling, canDeduct 2 · Deduction. */
export interface LoanAppPermissions { canAdd: boolean; canVerify: boolean; canApprove: boolean; canDeduct: boolean }
/** The signed-in person's loan application flags from the old system (Settings > Users). */
export const getLoanAppPermissions = () => getJson<LoanAppPermissions>('/api/loan-applications/permissions')
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

// ---- loan setup (administrators) ----------------------------------------------------------------------------------

export interface LoanSetupType {
  code: number; name: string; fullName: string | null; terms: number | null; termUnit: number | null; principalMode: number | null; interestMode: number | null
  amortization: number | null; interestRate: number | null; penaltyRate: number | null; serviceFeeRate: number | null; minCoMakers: number | null
  requireCollateral: boolean; priority: number | null; yearDays: number | null; disabled: boolean; loans: number; applications: number; unavailable: string | null
  /** 1 = interest on 30 days a month, 2 = on the real days (matters for annuity). */
  dayCount: number | null
}
export type LoanSetupForm = Omit<LoanSetupType, 'code' | 'loans' | 'applications' | 'unavailable'> & { reason: string }
export interface SetupOption { id: number; name: string }
export interface LoanSetupLists { termUnits: SetupOption[]; paymentModes: SetupOption[]; amortizations: SetupOption[]; loanClasses: SetupOption[] }
export interface SetupNamed { id: number; name: string; loanClass: number | null; uses: number }

const SETUP = '/api/admin/loan-setup'
export const getLoanSetupTypes = () => getJson<LoanSetupType[]>(`${SETUP}/types`)
export const getLoanSetupLists = () => getJson<LoanSetupLists>(`${SETUP}/lists`)
export const createLoanType = (copyFrom: number, f: LoanSetupForm) => send<LoanSetupType>('POST', `${SETUP}/types?copyFrom=${copyFrom}`, f)
export interface WiredAccount { event: number; what: string; status: number | null; statusName: string | null; account: string; accountName: string | null; accountActive: boolean; remarks: string | null; removable: boolean }
export const getLoanTypeAccounts = (code: number) => getJson<WiredAccount[]>(`${SETUP}/types/${code}/accounts`)
export interface LinkIn { event: number; status: number | null; account: string; reason: string }
export const addLoanTypeLink = (code: number, body: LinkIn) => send<WiredAccount[]>('POST', `${SETUP}/types/${code}/accounts`, body)
export const changeLoanTypeLink = (code: number, body: LinkIn) => send<WiredAccount[]>('PUT', `${SETUP}/types/${code}/accounts`, body)
export const removeLoanTypeLink = (code: number, event: number, status: number | null, reason: string) =>
  send<WiredAccount[]>('DELETE', `${SETUP}/types/${code}/accounts?event=${event}${status != null ? `&status=${status}` : ''}&reason=${encodeURIComponent(reason)}`)
export const updateLoanType = (code: number, f: LoanSetupForm) => send<LoanSetupType>('PUT', `${SETUP}/types/${code}`, f)
export const getSetupPurposes = () => getJson<SetupNamed[]>(`${SETUP}/purposes`)
export const getSetupCollateral = () => getJson<SetupNamed[]>(`${SETUP}/collateral-types`)
export const saveSetupNamed = (kind: 'purposes' | 'collateral-types', id: number | null, v: { name: string; loanClass: number | null; reason: string }) =>
  send<SetupNamed>(id == null ? 'POST' : 'PUT', id == null ? `${SETUP}/${kind}` : `${SETUP}/${kind}/${id}`, v)
export const deleteSetupNamed = (kind: 'purposes' | 'collateral-types', id: number, reason: string) =>
  send<void>('DELETE', `${SETUP}/${kind}/${id}?reason=${encodeURIComponent(reason)}`)

// ---- co-maker holds: the note on a member's accounts, and the setup of which deposits can be held ------------------------------------------

export interface CoMakerPlaced { amount: number; slc: number; slt: number; refNo: number; accountType: string | null; borrower: string; reference: string | null; since: string | null }
export interface CoMakerPending { amount: number; slc: number; slt: number; refNo: number; accountType: string | null; borrower: string | null; applicationId: number }
export interface CoMakerNotice { placed: CoMakerPlaced[]; pending: CoMakerPending[]; totalHeld: number; totalPending: number }
export const getCoMakerNotice = (b: number, c: number) => getJson<CoMakerNotice>(`/api/members/${b}/${c}/comaker-holds`)

export interface HoldSetupRow { slc: number; slt: number; group: string; name: string; holdable: boolean; isDefault: boolean; holds: number }
export const getHoldSetup = () => getJson<HoldSetupRow[]>('/api/admin/hold-setup')
export const setHoldable = (slc: number, slt: number, holdable: boolean, reason: string) => send<HoldSetupRow>('PUT', `/api/admin/hold-setup/${slc}/${slt}`, { holdable, reason })
export const setHoldDefault = (slc: number, slt: number, reason: string) => send<HoldSetupRow>('PUT', '/api/admin/hold-setup/default', { slc, slt, reason })

// ---- amortization reference and preview (administrators) ------------------------------------------------------------

export interface AmortKind { id: number; name: string; supported: boolean; formula: string; types: string[]; loans: number }
export interface AmortTry { kind: number; amount: number; termMonths: number; paymentMode: number; ratePercent: number; release: string | null; dayCount: number | null }
export interface AmortRow { dueDate: string; principalDue: number; interestDue: number; principalBalance: number }
export interface AmortPreview { rows: AmortRow[]; payments: number; payment: number; totalInterest: number; totalPaid: number; maturity: string }

export const getAmortKinds = () => getJson<AmortKind[]>('/api/admin/loan-setup/amortization')
export const previewAmortization = (t: AmortTry) => send<AmortPreview>('POST', '/api/admin/loan-setup/amortization/preview', t)

// ---- penalty waivers -------------------------------------------------------------------------------------------------
export interface PenaltyWaived {
  at: string; receipt: string; cashier: string; clientId: number; client: string; typeCode: number; refNo: number
  paid: number; suggested: number; charged: number; waived: number; allowedBy: string | null
}
export interface PenaltyWaiverReport { from: string; to: string; rows: PenaltyWaived[]; total: number }
export interface PenaltyWaiverReq { clientId: number; typeCode: number; refNo: number; amount: number; expenseAccount: string; reason: string }
export interface PenaltyWaiverCheck { description: string; suggestedPenalty: number | null; approval: 'NONE' | 'PENDING' | 'APPROVED' | 'REJECTED'; problems: string[]; ready: boolean }
export interface PenaltyWaiverPending { id: number; description: string; amount: number; requested_at: string; requested_by: string }
export const getPenaltyWaiverReport = (from: string, to: string) => getJson<PenaltyWaiverReport>(`/api/loan-penalty-waivers/report?from=${from}&to=${to}`)
export const getWaiverExpenseAccounts = () => getJson<{ code: string; name: string }[]>('/api/loan-penalty-waivers/expense-accounts')
export const checkPenaltyWaiver = (b: PenaltyWaiverReq) => send<PenaltyWaiverCheck>('POST', '/api/loan-penalty-waivers/check', b)
export const askPenaltyWaiver = (b: PenaltyWaiverReq) => send<{ status: string }>('POST', '/api/loan-penalty-waivers/ask', b)
export const postPenaltyWaiver = (b: PenaltyWaiverReq) => send<{ year: number; controlNo: number; documentNo: string; amount: number }>('POST', '/api/loan-penalty-waivers/post', b)
export const getPenaltyWaiversPending = () => getJson<PenaltyWaiverPending[]>('/api/loan-penalty-waivers/pending')
export const decidePenaltyWaiver = (id: number, approve: boolean, note: string) => send<{ status: string }>('POST', `/api/loan-penalty-waivers/${id}/decide`, { approve, note })

/**
 * Fetches the loan's Interest and Penalty Computation Worksheet as of a date (a PDF or an Excel file) with the sign-in and hands it to the browser as a download.
 * The server writes each download to the audit log and the export log. Resolves to the file name.
 */
export async function downloadLoanWorksheet(branchCode: number, clientId: number, typeCode: number, refNo: number, asOf: string, kind: 'pdf' | 'xlsx'): Promise<string> {
  const res = await http(`/api/members/${branchCode}/${clientId}/loans/${typeCode}/${refNo}/worksheet/${kind}?asOf=${encodeURIComponent(asOf)}`)
  if (!res.ok) throw new Error(await errorFrom(res))
  const name = `loan-worksheet-12${String(typeCode).padStart(2, '0')}-${String(refNo).padStart(8, '0')}-${asOf}.${kind}`
  const url = URL.createObjectURL(await res.blob())
  const a = document.createElement('a')
  a.href = url
  a.download = name
  a.click()
  URL.revokeObjectURL(url)
  return name
}

// ---- loan type deductions: the lines an application of the type starts with (administrators) -------------------------

export type LoanDeductionKind = 'MEMBER_ACCOUNT' | 'LOAN_CHARGE' | 'CHART_ACCOUNT'
export interface LoanDeduction {
  id: number; description: string; order: number; kind: LoanDeductionKind | 'NET_PROCEEDS' | 'OTHER'; classId: number | null; typeCode: number | null; event: number | null
  account: string | null; target: string; formula: string | null; hidePrinting: boolean; editableTarget: boolean; removable: boolean
}
export interface LoanDeductionForm {
  description: string; order: number; kind: LoanDeductionKind | null; classId: number | null; typeCode: number | null; event: number | null; account: string | null
  hidePrinting: boolean; reason: string
}
export interface LoanDeductionOptions { memberTypes: { classId: number; typeCode: number; name: string }[]; charges: { event: number; name: string }[] }
const deductionsOf = (code: number) => `${SETUP}/types/${code}/deductions`
export const getLoanDeductions = (code: number) => getJson<LoanDeduction[]>(deductionsOf(code))
export const getLoanDeductionOptions = (code: number) => getJson<LoanDeductionOptions>(`${deductionsOf(code)}/options`)
export const addLoanDeduction = (code: number, f: LoanDeductionForm) => send<LoanDeduction[]>('POST', deductionsOf(code), f)
export const changeLoanDeduction = (code: number, id: number, f: LoanDeductionForm) => send<LoanDeduction[]>('PUT', `${deductionsOf(code)}/${id}`, f)
export const removeLoanDeduction = (code: number, id: number, reason: string) => send<LoanDeduction[]>('DELETE', `${deductionsOf(code)}/${id}?reason=${encodeURIComponent(reason)}`)
