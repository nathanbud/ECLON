import type { ReactElement } from 'react'
import {
  FiBriefcase, FiClipboard, FiClock, FiCreditCard, FiDollarSign, FiFileText, FiHeart, FiHome, FiLayers, FiList, FiPhone, FiShield, FiTrendingUp, FiUserCheck, FiUser, FiUsers, FiBook, FiDatabase,
} from 'react-icons/fi'

/** One icon per profile section, by the section's key in `TAB_KEYS`, for the profile's side menu. A key without one falls back to a plain list icon. */
const ICONS: Record<string, ReactElement> = {
  membership: <FiUser />,
  personal: <FiHome />,
  business: <FiBriefcase />,
  education: <FiBook />,
  contacts: <FiPhone />,
  spouse: <FiHeart />,
  beneficiaries: <FiUsers />,
  groups: <FiLayers />,
  history: <FiClock />,
  pledges: <FiShield />,
  others: <FiList />,
  billing: <FiClipboard />,
  shares: <FiDatabase />,
  savings: <FiDollarSign />,
  timedeposits: <FiTrendingUp />,
  loans: <FiCreditCard />,
  documents: <FiFileText />,
  kyc: <FiUserCheck />,
}

export const tabIcon = (key: string): ReactElement => ICONS[key] ?? <FiList />
