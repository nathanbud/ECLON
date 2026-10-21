import { useState } from 'react'
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
import AccountingPage from './AccountingPage'
import { SessionGate } from './session'
import { ToolbarSlotProvider } from './toolbarSlot'

const titles: Record<View, string> = {
  home: 'Coop Platform', members: 'Client Profile', accounts: 'Chart of Accounts', accounting: 'Accounting', approvals: 'Approvals', users: 'Users', frontoffice: 'Front Office', loanapp: 'Coop Platform',
}

function Signed() {
  const [view, setView] = useState<View>('home')
  // The open client lives here so the app-wide search can open a profile from any screen.
  const [client, setClient] = useState<Id | null>(null)
  const openClient = (id: Id) => { setClient(id); setView('members') }
  return (
    <ToolbarSlotProvider>
      <TopBar title={titles[view]} onHome={view === 'home' ? undefined : () => setView('home')} onNavigate={setView} onOpenClient={openClient} />
      <ErrorBoundary resetKey={view}>
        {(view === 'home' || view === 'loanapp') && <Dashboard onOpen={setView} />}
        {view === 'loanapp' && <LoanApplicationDialog onClose={() => setView('home')} />}
        {view === 'members' && <ClientProfilePage selected={client} onSelect={setClient} />}
        {view === 'accounts' && <AccountsPage />}
        {view === 'accounting' && <AccountingPage />}
        {view === 'approvals' && <ApprovalsPage onOpenClient={openClient} />}
        {view === 'users' && <UsersPage />}
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
