import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { openLoanApplicationWindow, serveDetachedWindows } from './detachedWindow'
import AccountsPage from './AccountsPage'
import ApprovalsPage from './ApprovalsPage'
import ClientProfilePage from './ClientProfilePage'
import type { Id } from './clients'
import Dashboard, { type View } from './Dashboard'
import ErrorBoundary from './ErrorBoundary'
import FrontOfficePage from './FrontOfficePage'
import LoanApplicationDialog from './LoanApplicationDialog'
import TopBar from './TopBar'
import UsersPage from './UsersPage'
import AuditLogPage from './AuditLogPage'
import LoanSetupPage from './LoanSetupPage'
import AccountingPage from './AccountingPage'
import { SessionGate } from './session'
import { ToolbarSlotProvider } from './toolbarSlot'

const titles: Record<View, string> = {
  home: 'Coop Platform', members: 'Client Profile', accounts: 'Chart of Accounts', accounting: 'Accounting', approvals: 'Approvals', users: 'Users', audit: 'Audit Log', loansetup: 'Loan Setup', frontoffice: 'Front Office', loanapp: 'Coop Platform',
}

function Signed() {
  const qc = useQueryClient()
  const [view, setView] = useState<View>('home')
  // A loan application saved in its own window refreshes the lists here.
  useEffect(() => {
    const stop = serveDetachedWindows(() => { qc.invalidateQueries({ queryKey: ['member-loans'] }); qc.invalidateQueries({ queryKey: ['loan-applications'] }); qc.invalidateQueries({ queryKey: ['loan-releases'] }) })
    return () => { void stop.then((f) => f()) }
  }, [qc])
  // The open client lives here so the app-wide search can open a profile from any screen.
  const [client, setClient] = useState<Id | null>(null)
  // The Loan Application tile goes straight to the form in its own window; the in-app dialog is only the fallback if that window cannot open.
  const navigate = (v: View) => { if (v === 'loanapp' && openLoanApplicationWindow({})) return; setView(v) }
  const openClient = (id: Id) => { setClient(id); setView('members') }
  return (
    <ToolbarSlotProvider>
      <TopBar title={titles[view]} onHome={view === 'home' ? undefined : () => setView('home')} onNavigate={navigate} onOpenClient={openClient} />
      <ErrorBoundary resetKey={view}>
        {(view === 'home' || view === 'loanapp') && <Dashboard onOpen={navigate} />}
        {view === 'loanapp' && <LoanApplicationDialog onClose={() => setView('home')} />}
        {view === 'members' && <ClientProfilePage selected={client} onSelect={setClient} />}
        {view === 'accounts' && <AccountsPage />}
        {view === 'accounting' && <AccountingPage />}
        {view === 'approvals' && <ApprovalsPage onOpenClient={openClient} />}
        {view === 'users' && <UsersPage />}
        {view === 'audit' && <AuditLogPage />}
        {view === 'loansetup' && <LoanSetupPage />}
        {view === 'frontoffice' && <FrontOfficePage />}
      </ErrorBoundary>
    </ToolbarSlotProvider>
  )
}

export default function App() {
  return (
    <SessionGate>
      <Signed />
    </SessionGate>
  )
}
