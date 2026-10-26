import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { baseCompile } from '@intlify/message-compiler'
const locales = await Promise.all(['en', 'ru'].map(async locale => JSON.parse(await readFile(new URL(`../web/${locale}.json`, import.meta.url), 'utf8'))))
assert.deepEqual(Object.keys(locales[0]).sort(), Object.keys(locales[1]).sort())
for (const messages of locales) for (const [key, message] of Object.entries(messages)) {
  baseCompile(message, { onError(error) { throw Error(`${key}: ${error.message}`) } })
}
console.log('Python settings: RU/EN keys and message syntax passed')
