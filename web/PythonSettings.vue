<script setup lang="ts">
import { computed, onUnmounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { UIButton, UIInput, UISelect } from '@devline/ui-web/controls'
import { sdk } from './sdk'

interface Configuration { interpreter: string; directory: string; arguments: string[]; environment: Record<string, string> }
interface View { settings: Configuration; version: string; inherited: boolean; projectFile?: string; resolvedInterpreter: string }
const { t, locale } = useI18n()
const scope = ref('global'), projectFile = ref(''), ready = ref(false), busy = ref(false), error = ref(''), saved = ref(false)
const version = ref(''), inherited = ref(false), resolved = ref(''), baseline = ref('')
const draft = reactive({ interpreter: '', directory: '', arguments: '[]', environment: '{}' })
const dirty = computed(() => ready.value && JSON.stringify(draft) !== baseline.value)
const options = computed(() => [{ value: 'global', label: t('global') }, ...(projectFile.value ? [{ value: 'project', label: t('project') }] : [])])
function argumentsValue() { const value: unknown = JSON.parse(draft.arguments); if (!Array.isArray(value) || value.length > 64 || value.some(item => typeof item !== 'string' || item.length > 4096 || item.includes('\0'))) throw Error(); return value as string[] }
function environmentValue() { const value: unknown = JSON.parse(draft.environment); if (!value || Array.isArray(value) || typeof value !== 'object' || Object.keys(value).length > 64 || Object.entries(value).some(([key, item]) => !/^[A-Za-z_][A-Za-z0-9_]{0,127}$/.test(key) || typeof item !== 'string' || item.length > 4096 || item.includes('\0'))) throw Error(); return value as Record<string, string> }
const errors = computed(() => {
  const result: Record<string, string> = {}
  try { argumentsValue() } catch { result.arguments = t('invalidArguments') }
  try { environmentValue() } catch { result.environment = t('invalidEnvironment') }
  if (/^(?:[\\/]|[A-Za-z]:)/.test(draft.directory) || draft.directory.split(/[\\/]/).includes('..') || draft.directory.length > 4096 || draft.directory.includes('\0')) result.directory = t('invalidDirectory')
  if (draft.interpreter.length > 4096 || /[\u0000-\u001f\u007f]/.test(draft.interpreter)) result.interpreter = t('invalidInterpreter')
  return result
})
let disposed = false
function accept(value: View) {
  Object.assign(draft, value.settings, { arguments: JSON.stringify(value.settings.arguments), environment: JSON.stringify(value.settings.environment) })
  version.value = value.version; projectFile.value = value.projectFile ?? ''; inherited.value = value.inherited; resolved.value = value.resolvedInterpreter
  baseline.value = JSON.stringify(draft); ready.value = true
}
async function perform(method: string, selectedScope = scope.value) {
  if (busy.value) return
  busy.value = true; error.value = ''; saved.value = false
  try {
    const value = await sdk.call<View>(method, { scope: selectedScope, ...(selectedScope === 'project' ? { projectFile: projectFile.value } : {}),
      ...(method === 'settings' ? {} : { version: version.value }),
      ...(method === 'configure' ? { settings: { interpreter: draft.interpreter.trim(), directory: draft.directory.trim(), arguments: argumentsValue(), environment: environmentValue() } } : {}) })
    if (!disposed) { scope.value = selectedScope; accept(value); saved.value = method !== 'settings' }
  } catch (cause) { if (!disposed) { const code = (cause as { code?: string }).code; error.value = code && ['PYTHON_SETTINGS_ERROR','PYTHON_INVALID_SETTINGS','FILE_CONFLICT','PROJECT_CHANGED','NO_PROJECT'].includes(code) ? t(code) : t('failure') } }
  finally { if (!disposed) busy.value = false }
}
const unsubscribe = sdk.onContextChanged(context => { locale.value = context.locale.startsWith('ru') ? 'ru' : 'en'; if (context.visible && !dirty.value) void perform('settings') })
onUnmounted(() => { disposed = true; unsubscribe() })
void perform('settings')
</script>

<template>
  <section class="python-settings">
    <header><p>{{ t('description') }}</p></header>
    <label class="python-settings__field"><span>{{ t('scope') }}</span><UISelect :model-value="scope" :options="options" :aria-label="t('scope')" :disabled="busy || dirty" @update:model-value="perform('settings', String($event))" /></label>
    <p v-if="inherited" class="python-settings__hint" role="status">{{ t('inherited') }}</p>
    <template v-if="ready">
      <label class="python-settings__field"><span>{{ t('interpreter') }}</span><UIInput v-model="draft.interpreter"  :aria-label="t('interpreter')" :placeholder="t('auto')" :help-text="t('interpreterHelp')" :error-message="errors.interpreter" :disabled="busy" /></label>
      <p class="python-settings__hint is-selectable" role="status">{{ resolved ? `${t('resolved')}: ${resolved}` : t('missing') }}</p>
      <label class="python-settings__field"><span>{{ t('directory') }}</span><UIInput v-model="draft.directory"  :aria-label="t('directory')" placeholder="." :help-text="t('directoryHelp')" :error-message="errors.directory" :disabled="busy" /></label>
      <label class="python-settings__field"><span>{{ t('arguments') }}</span><UIInput v-model="draft.arguments"  :aria-label="t('arguments')" :help-text="t('argumentsHelp')" :error-message="errors.arguments" :disabled="busy" /></label>
      <label class="python-settings__field"><span>{{ t('environment') }}</span><UIInput v-model="draft.environment"  :aria-label="t('environment')" :help-text="t('environmentHelp', { example: JSON.stringify({ ASSET_MODE: 'preview' }) })" :error-message="errors.environment" :disabled="busy" /></label>
    </template>
    <p v-else role="status">{{ t('loading') }}</p>
    <div class="python-settings__actions">
      <UIButton :disabled="busy || !dirty || !!Object.keys(errors).length" @click="perform('configure')">{{ t('save') }}</UIButton>
      <UIButton variant="quiet" :disabled="busy" @click="perform('settings')">{{ t('reload') }}</UIButton>
      <UIButton variant="quiet" :disabled="busy || !ready" @click="perform('reset')">{{ t('reset') }}</UIButton>
      <span v-if="saved" role="status">{{ t('saved') }}</span>
    </div>
    <p v-if="error" class="python-settings__error" role="alert">{{ error }}</p>
  </section>
</template>
