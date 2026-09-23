<script setup lang="ts">
import { computed, onMounted, shallowRef } from 'vue'
import { useRouter } from 'vue-router'
import { importApi } from '../../api/endpoints'
import { useTaskEvents } from '../../composables/useTaskEvents'
import type { MediaImportStatus, VideoImportDetail, VideoImportJobStatus } from '../../types/import'
import Badge from '../ui/Badge.vue'
import Button from '../ui/Button.vue'
import Spinner from '../ui/Spinner.vue'

const props = defineProps<{ importId: number }>()
const emit = defineEmits<{ finished: [importId: number] }>()
const router = useRouter()

const eventState = shallowRef<'PROCESSING' | 'COMPLETED' | 'FAILED'>('PROCESSING')
const detail = shallowRef<VideoImportDetail | null>(null)
const fatalError = shallowRef('')
const retrying = shallowRef(false)
const missingReference = shallowRef(false)

const terminalStatuses = new Set<VideoImportJobStatus>(['COMPLETED', 'PARTIAL_SUCCESS', 'FAILED'])

const mediaStatusLabels: Record<MediaImportStatus, string> = {
  PENDING_DISPATCH: '等待调度',
  QUEUED: '等待下载',
  ACQUIRING: '正在下载视频',
  MEDIA_READY: '视频已入库',
  ANALYSIS_QUEUED: '等待生成笔记',
  ANALYZING: '正在生成字幕、笔记与知识索引',
  READY: '处理完成',
  DISPATCH_FAILED: '调度失败',
  FAILED: '处理失败',
  COMPLETED: '处理完成'
}

const events = useTaskEvents(importApi.eventsUrl(props.importId), {
  onEvent: (event) => {
    eventState.value = event.state
  },
  onTerminal: async (event) => {
    eventState.value = event.state
    const current = await refreshDetail()
    if (event.state === 'COMPLETED' || current?.status === 'COMPLETED') {
      emit('finished', props.importId)
    }
  },
  onFatal: (error) => {
    fatalError.value = error.message
    void refreshDetail()
  }
})

onMounted(() => {
  void initialize()
})

async function refreshDetail(): Promise<VideoImportDetail | null> {
  try {
    const current = await importApi.detail(props.importId)
    detail.value = current
    fatalError.value = ''
    return current
  } catch (error) {
    if ((error as Error & { status?: number }).status === 404) {
      missingReference.value = true
      emit('finished', props.importId)
      return null
    }
    fatalError.value = error instanceof Error ? error.message : '无法读取导入任务状态'
    return null
  }
}

async function initialize() {
  const current = await refreshDetail()
  if (missingReference.value) return
  if (current?.status === 'COMPLETED') {
    emit('finished', props.importId)
    return
  }
  if (current && terminalStatuses.has(current.status)) return
  events.start()
}

const displayStatus = computed<VideoImportJobStatus | 'EVENT_FAILED'>(() => {
  if (detail.value) return detail.value.status
  return eventState.value === 'FAILED' ? 'EVENT_FAILED' : eventState.value
})

const isCompleted = computed(() => displayStatus.value === 'COMPLETED')
const isFailed = computed(() =>
  displayStatus.value === 'FAILED' || displayStatus.value === 'DISPATCH_FAILED' || displayStatus.value === 'EVENT_FAILED'
)
const isPartial = computed(() => displayStatus.value === 'PARTIAL_SUCCESS')
const isProcessing = computed(() => !isCompleted.value && !isFailed.value && !isPartial.value)

/** 强制登录：缺失或过期的 Cookie 需要用户去设置页处理，重试无意义。 */
const isCookieError = computed(() => {
  const code = detail.value?.errorCode
  return code === 'BILIBILI_COOKIE_REQUIRED' || code === 'BILIBILI_COOKIE_EXPIRED'
})

const badgeTone = computed(() => {
  if (isCompleted.value) return 'success'
  if (isFailed.value || isPartial.value) return 'danger'
  return 'processing'
})

const stateLabel = computed(() => {
  if (isCompleted.value) return '导入完成'
  if (isPartial.value) return '部分完成'
  if (isFailed.value) return '导入失败'
  return '处理中'
})

const progressLabel = computed(() => {
  const current = detail.value
  if (!current) return '正在恢复任务状态'
  if (current.status === 'PENDING_DISPATCH') return '等待任务调度'
  if (current.status === 'QUEUED') return '任务排队中'
  if (current.status === 'RESOLVING') return '正在解析视频链接'
  if (current.status === 'DISPATCH_FAILED') return '任务调度失败'
  if (current.status === 'FAILED') return current.errorMessage || '视频导入失败'
  if (current.status === 'PARTIAL_SUCCESS') {
    return `已完成 ${current.counts.completed}/${current.counts.total}，失败 ${current.counts.failed}`
  }
  if (current.status === 'COMPLETED') return '视频、笔记与知识索引均已就绪'
  const activeItem = current.items.find((item) => !['READY', 'FAILED', 'COMPLETED'].includes(item.status))
  if (activeItem) return mediaStatusLabels[activeItem.status]
  return `已完成 ${current.counts.completed}/${current.counts.total}`
})

async function retry() {
  retrying.value = true
  try {
    await importApi.retry(props.importId)
    eventState.value = 'PROCESSING'
    fatalError.value = ''
    await refreshDetail()
    events.start()
  } catch (err) {
    fatalError.value = err instanceof Error ? err.message : '重试失败'
  } finally {
    retrying.value = false
  }
}
</script>

<template>
  <div class="strip" role="status" aria-live="polite">
    <Spinner v-if="isProcessing" :size="14" />
    <Badge :tone="badgeTone">{{ stateLabel }}</Badge>
    <span class="strip__text">视频导入任务 #{{ importId }}</span>
    <span class="strip__hint">{{ progressLabel }}</span>
    <span v-if="fatalError" class="strip__error">{{ fatalError }}</span>
    <Button
      v-if="isFailed && isCookieError"
      variant="secondary"
      @click="router.push({ name: 'settings' })"
    >
      去设置页更新 Cookie
    </Button>
    <Button
      v-if="(isFailed || isPartial) && (detail?.retryable ?? true)"
      variant="secondary"
      :loading="retrying"
      @click="retry"
    >
      重试
    </Button>
    <button v-if="!isProcessing" class="strip__dismiss" aria-label="关闭" @click="emit('finished', importId)">
      ×
    </button>
  </div>
</template>

<style scoped>
.strip {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 10px 16px;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-card);
}
.strip__text {
  color: var(--color-text-body);
  font-size: var(--text-sm);
}
.strip__hint {
  color: var(--color-text-muted);
  font-size: var(--text-xs);
}
.strip__error {
  color: var(--color-danger);
  font-size: var(--text-sm);
}
.strip__dismiss {
  margin-left: auto;
  border: 0;
  background: transparent;
  font-size: 18px;
  line-height: 1;
  color: var(--color-text-muted);
  padding: 2px 6px;
}
</style>
