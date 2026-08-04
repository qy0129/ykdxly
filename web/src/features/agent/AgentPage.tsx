import { useEffect, useState } from 'react'
import { ArrowRight, Check, RefreshCw, X } from 'lucide-react'
import { plannerApi, type AgentRunResponse, type AiDraft } from '../../services/plannerApi'

export function AgentPage({ seed, onDataChanged }: { seed?: string; onDataChanged: () => void }) {
  const [messages, setMessages] = useState<Array<{ role: 'user' | 'assistant'; content: string }>>([
    { role: 'assistant', content: '今天想推进什么？' },
  ])
  const [draft, setDraft] = useState<AiDraft>()
  const [input, setInput] = useState('')
  const [conversationId, setConversationId] = useState<string>()
  const [runId, setRunId] = useState<string>()
  const [runStatus, setRunStatus] = useState<AgentRunResponse['status']>()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [lastChangeSet, setLastChangeSet] = useState<string>()

  useEffect(() => {
    void plannerApi.loadAiSession().then((session) => {
      if (session.conversationId) setConversationId(session.conversationId)
      if (session.runId) setRunId(session.runId)
      if (session.runStatus) setRunStatus(session.runStatus)
      if (session.messages.length) setMessages(session.messages.map(({ role, content }) => ({ role, content })))
      if (session.draft) setDraft(session.draft)
    }).catch((cause) => setError(cause instanceof Error ? cause.message : '无法恢复 AI 会话'))
  }, [])

  useEffect(() => { if (seed) setInput(seed) }, [seed])

  const send = async () => {
    const message = input.trim()
    if (!message || busy || draft) return
    setMessages((current) => [...current, { role: 'user', content: message }])
    setInput('')
    setBusy(true)
    setError('')
    try {
      const response = runId && (runStatus === 'WAITING_USER' || runStatus === 'FAILED')
        ? await plannerApi.resumeAgent(runId, message)
        : await plannerApi.startAgent(message, conversationId)
      setRunId(response.runId)
      setRunStatus(response.status)
      setConversationId(response.conversationId)
      setMessages((current) => [...current, { role: 'assistant', content: response.reply }])
      setDraft(response.draft)
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

  return (
    <div className="agent-page content-page">
      <section className="ai-chat-workspace">
        <div className="section-heading">
          <div><span className="eyebrow">核心 Agent</span><h3>AI 对话</h3></div>
          {runStatus && <span className={`agent-run-status status-${runStatus.toLowerCase()}`}>{runStatus.replaceAll('_', ' ')}</span>}
        </div>
        <div className="ai-chat-messages" aria-live="polite">
          {messages.map((message, index) => <article className={`ai-chat-message ${message.role}`} key={`${message.role}-${index}`}><span>{message.role === 'assistant' ? 'AI' : '你'}</span><p>{message.content}</p></article>)}
          {busy && <article className="ai-chat-message assistant loading"><span>AI</span><p>正在处理...</p></article>}
        </div>
        {draft && (
          <section className="ai-change-draft">
            <div className="ai-change-heading"><div><strong>待确认草案</strong><span>编号 {draft.code}</span></div></div>
            {draft.actions.map((action, index) => <article key={`${action.type}-${index}`}><span>{action.type.replaceAll('_', ' ')}</span><div><strong>{action.summary || action.type}</strong>{action.changes?.map((change) => <small key={change.field}>{change.field}: {String(change.before ?? '空')} → {String(change.after ?? '空')}</small>)}</div></article>)}
            <div className="dialog-actions"><button className="primary-button" type="button" disabled={busy} onClick={() => void confirm()}><Check size={16} /> 确认执行</button><button className="secondary-button" type="button" disabled={busy} onClick={() => void cancel()}><X size={16} /> 取消</button></div>
          </section>
        )}
        <div className="ai-chat-composer">
          <textarea value={input} disabled={Boolean(draft)} onChange={(event) => setInput(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); void send() } }} placeholder="例如：把本周的学习目标拆成每天的任务" rows={3} />
          <button className="primary-button" type="button" disabled={busy || Boolean(draft) || !input.trim()} onClick={() => void send()}><ArrowRight size={16} /> 发送</button>
        </div>
        {error && <p className="ai-chat-error">{error}</p>}
        {lastChangeSet && <button className="secondary-button agent-undo" type="button" disabled={busy} onClick={() => void undo()}><RefreshCw size={16} /> 撤销上次 AI 变更</button>}
      </section>
    </div>
  )
}
