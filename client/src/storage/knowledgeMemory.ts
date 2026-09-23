import type { KnowledgeConversation, KnowledgeTurn } from '../types/knowledge'
import {
  IndexedDbKnowledgeStorageDriver,
  type KnowledgeStorageDriver,
  type KnowledgeStoreName,
  type StorageMutation
} from './knowledgeStorageDriver'

const DAY_MS = 24 * 60 * 60 * 1000
const MAX_CONVERSATIONS_PER_USER = 20
const MAX_TURNS_PER_CONVERSATION = 10
const MAX_CACHE_BYTES_PER_USER = 5 * 1024 * 1024

export interface PendingQuestion {
  requestId: string
  question: string
  conversationId: number | null
  turnId: number | null
  mediaId: number
}

interface ConversationHeadRecord extends KnowledgeConversation {
  userId: number
  cachedAt: number
  lastAccessedAt: number
}

interface CompletedTurnRecord extends KnowledgeTurn {
  userId: number
  conversationId: number
  cachedAt: number
}

interface DraftRecord {
  userId: number
  mediaId: number
  text: string
  savedAt: number
  expiresAt: number
}

interface PendingQuestionRecord extends PendingQuestion {
  userId: number
  savedAt: number
  expiresAt: number
}

export interface ConversationReference {
  conversationId: number
  version: number
}

interface ConversationRefRecord extends ConversationReference {
  userId: number
  mediaId: number
  lastTurnNo: number
  lastAccessedAt: number
}

export interface CachedConversation {
  head: KnowledgeConversation
  turns: KnowledgeTurn[]
}

function headValue(record: ConversationHeadRecord): KnowledgeConversation {
  const { userId: _userId, cachedAt: _cachedAt, lastAccessedAt: _lastAccessedAt, ...head } = record
  return head
}

function turnValue(record: CompletedTurnRecord): KnowledgeTurn {
  const { userId: _userId, conversationId: _conversationId, cachedAt: _cachedAt, ...turn } = record
  return turn
}

function byteSize(value: unknown): number {
  return new TextEncoder().encode(JSON.stringify(value)).byteLength
}

function belongsToUser(record: unknown, userId: number): boolean {
  return (record as { userId?: number }).userId === userId
}

/**
 * IndexedDB 上的设备级只读视图。任何 driver 故障都降级为空缓存，不能阻塞服务端真源链路。
 */
export class KnowledgeMemoryService {
  constructor(
    private readonly driver: KnowledgeStorageDriver,
    private readonly now: () => number = Date.now
  ) {}

  async getDraft(userId: number | null, mediaId: number): Promise<string> {
    if (userId === null) return ''
    return this.safe(async () => {
      const record = await this.driver.get<DraftRecord>('drafts', [userId, mediaId])
      if (!record) return ''
      if (record.expiresAt <= this.now()) {
        await this.driver.apply([{ type: 'delete', store: 'drafts', key: [userId, mediaId] }])
        return ''
      }
      return record.text
    }, '')
  }

  async setDraft(userId: number | null, mediaId: number, text: string): Promise<boolean> {
    if (userId === null) return false
    return this.safe(async () => {
      const mutation: StorageMutation = text
        ? {
            type: 'put',
            store: 'drafts',
            value: {
              userId,
              mediaId,
              text,
              savedAt: this.now(),
              expiresAt: this.now() + DAY_MS
            } satisfies DraftRecord
          }
        : { type: 'delete', store: 'drafts', key: [userId, mediaId] }
      await this.driver.apply([mutation])
      await this.pruneToBudget(userId)
      return true
    }, false)
  }

  async getPending(userId: number | null): Promise<PendingQuestion | null> {
    if (userId === null) return null
    return this.safe(async () => {
      const record = await this.driver.get<PendingQuestionRecord>('pendingQuestions', userId)
      if (!record) return null
      if (record.expiresAt <= this.now()) {
        await this.driver.apply([{ type: 'delete', store: 'pendingQuestions', key: userId }])
        return null
      }
      const { userId: _userId, savedAt: _savedAt, expiresAt: _expiresAt, ...pending } = record
      return pending
    }, null)
  }

  async setPending(userId: number | null, pending: PendingQuestion): Promise<boolean> {
    if (userId === null) return false
    return this.safe(async () => {
      const savedAt = this.now()
      await this.driver.apply([
        {
          type: 'put',
          store: 'pendingQuestions',
          value: { userId, ...pending, savedAt, expiresAt: savedAt + DAY_MS } satisfies PendingQuestionRecord
        }
      ])
      await this.pruneToBudget(userId)
      return true
    }, false)
  }

  async clearPending(userId: number | null): Promise<boolean> {
    if (userId === null) return false
    return this.safe(async () => {
      await this.driver.apply([{ type: 'delete', store: 'pendingQuestions', key: userId }])
      return true
    }, false)
  }

  async getConversationRef(userId: number | null, mediaId: number): Promise<ConversationReference | null> {
    if (userId === null) return null
    return this.safe(async () => {
      const record = await this.driver.get<ConversationRefRecord>('conversationRefs', [userId, mediaId])
      if (!record) return null
      return { conversationId: record.conversationId, version: record.version }
    }, null)
  }

  async setConversationRef(
    userId: number | null,
    mediaId: number,
    conversationId: number,
    version: number,
    lastTurnNo = 0
  ): Promise<boolean> {
    if (userId === null) return false
    return this.safe(async () => {
      const existing = await this.driver.get<ConversationRefRecord>('conversationRefs', [userId, mediaId])
      if (
        existing &&
        (existing.version > version ||
          (existing.version === version && (existing.lastTurnNo ?? -1) > lastTurnNo))
      ) {
        return false
      }
      await this.driver.apply([
        {
          type: 'put',
          store: 'conversationRefs',
          value: {
            userId,
            mediaId,
            conversationId,
            version,
            lastTurnNo,
            lastAccessedAt: this.now()
          } satisfies ConversationRefRecord
        }
      ])
      return true
    }, false)
  }

  async clearConversationRef(userId: number | null, mediaId: number): Promise<boolean> {
    if (userId === null) return false
    return this.safe(async () => {
      await this.driver.apply([{ type: 'delete', store: 'conversationRefs', key: [userId, mediaId] }])
      return true
    }, false)
  }

  async getCachedConversation(userId: number | null, mediaId: number): Promise<CachedConversation | null> {
    if (userId === null) return null
    return this.safe(async () => {
      const reference = await this.driver.get<ConversationRefRecord>('conversationRefs', [userId, mediaId])
      if (!reference) return null
      const key: IDBValidKey = [userId, reference.conversationId]
      const head = await this.driver.get<ConversationHeadRecord>('conversationHeads', key)
      if (!head) return null
      if (head.scopeMediaId !== mediaId || head.version < reference.version) {
        await this.driver.apply([{ type: 'delete', store: 'conversationRefs', key: [userId, mediaId] }])
        return null
      }

      const turns = (await this.driver.getAll<CompletedTurnRecord>('completedTurns'))
        .filter((turn) => turn.userId === userId && turn.conversationId === head.conversationId)
        .sort((left, right) => left.turnNo - right.turnNo)
        .map(turnValue)
      const accessTime = this.now()
      await this.driver.apply([
        { type: 'put', store: 'conversationHeads', value: { ...head, lastAccessedAt: accessTime } },
        { type: 'put', store: 'conversationRefs', value: { ...reference, lastAccessedAt: accessTime } }
      ])
      return { head: headValue(head), turns }
    }, null)
  }

  async saveConversation(
    userId: number | null,
    head: KnowledgeConversation,
    turns: KnowledgeTurn[]
  ): Promise<boolean> {
    if (userId === null) return false
    return this.safe(async () => {
      const existingHead = await this.driver.get<ConversationHeadRecord>('conversationHeads', [
        userId,
        head.conversationId
      ])
      if (
        existingHead &&
        (existingHead.version > head.version ||
          (existingHead.version === head.version && existingHead.lastTurnNo > head.lastTurnNo))
      ) {
        return false
      }

      const cachedAt = this.now()
      const completed = turns
        .filter((turn) => turn.status === 'COMPLETED')
        .sort((left, right) => right.turnNo - left.turnNo)
        .slice(0, MAX_TURNS_PER_CONVERSATION)
        .sort((left, right) => left.turnNo - right.turnNo)
      const allTurns = await this.driver.getAll<CompletedTurnRecord>('completedTurns')
      const mutations: StorageMutation[] = allTurns
        .filter((turn) => turn.userId === userId && turn.conversationId === head.conversationId)
        .map((turn) => ({
          type: 'delete' as const,
          store: 'completedTurns' as const,
          key: [userId, head.conversationId, turn.turnId]
        }))

      mutations.push({
        type: 'put',
        store: 'conversationHeads',
        value: { ...head, userId, cachedAt, lastAccessedAt: cachedAt } satisfies ConversationHeadRecord
      })
      for (const turn of completed) {
        mutations.push({
          type: 'put',
          store: 'completedTurns',
          value: { ...turn, userId, conversationId: head.conversationId, cachedAt } satisfies CompletedTurnRecord
        })
      }
      if (head.scopeMediaId !== null) {
        mutations.push({
          type: 'put',
          store: 'conversationRefs',
          value: {
            userId,
            mediaId: head.scopeMediaId,
            conversationId: head.conversationId,
            version: head.version,
            lastTurnNo: head.lastTurnNo,
            lastAccessedAt: cachedAt
          } satisfies ConversationRefRecord
        })
      }

      const allHeads = await this.driver.getAll<ConversationHeadRecord>('conversationHeads')
      const headsById = new Map(
        allHeads.filter((record) => record.userId === userId).map((record) => [record.conversationId, record])
      )
      headsById.set(head.conversationId, { ...head, userId, cachedAt, lastAccessedAt: cachedAt })
      const evicted = [...headsById.values()]
        .sort((left, right) => right.lastAccessedAt - left.lastAccessedAt)
        .slice(MAX_CONVERSATIONS_PER_USER)
      const references = await this.driver.getAll<ConversationRefRecord>('conversationRefs')
      for (const evictedHead of evicted) {
        mutations.push(
          { type: 'delete', store: 'conversationHeads', key: [userId, evictedHead.conversationId] },
          ...allTurns
            .filter((turn) => turn.userId === userId && turn.conversationId === evictedHead.conversationId)
            .map((turn) => ({
              type: 'delete' as const,
              store: 'completedTurns' as const,
              key: [userId, evictedHead.conversationId, turn.turnId]
            })),
          ...references
            .filter((reference) => reference.userId === userId && reference.conversationId === evictedHead.conversationId)
            .map((reference) => ({
              type: 'delete' as const,
              store: 'conversationRefs' as const,
              key: [userId, reference.mediaId]
            }))
        )
      }

      await this.driver.apply(mutations)
      await this.pruneToBudget(userId)
      return true
    }, false)
  }

  async removeConversation(userId: number | null, conversationId: number): Promise<boolean> {
    if (userId === null) return false
    return this.safe(async () => {
      const turns = await this.driver.getAll<CompletedTurnRecord>('completedTurns')
      const references = await this.driver.getAll<ConversationRefRecord>('conversationRefs')
      await this.driver.apply([
        { type: 'delete', store: 'conversationHeads', key: [userId, conversationId] },
        ...turns
          .filter((turn) => turn.userId === userId && turn.conversationId === conversationId)
          .map((turn) => ({
            type: 'delete' as const,
            store: 'completedTurns' as const,
            key: [userId, conversationId, turn.turnId]
          })),
        ...references
          .filter((reference) => reference.userId === userId && reference.conversationId === conversationId)
          .map((reference) => ({
            type: 'delete' as const,
            store: 'conversationRefs' as const,
            key: [userId, reference.mediaId]
          }))
      ])
      return true
    }, false)
  }

  async clearUser(userId: number | null): Promise<boolean> {
    if (userId === null) return false
    return this.safe(async () => {
      const stores: KnowledgeStoreName[] = [
        'conversationHeads',
        'completedTurns',
        'drafts',
        'pendingQuestions',
        'conversationRefs'
      ]
      const mutations: StorageMutation[] = []
      for (const store of stores) {
        const records = await this.driver.getAll<Record<string, unknown>>(store)
        for (const record of records.filter((candidate) => belongsToUser(candidate, userId))) {
          mutations.push({ type: 'delete', store, key: this.keyFor(store, record) })
        }
      }
      await this.driver.apply(mutations)
      return true
    }, false)
  }

  private async pruneToBudget(userId: number): Promise<void> {
    const heads = (await this.driver.getAll<ConversationHeadRecord>('conversationHeads')).filter((record) =>
      belongsToUser(record, userId)
    )
    const turns = (await this.driver.getAll<CompletedTurnRecord>('completedTurns')).filter((record) =>
      belongsToUser(record, userId)
    )
    const drafts = (await this.driver.getAll<DraftRecord>('drafts')).filter((record) => belongsToUser(record, userId))
    const pending = (await this.driver.getAll<PendingQuestionRecord>('pendingQuestions')).filter((record) =>
      belongsToUser(record, userId)
    )
    const references = (await this.driver.getAll<ConversationRefRecord>('conversationRefs')).filter((record) =>
      belongsToUser(record, userId)
    )
    let size = byteSize({ heads, turns, drafts, pending, references })
    if (size <= MAX_CACHE_BYTES_PER_USER) return

    const mutations: StorageMutation[] = []
    for (const head of [...heads].sort((left, right) => left.lastAccessedAt - right.lastAccessedAt)) {
      mutations.push({ type: 'delete', store: 'conversationHeads', key: [userId, head.conversationId] })
      size -= byteSize(head)
      for (const turn of turns.filter((candidate) => candidate.conversationId === head.conversationId)) {
        mutations.push({
          type: 'delete',
          store: 'completedTurns',
          key: [userId, head.conversationId, turn.turnId]
        })
        size -= byteSize(turn)
      }
      for (const reference of references.filter((candidate) => candidate.conversationId === head.conversationId)) {
        mutations.push({ type: 'delete', store: 'conversationRefs', key: [userId, reference.mediaId] })
        size -= byteSize(reference)
      }
      if (size <= MAX_CACHE_BYTES_PER_USER) break
    }
    await this.driver.apply(mutations)
  }

  private keyFor(store: KnowledgeStoreName, record: Record<string, unknown>): IDBValidKey {
    const userId = record.userId as number
    switch (store) {
      case 'conversationHeads':
        return [userId, record.conversationId as number]
      case 'completedTurns':
        return [userId, record.conversationId as number, record.turnId as number]
      case 'drafts':
      case 'conversationRefs':
        return [userId, record.mediaId as number]
      case 'pendingQuestions':
        return userId
    }
  }

  private async safe<T>(operation: () => Promise<T>, fallback: T): Promise<T> {
    try {
      return await operation()
    } catch {
      return fallback
    }
  }
}

export const knowledgeMemory = new KnowledgeMemoryService(new IndexedDbKnowledgeStorageDriver())
