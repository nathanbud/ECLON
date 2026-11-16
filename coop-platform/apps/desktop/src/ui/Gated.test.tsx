import { Button } from '@mui/material'
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Gated } from './Gated'

describe('Gated', () => {
  it('leaves an allowed action as it is', () => {
    render(<Gated allowed reason="no"><Button>Approve</Button></Gated>)
    expect(screen.getByRole('button', { name: 'Approve' })).toBeEnabled()
  })

  it('shows a refused action disabled, with the reason available to everyone', () => {
    render(<Gated allowed={false} reason="You made this request."><Button>Approve</Button></Gated>)
    expect(screen.getByRole('button', { name: 'Approve' })).toBeDisabled()
    expect(screen.getByLabelText('You made this request.')).toBeInTheDocument()
  })
})
