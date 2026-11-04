import { test, expect, type Browser, type Page } from '@playwright/test'

async function phone(browser: Browser, name: string, kind: 'adult' | 'child'): Promise<Page> {
  const context = await browser.newContext()
  const page = await context.newPage()
  page.on('pageerror', (e) => console.log(`[${name}] page error:`, e.message))
  await page.goto('/')
  await page.getByText(kind === 'child' ? "A child's phone" : "Mine: I'm a parent, carer or relative", { exact: true }).click()
  await page.getByLabel('Name shown to your family').fill(name)
  await page.getByText('Just trying it out?').click()
  await page.getByRole('button', { name: 'Make a key on this phone' }).click()
  await expect(page.locator('header.bar')).toContainText(name)
  return page
}

async function pair(guardian: Page, child: Page, childName: string, tier?: string) {
  await guardian.getByLabel("Child's name").fill(childName)
  if (tier) await guardian.getByText(tier, { exact: true }).click()
  await guardian.getByRole('button', { name: "Pair with a child's phone" }).click()
  const code = await guardian.getByTestId('pairing-code').inputValue()
  expect(code).toMatch(/^kinclude-pair:/)
  await child.getByLabel("Pairing code from your parent or carer's phone").fill(code)
  await child.getByRole('button', { name: 'Pair', exact: true }).click()
  await expect(guardian.getByTestId('child').filter({ hasText: childName })).toBeVisible()
}

test('a family: pairing, a room everyone agreed, and a guardian who reads in the open', async ({ browser }) => {
  const mum = await phone(browser, 'Mum', 'adult')
  const dad = await phone(browser, 'Dad', 'adult')
  const sam = await phone(browser, 'Sam', 'child')

  await pair(mum, sam, 'Sam', 'Growing up')
  await expect(sam.getByTestId('guardian')).toContainText('Mum')
  await expect(sam.getByTestId('my-tier')).toHaveText('Growing up')
  await pair(dad, sam, 'Sam')
  await expect(sam.getByTestId('guardian')).toHaveCount(2)
  // Sam's phone keeps the oversight agreed with Mum.
  await expect(dad.getByTestId('child')).toContainText('Growing up')

  // Dad will take invitations from Mum.
  const mumKey = (await mum.getByTestId('my-key').textContent())!.trim()
  await dad.getByLabel('Their key').fill(mumKey)
  await dad.getByLabel('Name', { exact: true }).fill('Mum')
  await dad.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(dad.getByTestId('contact')).toContainText('Mum')

  // Mum makes a room and adds Sam: the invitation goes to both guardians.
  await mum.getByLabel('New room name').fill('Home')
  await mum.getByRole('button', { name: 'Make room' }).click()
  await expect(mum.locator('header.bar h1')).toHaveText('Home')
  await mum.getByRole('button', { name: 'Invite someone' }).click()
  await mum.getByRole('button', { name: 'Add Sam' }).click()
  await mum.getByRole('button', { name: 'Back' }).click()

  for (const g of [mum, dad]) {
    const invite = g.getByTestId('invite')
    await expect(invite).toContainText('invites Sam to Home')
    await invite.getByRole('button', { name: 'Accept' }).click()
  }
  const samInvite = sam.getByTestId('invite')
  await expect(samInvite).toContainText('passed on an invitation to Home')
  await samInvite.getByRole('button', { name: 'Accept' }).click()

  await sam.getByTestId('room-tile').filter({ hasText: 'Home' }).click()
  await expect(sam.getByLabel('Message')).toBeVisible()
  // Sam is told who can read, and how.
  await expect(sam.getByTestId('banner').first()).toContainText('Dad')
  await expect(sam.getByTestId('banner').first()).toContainText('everyone sees when')
  await sam.getByLabel('Message').fill('Are you coming on Saturday?')
  await sam.getByRole('button', { name: 'Send' }).click()

  await mum.getByTestId('room-tile').filter({ hasText: 'Home' }).click()
  await expect(mum.getByTestId('message')).toContainText('Are you coming on Saturday?')

  // Dad is listed as Sam's guardian at Growing up: nothing is shown until he
  // reads in the open.
  await dad.getByTestId('room-tile').filter({ hasText: 'Home' }).click()
  const gate = dad.getByTestId('gate')
  await expect(gate).toBeVisible()
  await expect(dad.getByTestId('message')).toHaveCount(0)
  await gate.getByRole('button', { name: 'Read, and show that I did' }).click()
  await expect(dad.getByTestId('message')).toContainText('Are you coming on Saturday?')
  await expect(sam.getByTestId('receipt')).toContainText('Dad read this conversation')
})

async function household(browser: Browser, startTier: string) {
  const mum = await phone(browser, 'Mum', 'adult')
  const dad = await phone(browser, 'Dad', 'adult')
  const sam = await phone(browser, 'Sam', 'child')
  await pair(mum, sam, 'Sam', startTier)
  await pair(dad, sam, 'Sam')
  const mumKey = (await mum.getByTestId('my-key').textContent())!.trim()
  await dad.getByLabel('Their key').fill(mumKey)
  await dad.getByLabel('Name', { exact: true }).fill('Mum')
  await dad.getByRole('button', { name: 'Add', exact: true }).click()
  await mum.getByLabel('New room name').fill('Home')
  await mum.getByRole('button', { name: 'Make room' }).click()
  await mum.getByRole('button', { name: 'Invite someone' }).click()
  await mum.getByRole('button', { name: 'Add Sam' }).click()
  await mum.getByRole('button', { name: 'Back' }).click()
  for (const g of [mum, dad]) await g.getByTestId('invite').getByRole('button', { name: 'Accept' }).click()
  await sam.getByTestId('invite').getByRole('button', { name: 'Accept' }).click()
  for (const p of [mum, dad, sam]) await p.getByTestId('room-tile').filter({ hasText: 'Home' }).click()
  await expect(sam.getByLabel('Message')).toBeVisible()
  return { mum, dad, sam }
}

test('Nearly grown: Dad cannot read, and opening in an emergency tells Sam at once', async ({ browser }) => {
  const { dad, sam } = await household(browser, 'Nearly grown')
  await expect(sam.getByTestId('banner').first()).toContainText("Dad can't see this chat")
  await sam.getByLabel('Message').fill("Text me when you're home.")
  await sam.getByRole('button', { name: 'Send' }).click()

  await expect(dad.getByText('You are listed here as a guardian').or(dad.getByTestId('emergency-open'))).toBeVisible()
  const open = dad.getByTestId('emergency-open')
  await expect(open).toBeVisible()
  await expect(dad.getByTestId('message')).toHaveCount(0)
  await open.getByLabel('What should Sam be told?').fill('Your phone has been off since 9')
  dad.once('dialog', (d) => d.accept())
  await open.getByRole('button', { name: 'Open in an emergency' }).click()
  await expect(dad.getByTestId('message')).toContainText("Text me when you're home.")
  const notice = sam.getByTestId('emergency')
  await expect(notice).toContainText('Dad opened your messages in an emergency')
  await expect(notice).toContainText('Your phone has been off since 9')
})

test('moving tier takes both: Sam asks, Mum agrees, and the room follows', async ({ browser }) => {
  const { mum, dad, sam } = await household(browser, 'First messages')
  await expect(sam.getByTestId('banner').first()).toContainText('can see this chat')
  await sam.getByRole('button', { name: 'Back' }).click()
  await sam.getByLabel('Move to').selectOption({ label: 'Nearly grown' })
  await sam.getByRole('button', { name: 'Ask to move' }).click()
  await expect(sam.getByText('Waiting for the other side to agree.')).toBeVisible()
  // Nothing changed yet.
  await expect(sam.getByTestId('my-tier')).toHaveText('First messages')

  await mum.getByRole('button', { name: 'Back' }).click()
  const ask = mum.getByTestId('tier-request')
  await expect(ask).toContainText('would like Sam to move to Nearly grown')
  await ask.getByRole('button', { name: 'Agree' }).click()
  await expect(sam.getByTestId('my-tier')).toHaveText('Nearly grown')

  // Dad, listed for Sam, steps back.
  await expect(dad.getByTestId('banner').first()).toContainText("can't see this chat")
  await expect(dad.getByLabel('Message')).toHaveCount(0)
})
