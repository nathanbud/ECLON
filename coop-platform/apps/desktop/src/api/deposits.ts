import { type FoOverride } from './frontOffice'
import { getJson, http, send, errorFrom } from './http'

// ---- share capital (read-only) -------------------------------------------------------------------------------------

export interface ShareAccount {
  typeCode: number; type: string | null; refNo: number; refNoText: string | null; opened: string | null
  status: number; statusName: string; minimumBalance: number | null; balance: number; lastActivity: string | null; held: number
}
export interface ShareSummary { accounts: ShareAccount[]; totalBalance: number }
export interface ShareEntry { date: string; glDate: string | null; transactionCode: number; transaction: string | null; documentNo: string | null; amount: number; balanceAfter: number
  /** Money paid in by cheque (savings only): the cheque, whether it cleared and the day it cleared when that was recorded. */
  chequeNo?: string | null; chequeCleared?: boolean | null; clearedOn?: string | null
  /** The control number of the transaction behind the row; with the year of the date and the transaction code it opens the transaction. */
  controlNo?: number | null }
export interface ShareHistory { account: ShareAccount; entries: ShareEntry[]; page: number; size: number; totalEntries: number }

export const getShares = (branchCode: number, clientId: number) => send<ShareSummary>('GET', `/api/members/${branchCode}/${clientId}/shares`)
export const getShareHistory = (branchCode: number, clientId: number, typeCode: number, refNo: number, page: number, size: number) =>
  send<ShareHistory>('GET', `/api/members/${branchCode}/${clientId}/shares/${typeCode}/${refNo}?page=${page}&size=${size}`)

/** A cheque taken at the window; the part of a payment the cheques do not cover is cash. */
export interface ChequeIn { number: string; bankCode: number | null; date: string | null; kind: number | null; amount: number | null }
export interface ChequeSetup { kinds: { id: number; name: string; days: number }[]; banks: { id: number; name: string }[] }
export const getChequeSetup = () => send<ChequeSetup>('GET', '/api/front-office/cheque-setup')

export interface SharePaymentIn { clientId: number; typeCode: number; refNo: number; amount: number; explanation?: string; receiptNo?: string; confirmDuplicate?: boolean; cheques?: ChequeIn[] }
export interface SharePaymentReceipt {
  branchCode: number; year: number; controlNo: number; documentNo: string; date: string; clientId: number; clientName: string
  account: string; amount: number; balanceAfter: number; cashier: string
}
/** Posts a cash payment into a share capital account (a Cash Receipt in the legacy ledger). */
export const postSharePayment = (body: SharePaymentIn, key?: string) => send<SharePaymentReceipt>('POST', '/api/front-office/share-payments', body, key)

export interface ShareWithdrawalIn { clientId: number; typeCode: number; refNo: number; amount: number; explanation?: string }
export interface ShareWithdrawalOut { branchCode: number; year: number; controlNo: number; documentNo: string; clientId: number; clientName: string; amount: number; availableAfter: number }
/** Encodes a share capital withdrawal as a Cash Voucher; it is approved and posted on the Disburse tab. */
export const postShareWithdrawal = (body: ShareWithdrawalIn) => send<ShareWithdrawalOut>('POST', '/api/front-office/share-withdrawals', body)

export interface BankCashBank { bankId: number; name: string; ledgerAccount: string; event: number }
export interface BankCashIn { bankId: number; amount: number; counts: Record<number, number>; remarks?: string; /** Deposit the vault's cheques instead of cash (no count). */ cheques?: boolean }
export interface BankCashOut { year: number; controlNo: number; documentNo: string; bank: string; amount: number; vaultAfter: number }
export const getBankCashBanks = () => send<BankCashBank[]>('GET', '/api/front-office/bank-cash/banks')
export const postBankDeposit = (body: BankCashIn) => send<BankCashOut>('POST', '/api/front-office/bank-cash/deposits', body)
export const postBankWithdrawal = (body: BankCashIn) => send<BankCashOut>('POST', '/api/front-office/bank-cash/withdrawals', body)

// ---- savings deposits (read-only) ----------------------------------------------------------------------------------

export interface SavingsAccount {
  typeCode: number; type: string | null; refNo: number; refNoText: string | null; opened: string | null; status: number; statusName: string
  interestRate: number | null; minimumBalance: number | null; balance: number; held: number; available: number; lastActivity: string | null
  /** Cheque money not yet cleared: in the balance, not available. */ uncleared: number
}
export interface SavingsSummary { accounts: SavingsAccount[]; totalBalance: number; totalHeld: number }
export interface SavingsHold { reason: string | null; description: string | null; amount: number; date: string | null; reference: string | null }
export interface SavingsHistory { account: SavingsAccount; holds: SavingsHold[]; entries: ShareEntry[]; page: number; size: number; totalEntries: number }

export const getSavings = (branchCode: number, clientId: number) => send<SavingsSummary>('GET', `/api/members/${branchCode}/${clientId}/savings`)
export const getSavingsHistory = (branchCode: number, clientId: number, typeCode: number, refNo: number, page: number, size: number) =>
  send<SavingsHistory>('GET', `/api/members/${branchCode}/${clientId}/savings/${typeCode}/${refNo}?page=${page}&size=${size}`)

export interface SavingsCashIn { clientId: number; typeCode: number; refNo: number; amount: number; explanation?: string; receiptNo?: string; confirmDuplicate?: boolean; cheques?: ChequeIn[] }
export interface SavingsReceipt {
  kind: 'DEPOSIT' | 'WITHDRAWAL'; branchCode: number; year: number; controlNo: number; documentNo: string; date: string; clientId: number; clientName: string
  account: string; amount: number; balanceAfter: number; cashier: string
}
export interface SavingsNeed { kind: string; title: string; description: string; amount: number; limit: number | null; status: 'NONE' | 'PENDING' | 'APPROVED' | 'REJECTED' }
export interface SavingsWithdrawalCheck { problems: string[]; needs: SavingsNeed[]; ready: boolean }

export const postSavingsDeposit = (b: SavingsCashIn, key?: string) => send<SavingsReceipt>('POST', '/api/front-office/savings/deposits', b, key)
export const checkSavingsWithdrawal = (b: SavingsCashIn) => send<SavingsWithdrawalCheck>('POST', '/api/front-office/savings/withdrawals/check', b)
export const postSavingsWithdrawal = (b: SavingsCashIn, key?: string) => send<SavingsReceipt>('POST', '/api/front-office/savings/withdrawals', b, key)

// ---- cheque clearing -------------------------------------------------------------------------------------------------
export interface ChequeClearRow {
  year: number; ctlNo: number; seq: number; documentNo: string; receiptDate: string; client: string | null; number: string; bank: string | null
  chequeDate: string | null; kind: string | null; amount: number; clearsOn: string; due: boolean; cleared: boolean
  clearedOn: string | null; clearedBy: string | null; bankReference: string | null
}
export const getChequeClearing = () => getJson<ChequeClearRow[]>('/api/front-office/cheque-clearing')
export const clearCheques = (items: { year: number; ctlNo: number; seq: number }[], reason: string, clearedOn?: string, bankReference?: string) =>
  send<{ cleared: number; skipped: number }>('POST', '/api/front-office/cheque-clearing', { items, reason, clearedOn, bankReference })

// ---- returned cheques ---------------------------------------------------------------------------------------------------
export interface ChequeReturnCandidate {
  year: number; ctlNo: number; seq: number; documentNo: string; receiptDate: string; clientId: number; client: string; number: string; bank: string | null
  amount: number; typeCode: number | null; refNo: number | null; returned: boolean; problem: string | null
}
/** Where the cheque is now: with a cashier or teller (event 52 or 53 and the number), in the vault, or in a bank account. */
export interface ChequeReturnReq { year: number; ctlNo: number; seq: number; where: 'DRAWER' | 'VAULT' | 'BANK'; event?: number; number?: number; bankId?: number }
export interface ChequeReturnCheck { description: string; amount: number; approval: 'NONE' | 'PENDING' | 'APPROVED' | 'REJECTED'; problems: string[]; ready: boolean }
export const findReturnableCheques = (number: string) => getJson<ChequeReturnCandidate[]>(`/api/front-office/cheque-returns/find?number=${encodeURIComponent(number)}`)
export const checkChequeReturn = (b: ChequeReturnReq) => send<ChequeReturnCheck>('POST', '/api/front-office/cheque-returns/check', b)
export const askChequeReturn = (b: ChequeReturnReq) => send<FoOverride>('POST', '/api/front-office/cheque-returns/ask', b)
export const postChequeReturn = (b: ChequeReturnReq) => send<{ year: number; controlNo: number; documentNo: string; client: string; amount: number; where: string }>('POST', '/api/front-office/cheque-returns/post', b)

// ---- time deposits (read only) ---------------------------------------------------------------------------------------
export interface TimeDeposit {
  typeCode: number; type: string | null; refNo: number; certificate: string | null; placed: string | null; maturity: string | null; termDays: number; ratePercent: number | null
  principal: number | null; balance: number; accruedInterest: number; estimatedInterest: number | null; status: 'ACTIVE' | 'MATURED' | 'CLOSED' | 'NO_LEDGER'; remarks: string | null
}
export interface TimeDepositSummary { deposits: TimeDeposit[]; totalBalance: number; totalAccrued: number; asOf: string }
export interface TimeDepositEntry { date: string; glDate: string | null; transactionCode: number; transaction: string | null; documentNo: string | null; event: number; eventName: string; amount: number; balanceAfter: number
  chequeNo?: string | null; chequeCleared?: boolean | null; clearedOn?: string | null
  /** The control number of the transaction behind the row; with the year of the date and the transaction code it opens the transaction. */
  controlNo?: number | null }
export interface TimeDepositHistory { deposit: TimeDeposit; entries: TimeDepositEntry[]; page: number; size: number; totalEntries: number }
export const getTimeDeposits = (branchCode: number, clientId: number, includeClosed: boolean) =>
  send<TimeDepositSummary>('GET', `/api/members/${branchCode}/${clientId}/time-deposits?includeClosed=${includeClosed}`)
export const getTimeDepositHistory = (branchCode: number, clientId: number, typeCode: number, refNo: number, page: number, size: number) =>
  send<TimeDepositHistory>('GET', `/api/members/${branchCode}/${clientId}/time-deposits/${typeCode}/${refNo}?page=${page}&size=${size}`)

// ---- placing a time deposit --------------------------------------------------------------------------------------------
export interface TdTermRates { termDays: number; min: number; max: number; usual: number }
export interface TdTypeSetup { typeCode: number; name: string; minAmount: number; terms: TdTermRates[] }
export interface TdSetup { types: TdTypeSetup[]; certificateFee: number }
export interface TdPlaceIn {
  clientId: number; typeCode: number; termDays: number; ratePercent: number; amount: number; certificate: boolean; explanation?: string; receiptNo?: string
  confirmDuplicate?: boolean; cheques?: ChequeIn[]
}
export interface TdPlaceCheck {
  problems: string[]; maturity: string | null; estimatedInterest: number | null; fee: number; total: number
  rateDescription: string | null; rateApproval: 'NOT_NEEDED' | 'NONE' | 'PENDING' | 'APPROVED' | 'REJECTED'; ready: boolean
}
export interface TdPlaceReceipt {
  branchCode: number; year: number; controlNo: number; documentNo: string; date: string; clientId: number; clientName: string; type: string; certificate: number
  maturity: string; termDays: number; ratePercent: number; amount: number; fee: number; total: number; cashier: string
}
export const getTdSetup = () => send<TdSetup>('GET', '/api/front-office/time-deposits/setup')
export const checkTdPlacement = (b: TdPlaceIn) => send<TdPlaceCheck>('POST', '/api/front-office/time-deposits/check', b)
export const askTdRate = (b: TdPlaceIn) => send<FoOverride>('POST', '/api/front-office/time-deposits/ask', b)
export const placeTimeDeposit = (b: TdPlaceIn, key?: string) => send<TdPlaceReceipt>('POST', '/api/front-office/time-deposits', b, key)

// ---- paying out and renewing a matured time deposit, and the maturity notices ---------------------------------------------
export interface TdCloseReq {
  clientId: number; typeCode: number; refNo: number; mode: 'PAYOUT' | 'RENEW'; termDays?: number; ratePercent?: number; withdraw?: number; renewalFee?: boolean; explanation?: string
  newTypeCode?: number; interestYears?: number; pretermRate?: number; interestPaid?: number
}
export interface TdCloseQuote {
  mode: string; clientId: number; clientName: string; typeCode: number; type: string | null; refNo: number; certificate: string | null; maturity: string | null; termDays: number
  ratePercent: number | null; principal: number; interest: number; accrued: number; expense: number; due: number; newPrincipal: number | null; newMaturity: string | null
  fee: number | null; withdraw: number | null; terms: number[]; problems: string[]; ready: boolean
  lockIn: boolean; interestYears: number; maxYears: number; newTypeCode: number
  preterm: boolean; terminationFee: number; pretermRate: number | null; daysHeld: number
}
export interface TdCloseEncoded { branchCode: number; year: number; controlNo: number; documentNo: string; mode: string; clientId: number; clientName: string; due: number; newCertificate: number | null; newPrincipal: number | null; paidToPayable: number }
export interface TdInterestReq { clientId: number; typeCode: number; refNo: number; amount?: number; explanation?: string }
export interface TdInterestQuote {
  clientId: number; clientName: string; typeCode: number; type: string | null; refNo: number; certificate: string | null; balance: number; ratePercent: number | null; placed: string | null
  completedYears: number; yearInterest: number; withdrawn: number; available: number; accrued: number; amount: number; fromAccrual: number; expense: number; problems: string[]; ready: boolean
}
export const quoteTdInterest = (b: TdInterestReq) => send<TdInterestQuote>('POST', '/api/front-office/time-deposit-closures/interest/quote', b)
export const encodeTdInterest = (b: TdInterestReq) => send<TdCloseEncoded>('POST', '/api/front-office/time-deposit-closures/interest', b)
export const quoteTdClose = (b: TdCloseReq) => send<TdCloseQuote>('POST', '/api/front-office/time-deposit-closures/quote', b)
export const encodeTdClose = (b: TdCloseReq) => send<TdCloseEncoded>('POST', '/api/front-office/time-deposit-closures', b)

export interface TdNotice {
  clientId: number; client: string | null; typeCode: number; type: string | null; refNo: number; certificate: string | null; placed: string | null; maturity: string; daysLeft: number; termDays: number
  ratePercent: number | null; balance: number; accruedInterest: number; estimatedInterest: number; overdue: boolean; canClose: boolean
}
export interface TdNotices { asOf: string; days: number; notices: TdNotice[]; overdue: number; maturingSoon: number; totalBalance: number }
export const getTdMaturities = (days: number) => getJson<TdNotices>(`/api/time-deposits/maturities?days=${days}`)
export const getTdMaturityCount = () => getJson<number[]>('/api/time-deposits/maturities/count')

// ---- paying vouchers payable out in cash ---------------------------------------------------------------------------

export interface VoucherPayable { clientId: number; client: string | null; refNo: number; status: number; balance: number; lastCredit: string | null }
export interface VoucherPayoutCheck { payable: VoucherPayable; problems: string[]; needs: SavingsNeed[]; drawerCash: number; ready: boolean }
export interface VoucherPayoutReceipt { branchCode: number; year: number; controlNo: number; documentNo: string; date: string; clientId: number; clientName: string | null; refNo: number; amount: number; cashier: string }
export interface VoucherPayoutIn { clientId: number; refNo: number; explanation?: string; receiptNo?: string }

export const getVoucherPayablesWaiting = () => getJson<VoucherPayable[]>('/api/front-office/voucher-payouts/waiting')
export const getVoucherPayablesOf = (clientId: number) => getJson<VoucherPayable[]>(`/api/front-office/voucher-payouts/member/${clientId}`)
export const checkVoucherPayout = (b: VoucherPayoutIn) => send<VoucherPayoutCheck>('POST', '/api/front-office/voucher-payouts/check', b)
export const postVoucherPayout = (b: VoucherPayoutIn, key?: string) => send<VoucherPayoutReceipt>('POST', '/api/front-office/voucher-payouts', b, key)

// ---- time deposit interest accruals (module 8, type 52) ------------------------------------------------------------

export type AccrualGroup = 'LOCK_IN' | 'SHORT_TERM'
export interface AccrualTypeRow { typeCode: number; type: string; certificates: number; total: number; alreadyEncoded: boolean; vouchers: number }
export interface AccrualSummary { group: AccrualGroup; accrualType: string; from: string; to: string; types: AccrualTypeRow[]; certificates: number; total: number; withoutDepartment: number }
export interface AccrualVoucher { year: number; controlNo: number; documentNo: string; typeCode: number; lines: number; total: number; encodedBy: number; approvedBy: number | null; status: 'WAITING FOR APPROVAL' | 'APPROVED' | 'POSTED' }

export const getAccrualPreview = (group: AccrualGroup, to: string) => getJson<AccrualSummary>(`/api/front-office/time-deposit-accruals/preview?group=${group}&to=${to}`)
export const getAccrualVouchers = (group: AccrualGroup, to: string) => getJson<AccrualVoucher[]>(`/api/front-office/time-deposit-accruals/vouchers?group=${group}&to=${to}`)
export const encodeAccrual = (b: { group: AccrualGroup; to: string; types: number[] }) => send<AccrualVoucher[]>('POST', '/api/front-office/time-deposit-accruals/encode', b)
export const approveAccrual = (year: number, controlNo: number) => send<AccrualVoucher>('POST', `/api/front-office/time-deposit-accruals/${year}/${controlNo}/approve`)
export const postAccrual = (year: number, controlNo: number) => send<AccrualVoucher>('POST', `/api/front-office/time-deposit-accruals/${year}/${controlNo}/post`)

// ---- annual dividend and patronage refund (module 6, type 54) -------------------------------------------------------
export interface DividendMemberAmounts { clientId: number; mutualBenefit?: number | null; hospitalization?: number | null }
export interface DividendStatutory { name: string; percent: number }
/** Either the two rates, or (from the approved allocation) the net surplus, statutory funds, the two allocations and the resolution; the rates are then worked out. */
export interface DividendReq {
  cutoff: string; dividendRate?: number | null; patronageRate?: number | null; mutualBenefit: number; hospitalization: number; overrides?: DividendMemberAmounts[]
  iscAllocation?: number | null; prAllocation?: number | null; netSurplus?: number | null; statutory?: DividendStatutory[]; approvalRef?: string
}
export interface DividendHeld { clientId: number; total: number; reason: string }
export interface DividendSummary {
  cutoff: string; year: number; alreadyEncoded: boolean; members: number; dividend: number; refund: number; total: number; mutualBenefit: number; hospitalization: number
  savings: number; shareCapital: number; vouchers: number; heldCount: number; held: DividendHeld[]
  fromAllocation: boolean; totalAverageShareMonth: number; totalQualifiedPatronage: number; dividendRate: number; patronageRate: number
  iscAllocation: number | null; prAllocation: number | null; netSurplus: number | null; availableForDistribution: number | null; controls: string[]
}
export interface DividendStatement { clientId: number; name: string | null; year: number; averageShareMonth: number; dividendRate: number; dividend: number; qualifiedPatronage: number; patronageRate: number; refund: number; total: number }
export interface DividendVoucher { year: number; controlNo: number; documentNo: string; lines: number; total: number; encodedBy: number; approvedBy: number | null; status: 'WAITING FOR APPROVAL' | 'APPROVED' | 'POSTED' }
export const previewDividend = (b: DividendReq) => send<DividendSummary>('POST', '/api/front-office/dividends/preview', b)
export const encodeDividend = (b: DividendReq) => send<DividendVoucher[]>('POST', '/api/front-office/dividends/encode', b)
export const getDividendVouchers = (cutoff: string) => getJson<DividendVoucher[]>(`/api/front-office/dividends/vouchers?cutoff=${cutoff}`)
export const approveDividend = (year: number, controlNo: number) => send<DividendVoucher>('POST', `/api/front-office/dividends/${year}/${controlNo}/approve`)
export const postDividend = (year: number, controlNo: number) => send<DividendVoucher>('POST', `/api/front-office/dividends/${year}/${controlNo}/post`)
export const getDividendStatement = (b: DividendReq, clientId: number) => send<DividendStatement>('POST', `/api/front-office/dividends/statement?clientId=${clientId}`, b)

/** The distribution register as a CSV file, handed to the browser as a download. Resolves to the file name. */
export async function downloadDividendRegister(b: DividendReq): Promise<string> {
  const res = await http('/api/front-office/dividends/register', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(b) })
  if (!res.ok) throw new Error(await errorFrom(res))
  const name = /filename="([^"]+)"/.exec(res.headers.get('Content-Disposition') ?? '')?.[1] ?? 'distribution-register.csv'
  const url = URL.createObjectURL(await res.blob())
  const a = document.createElement('a')
  a.href = url
  a.download = name
  a.click()
  URL.revokeObjectURL(url)
  return name
}
