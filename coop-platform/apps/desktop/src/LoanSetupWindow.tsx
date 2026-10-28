import LoanSetupPage from './LoanSetupPage'

/**
 * Loan Setup in a window of its own, like the loan application: loan types (with their setup and COA links), purposes, collateral types and co-maker holds, and the amortization
 * reference, each a tab of the page. Opened from Settings; it asks the main window for the sign-in, so closing the main window or signing out closes this one too.
 * The window's own title bar is the only bar around the page: it names the window and closes it.
 */
export default function LoanSetupWindow() {
  const q = new URLSearchParams(window.location.hash.split('?')[1] ?? '')
  return <LoanSetupPage initialTab={q.get('tab') === 'amortization' ? 'amortization' : 'types'} fullHeight />
}
