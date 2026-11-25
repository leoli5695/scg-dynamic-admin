import { launch, newPage, BASE } from './harness.mjs'
const b = await launch()
const { ctx, page } = await newPage(b)
await page.goto(`${BASE}/instances/inst-demo-0001/routes`, { waitUntil: 'domcontentloaded' })
await page.locator('.vn-tile').first().waitFor()
await page.locator('.vn-toolbar .vn-seg-item', { hasText: '表格' }).click()
await page.locator('.vn-table').waitFor()
await page.waitForTimeout(400)
console.log(await page.evaluate(() => {
  const td = document.querySelector('.vn-table tbody td:last-child')
  const g = td && td.querySelector('.vn-iconbtn-group')
  const th = document.querySelector('.vn-table-actions-head')
  return {
    tdAlign: td && getComputedStyle(td).textAlign,
    gDisplay: g && getComputedStyle(g).display,
    gJustify: g && getComputedStyle(g).justifyContent,
    thAlign: th && getComputedStyle(th).textAlign,
    gClass: g && g.className,
  }
}))
await ctx.close(); await b.close()
