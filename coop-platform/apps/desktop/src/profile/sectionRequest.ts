/** Lets another screen (the KYC review list) open a member's profile on a particular section: the profile is not open yet when it asks. */

let pendingSection: { branchCode: number; clientId: number; tab: number } | null = null

/** Open this member's profile on this section once it is shown. */
export function requestSectionFor(id: { branchCode: number; clientId: number }, tab: number) {
  pendingSection = { ...id, tab }
}

/** The section a profile for this member should open on, if another screen asked for one; it is forgotten once read. */
export function takePendingSection(id: { branchCode: number; clientId: number }): number | null {
  const p = pendingSection
  if (!p || p.branchCode !== id.branchCode || p.clientId !== id.clientId) return null
  pendingSection = null
  return p.tab
}
