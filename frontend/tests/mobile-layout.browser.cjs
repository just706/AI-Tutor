const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const path = require('node:path')
const { chromium } = require(require.resolve('playwright', {
  paths: [path.resolve(__dirname, '../../backend/target/browser-tools'), path.resolve(__dirname, '..')]
}))

// Run against Vite with synthetic API responses; unexpected API/model calls fail closed.
const baseURL = process.env.LAYOUT_BASE_URL || 'http://127.0.0.1:5173'
const output = path.resolve(process.env.LAYOUT_OUTPUT || 'backend/target/mobile-layout')
const timestamp = '2026-10-26T10:00:00'
const conversations = Array.from({ length: 8 }, (_, index) => ({
  id: index + 1, title: `教材会话 ${index + 1}`, mode: 'rag', documentIds: [1], updateTime: timestamp
}))
const document = {
  id: 1, fileName: '数学走查.txt', fileType: 'txt', processStatus: 'completed',
  chunkCount: 1, uploadTime: timestamp, preview: '导数表示函数的局部变化率。'
}
const messages = [
  { role: 'user', messageContent: '导数表示什么？', createTime: timestamp },
  { role: 'assistant', messageContent: '导数表示函数的局部变化率。', createTime: timestamp,
    sources: [{ documentId: 1, fileName: document.fileName, chunkIndex: 0,
      snippet: document.preview, available: true }] }
]

async function main() {
  await fs.mkdir(output, { recursive: true })
  const browser = await chromium.launch({
    executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe',
    headless: true
  })
  const checks = []
  const unexpected = []
  const errors = []
  const measurements = []
  let showLongDirection = false
  const check = (name, condition, detail) => checks.push({ name, passed: !!condition, detail })
  try {
    const context = await browser.newContext({ viewport: { width: 360, height: 800 }, serviceWorkers: 'block' })
    await context.addInitScript(() => localStorage.setItem('ai_tutor_token', 'synthetic-layout-token'))
    await context.route('**/*', async route => {
      const request = route.request()
      const url = new URL(request.url())
      if (url.origin === new URL(baseURL).origin && !url.pathname.startsWith('/api/')) return route.continue()
      const endpoint = url.pathname.replace(/^\/api/, '')
      let data
      if (request.method() === 'GET') {
        if (endpoint === '/users/me') data = { id: 7, username: 'layout-tester', role: 'student' }
        else if (endpoint === '/profile') data = showLongDirection
          ? { learningDirection: 'Java、数学与物理的跨学科学习和长期复习计划' } : null
        else if (endpoint === '/conversations') data = conversations
        else if (/^\/conversations\/\d+$/.test(endpoint)) data = conversations.find(c => c.id === Number(endpoint.split('/').pop()))
        else if (/^\/conversations\/\d+\/messages$/.test(endpoint)) data = messages
        else if (endpoint === '/learning-sessions/active' || endpoint === '/personal-graph/extractions') data = null
        else if (endpoint === '/documents') data = [document]
        else if (endpoint === '/documents/1') data = document
        else if (endpoint === '/documents/1/chunks') data = [{ id: 1, documentId: 1, chunkIndex: 0, chunkText: document.preview, createTime: timestamp }]
        else if (endpoint === '/learner-memories' || endpoint === '/knowledge-points/tree') data = []
        else if (endpoint === '/analysis/overview') data = {
          learnedCount: 0, masteredCount: 0, inProgressCount: 0, averageMasteryLevel: 0,
          totalStudyTime: 0, answeredQuestionCount: 0, correctAnswerCount: 0, answerAccuracy: 0,
          chatMessageCount: 0, weakKnowledgePoints: [], suggestions: [], nextActions: []
        }
        else if (endpoint === '/evaluation/overview') data = {
          strategyDecisionCount: 0, explainableStrategyDecisionCount: 0, strategyExplanationCoverage: 0,
          strategyDistribution: [], totalSessionCount: 0, activeSessionCount: 0, completedSessionCount: 0,
          sessionCompletionRate: 0, activeMemoryCount: 0, suppressedMemoryCount: 0, expiredMemoryCount: 0,
          aiCallCount: 0, successfulAiCallCount: 0, failedAiCallCount: 0, aiFailureRate: 0,
          aiLatencySampleCount: 0, averageAiDurationMs: 0, slowAiCallCount: 0, totalAiTokens: 0
        }
      }
      if (data === undefined) {
        unexpected.push(`${request.method()} ${url.pathname}`)
        return route.abort('blockedbyclient')
      }
      return route.fulfill({ json: { code: 200, message: 'success', data } })
    })
    const page = await context.newPage()
    page.on('pageerror', error => errors.push(error.message))
    for (const width of [320, 360, 390, 768, 860, 861, 1440]) {
      await page.setViewportSize({ width, height: 800 })
      await page.goto(`${baseURL}/library?conversationId=1`)
      await page.getByRole('button', { name: '在主聊天继续', exact: true }).waitFor()
      await page.locator('.library-qa .source-summary small').filter({ hasText: '教材选择已随会话保存' }).waitFor()
      const geometry = await page.evaluate(() => {
        const panel = window.document.querySelector('.library-qa')
        const bounds = panel.getBoundingClientRect()
        const textarea = panel.querySelector('textarea').getBoundingClientRect()
        const buttons = [...panel.querySelectorAll('.section-head button')].map(button => {
          const box = button.getBoundingClientRect()
          return { text: button.textContent.trim(), left: box.left, right: box.right,
            inside: box.left >= bounds.left && box.right <= bounds.right && button.scrollWidth <= button.clientWidth + 1 }
        })
        const summary = panel.querySelector('.source-summary')
        const content = window.document.querySelector('.workspace-content')
        const topbar = window.document.querySelector('.workspace-topbar')
        const panels = [...window.document.querySelectorAll('.library-page > .panel')].map(element => ({
          name: element.className,
          overflow: element.scrollWidth > element.clientWidth + 1
        }))
        return { textareaTop: textarea.top, textareaBottom: textarea.bottom, buttons,
          topbarHeight: topbar.getBoundingClientRect().height,
          panelOverflow: panel.scrollWidth > panel.clientWidth + 1,
          summaryOverflow: summary.scrollWidth > summary.clientWidth + 1,
          contentOverflow: content.scrollWidth > content.clientWidth + 1, panels,
          pageOverflow: window.document.documentElement.scrollWidth > innerWidth }
      })
      measurements.push({ width, ...geometry })
      check(`${width}px: workspace header reserves only necessary space`, width <= 860
        ? geometry.topbarHeight <= 52 : geometry.topbarHeight === 0, geometry.topbarHeight)
      check(`${width}px: header actions fit inside panel`, geometry.buttons.every(b => b.inside), geometry.buttons)
      check(`${width}px: no page or panel clipping`, !geometry.pageOverflow && !geometry.panelOverflow && !geometry.summaryOverflow, geometry)
      check(`${width}px: all library panels fit workspace`, !geometry.contentOverflow && geometry.panels.every(p => !p.overflow), geometry.panels)
      if (width <= 860) {
        check(`${width}px: question composer visible in first viewport`, geometry.textareaTop >= 0 && geometry.textareaBottom < 800, geometry.textareaBottom)
      } else {
        check(`${width}px: desktop navigation visible`, await page.locator('.workspace-sidebar').first().isVisible())
        check(`${width}px: mobile navigation trigger hidden`, !await page.getByRole('button', { name: '打开导航', exact: true }).isVisible())
      }
      if ([360, 390, 1440].includes(width)) await page.screenshot({ path: path.join(output, `library-${width}.png`), fullPage: true, animations: 'disabled' })
    }

    showLongDirection = true
    for (const width of [320, 360, 861, 1440]) {
      await page.setViewportSize({ width, height: 800 })
      await page.goto(`${baseURL}/chat?conversationId=1`)
      await page.locator('.chat-main .message.assistant').waitFor()
      await page.locator('.topbar-actions .el-tag').waitFor({ state: 'attached' })
      const geometry = await page.evaluate(() => {
        const header = document.querySelector('.workspace-topbar')
        const bounds = header.getBoundingClientRect()
        const title = header.querySelector('h1')
        const content = document.querySelector('.workspace-content')
        const contentBounds = content.getBoundingClientRect()
        const chatBounds = document.querySelector('.chat-workbench').getBoundingClientRect()
        const buttons = [...header.querySelectorAll('button')].filter(button => button.getBoundingClientRect().width > 0)
        return { headerHeight: bounds.height, titleFits: title.scrollWidth <= title.clientWidth + 1,
          buttonsFit: buttons.every(button => {
            const box = button.getBoundingClientRect()
            return box.left >= bounds.left && box.right <= bounds.right && box.top >= bounds.top && box.bottom <= bounds.bottom
          }), contentOverflow: content.scrollWidth > content.clientWidth + 1,
          remainingBottomSpace: contentBounds.bottom - parseFloat(getComputedStyle(content).paddingBottom) - chatBounds.bottom }
      })
      measurements.push({ page: 'chat', width, longDirection: true, ...geometry })
      check(`${width}px: long profile does not enlarge or crowd chat header`, (width <= 860 ? geometry.headerHeight <= 52 : geometry.headerHeight === 0)
        && geometry.titleFits && geometry.buttonsFit && !geometry.contentOverflow, geometry)
      if (width > 860) check(`${width}px: chat uses all remaining content height`, Math.abs(geometry.remainingBottomSpace) <= 1, geometry)
      if ([360, 1440].includes(width)) await page.screenshot({ path: path.join(output, `chat-${width}.png`), fullPage: true, animations: 'disabled' })
    }
    showLongDirection = false

    await page.setViewportSize({ width: 360, height: 800 })
    await page.goto(`${baseURL}/library?conversationId=1`)
    await page.getByRole('button', { name: '在主聊天继续', exact: true }).waitFor()
    const menu = page.getByRole('button', { name: '打开导航', exact: true })
    check('mobile navigation trigger available', await menu.isVisible())
    if (await menu.isVisible()) {
      await menu.click()
      const drawer = page.getByRole('dialog', { name: '导航', exact: true })
      await drawer.waitFor()
      await drawer.locator('.sidebar-conversation-item').last().scrollIntoViewIfNeeded()
      check('all eight recent conversations reachable', await drawer.locator('.sidebar-conversation-item').count() === 8)
      await page.screenshot({ path: path.join(output, 'navigation-360.png'), animations: 'disabled' })
      await page.keyboard.press('Escape')
      await drawer.waitFor({ state: 'hidden' })
      await page.waitForFunction(() => window.document.activeElement?.getAttribute('aria-label') === '打开导航')
      check('Escape closes navigation and restores focus', await menu.getAttribute('aria-expanded') === 'false')

      await menu.click()
      await drawer.waitFor()
      await page.setViewportSize({ width: 1440, height: 800 })
      await drawer.waitFor({ state: 'hidden' })
      check('resizing to desktop closes drawer', await page.locator('.desktop-navigation').isVisible())
      await page.setViewportSize({ width: 360, height: 800 })

      await menu.click()
      await drawer.locator('.sidebar-conversation-item').nth(1).click()
      await drawer.waitFor({ state: 'hidden' })
      await page.waitForURL('**/chat?conversationId=2')
      check('selecting conversation closes drawer and keeps conversation', true)
      await page.getByText('当前教材：数学走查.txt', { exact: false }).waitFor()

      await menu.click()
      await drawer.getByRole('link', { name: 'AI Chat', exact: true }).click()
      await drawer.waitFor({ state: 'hidden' })
      check('same-route navigation closes drawer', true)

      await menu.click()
      await drawer.getByRole('button', { name: '新学习会话', exact: true }).click()
      await drawer.waitFor({ state: 'hidden' })
      await page.waitForURL('**/chat')
      await page.waitForFunction(() => !window.document.querySelector('.chat-main .source-summary'))
      check('new session clears selected conversation', true)

      await menu.click()
      await drawer.getByRole('link', { name: '学习档案', exact: true }).click()
      await drawer.waitFor({ state: 'hidden' })
      await page.waitForURL('**/profile')
      check('profile remains reachable', true)

      await menu.click()
      await drawer.getByRole('button', { name: '退出登录', exact: true }).click()
      await page.waitForURL('**/login')
      check('logout clears authentication', await page.evaluate(() => !localStorage.getItem('ai_tutor_token')))
    }
    check('no unexpected API or external requests', unexpected.length === 0, unexpected)
    check('no page script errors', errors.length === 0, errors)
    await context.close()
  } finally {
    await browser.close()
    await fs.writeFile(path.join(output, 'result.json'), JSON.stringify({ checks, measurements, unexpected, errors }, null, 2))
  }
  console.log(JSON.stringify({ passed: checks.filter(c => c.passed).length, failed: checks.filter(c => !c.passed), output }, null, 2))
  assert.equal(checks.filter(c => !c.passed).length, 0, 'Mobile layout regression checks failed')
}

main().catch(error => { console.error(error); process.exitCode = 1 })
