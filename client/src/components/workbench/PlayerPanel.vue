<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { apiRequest } from '../../api/http'
import { useWorkbenchStore } from '../../stores/workbench'
import Spinner from '../ui/Spinner.vue'
import Button from '../ui/Button.vue'

const props = defineProps<{ mediaId: number; title?: string | null }>()
const workbench = useWorkbenchStore()

const videoEl = ref<HTMLVideoElement | null>(null)
const src = ref('')
const loading = ref(true)
const error = ref('')

let seeking = false
let seekTarget = 0

async function load() {
  loading.value = true
  error.value = ''
  try {
    const url = await apiRequest<string>(`/media/playback?id=${props.mediaId}`)
    if (!url) throw new Error('未取得播放地址')
    src.value = url
  } catch (err) {
    src.value = ''
    error.value = err instanceof Error ? err.message : '视频加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)

// 证据跳转（store.seekTo）→ 播放器定位；用户手动拖动 → timeupdate 回写 store（联动高亮）
watch(
  () => workbench.playbackTimeMs,
  (ms) => {
    const video = videoEl.value
    if (!video || video.readyState < 1) return
    const target = ms / 1000
    if (Math.abs(video.currentTime - target) > 0.4) {
      seeking = true
      seekTarget = target
      video.currentTime = target
    }
  }
)

function onTimeUpdate() {
  const video = videoEl.value
  if (!video) return
  if (seeking) {
    if (Math.abs(video.currentTime - seekTarget) < 0.3) seeking = false
    return
  }
  workbench.playbackTimeMs = Math.round(video.currentTime * 1000)
}
</script>

<template>
  <div class="player">
    <div class="player__stage">
      <video
        v-if="src"
        ref="videoEl"
        class="player__video"
        :src="src"
        controls
        playsinline
        preload="metadata"
        @timeupdate="onTimeUpdate"
      ></video>
      <div v-else-if="loading" class="player__state">
        <Spinner :size="18" />
        <span>正在载入视频…</span>
      </div>
      <div v-else class="player__state">
        <span class="player__error">{{ error }}</span>
        <Button variant="secondary" @click="load">重新加载</Button>
      </div>
    </div>
    <p class="player__caption">{{ title || `视频 #${mediaId}` }}</p>
  </div>
</template>

<style scoped>
.player {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.player__stage {
  aspect-ratio: 16 / 9;
  background: #20231f;
  border-radius: var(--radius-card);
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}
.player__video {
  width: 100%;
  height: 100%;
  display: block;
}
.player__state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}
.player__error {
  color: #e8b3ab;
}
.player__caption {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
