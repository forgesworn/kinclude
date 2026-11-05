import { test, expect, type Page } from '@playwright/test'
import { household } from './helpers.js'

async function playing(page: Page): Promise<boolean> {
  return page.evaluate(() => [...document.querySelectorAll<HTMLVideoElement>('[data-testid=remote-tile] video')]
    .some((v) => v.videoWidth > 0 && !v.paused))
}

test('a video call: Sam rings, Mum joins, and a guardian who can read joins in sight of everyone', async ({ browser }) => {
  const { mum, dad, sam } = await household(browser, 'Growing up')

  await sam.getByRole('button', { name: 'Video call' }).click()
  await expect(sam.getByTestId('local-tile')).toBeVisible()
  await expect(sam.getByTestId('call-status')).toHaveText('Waiting for others to join…')

  const mumPanel = mum.getByTestId('call-panel')
  await expect(mumPanel).toContainText('Sam is on a call.')
  await mumPanel.getByRole('button', { name: 'Join with video' }).click()
  await expect(sam.getByTestId('remote-tile')).toHaveCount(1)
  await expect(mum.getByTestId('remote-tile')).toHaveCount(1)
  // Real media flows both ways.
  await expect.poll(() => playing(sam)).toBe(true)
  await expect.poll(() => playing(mum)).toBe(true)

  // Dad can read the room at Growing up, so he can join, and is told that
  // Sam will see it. Sam does.
  const dadPanel = dad.getByTestId('call-panel')
  await expect(dadPanel).toContainText('will see that you joined')
  await dadPanel.getByRole('button', { name: 'Join with voice' }).click()
  await expect(sam.getByTestId('call-status')).toHaveText(/On a call with (Mum and Dad|Dad and Mum)/)
  await expect(sam.getByTestId('remote-tile')).toHaveCount(2)
  await expect(sam.getByTestId('call-log').filter({ hasText: 'Dad joined the voice call' })).toBeVisible()

  await dad.getByRole('button', { name: 'Leave call' }).click()
  await expect(sam.getByTestId('call-log').filter({ hasText: 'Dad left the call' })).toBeVisible()
  await expect(sam.getByTestId('remote-tile')).toHaveCount(1)

  // Mute and camera off are local switches; leaving the room list keeps the call.
  await sam.getByRole('button', { name: 'Mute' }).click()
  await expect(sam.getByRole('button', { name: 'Unmute' })).toBeVisible()
  await sam.getByRole('button', { name: 'Back' }).click()
  await expect(sam.getByTestId('callbar')).toContainText('On a call in Home')
  await sam.getByTestId('callbar').click()
  await expect(sam.getByTestId('remote-tile')).toHaveCount(1)
})

test('at Nearly grown a guardian listed for the child cannot join the call', async ({ browser }) => {
  const { dad, sam } = await household(browser, 'Nearly grown')
  await sam.getByRole('button', { name: 'Voice call' }).click()
  await expect(sam.getByTestId('call-status')).toHaveText('Waiting for others to join…')
  await expect(dad.getByTestId('call-closed')).toBeVisible()
  await expect(dad.getByRole('button', { name: /Join with|Voice call|Video call/ })).toHaveCount(0)
})
