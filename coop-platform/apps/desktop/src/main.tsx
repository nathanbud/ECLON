import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import { installArrowNavigation } from './arrowNavigation'
import { adoptSessionFromMainWindow, LOAN_APP_ROUTE } from './detachedWindow'
import LoanApplicationWindow from './LoanApplicationWindow'
import { SessionGate } from './session'
import { AppThemeProvider } from './theme'

installArrowNavigation()
const queryClient = new QueryClient()

const detached = window.location.hash.startsWith(LOAN_APP_ROUTE)

// A detached form window starts without a sign-in, so it asks the main window for it before showing anything.
;(detached ? adoptSessionFromMainWindow() : Promise.resolve()).then(() => {
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <QueryClientProvider client={queryClient}>
        <AppThemeProvider>
          {detached ? <SessionGate><LoanApplicationWindow /></SessionGate> : <App />}
        </AppThemeProvider>
      </QueryClientProvider>
    </StrictMode>,
  )
})
