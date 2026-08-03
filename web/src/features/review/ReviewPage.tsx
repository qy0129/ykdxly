import { useEffect, useState } from 'react'
import { ArrowRight, Check, Clock3, RefreshCw, Sparkles, X } from 'lucide-react'
import { plannerApi, type AiDraft, type ReviewFacts } from '../../services/plannerApi'

/** AI only creates a persistent draft here; database changes happen after confirmation. */
export function ReviewPage({ seed, onDataChanged }: { seed?: string; onDataChanged: () => void }) {
  const [messages, setMessages] = useState<Array<{ role: 'user' | 'assistant'; content: string }>>([
    { role: 'assistant', content: '告诉我你的目标、可用时间，或直接说要完成、延期、调整哪项计划。所有修改都会先生成草案。' },
  ])
  const [draft, setDraft] = useState<AiDraft>()
  const [facts, setFacts] = useState<ReviewFacts>()
  const [input, setInput] = useState('')
  const [conversationId, setConversationId] = useState<string>()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [lastChangeSet, setLastChangeSet] = useState<string>()

  const loadFacts = async () => {
    try { setFacts(await plannerApi.reviewFacts()) }
    catch (cause) { setError(cause instanceof Error ? cause.message : '无法读取复盘事实') }
  }

  useEffect(() => {
    void loadFacts()
    void plannerApi.loadAiSession().then((session) => {
      if (session.conversationId) setConversationId(session.conversationId)
      if (session.messages.length) setMessages(session.messages.map(({ role, content }) => ({ role, content })))
      if (session.draft) setDraft(session.draft)
    }).catch((cause) => setError(cause instanceof Error ? cause.message : '无法恢复 AI 会话'))
  }, [])

  useEffect(() => { if (seed) setInput(seed) }, [seed])

  const send = async () => {
    const message = input.trim()
    if (!message || busy) return
    setMessages((current) => [...current, { role: 'user', content: message }])
    setInput('')
    setBusy(true)
    setError('')
    try {
      const response = await plannerApi.sendAiCommand(message, conversationId)
      setConversationId(response.conversationId)
      setMessages((current) => [...current, { role: 'assistant', content: response.reply }])
      if (response.draft) setDraft(response.draft)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法提交 AI 请求')
    } finally { setBusy(false) }
  }

  const confirm = async () => {
    if (!draft || busy) return
    setBusy(true)
    setError('')
    try {
      const result = await plannerApi.confirmAiDraft(draft.id)
      setMessages((current) => [...current, { role: 'assistant', content: `已确认并执行 ${result.executed.length} 项操作。` }])
      setLastChangeSet(result.changeSetId)
      setDraft(undefined)
      onDataChanged()
      loadFacts()
    } catch (cause) {
      setError(cause instanceof Error ? '草案无法确认：' + cause.message : '草案无法确认。')
    } finally { setBusy(false) }
  }

  const undo = async () => {
    if (!lastChangeSet || busy) return
    setBusy(true); setError('')
    try {
      const result = await plannerApi.undoChangeSet(lastChangeSet)
      setMessages((current) => [...current, { role: 'assistant', content: `已撤销本次变更，共恢复 ${result.restored} 项。` }])
      setLastChangeSet(undefined); onDataChanged(); void loadFacts()
    } catch (cause) { setError(cause instanceof Error ? cause.message : '无法撤销变更') }
    finally { setBusy(false) }
  }

  const cancel = async () => {
    if (!draft || busy) return
    setBusy(true)
    try {
      await plannerApi.cancelAiDraft(draft.id)
      setMessages((current) => [...current, { role: 'assistant', content: '已取消本次草案，未修改任何计划数据。' }])
      setDraft(undefined)
    } catch (cause) {
      setError(cause instanceof Error ? '草案无法取消：' + cause.message : '草案无法取消。')
    } finally { setBusy(false) }
  }

  return (
    <div className="review-page content-page">
      <section className="ai-chat-workspace">
        <div className="section-heading">
          <div><span className="eyebrow">AI 规划工作台</span><h3>用自然语言创建、调整和复盘计划</h3></div>
          <span className="section-note">所有改动需确认后执行</span>
        </div>
        <div className="ai-chat-messages" aria-live="polite">
          {messages.map((message, index) => <article className={`ai-chat-message ${message.role}`} key={`${message.role}-${index}`}><span>{message.role === 'assistant' ? 'AI' : '你'}</span><p>{message.content}</p></article>)}
          {busy && <article className="ai-chat-message assistant loading"><span>AI</span><p>正在生成可确认的草案...</p></article>}
        </div>
        {draft && (
          <section className="ai-change-draft">
            <div className="ai-change-heading"><div><strong>待确认草案</strong><span>编号 {draft.code}，确认后才会写入数据</span></div></div>
            {draft.actions.map((action, index) => <article key={`${action.type}-${index}`}><span>{action.type.replaceAll('_', ' ')}</span><div><strong>{action.summary || action.type}</strong>{action.changes?.map((change) => <small key={change.field}>{change.field}: {String(change.before ?? '空')} → {String(change.after ?? '空')}</small>)}</div></article>)}
            <div className="dialog-actions"><button className="primary-button" type="button" disabled={busy} onClick={() => void confirm()}><Check size={16} /> 确认执行</button><button className="secondary-button" type="button" disabled={busy} onClick={() => void cancel()}><X size={16} /> 取消</button></div>
          </section>
        )}
        <div className="ai-chat-composer">
          <textarea value={input} onChange={(event) => setInput(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); void send() } }} placeholder="例如：我想三个月学完 React，每晚两小时" rows={3} />
          <button className="primary-button" type="button" disabled={busy || !input.trim()} onClick={() => void send()}><ArrowRight size={16} /> 发送</button>
        </div>
        {error && <p className="ai-chat-error">{error}</p>}
        {lastChangeSet && <button className="secondary-button" type="button" disabled={busy} onClick={() => void undo()}><RefreshCw size={16} /> 撤销上次 AI 变更</button>}
      </section>

      <section className="review-date-row">
        <div><span className="eyebrow">今日真实记录</span><h2>{facts?.date ?? '暂时无法读取日期'}</h2><p>只统计已确认操作和数据库中的完成状态。</p></div>
      </section>
      <section className="review-facts">
        <div><Check size={19} /><span>完成任务</span><strong>{facts?.completedTasks ?? '-'}</strong></div>
        <div><Sparkles size={19} /><span>完成日程</span><strong>{facts?.scheduleCompleted ?? '-'}</strong></div>
        <div><RefreshCw size={19} /><span>延期</span><strong>{facts?.delayed ?? '-'}</strong></div>
        <div><Clock3 size={19} /><span>实际专注</span><strong>{facts ? `${Math.round(facts.focusMinutes / 10) / 6} 小时` : '-'}</strong></div>
      </section>
      <section className="review-log">
        <div className="section-heading"><div><span className="eyebrow">执行记录</span><h3>今天发生的确认操作</h3></div></div>
        {facts?.logs.length ? facts.logs.map((entry, index) => <article className="log-row" key={`${entry.occurredAt}-${index}`}><span>{entry.occurredAt.slice(11, 16)}</span><i className="product" /><strong>{entry.note || entry.action}</strong></article>) : <p className="section-note">今天还没有可记录的执行操作。</p>}
      </section>
    </div>
  )
}
