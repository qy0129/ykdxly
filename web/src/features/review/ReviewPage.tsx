import { useEffect, useState } from 'react'
import { AlertTriangle, Check, Clock3, RefreshCw, Sparkles, Target } from 'lucide-react'
import { plannerApi, type ReviewReport } from '../../services/plannerApi'

export function ReviewPage() {
  const [report, setReport] = useState<ReviewReport>()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const load = async (regenerate = false) => {
    setBusy(true)
    setError('')
    // 重新生成失败时不能继续展示上一份旧报告，避免把旧内容误认为最新 AI 结果。
    setReport(undefined)
    try {
      setReport(regenerate ? await plannerApi.regenerateTodayReview() : await plannerApi.loadTodayReview())
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法生成今日复盘')
    } finally { setBusy(false) }
  }

  useEffect(() => { void load() }, [])

  const facts = report?.facts
  const summary = report?.summary.split(/\n+/).filter(Boolean) ?? []
  const activityGroups = [
    {
      label: '完成事项',
      tone: 'complete',
      logs: facts?.logs.filter((entry) => /complete|done/i.test(entry.action)) ?? [],
    },
    {
      label: '新增安排',
      tone: 'created',
      logs: facts?.logs.filter((entry) => /create/i.test(entry.action)) ?? [],
    },
    {
      label: '调整计划',
      tone: 'adjusted',
      logs: facts?.logs.filter((entry) => /update|delay|reschedule/i.test(entry.action)) ?? [],
    },
    {
      label: '其他操作',
      tone: 'other',
      logs: facts?.logs.filter((entry) => !/complete|done|create|update|delay|reschedule/i.test(entry.action)) ?? [],
    },
  ]
  const recentLogs = facts?.logs.slice(0, 5) ?? []

  return (
    <div className={`review-page content-page${busy ? ' is-refreshing' : ''}`}>
      <section className="review-date-row">
        <div><span className="eyebrow">今日复盘</span><h2>{report?.date ?? '正在读取'}</h2><p>{report ? `生成于 ${report.generatedAt.replace('T', ' ').slice(0, 19)}` : '基于已确认的真实执行记录'}</p></div>
        <button className="secondary-button" type="button" disabled={busy} onClick={() => void load(true)}><RefreshCw className={busy ? 'spin' : ''} size={16} /> {busy ? '生成中' : '重新生成'}</button>
      </section>
      {error && <p className="ai-chat-error" role="alert">{error}</p>}

      <section className="review-facts">
        <div><Check size={19} /><span>完成任务</span><strong>{facts?.completedTasks ?? '-'}</strong></div>
        <div><Sparkles size={19} /><span>完成日程</span><strong>{facts?.scheduleCompleted ?? '-'}</strong></div>
        <div><RefreshCw size={19} /><span>延期</span><strong>{facts?.delayed ?? '-'}</strong></div>
        <div><Clock3 size={19} /><span>实际专注</span><strong>{facts ? `${Math.round(facts.focusMinutes / 10) / 6} 小时` : '-'}</strong></div>
      </section>

      <section className="review-layout">
        <div className="ai-review">
          <div className="ai-review-heading"><span className="ai-mark"><Sparkles size={16} /></span><div><span className="eyebrow">AI 总结</span><h3>今天的执行情况</h3></div></div>
          <div className="review-copy">
            {busy && !report ? <p>正在生成复盘...</p> : summary.map((paragraph, index) => <p key={index}>{paragraph}</p>)}
          </div>
        </div>
        <div className="review-next-actions">
          <div className="section-heading"><div><span className="eyebrow">下一步</span><h3>明日行动</h3></div></div>
          <ol>{report?.nextActions.map((item) => <li key={item}>{item}</li>)}</ol>
          {!report?.nextActions.length && <p className="section-note">暂时没有建议行动。</p>}
        </div>
      </section>

      <section className="review-insights">
        <article><Sparkles size={17} /><div><strong>有效进展</strong><span>{report?.highlights.join('；') || '暂无足够记录'}</span></div></article>
        <article><AlertTriangle size={17} /><div><strong>风险信号</strong><span>{report?.risks.join('；') || '暂未发现明显风险'}</span></div></article>
        <article><Target size={17} /><div><strong>估时偏差</strong><span>{facts ? `近 7 天平均偏差 ${facts.estimationError7d} 分钟` : '正在读取'}</span></div></article>
      </section>

      <section className="review-log">
        <div className="section-heading"><div><span className="eyebrow">今日活动</span><h3>执行概览</h3></div></div>
        {facts?.logs.length ? <>
          <div className="activity-summary-grid">
            {activityGroups.map((group) => <article className={`activity-summary-item ${group.tone}`} key={group.label}>
              <span>{group.label}</span>
              <strong>{group.logs.length}</strong>
              <small>{group.logs[0]?.occurredAt.slice(11, 16) ?? '—'}</small>
            </article>)}
          </div>
          <details className="activity-details">
            <summary>查看最近 5 条记录</summary>
            <div className="activity-detail-list">
              {recentLogs.map((entry, index) => <article className="log-row" key={`${entry.occurredAt}-${index}`}>
                <span>{entry.occurredAt.slice(11, 16)}</span><i className="product" /><strong>{entry.note || entry.action}</strong>
              </article>)}
            </div>
          </details>
        </> : <p className="section-note">今天还没有可记录的执行活动。</p>}
      </section>
    </div>
  )
}
