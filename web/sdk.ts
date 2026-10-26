import type { DevLineWebviewContext, DevLineWebviewSdk } from '@devline/extension-devkit'
export type Context = DevLineWebviewContext
type SDK = DevLineWebviewSdk<Context>

declare global { interface Window { devline: SDK } }
export const sdk = window.devline
