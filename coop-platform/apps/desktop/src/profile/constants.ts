import { type Id } from '../clients'
import { type Member } from '../api'

export const TABS = [
  'Membership', 'Personal', 'Business / Employment', 'Education', 'Contacts & IDs', 'Spouse Info', 'Beneficiaries', 'Groups',
  'History', 'Pledges / Insurances', 'Others', 'Billing', 'Share Capital', 'Savings', 'Time Deposits', 'Loans', 'Documents', 'KYC',
]

/** The two main tabs and the sub-tabs under each; anything not named in Info or Accounts is under Other so nothing is lost. */
const INFO_TABS = ['Membership', 'Personal', 'Business / Employment', 'Education', 'Contacts & IDs', 'KYC', 'Spouse Info', 'Beneficiaries']
const ACCOUNT_TABS = ['Share Capital', 'Savings', 'Time Deposits', 'Loans', 'Billing', 'Pledges / Insurances']
export const FRESH_GROUPS: { label: string; tabs: number[] }[] = [
  // A member who is not registered yet has no beneficiaries to list.
  { label: 'Info', tabs: INFO_TABS.filter((n) => n !== 'Beneficiaries' && n !== 'KYC').map((n) => TABS.indexOf(n)) },
  { label: 'Other', tabs: [TABS.indexOf('Groups')] },
]
export const FRESH_ID: Id = { branchCode: 1, clientId: 0 }
export const BLANK_MEMBER = {
  id: FRESH_ID, firstName: null, lastName: null, accountName: null, dateOpened: null, accountStatus: null, cellNumber: null, city: null, closedDate: null, updatedAt: null,
  middleName: null, suffix: null, clientType: null, dateOfBirth: null, gender: null, civilStatus: null, email: null, street: null, barangay: null, province: null, referenceId: null,
  accountType: null, alertLevel: null, department: null, profession: null, religion: null, mailingAddress: null, dosriTag: null, includeHealthCare: null, monthlyIncome: null,
  zipCode: null, residenceSince: null, residenceType: null, provStreet: null, provBarangay: null, provCity: null, provProvince: null, provZipCode: null, houseNo: null, subdivision: null, provHouseNo: null, provSubdivision: null,
} as Member
export const GROUPS: { label: string; tabs: number[] }[] = [
  { label: 'Info', tabs: INFO_TABS.map((n) => TABS.indexOf(n)) },
  { label: 'Accounts', tabs: ACCOUNT_TABS.map((n) => TABS.indexOf(n)) },
  { label: 'Other', tabs: TABS.map((_, i) => i).filter((i) => ![...INFO_TABS, ...ACCOUNT_TABS].includes(TABS[i])) },
]

/** The key that opens each group of the section menu (I for Member info, A for Accounts, O for Other), by the group's name. */
export const GROUP_KEYS: Record<string, string> = { Info: 'I', Accounts: 'A', Other: 'O' }

/**
 * Which group a key press asks for: the index in {@code groups}, or -1 when it is not one of the group keys or a modifier is held (so Ctrl+A and the like are left alone).
 * Whether the person is typing or a dialog is open is for the caller, which can see the page.
 */
export function groupForKey(e: { key: string; ctrlKey: boolean; metaKey: boolean; altKey: boolean; shiftKey: boolean; repeat: boolean }, groups: { label: string }[]): number {
  if (e.ctrlKey || e.metaKey || e.altKey || e.shiftKey || e.repeat) return -1
  const key = e.key.toUpperCase()
  return groups.findIndex((g) => GROUP_KEYS[g.label] === key)
}

// ---- the other tabs -----------------------------------------------------------------------------------------------

// Same order as TABS. A key names a tab of the details endpoint; the rest have their own content.
export const TAB_KEYS = ['membership', 'personal', 'business', 'education', 'contacts', 'spouse', 'beneficiaries', 'groups', 'history', 'pledges', 'others', 'billing', 'shares', 'savings', 'timedeposits', 'loans', 'documents', 'kyc']
