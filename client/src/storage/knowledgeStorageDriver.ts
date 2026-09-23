export const KNOWLEDGE_STORE_NAMES = [
  'conversationHeads',
  'completedTurns',
  'drafts',
  'pendingQuestions',
  'conversationRefs'
] as const

export type KnowledgeStoreName = (typeof KNOWLEDGE_STORE_NAMES)[number]

export type StorageMutation =
  | { type: 'put'; store: KnowledgeStoreName; value: unknown }
  | { type: 'delete'; store: KnowledgeStoreName; key: IDBValidKey }

export interface KnowledgeStorageDriver {
  get<T>(store: KnowledgeStoreName, key: IDBValidKey): Promise<T | undefined>
  getAll<T>(store: KnowledgeStoreName): Promise<T[]>
  apply(mutations: StorageMutation[]): Promise<void>
}

const DATABASE_NAME = 'dovideo-memory'
const DATABASE_VERSION = 1

function requestResult<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error ?? new Error('IndexedDB request failed'))
  })
}

function transactionDone(transaction: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    transaction.oncomplete = () => resolve()
    transaction.onabort = () => reject(transaction.error ?? new Error('IndexedDB transaction aborted'))
    transaction.onerror = () => reject(transaction.error ?? new Error('IndexedDB transaction failed'))
  })
}

/** 原生 IndexedDB driver；只负责事务与 schema，不包含业务淘汰规则。 */
export class IndexedDbKnowledgeStorageDriver implements KnowledgeStorageDriver {
  private databasePromise: Promise<IDBDatabase> | null = null

  async get<T>(store: KnowledgeStoreName, key: IDBValidKey): Promise<T | undefined> {
    const database = await this.open()
    const transaction = database.transaction(store, 'readonly')
    const result = await requestResult(transaction.objectStore(store).get(key))
    await transactionDone(transaction)
    return result as T | undefined
  }

  async getAll<T>(store: KnowledgeStoreName): Promise<T[]> {
    const database = await this.open()
    const transaction = database.transaction(store, 'readonly')
    const result = await requestResult(transaction.objectStore(store).getAll())
    await transactionDone(transaction)
    return result as T[]
  }

  async apply(mutations: StorageMutation[]): Promise<void> {
    if (mutations.length === 0) return
    const database = await this.open()
    const stores = [...new Set(mutations.map((mutation) => mutation.store))]
    const transaction = database.transaction(stores, 'readwrite')
    for (const mutation of mutations) {
      const store = transaction.objectStore(mutation.store)
      if (mutation.type === 'put') store.put(mutation.value)
      else store.delete(mutation.key)
    }
    await transactionDone(transaction)
  }

  private open(): Promise<IDBDatabase> {
    if (this.databasePromise) return this.databasePromise
    this.databasePromise = new Promise((resolve, reject) => {
      if (typeof indexedDB === 'undefined') {
        reject(new Error('IndexedDB is unavailable'))
        return
      }

      const request = indexedDB.open(DATABASE_NAME, DATABASE_VERSION)
      request.onupgradeneeded = () => {
        const database = request.result
        const heads = database.createObjectStore('conversationHeads', {
          keyPath: ['userId', 'conversationId']
        })
        heads.createIndex('byUserAccess', ['userId', 'lastAccessedAt'])
        heads.createIndex('byUserMedia', ['userId', 'scopeMediaId'])

        const turns = database.createObjectStore('completedTurns', {
          keyPath: ['userId', 'conversationId', 'turnId']
        })
        turns.createIndex('byConversationTurn', ['userId', 'conversationId', 'turnNo'])

        database.createObjectStore('drafts', { keyPath: ['userId', 'mediaId'] })
        database.createObjectStore('pendingQuestions', { keyPath: 'userId' })
        database.createObjectStore('conversationRefs', { keyPath: ['userId', 'mediaId'] })
      }
      request.onsuccess = () => {
        const database = request.result
        database.onversionchange = () => database.close()
        resolve(database)
      }
      request.onerror = () => {
        this.databasePromise = null
        reject(request.error ?? new Error('Unable to open IndexedDB'))
      }
      request.onblocked = () => {
        this.databasePromise = null
        reject(new Error('IndexedDB upgrade is blocked'))
      }
    })
    return this.databasePromise
  }
}

function recordKey(store: KnowledgeStoreName, value: unknown): IDBValidKey {
  const record = value as Record<string, number>
  switch (store) {
    case 'conversationHeads':
      return [record.userId, record.conversationId]
    case 'completedTurns':
      return [record.userId, record.conversationId, record.turnId]
    case 'drafts':
    case 'conversationRefs':
      return [record.userId, record.mediaId]
    case 'pendingQuestions':
      return record.userId
  }
}

function stableKey(key: IDBValidKey): string {
  return JSON.stringify(key)
}

/** 测试用内存 driver；与生产 driver 共用同一 service 契约，不模拟浏览器 API。 */
export class MemoryKnowledgeStorageDriver implements KnowledgeStorageDriver {
  private readonly stores = new Map<KnowledgeStoreName, Map<string, unknown>>(
    KNOWLEDGE_STORE_NAMES.map((name) => [name, new Map()])
  )

  async get<T>(store: KnowledgeStoreName, key: IDBValidKey): Promise<T | undefined> {
    return this.stores.get(store)?.get(stableKey(key)) as T | undefined
  }

  async getAll<T>(store: KnowledgeStoreName): Promise<T[]> {
    return [...(this.stores.get(store)?.values() ?? [])] as T[]
  }

  async apply(mutations: StorageMutation[]): Promise<void> {
    for (const mutation of mutations) {
      const store = this.stores.get(mutation.store)
      if (!store) continue
      if (mutation.type === 'put') {
        store.set(stableKey(recordKey(mutation.store, mutation.value)), mutation.value)
      } else {
        store.delete(stableKey(mutation.key))
      }
    }
  }
}
