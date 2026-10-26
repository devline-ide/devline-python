export interface Context { locale: string; theme: string; visible: boolean; colors?: Record<string, string> }
interface SDK {
  ready: Promise<Context>
  getContext(): Context
  onContextChanged(callback: (context: Context) => void): () => void
  call<T>(method: string, arguments_?: Record<string, unknown>): Promise<T>
}
declare global { interface Window { devline: SDK } }
export const sdk = window.devline
