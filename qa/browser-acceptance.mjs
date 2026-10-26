import assert from 'node:assert/strict'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve, join } from 'node:path'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { setTimeout as delay } from 'node:timers/promises'

// A frozen distribution and a fresh output directory are required. Never touches the user's IDE/profile.
const distribution = resolve(process.argv[2]), output = resolve(process.argv[3])
await mkdir(output, { recursive: false })
const fixture = join(output, 'project юникод'), profile = join(output, 'profile')
await mkdir(fixture); await mkdir(join(profile, '.devline'), { recursive: true })
await writeFile(join(profile, '.devline/preferences.properties'), 'locale=ru\ntheme=light\nsplashEnabled=false\nnotificationSound=false\n')
const descriptor = join(fixture, 'Python.dlproject')
await writeFile(descriptor, JSON.stringify({ schemaVersion: 1, name: 'Python Runner QA', language: 'php', forms: [], dependencies: [], assets: [], controllerPolicy: 'native' }))
const script = join(fixture, 'hello юникод.py')
await writeFile(script, 'import pathlib\nprint("READY_ПРИВЕТ", flush=True)\nvalue = input()\nprint("INPUT_" + value, flush=True)\npathlib.Path("result.txt").write_text(value, encoding="utf-8")\n')
await writeFile(join(fixture, 'notes.txt'), 'ordinary text\n')
const { chromium } = await import(process.env.DEVLINE_PLAYWRIGHT_MODULE ?? 'playwright')
let browser, host, exited, page, url, log = ''
const proof = { serviceOnly: true, headless: true }, errors = []
async function until(check, message, timeout = 20000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) { const value = await check(); if (value) return value; await delay(100) }
  throw Error(message)
}
async function rpc(method, params = {}) {
  return page.evaluate(({ method, params }) => new Promise((resolve, reject) => window.cefQuery({ request: JSON.stringify({ id: 'python-owned-qa', method, params }),
    onSuccess: raw => { const reply = JSON.parse(raw); reply.error ? reject(Error(JSON.stringify(reply.error))) : resolve(reply.result) }, onFailure: (_, message) => reject(Error(message)) })), { method, params })
}
const owner = { expectedProjectFile: descriptor }
async function readTerminal(id) { return rpc('terminal.read', { ...owner, sessionId: id, cursor: 0 }) }
async function newSession(before = []) { return until(async () => (await rpc('terminal.list', owner)).find(item => item.program && !before.includes(item.id)), 'No Python terminal appeared') }
async function openSettings() {
  await page.keyboard.press('Control+,')
  await page.locator('#settings-extensions-tab').click()
  const expand = page.locator('.settings-category__expand[aria-label="Расширения"]')
  if (await expand.getAttribute('aria-expanded') !== 'true') await expand.click()
  await page.getByRole('tab', { name: 'Python', exact: true }).click()
  const frame = page.frameLocator('[data-extension-panel="devline.python:settings"] iframe')
  await frame.getByRole('textbox', { name: 'Интерпретатор', exact: true }).waitFor()
  return frame
}
try {
  const java = process.env.DEVLINE_QA_JAVA ?? resolve('devline-toolchain/build/install/application-toolchain/runtime/bin/java' + (process.platform === 'win32' ? '.exe' : ''))
  host = spawn(java, ['-Djava.awt.headless=true', '-Dfile.encoding=UTF-8', `-Duser.home=${profile}`, '--enable-native-access=ALL-UNNAMED', '-cp', join(distribution, 'lib/*'), 'devline.desktop.MainKt', '--browser-ui', '--home', distribution, descriptor], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] })
  exited = once(host, 'exit')
  host.stderr.on('data', data => { log += data.toString() })
  url = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(Error('Host timeout: ' + log)), 30000)
    host.stdout.on('data', data => { log += data.toString(); const match = /DevLine browser development mode: (http:\/\/127\.0\.0\.1:\d+\/)/.exec(log); if (match) { clearTimeout(timer); resolve(match[1]) } })
    host.once('exit', code => { clearTimeout(timer); reject(Error('Host exited: ' + code + log)) })
  })
  browser = await chromium.launch({ headless: true })
  page = await browser.newPage({ viewport: { width: 1440, height: 960 } })
  page.on('pageerror', cause => errors.push(String(cause)))
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()) })
  await page.goto(url)
  await page.locator('.project-root').waitFor()
  assert.equal((await rpc('app.info')).nativeSplash, false)
  const initial = (await rpc('ide.extensions.list')).extensions.find(item => item.manifest.id === 'devline.python')
  assert.ok(initial.enabled && initial.installed && !initial.restartRequired)
  proof.bundledEnabled = true
  let frame = await openSettings()
  const args = frame.getByRole('textbox', { name: 'Аргументы скрипта', exact: true })
  await args.fill('bad json')
  await frame.getByRole('button', { name: 'Сохранить', exact: true }).isDisabled().then(assert.ok)
  assert.equal(await args.getAttribute('aria-invalid'), 'true')
  await args.fill('["literal & dollar$() привет"]')
  await frame.getByRole('button', { name: 'Сохранить', exact: true }).click()
  await frame.getByText('Сохранено', { exact: true }).waitFor()
  await frame.getByRole('combobox', { name: 'Настройки для', exact: true }).click()
  await frame.getByRole('option', { name: 'Текущего проекта', exact: true }).click()
  await frame.getByText('Используются настройки для всех проектов', { exact: true }).waitFor()
  await frame.getByRole('textbox', { name: 'Рабочая папка', exact: true }).fill('.')
  await frame.getByRole('button', { name: 'Сохранить', exact: true }).click()
  await frame.getByText('Сохранено', { exact: true }).waitFor()
  assert.equal((await rpc('pythonRunner.settings', { scope: 'project', projectFile: descriptor })).settings.directory, '.')
  await frame.getByRole('button', { name: 'Сбросить', exact: true }).click()
  await frame.getByText('Используются настройки для всех проектов', { exact: true }).waitFor()
  const geometry = await frame.getByRole('textbox', { name: 'Интерпретатор', exact: true }).boundingBox()
  assert.ok(geometry.height <= 32)
  await page.screenshot({ path: join(output, 'settings-light-ru.png') })
  proof.settings = { registered: true, validation: true, saved: true, projectOverrideAndReset: true, controlHeight: geometry.height }
  await page.locator('.ui-modal__close').last().click()
  await page.locator('.tree-row[data-path="hello юникод.py"]').click()
  await page.locator('.monaco-editor textarea').first().waitFor()
  const run = page.getByRole('button', { name: 'Запустить Python-файл', exact: true })
  await until(() => run.isEnabled(), 'Python source did not finish loading')
  await until(async () => (await page.locator('.monaco-editor .view-lines').first().textContent()).includes('READY_ПРИВЕТ'), 'Source model did not load')
  await page.locator('.monaco-editor .view-lines').first().click({ position: { x: 30, y: 10 } })
  await page.keyboard.press('Control+End')
  await page.keyboard.press('Enter')
  await page.keyboard.type('print("SAVED_DRAFT", flush=True)')
  await until(async () => (await page.locator('.monaco-editor .view-lines').first().textContent()).includes('SAVED_DRAFT'), 'Typing did not update the editor')
  assert.ok(!(await readFile(script, 'utf8')).includes('SAVED_DRAFT'))
  await run.click()
  const session = await newSession()
  await until(async () => (await readTerminal(session.id)).data.includes('READY_ПРИВЕТ'), 'Python did not print UTF-8')
  await page.locator('.terminal-panel .xterm-helper-textarea').last().focus()
  await page.keyboard.insertText('проверка ввода')
  await page.keyboard.press('Enter')
  await until(async () => (await readTerminal(session.id)).exited, 'Python did not exit after terminal input')
  const firstOutput = await readTerminal(session.id)
  assert.equal(firstOutput.exitCode, 0)
  assert.ok(firstOutput.data.includes('SAVED_DRAFT'))
  assert.equal(await readFile(join(fixture, 'result.txt'), 'utf8'), 'проверка ввода')
  assert.ok((await readFile(script, 'utf8')).includes('SAVED_DRAFT'))
  proof.editorRun = { savedDraft: true, utf8: true, stdin: true, exitCode: firstOutput.exitCode }
  await page.locator('.terminal-panel').getByRole('button', { name: 'Перезапустить терминал', exact: true }).click()
  await until(async () => { const output = await readTerminal(session.id); return !output.exited && output.data.includes('READY_ПРИВЕТ') }, 'Restart did not start Python')
  await page.locator('.terminal-panel').getByRole('button', { name: 'Остановить', exact: true }).click()
  await until(async () => (await readTerminal(session.id)).exited, 'Stop did not finish the process')
  proof.terminalRestartAndStop = true
  await page.locator('.tree-row[data-path="hello юникод.py"]').click({ button: 'right' })
  await page.getByRole('menuitem', { name: 'Запустить Python-файл', exact: true }).click()
  const second = await newSession([session.id])
  await until(async () => (await readTerminal(second.id)).data.includes('READY_ПРИВЕТ'), 'Explorer run did not execute')
  await rpc('terminal.stop', { ...owner, sessionId: second.id })
  proof.explorerRun = true
  await page.locator('.tab-select[data-path="hello юникод.py"]').click({ button: 'right' })
  await page.getByRole('menuitem', { name: 'Запустить Python-файл', exact: true }).waitFor()
  await page.keyboard.press('Escape')
  proof.tabMenu = true
  await page.locator('.tree-row[data-path="notes.txt"]').click()
  assert.equal(await run.count(), 0)
  proof.onlyPythonFiles = true
  await rpc('app.preferences.set', { theme: 'dark', locale: 'en' }).catch(() => {})
  // Apply persisted preferences using the public UI refresh, without reloading the project.
  await page.reload()
  await page.locator('.project-root').waitFor()
  await page.keyboard.press('Control+,')
  await page.locator('#settings-extensions-tab').click()
  const expand = page.locator('.settings-category__expand[aria-label="Extensions"]')
  const pythonTab = page.getByRole('tab', { name: 'Python', exact: true })
  if (!await pythonTab.count()) await expand.click()
  await pythonTab.click()
  frame = page.frameLocator('[data-extension-panel="devline.python:settings"] iframe')
  await frame.locator('input').first().waitFor()
  await page.screenshot({ path: join(output, 'settings-dark-en.png') })
  await page.locator('.ui-modal__close').last().click()
  const overview = await rpc('ide.extensions.list')
  await rpc('ide.extensions.setEnabled', { id: 'devline.python', enabled: false, expectedVersion: overview.version })
  await page.reload()
  await page.locator('.tree-row[data-path="hello юникод.py"]').click()
  assert.equal(await page.getByRole('button', { name: /Run Python file|Запустить Python-файл/ }).count(), 0)
  proof.disableRemovesActions = true
  assert.deepEqual(errors, [])
  proof.errors = errors
  console.log('PASS: production Main, bundled enablement, settings registration, save/validation, draft save, Python editor/explorer/tab actions, real UTF-8 stdin/output, Stop/Restart and disable')
} finally {
  proof.errors = errors
  if (page) proof.frames = page.frames().map(frame => frame.url())
  if (page) await page.screenshot({ path: join(output, 'final.png') }).catch(() => {})
  // Stop project-owned programs before terminating the owned service-only JVM on Windows.
  if (page && host?.exitCode === null) {
    const sessions = await rpc('terminal.list', owner).catch(() => [])
    for (const session of sessions) await rpc('terminal.close', { ...owner, sessionId: session.id }).catch(() => {})
  }
  await browser?.close()
  if (host?.exitCode === null) host.kill()
  if (exited) await exited
  proof.ownedHostExited = host?.exitCode !== null || host?.signalCode !== null
  await writeFile(join(output, 'host.log'), log)
  await writeFile(join(output, 'proof.json'), JSON.stringify(proof, null, 2) + '\n')
}
