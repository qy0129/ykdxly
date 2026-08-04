import { useEffect, useRef, useState } from 'react'
import { Brain, Check, FileText, LoaderCircle, MessageSquare, Paperclip, Pencil, Plus, RefreshCw, Save, Send, Trash2, X } from 'lucide-react'
import {
  plannerApi,
  type AgentDocument,
  type AgentRunResponse,
  type AiConversation,
  type AiDraft,
  type AiMemory,
  type AiSession,
} from '../../services/plannerApi'

type ChatMessage = { role: 'user' | 'assistant'; content: string }

const welcomeMessages = (): ChatMessage[] => [{ role: 'assistant', content: '今天想推进什么？' }]

const memoryCategory: Record<AiMemory['category'], string> = {
  preference: '偏好',
  personality: '个性',
  communication_style: '沟通风格',
  long_term_goal: '长期目标',
  constraint: '长期限制',
  personal_fact: '个人信息',
}

function conversationTime(value: string) {
  const date = new Date(value)
  const today = new Date()
  if (date.toDateString() === today.toDateString()) return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  return date.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' })
}

export function AgentPage({ seed, onDataChanged }: { seed?: string; onDataChanged: () => void }) {
  const [messages, setMessages] = useState<ChatMessage[]>(welcomeMessages)
  const [conversations, setConversations] = useState<AiConversation[]>([])
  const [selectedConversationIds, setSelectedConversationIds] = useState<string[]>([])
  const [draft, setDraft] = useState<AiDraft>()
  const [input, setInput] = useState('')
  const [conversationId, setConversationId] = useState<string>()
  const [runId, setRunId] = useState<string>()
  const [runStatus, setRunStatus] = useState<AgentRunResponse['status']>()
  const [busy, setBusy] = useState(false)
  const [loadingConversation, setLoadingConversation] = useState(false)
  const [error, setError] = useState('')
  const [lastChangeSet, setLastChangeSet] = useState<string>()
  const [attachments, setAttachments] = useState<AgentDocument[]>([])
  const [uploading, setUploading] = useState(false)
  const [renamingId, setRenamingId] = useState<string>()
  const [renamingTitle, setRenamingTitle] = useState('')
  const [memoryOpen, setMemoryOpen] = useState(false)
  const [memories, setMemories] = useState<AiMemory[]>([])
  const [editingMemoryId, setEditingMemoryId] = useState<string>()
  const [editingMemoryText, setEditingMemoryText] = useState('')
  const fileInput = useRef<HTMLInputElement>(null)
  const messageList = useRef<HTMLDivElement>(null)
  const activeConversationIdRef = useRef<string | undefined>(undefined)

  const applySession = (session: AiSession) => {
    activeConversationIdRef.current = session.conversationId
    setConversationId(session.conversationId)
    setRunId(session.runId)
    setRunStatus(session.runStatus)
    setMessages(session.messages.length
      ? session.messages.map(({ role, content }) => ({ role, content }))
      : welcomeMessages())
    setDraft(session.draft)
    setLastChangeSet(undefined)
    setAttachments([])
  }

  const refreshConversations = async () => {
    const value = await plannerApi.loadAiConversations()
    setConversations(value)
    const ids = new Set(value.filter((conversation) => conversation.runStatus !== 'RUNNING').map((conversation) => conversation.id))
    setSelectedConversationIds((current) => current.filter((id) => ids.has(id)))
    return value
  }

  useEffect(() => {
    let active = true
    void Promise.all([plannerApi.loadAiSession(), plannerApi.loadAiConversations()]).then(async ([session, list]) => {
      if (!active) return
      if (session.conversationId) {
        setConversations(list)
        applySession(session)
        return
      }
      const created = await plannerApi.createAiConversation()
      if (!active) return
      applySession(created)
      await refreshConversations()
    }).catch((cause) => setError(cause instanceof Error ? cause.message : '无法恢复 AI 会话'))
    return () => { active = false }
  }, [])

  useEffect(() => { if (seed) setInput(seed) }, [seed])

  useEffect(() => {
    messageList.current?.scrollTo({ top: messageList.current.scrollHeight, behavior: 'smooth' })
  }, [messages, busy, draft])

  const runningKey = conversations
    .filter((conversation) => conversation.runStatus === 'RUNNING' && conversation.runId)
    .map((conversation) => `${conversation.id}:${conversation.runId}`)
    .sort()
    .join('|')

  useEffect(() => {
    const running = runningKey ? runningKey.split('|').map((value) => {
      const [id, activeRunId] = value.split(':')
      return { id, runId: activeRunId }
    }) : []
    if (!running.length) return
    let stopped = false
    let timer: number | undefined
    const poll = async () => {
      try {
        const states = await Promise.all(running.map(async (conversation) => ({
          conversation,
          state: await plannerApi.loadAgentRun(conversation.runId),
        })))
        const completed = states.filter(({ state }) => state.status !== 'RUNNING')
        if (completed.length) {
          const currentId = activeConversationIdRef.current
          const currentResult = completed.find(({ conversation }) => conversation.id === currentId)
          const [list, detail] = await Promise.all([
            plannerApi.loadAiConversations(),
            currentResult && currentId ? plannerApi.loadAiConversation(currentId) : Promise.resolve(undefined),
          ])
          if (stopped) return
          setConversations(list)
          const ids = new Set(list.map((conversation) => conversation.id))
          setSelectedConversationIds((current) => current.filter((id) => ids.has(id)))
          if (detail && activeConversationIdRef.current === currentId) {
            applySession(detail)
            if (currentResult?.state.status === 'FAILED') setError(currentResult.state.lastError || 'Agent 执行失败')
          }
        }
      } catch {
        // 短暂轮询失败时保持运行状态，下次继续查询。
      }
      if (!stopped) timer = window.setTimeout(() => void poll(), 1000)
    }
    timer = window.setTimeout(() => void poll(), 500)
    return () => { stopped = true; if (timer) window.clearTimeout(timer) }
  }, [runningKey])

  const openConversation = async (id: string) => {
    if (id === conversationId || loadingConversation) return
    setLoadingConversation(true)
    setError('')
    try {
      const detail = await plannerApi.loadAiConversation(id)
      applySession(detail)
      setInput('')
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法打开对话')
    } finally { setLoadingConversation(false) }
  }

  const createConversation = async () => {
    if (busy || loadingConversation) return
    setLoadingConversation(true)
    setError('')
    try {
      const detail = await plannerApi.createAiConversation()
      applySession(detail)
      setInput('')
      await refreshConversations()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法新建对话')
    } finally { setLoadingConversation(false) }
  }

  const saveConversationTitle = async (id: string) => {
    const title = renamingTitle.trim()
    if (!title) return
    try {
      await plannerApi.renameAiConversation(id, title)
      setConversations((current) => current.map((item) => item.id === id ? { ...item, title } : item))
      setRenamingId(undefined)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法重命名对话')
    }
  }

  const deleteConversation = async (id: string) => {
    if (busy || conversations.some((conversation) => conversation.id === id && conversation.runStatus === 'RUNNING')) return
    if (!window.confirm('删除后无法恢复这个对话，确定继续吗？')) return
    setError('')
    try {
      await plannerApi.deleteAiConversation(id)
      const remaining = await refreshConversations()
      if (id !== conversationId) return
      if (remaining.length) {
        const detail = await plannerApi.loadAiConversation(remaining[0].id)
        applySession(detail)
      } else {
        const created = await plannerApi.createAiConversation()
        applySession(created)
        await refreshConversations()
      }
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法删除对话')
    }
  }

  const toggleConversationSelection = (id: string) => {
    setSelectedConversationIds((current) => current.includes(id)
      ? current.filter((value) => value !== id)
      : [...current, id])
  }

  const deleteSelectedConversations = async () => {
    const deletingIds = selectedConversationIds.filter((id) => conversations.some((conversation) => conversation.id === id && conversation.runStatus !== 'RUNNING'))
    if (busy || loadingConversation || deletingIds.length === 0
      || !window.confirm(`确定删除选中的 ${deletingIds.length} 个对话吗？删除后无法恢复。`)) return
    setLoadingConversation(true)
    setError('')
    try {
      await Promise.all(deletingIds.map((id) => plannerApi.deleteAiConversation(id)))
      const remaining = await refreshConversations()
      setSelectedConversationIds([])
      if (!conversationId || !deletingIds.includes(conversationId)) return
      if (remaining.length) {
        applySession(await plannerApi.loadAiConversation(remaining[0].id))
      } else {
        const created = await plannerApi.createAiConversation()
        applySession(created)
        await refreshConversations()
      }
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法批量删除对话')
    } finally {
      setLoadingConversation(false)
    }
  }

  const toggleMemories = async () => {
    const next = !memoryOpen
    setMemoryOpen(next)
    if (!next) return
    setError('')
    try { setMemories(await plannerApi.loadAiMemories()) }
    catch (cause) { setError(cause instanceof Error ? cause.message : '无法读取长期记忆') }
  }

  const saveMemory = async (id: string) => {
    const content = editingMemoryText.trim()
    if (!content) return
    try {
      const updated = await plannerApi.updateAiMemory(id, content)
      setMemories((current) => current.map((item) => item.id === id ? updated : item))
      setEditingMemoryId(undefined)
    } catch (cause) { setError(cause instanceof Error ? cause.message : '无法保存记忆') }
  }

  const deleteMemory = async (id: string) => {
    if (!window.confirm('确定删除这条长期记忆吗？')) return
    try {
      await plannerApi.deleteAiMemory(id)
      setMemories((current) => current.filter((item) => item.id !== id))
    } catch (cause) { setError(cause instanceof Error ? cause.message : '无法删除记忆') }
  }

  const selectFiles = async (files: FileList | null) => {
    const selected = Array.from(files ?? [])
    if (!selected.length || uploading) return
    setUploading(true)
    setError('')
    try {
      for (const file of selected) {
        if (file.size > 25 * 1024 * 1024) throw new Error(`${file.name} 超过 25 MB`)
        const document = await plannerApi.uploadAgentFile(file)
        setAttachments((current) => current.some((item) => item.id === document.id)
          ? current : [...current, document])
      }
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '文件上传失败')
    } finally {
      setUploading(false)
      if (fileInput.current) fileInput.current.value = ''
    }
  }

  const send = async () => {
    const message = input.trim() || (attachments.length ? '请分析这些文件' : '')
    const currentRunning = conversations.some((conversation) => conversation.id === conversationId && conversation.runStatus === 'RUNNING')
      || runStatus === 'RUNNING'
    if (!message || busy || uploading || loadingConversation || draft || currentRunning) return
    const documentIds = attachments.map((item) => item.id)
    const displayMessage = attachments.length
      ? `${message}\n\n附件：${attachments.map((item) => item.fileName).join('、')}` : message
    setMessages((current) => [...current, { role: 'user', content: displayMessage }])
    setInput('')
    setBusy(true)
    setError('')
    try {
      let activeConversationId = conversationId
      if (!activeConversationId) {
        const created = await plannerApi.createAiConversation()
        activeConversationId = created.conversationId
        activeConversationIdRef.current = activeConversationId
        setConversationId(activeConversationId)
        await refreshConversations()
      }
      const targetRunId = runId
      const targetRunStatus = runStatus
      const response = targetRunId && (targetRunStatus === 'WAITING_USER' || targetRunStatus === 'FAILED')
        ? await plannerApi.resumeAgent(targetRunId, message, documentIds)
        : await plannerApi.startAgent(message, activeConversationId, documentIds)
      setConversations((current) => current.map((conversation) => conversation.id === activeConversationId
        ? { ...conversation, runId: response.runId, runStatus: 'RUNNING' }
        : conversation))
      setSelectedConversationIds((current) => current.filter((id) => id !== activeConversationId))
      if (activeConversationIdRef.current === activeConversationId) {
        setRunId(response.runId)
        setRunStatus('RUNNING')
        setAttachments([])
      }
      await refreshConversations()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法提交 Agent 请求')
    } finally { setBusy(false) }
  }

  const confirm = async () => {
    if (!draft || busy) return
    setBusy(true)
    setError('')
    try {
      const result = await plannerApi.confirmAgentDraft(draft.id)
      setMessages((current) => [...current, { role: 'assistant', content: `已执行 ${result.executed.length} 项操作。` }])
      setRunStatus('COMPLETED')
      setLastChangeSet(result.changeSetId)
      setDraft(undefined)
      await refreshConversations()
      onDataChanged()
    } catch (cause) {
      setError(cause instanceof Error ? '草案无法确认：' + cause.message : '草案无法确认。')
    } finally { setBusy(false) }
  }

  const cancel = async () => {
    if (!draft || busy) return
    setBusy(true)
    setError('')
    try {
      await plannerApi.cancelAgentDraft(draft.id)
      setMessages((current) => [...current, { role: 'assistant', content: '草案已取消，计划数据没有变化。' }])
      setRunStatus('CANCELLED')
      setDraft(undefined)
      await refreshConversations()
    } catch (cause) {
      setError(cause instanceof Error ? '草案无法取消：' + cause.message : '草案无法取消。')
    } finally { setBusy(false) }
  }

  const undo = async () => {
    if (!lastChangeSet || busy) return
    setBusy(true)
    setError('')
    try {
      const result = await plannerApi.undoChangeSet(lastChangeSet)
      setMessages((current) => [...current, { role: 'assistant', content: `已撤销本次变更，共恢复 ${result.restored} 项。` }])
      setLastChangeSet(undefined)
      onDataChanged()
    } catch (cause) { setError(cause instanceof Error ? cause.message : '无法撤销变更') }
    finally { setBusy(false) }
  }

  const selectableConversations = conversations.filter((conversation) => conversation.runStatus !== 'RUNNING')
  const allConversationsSelected = selectableConversations.length > 0
    && selectableConversations.every((conversation) => selectedConversationIds.includes(conversation.id))
  const currentConversationRunning = runStatus === 'RUNNING'
    || conversations.some((conversation) => conversation.id === conversationId && conversation.runStatus === 'RUNNING')

  return (
    <div className="agent-page content-page">
      <div className={`ai-shell${memoryOpen ? ' memory-open' : ''}`}>
        <aside className="ai-conversation-sidebar">
          <div className="ai-sidebar-heading">
            <strong>对话</strong>
            <div className="ai-sidebar-actions">
              {selectableConversations.length > 0 && <input className="batch-checkbox" type="checkbox" checked={allConversationsSelected} disabled={busy || loadingConversation} onChange={() => setSelectedConversationIds(allConversationsSelected ? [] : selectableConversations.map((conversation) => conversation.id))} title="全选空闲对话" aria-label="全选空闲对话" />}
              {selectedConversationIds.length > 0 && <button className="icon-button danger-text ai-batch-delete" type="button" title={`删除选中的 ${selectedConversationIds.length} 个对话`} aria-label={`删除选中的 ${selectedConversationIds.length} 个对话`} disabled={busy || loadingConversation} onClick={() => void deleteSelectedConversations()}><Trash2 size={16} /><small>{selectedConversationIds.length}</small></button>}
              <button className="icon-button" type="button" title="新建对话" aria-label="新建对话" disabled={busy || loadingConversation} onClick={() => void createConversation()}><Plus size={17} /></button>
            </div>
          </div>
          <div className="ai-conversation-list">
            {conversations.map((conversation) => (
              <div className={`ai-conversation-item${conversation.id === conversationId ? ' active' : ''}${selectedConversationIds.includes(conversation.id) ? ' selected' : ''}`} key={conversation.id}>
                {renamingId === conversation.id ? (
                  <div className="ai-conversation-rename">
                    <input autoFocus value={renamingTitle} maxLength={80} onChange={(event) => setRenamingTitle(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter') void saveConversationTitle(conversation.id); if (event.key === 'Escape') setRenamingId(undefined) }} />
                    <button type="button" title="保存名称" aria-label="保存名称" onClick={() => void saveConversationTitle(conversation.id)}><Check size={14} /></button>
                    <button type="button" title="取消重命名" aria-label="取消重命名" onClick={() => setRenamingId(undefined)}><X size={14} /></button>
                  </div>
                ) : (
                  <>
                    <input className="batch-checkbox ai-conversation-checkbox" type="checkbox" checked={selectedConversationIds.includes(conversation.id)} disabled={busy || loadingConversation || conversation.runStatus === 'RUNNING'} onChange={() => toggleConversationSelection(conversation.id)} aria-label={`选择对话：${conversation.title}`} />
                    <button className="ai-conversation-select" type="button" disabled={loadingConversation} onClick={() => void openConversation(conversation.id)}>
                      {conversation.runStatus === 'RUNNING' ? <LoaderCircle className="spin" size={14} /> : <MessageSquare size={14} />}
                      <span><strong>{conversation.title}</strong><small>{conversationTime(conversation.updatedAt)}{conversation.runStatus === 'RUNNING' ? ' · 处理中' : conversation.hasPendingDraft ? ' · 待确认' : ''}</small></span>
                    </button>
                    <div className="ai-conversation-actions">
                      <button type="button" title="重命名" aria-label="重命名" onClick={() => { setRenamingId(conversation.id); setRenamingTitle(conversation.title) }}><Pencil size={13} /></button>
                      <button type="button" title={conversation.runStatus === 'RUNNING' ? '处理中，暂时不能删除' : '删除对话'} aria-label="删除对话" disabled={conversation.runStatus === 'RUNNING'} onClick={() => void deleteConversation(conversation.id)}><Trash2 size={13} /></button>
                    </div>
                  </>
                )}
              </div>
            ))}
          </div>
          <button className={`ai-memory-toggle${memoryOpen ? ' active' : ''}`} type="button" onClick={() => void toggleMemories()}><Brain size={16} /><span>长期记忆</span><small>{memories.length || ''}</small></button>
        </aside>

        <section className="ai-chat-workspace">
          <div className="agent-context-bar">
            <span>核心 Agent</span>
            {runStatus && <span className={`agent-run-status status-${runStatus.toLowerCase()}`}>{runStatus.replaceAll('_', ' ')}</span>}
          </div>
          <div className="ai-chat-messages" ref={messageList} aria-live="polite">
            {messages.map((message, index) => <article className={`ai-chat-message ${message.role}`} key={`${message.role}-${index}`}><span>{message.role === 'assistant' ? 'AI' : '你'}</span><p>{message.content}</p></article>)}
            {(currentConversationRunning || busy || loadingConversation) && <article className="ai-chat-message assistant loading"><span>AI</span><p><LoaderCircle className="spin" size={14} />{currentConversationRunning ? '后台处理中，可切换其他对话' : '正在处理'}</p></article>}
          </div>
          {draft && (
            <section className="ai-change-draft">
              <div className="ai-change-heading"><div><strong>待确认草案</strong><span>编号 {draft.code}</span></div></div>
              {draft.actions.map((action, index) => <article key={`${action.type}-${index}`}><span>{action.type.replaceAll('_', ' ')}</span><div><strong>{action.summary || action.type}</strong>{action.changes?.map((change) => <small key={change.field}>{change.field}: {String(change.before ?? '空')} → {String(change.after ?? '空')}</small>)}</div></article>)}
              <div className="dialog-actions"><button className="primary-button" type="button" disabled={busy} onClick={() => void confirm()}><Check size={16} /> 确认执行</button><button className="secondary-button" type="button" disabled={busy} onClick={() => void cancel()}><X size={16} /> 取消</button></div>
            </section>
          )}
          <div className="ai-composer-zone">
            {attachments.length > 0 && <div className="agent-attachments">{attachments.map((item) => <span className="agent-attachment" key={item.id}><FileText size={14} /><span><strong>{item.fileName}</strong><small>{item.vectorIndexed ? '已索引' : '已解析'}</small></span><button type="button" title="移除附件" aria-label={`移除 ${item.fileName}`} onClick={() => setAttachments((current) => current.filter((file) => file.id !== item.id))}><X size={13} /></button></span>)}</div>}
            <div className="ai-chat-composer">
              <textarea value={input} disabled={Boolean(draft) || currentConversationRunning} onChange={(event) => setInput(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); void send() } }} placeholder={draft ? '请先处理当前对话的待确认草案' : currentConversationRunning ? '当前对话正在后台处理，可切换到其他对话' : '输入消息'} rows={1} />
              <div className="ai-composer-actions">
                <input ref={fileInput} type="file" multiple hidden accept=".pdf,.doc,.docx,.txt,.md,.csv,.xls,.xlsx,.ppt,.pptx,.png,.jpg,.jpeg" onChange={(event) => void selectFiles(event.target.files)} />
                <button className="icon-button" type="button" disabled={busy || uploading || Boolean(draft) || currentConversationRunning} title="添加附件" aria-label="添加附件" onClick={() => fileInput.current?.click()}>{uploading ? <LoaderCircle className="spin" size={17} /> : <Paperclip size={17} />}</button>
                <button className="icon-button ai-send-button" type="button" disabled={busy || uploading || Boolean(draft) || currentConversationRunning || (!input.trim() && !attachments.length)} title="发送" aria-label="发送" onClick={() => void send()}><Send size={17} /></button>
              </div>
            </div>
          </div>
          {error && <p className="ai-chat-error">{error}</p>}
          {lastChangeSet && <button className="secondary-button agent-undo" type="button" disabled={busy} onClick={() => void undo()}><RefreshCw size={16} /> 撤销上次 AI 变更</button>}
        </section>

        {memoryOpen && (
          <aside className="ai-memory-panel">
            <div className="ai-memory-heading"><div><Brain size={17} /><strong>长期记忆</strong></div><button className="icon-button" type="button" title="关闭记忆" aria-label="关闭记忆" onClick={() => setMemoryOpen(false)}><X size={16} /></button></div>
            <div className="ai-memory-list">
              {!memories.length && <p className="ai-memory-empty">对话中形成的长期偏好会出现在这里。</p>}
              {memories.map((memory) => (
                <article className="ai-memory-item" key={memory.id}>
                  <span>{memoryCategory[memory.category]}</span>
                  {editingMemoryId === memory.id ? (
                    <textarea autoFocus value={editingMemoryText} maxLength={2000} rows={4} onChange={(event) => setEditingMemoryText(event.target.value)} />
                  ) : <p>{memory.content}</p>}
                  <div>
                    {editingMemoryId === memory.id ? <button type="button" title="保存记忆" aria-label="保存记忆" onClick={() => void saveMemory(memory.id)}><Save size={14} /></button> : <button type="button" title="编辑记忆" aria-label="编辑记忆" onClick={() => { setEditingMemoryId(memory.id); setEditingMemoryText(memory.content) }}><Pencil size={14} /></button>}
                    <button type="button" title="删除记忆" aria-label="删除记忆" onClick={() => void deleteMemory(memory.id)}><Trash2 size={14} /></button>
                  </div>
                </article>
              ))}
            </div>
          </aside>
        )}
      </div>
    </div>
  )
}
