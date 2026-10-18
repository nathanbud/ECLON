import { expect, test } from '@playwright/test'

test.describe('sign-in', () => {
  test('shows the sign-in page and refuses a wrong password', async ({ page }) => {
    await page.goto('/')
    await expect(page.getByRole('heading', { name: 'Sign in to Coop Platform' })).toBeVisible()
    const signIn = page.getByRole('button', { name: 'Sign in' })
    await expect(signIn).toBeDisabled() // nothing typed yet

    await page.getByLabel('Username').fill('no.such.person')
    await page.getByLabel('Password').fill('definitely-wrong')
    await signIn.click()
    await expect(page.getByRole('alert')).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Sign in to Coop Platform' })).toBeVisible() // still signed out
  })
})
