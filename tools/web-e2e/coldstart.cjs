// Cold start of the built page as the phone loads it (file://, no backend), with the CPU slowed down 4×.
//   node coldstart.cjs [path/to/index.html]   → median of 5 loads: script parse+eval and first composer paint
const { chromium } = require('playwright')
const path = require('path')
const file = path.resolve(process.argv[2] || path.join(__dirname, '../../web/dist/index.html'))
;(async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM || undefined })
  const runs = []
  for (let i = 0; i < 5; i++) {
    const page = await browser.newPage({ viewport: { width: 375, height: 812 } })
    const cdp = await page.context().newCDPSession(page)
    await cdp.send('Emulation.setCPUThrottlingRate', { rate: 4 })
    const t0 = Date.now()
    await page.goto('file://' + file)
    await page.locator('.composer textarea').waitFor({ timeout: 60000 })
    const ready = Date.now() - t0
    const nav = await page.evaluate(() => {
      const n = performance.getEntriesByType('navigation')[0]
      return { dcl: Math.round(n.domContentLoadedEventEnd), load: Math.round(n.loadEventEnd) }
    })
    runs.push({ ready, ...nav })
    await page.close()
  }
  await browser.close()
  const med = k => runs.map(r => r[k]).sort((a, b) => a - b)[2]
  console.log(`${path.basename(file)}: composer visible ${med('ready')} ms, DOMContentLoaded ${med('dcl')} ms, load ${med('load')} ms (median of 5, CPU ×4)`)
})()
