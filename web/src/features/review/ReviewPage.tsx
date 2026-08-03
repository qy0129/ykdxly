import { useState } from 'react'
import { ArrowRight, BrainCircuit, CalendarDays, Check, CheckCircle2, Clock3, Lightbulb, RefreshCw, Sparkles, Target, TrendingUp } from 'lucide-react'
import type { CalendarItem, Plan } from '../../types/planner'
import { plannerApi, type AiPlanChange, type AiReviewMessage } from '../../services/plannerApi'

/** AI 复盘管理对话交互，计划变更仍需用户确认后交由上层。 */
export function ReviewPage({
  plansData,
  calendarItems,
  onUpdatePlan,
  onUpdateSchedule,
}: {
  plansData: Plan[]
  calendarItems: CalendarItem[]
  onUpdatePlan: (plan: Plan) => void
  onUpdateSchedule: (item: CalendarItem) => void
}) {
  const [accepted, setAccepted] = useState<string[]>([])
  const [messages, setMessages] = useState<AiReviewMessage[]>([
    { role: 'assistant', content: '把今天的执行感受、遇到的阻力或想调整的安排告诉我。我会结合现有计划一起复盘。' },
  ])
  const [chatDraft, setChatDraft] = useState('')
  const [conversationId, setConversationId] = useState<string>()
  const [pendingChanges, setPendingChanges] = useState<AiPlanChange[]>([])
  const [chatBusy, setChatBusy] = useState(false)
  const [chatError, setChatError] = useState('')

  const sendReviewMessage = async () => {
    const content = chatDraft.trim()
    if (!content || chatBusy) return
    const userMessage: AiReviewMessage = { role: 'user', content }
    const history = [...messages, userMessage]
    setMessages(history)
    setChatDraft('')
    setChatBusy(true)
    setChatError('')
    try {
      const result = await plannerApi.chatReview(content, messages, conversationId)
      setConversationId(result.conversationId)
      setMessages((current) => [...current, { role: 'assistant', content: result.reply }])
      setPendingChanges(result.changes ?? [])
    } catch (error) {
      setChatError(error instanceof Error && error.message.includes('503')
        ? 'AI 尚未配置，请先在后端设置 PLANNER_AI_API_KEY。'
        : '暂时无法连接 AI，请稍后重试。')
    } finally {
      setChatBusy(false)
    }
  }

  const applyAiChanges = () => {
    pendingChanges.forEach((change) => {
      if (change.entity === 'plan') {
        const current = plansData.find((plan) => plan.id === change.id)
        if (!current) return
        const fields = change.fields
        onUpdatePlan({
          ...current,
          title: typeof fields.title === 'string' ? fields.title : current.title,
          subtitle: typeof fields.description === 'string' ? fields.description : current.subtitle,
          dueDate: typeof fields.dueDate === 'string' ? fields.dueDate : current.dueDate,
          progress: typeof fields.progress === 'number' ? Math.max(0, Math.min(100, fields.progress)) : current.progress,
          status: fields.status === 'active' || fields.status === 'paused' || fields.status === 'completed' ? fields.status : current.status,
        })
        return
      }
      const current = calendarItems.find((item) => item.id === change.id)
      if (!current) return
      const fields = change.fields
      const startAt = typeof fields.startAt === 'string' ? fields.startAt : `${current.date}T${current.time}:00`
      onUpdateSchedule({
        ...current,
        title: typeof fields.title === 'string' ? fields.title : current.title,
        date: startAt.slice(0, 10),
        time: startAt.slice(11, 16),
        duration: typeof fields.durationMinutes === 'number' ? fields.durationMinutes : current.duration,
        status: fields.status === 'pending' || fields.status === 'done' || fields.status === 'delayed' ? fields.status : current.status,
        planId: typeof fields.planId === 'string' ? fields.planId : current.planId,
      })
    })
    setMessages((current) => [...current, { role: 'assistant', content: `已应用 ${pendingChanges.length} 项调整。` }])
    setPendingChanges([])
  }
  const suggestions = [
    { id: 's1', title: '把晚间深度任务缩短到 60 分钟', reason: '过去 7 天，超过 90 分钟的晚间任务有 4 次延期。', impact: '影响未来 6 项任务' },
    { id: 's2', title: '周三晚上保留为缓冲时间', reason: '临时事项主要集中在周中，当前计划没有恢复空间。', impact: '移动 2 项任务' },
    { id: 's3', title: '优先完成首页交互再扩展功能', reason: '产品计划同时推进 3 个方向，核心链路完成度最高。', impact: '调整本周优先级' },
  ]

  return (
    <div className="review-page content-page">
      <section className="ai-chat-workspace">
        <div className="section-heading">
          <div><span className="eyebrow">AI 计划伙伴</span><h3>边复盘，边把计划调到合适的位置</h3></div>
          <span className="section-note">修改前需要你确认</span>
        </div>
        <div className="ai-chat-messages" aria-live="polite">
          {messages.map((message, index) => (
            <article className={`ai-chat-message ${message.role}`} key={`${message.role}-${index}`}>
              <span>{message.role === 'assistant' ? 'AI' : '你'}</span>
              <p>{message.content}</p>
            </article>
          ))}
          {chatBusy && <article className="ai-chat-message assistant loading"><span>AI</span><p>正在结合你的计划分析...</p></article>}
        </div>
        {pendingChanges.length > 0 && (
          <div className="ai-change-draft">
            <div className="ai-change-heading"><div><strong>计划调整草案</strong><span>{pendingChanges.length} 项变更，确认后才会执行</span></div><button className="primary-button" type="button" onClick={applyAiChanges}><Check size={16} /> 确认应用</button></div>
            {pendingChanges.map((change, index) => (
              <article key={`${change.id}-${index}`}><span>{change.entity === 'plan' ? '计划' : '日程'}</span><div><strong>{change.title}</strong><p>{change.summary}</p></div></article>
            ))}
            <button className="text-button" type="button" onClick={() => setPendingChanges([])}>放弃本次调整</button>
          </div>
        )}
        <div className="ai-chat-composer">
          <textarea value={chatDraft} onChange={(event) => setChatDraft(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); void sendReviewMessage() } }} placeholder="例如：我晚上总是完不成，帮我把这周安排得轻一点" rows={3} />
          <button className="primary-button" type="button" disabled={chatBusy || !chatDraft.trim()} onClick={() => void sendReviewMessage()}><ArrowRight size={16} /> 发送</button>
        </div>
        {chatError && <p className="ai-chat-error">{chatError}</p>}
      </section>
      <section className="review-date-row">
        <div>
          <span className="eyebrow">每日复盘</span>
          <h2>8 月 2 日，星期日</h2>
          <p>今天的执行事实已经整理完成。</p>
        </div>
        <button className="secondary-button" type="button"><CalendarDays size={16} /> 选择日期</button>
      </section>

      <section className="review-facts">
        <div><CheckCircle2 size={19} /><span>完成</span><strong>5 项</strong></div>
        <div><Clock3 size={19} /><span>专注</span><strong>3.2 小时</strong></div>
        <div><RefreshCw size={19} /><span>延期</span><strong>2 项</strong></div>
        <div><Target size={19} /><span>计划完成率</span><strong>71%</strong></div>
      </section>

      <div className="review-layout">
        <section className="ai-review">
          <div className="ai-review-heading">
            <div className="ai-mark"><Sparkles size={20} /></div>
            <div><span className="eyebrow">AI 复盘</span><h3>今天推进得比你感觉中更扎实</h3></div>
          </div>
          <div className="review-copy">
            <p>你完成了产品首页的信息结构，并明确了统计与复盘页面的边界。虽然有两项任务延期，但它们都属于后续视觉细节，没有阻塞核心路径。</p>
            <p>值得注意的是，你在上午的完成效率明显高于晚间。最近一周，上午安排的核心任务完成率是 86%，晚间超过 90 分钟的任务完成率只有 48%。</p>
          </div>
          <div className="review-insights">
            <article><TrendingUp size={18} /><div><strong>最有效的时段</strong><span>上午 09:00—11:30，适合核心设计与开发。</span></div></article>
            <article><Lightbulb size={18} /><div><strong>主要偏差</strong><span>晚间任务估时偏乐观，平均多安排了 37 分钟。</span></div></article>
            <article><Target size={18} /><div><strong>明日重点</strong><span>完成日历日期详情，不同时开启新的页面分支。</span></div></article>
          </div>
        </section>

        <section className="review-log">
          <div className="section-heading">
            <div><span className="eyebrow">执行记录</span><h3>今天发生了什么</h3></div>
          </div>
          {[
            ['09:20', '完成首页结构确认', 'product'],
            ['11:10', '完成暖色视觉方向', 'product'],
            ['15:30', '调整项目方案范围', 'learning'],
            ['20:40', '日历交互原型延期', 'delayed'],
          ].map((row) => (
            <article className="log-row" key={row[0]}>
              <span>{row[0]}</span>
              <i className={row[2]} />
              <strong>{row[1]}</strong>
            </article>
          ))}
        </section>
      </div>

      <section className="adjustments">
        <div className="section-heading">
          <div><span className="eyebrow">明日调整</span><h3>基于最近 7 天的建议</h3></div>
          <span className="section-note">应用后仍可撤销</span>
        </div>
        <div className="suggestion-list">
          {suggestions.map((suggestion) => {
            const isAccepted = accepted.includes(suggestion.id)
            return (
              <article className="suggestion-row" key={suggestion.id}>
                <div className="suggestion-icon"><BrainCircuit size={19} /></div>
                <div><strong>{suggestion.title}</strong><p>{suggestion.reason}</p><span>{suggestion.impact}</span></div>
                <button
                  className={isAccepted ? 'accepted-button' : 'secondary-button'}
                  type="button"
                  onClick={() => setAccepted((current) => isAccepted ? current.filter((id) => id !== suggestion.id) : [...current, suggestion.id])}
                >
                  {isAccepted ? <><Check size={16} /> 已采纳</> : '采纳建议'}
                </button>
              </article>
            )
          })}
        </div>
      </section>
    </div>
  )
}

