import { useContext } from 'react'
import { SessionCtx } from '../sessionContext'

/**
 * The areas a cooperative can switch off (Settings > System > Features), as the server names them. A screen, tab or tile that belongs to one is hidden while it
 * is off; the server refuses its addresses too.
 */
export type Feature = 'savings' | 'passbook' | 'timedeposits' | 'loans' | 'dividends' | 'pos' | 'inventory' | 'procurement' | 'assets' | 'cic'

/** The member profile's and the teller's account sections that belong to an area. */
export const SECTION_FEATURE: Record<string, Feature> = { Savings: 'savings', 'Time Deposits': 'timedeposits', Loans: 'loans' }

/** The areas switched off for this cooperative; empty outside a session (tests, sign-in). */
export function useFeaturesOff(): string[] {
  return useContext(SessionCtx)?.featuresOff ?? []
}

/** True unless the area is switched off. */
export function featureOn(off: string[], f: Feature | undefined | null): boolean {
  return !f || !off.includes(f)
}
