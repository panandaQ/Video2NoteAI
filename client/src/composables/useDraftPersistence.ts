import type { ComputedRef } from 'vue'
import {
  knowledgeMemory,
  type CachedConversation,
  type KnowledgeMemoryService,
  type PendingQuestion
} from '../storage/knowledgeMemory'
import type { KnowledgeConversation, KnowledgeTurn } from '../types/knowledge'

export type { PendingQuestion } from '../storage/knowledgeMemory'

/** 用户隔离的设备记忆 facade；组件与会话编排不直接接触 IndexedDB。 */
export function useDraftPersistence(
  userId: ComputedRef<number | null>,
  memory: KnowledgeMemoryService = knowledgeMemory
) {
  return {
    getDraft(mediaId: number): Promise<string> {
      return memory.getDraft(userId.value, mediaId)
    },
    setDraft(mediaId: number, text: string): Promise<boolean> {
      return memory.setDraft(userId.value, mediaId, text)
    },
    getPending(): Promise<PendingQuestion | null> {
      return memory.getPending(userId.value)
    },
    setPending(pending: PendingQuestion): Promise<boolean> {
      return memory.setPending(userId.value, pending)
    },
    clearPending(): Promise<boolean> {
      return memory.clearPending(userId.value)
    },
    getConversationRef(mediaId: number) {
      return memory.getConversationRef(userId.value, mediaId)
    },
    setConversationRef(
      mediaId: number,
      conversationId: number,
      version: number,
      lastTurnNo = 0
    ): Promise<boolean> {
      return memory.setConversationRef(userId.value, mediaId, conversationId, version, lastTurnNo)
    },
    clearConversationRef(mediaId: number): Promise<boolean> {
      return memory.clearConversationRef(userId.value, mediaId)
    },
    getCachedConversation(mediaId: number): Promise<CachedConversation | null> {
      return memory.getCachedConversation(userId.value, mediaId)
    },
    saveConversation(head: KnowledgeConversation, turns: KnowledgeTurn[]): Promise<boolean> {
      return memory.saveConversation(userId.value, head, turns)
    },
    removeConversation(conversationId: number): Promise<boolean> {
      return memory.removeConversation(userId.value, conversationId)
    }
  }
}

export type KnowledgePersistence = ReturnType<typeof useDraftPersistence>
