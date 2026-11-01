import { type Id } from '../clients'
import { type Member } from '../api'

export const TABS = [
  'Membership', 'Personal', 'Business / Employment', 'Education', 'Contacts & IDs', 'Spouse Info', 'Beneficiaries', 'Groups',
  'History', 'Pledges / Insurances', 'Others', 'Billing', 'Share Capital', 'Savings', 'Time Deposits', 'Loans', 'Documents',
]

/** The two main tabs and the sub-tabs under each; anything not named in Info or Accounts is under Other so nothing is lost. */
const INFO_TABS = ['Membership', 'Personal', 'Business / Employment', 'Education', 'Contacts & IDs', 'Spouse Info', 'Beneficiaries']
const ACCOUNT_TABS = ['Share Capital', 'Savings', 'Time Deposits', 'Loans', 'Billing', 'Pledges / Insurances']
export const FRESH_GROUPS: { label: string; tabs: number[] }[] = [
  // A member who is not registered yet has no beneficiaries to list.
  { label: 'Info', tabs: INFO_TABS.filter((n) => n !== 'Beneficiaries').map((n) => TABS.indexOf(n)) },
  { label: 'Other', tabs: [TABS.indexOf('Groups')] },
]
export const FRESH_ID: Id = { branchCode: 1, clientId: 0 }
export const BLANK_MEMBER = {
  id: FRESH_ID, firstName: null, lastName: null, accountName: null, dateOpened: null, accountStatus: null, cellNumber: null, city: null, closedDate: null, updatedAt: null,
  middleName: null, suffix: null, clientType: null, dateOfBirth: null, gender: null, civilStatus: null, email: null, street: null, barangay: null, province: null, referenceId: null,
  accountType: null, alertLevel: null, department: null, profession: null, religion: null, mailingAddress: null, dosriTag: null, includeHealthCare: null, monthlyIncome: null,
  zipCode: null, residenceSince: null, residenceType: null, provStreet: null, provBarangay: null, provCity: null, provProvince: null,
} as Member
export const GROUPS: { label: string; tabs: number[] }[] = [
  { label: 'Info', tabs: INFO_TABS.map((n) => TABS.indexOf(n)) },
  { label: 'Accounts', tabs: ACCOUNT_TABS.map((n) => TABS.indexOf(n)) },
  { label: 'Other', tabs: TABS.map((_, i) => i).filter((i) => ![...INFO_TABS, ...ACCOUNT_TABS].includes(TABS[i])) },
]

// ---- the other tabs -----------------------------------------------------------------------------------------------

// Same order as TABS. A key names a tab of the details endpoint; the rest have their own content.
export const TAB_KEYS = ['membership', 'personal', 'business', 'education', 'contacts', 'spouse', 'beneficiaries', 'groups', 'history', 'pledges', 'others', 'billing', 'shares', 'savings', 'timedeposits', 'loans', 'documents']
