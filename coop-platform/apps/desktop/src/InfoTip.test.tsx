import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { TextField } from '@mui/material'
import { AboutNote, Hinted, ScreenTitle } from './appleUi'
import { AppThemeProvider } from './theme'

const show = () => render(<AppThemeProvider><ScreenTitle title="Offices">Every member belongs to one office.</ScreenTitle></AppThemeProvider>)

describe('ScreenTitle and its ⓘ', () => {
  it('shows the title, and keeps the explanation off the screen until asked for', () => {
    show()
    expect(screen.getByRole('heading', { level: 1, name: 'Offices' })).toBeInTheDocument()
    expect(screen.queryByText('Every member belongs to one office.')).toBeNull()
  })

  it('shows the explanation on hover', async () => {
    show()
    await userEvent.setup().hover(screen.getByRole('button', { name: 'About this screen' }))
    expect(await screen.findByRole('tooltip')).toHaveTextContent('Every member belongs to one office.')
  })

  it('shows it on keyboard focus too, and the button describes itself by it', async () => {
    show()
    await userEvent.setup().tab()
    const button = screen.getByRole('button', { name: 'About this screen' })
    expect(button).toHaveFocus()
    expect(await screen.findByRole('tooltip')).toHaveTextContent('Every member belongs to one office.')
    expect(button).toHaveAccessibleDescription('Every member belongs to one office.')
  })

  it('opens and closes with a tap, for a touch screen', async () => {
    show()
    const user = userEvent.setup()
    const button = screen.getByRole('button', { name: 'About this screen' })
    await user.pointer({ keys: '[MouseLeft]', target: button })
    expect(await screen.findByRole('tooltip')).toBeInTheDocument()
  })

  it('has no ⓘ when there is nothing to explain', () => {
    render(<AppThemeProvider><ScreenTitle title="Inventory" /></AppThemeProvider>)
    expect(screen.queryByRole('button', { name: 'About this screen' })).toBeNull()
  })

  it('puts the explanation of a field behind an ⓘ beside it, and the field keeps its own name', async () => {
    render(<AppThemeProvider><Hinted tip="Kept in the audit log"><TextField label="Reason" /></Hinted></AppThemeProvider>)
    expect(screen.getByRole('textbox', { name: 'Reason' })).toBeInTheDocument()
    expect(screen.queryByText('Kept in the audit log')).toBeNull()
    await userEvent.setup().hover(screen.getByRole('button', { name: 'About this field' }))
    expect(await screen.findByRole('tooltip')).toHaveTextContent('Kept in the audit log')
  })

  it('shows no ⓘ for a field with nothing to explain', () => {
    render(<AppThemeProvider><Hinted tip={undefined}><TextField label="Reason" /></Hinted></AppThemeProvider>)
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('folds a paragraph into one How this works line that opens on a tap', async () => {
    render(<AppThemeProvider><AboutNote>Straight-line, monthly.</AboutNote></AppThemeProvider>)
    expect(screen.queryByText('Straight-line, monthly.')).toBeNull()
    await userEvent.setup().click(screen.getByRole('button', { name: 'How this works' }))
    expect(await screen.findByRole('tooltip')).toHaveTextContent('Straight-line, monthly.')
  })
})
