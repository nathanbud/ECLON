import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { openLoanApplicationWindow, openLoanSetupWindow, serveDetachedWindows } from './detachedWindow'
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
import SettingsPage from './SettingsPage'
import AccountingPage from './AccountingPage'
import ProcurementPage from './ProcurementPage'
import InventoryPage from './InventoryPage'
import PosPage from './PosPage'
import AssetsPage from './AssetsPage'
import ReportsPage from './ReportsPage'
import DocumentsPage from './DocumentsPage'
import WorkflowSettingsPage from './WorkflowSettingsPage'
import MembershipPolicyPage from './MembershipPolicyPage'
import DocumentationPage from './DocumentationPage'
import { Button, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle } from '@mui/material'
import { unsavedWork } from './leaveGuard'
import { SessionGate } from './session'
import { ToolbarSlotProvider } from './toolbarSlot'

const titles: Record<View, string> = {
  home: 'Coop Platform', members: 'Client Profile', accounts: 'Chart of Accounts', accounting: 'Accounting', approvals: 'Approvals', users: 'Users', audit: 'Audit Log', loansetup: 'Loan Setup', amortization: 'Loan Setup', settings: 'Settings', frontoffice: 'Front Office', loanapp: 'Coop Platform', procurement: 'Purchasing', inventory: 'Inventory', pos: 'Point of Sale', assets: 'Fixed Assets', reports: 'Reports', documents: 'Documents', workflows: 'Approval workflows', membershippolicy: 'Membership policy', documentation: 'Documentation',
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
  // Screens opened from Settings go back to Settings, not to the dashboard.
  const [cameFromSettings, setCameFromSettings] = useState(false)
  const navigate = (v: View) => { if (v === 'loanapp' && openLoanApplicationWindow({})) return; if ((v === 'loansetup' || v === 'amortization') && openLoanSetupWindow(v === 'amortization' ? 'amortization' : 'types')) return; setCameFromSettings(view === 'settings' && v !== 'home'); setView(v) }
  const openClient = (id: Id) => { setClient(id); setView('members') }
  // The window title names the screen, for the task bar, the window list and screen readers.
  useEffect(() => { document.title = view === 'home' ? 'Coop Platform' : `${titles[view]} · Coop Platform` }, [view])
  const [leaving, setLeaving] = useState<string | null>(null)
  /** Straight to the dashboard, asking first when a screen holds unsaved work. */
  const goHome = (direct: boolean) => {
    const unsaved = unsavedWork()
    if (unsaved) { setLeaving(unsaved); return }
    if (direct) setCameFromSettings(false)
    setView('home')
  }
  // H goes to the main dashboard from any screen. It is ignored while typing, with a modifier key held, or while a dialog is open, so it never interrupts work.
  useEffect(() => {
    const on = (e: KeyboardEvent) => {
      if (e.key.toLowerCase() !== 'h' || e.ctrlKey || e.metaKey || e.altKey || e.shiftKey || e.repeat) return
      const t = e.target as HTMLElement | null
      if (t && (t.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(t.tagName))) return
      if (document.querySelector('[role="dialog"], [role="menu"], [role="listbox"]')) return
      goHome(true)
    }
    window.addEventListener('keydown', on)
    return () => window.removeEventListener('keydown', on)
  }, [])
  return (
    <ToolbarSlotProvider>
      <Dialog open={!!leaving} onClose={() => setLeaving(null)} aria-labelledby="leave-title">
        <DialogTitle id="leave-title">Leave without saving?</DialogTitle>
        <DialogContent><DialogContentText>{leaving} Going to the dashboard will lose it.</DialogContentText></DialogContent>
        <DialogActions>
          <Button onClick={() => setLeaving(null)} autoFocus>Stay</Button>
          <Button color="error" onClick={() => { setLeaving(null); setCameFromSettings(false); setView('home') }}>Leave</Button>
        </DialogActions>
      </Dialog>
      <TopBar title={titles[view]} onHome={view === 'home' ? undefined : () => { const unsaved = unsavedWork(); if (unsaved) { setLeaving(unsaved); return } if (cameFromSettings && view !== 'settings') { setCameFromSettings(false); setView('settings') } else setView('home') }} onNavigate={navigate} onOpenClient={openClient} />
      <ErrorBoundary resetKey={view}>
        {(view === 'home' || view === 'loanapp') && <Dashboard onOpen={navigate} />}
        {view === 'loanapp' && <LoanApplicationDialog onClose={() => setView('home')} />}
        {view === 'members' && <ClientProfilePage selected={client} onSelect={setClient} />}
        {view === 'accounts' && <AccountsPage />}
        {view === 'accounting' && <AccountingPage />}
        {view === 'approvals' && <ApprovalsPage onOpenClient={openClient} />}
        {view === 'users' && <UsersPage />}
        {view === 'audit' && <AuditLogPage />}
        {view === 'loansetup' && <LoanSetupPage key="types" />}
        {view === 'amortization' && <LoanSetupPage key="amortization" initialTab="amortization" />}
        {view === 'settings' && <SettingsPage onOpen={navigate} />}
        {view === 'frontoffice' && <FrontOfficePage />}
        {view === 'procurement' && <ProcurementPage />}
        {view === 'inventory' && <InventoryPage />}
        {view === 'pos' && <PosPage />}
        {view === 'assets' && <AssetsPage />}
        {view === 'reports' && <ReportsPage onOpen={navigate} />}
        {view === 'documents' && <DocumentsPage />}
        {view === 'workflows' && <WorkflowSettingsPage />}
        {view === 'membershippolicy' && <MembershipPolicyPage />}
        {view === 'documentation' && <DocumentationPage />}
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
