import { useEffect, useState } from 'react'
import { CheckCircle2, Circle, Clock3, ListChecks, RotateCcw, Trash2 } from 'lucide-react'
import type { TrashItem } from '../../services/plannerApi'

const typeLabels: Record<TrashItem['type'], string> = {
  plan: '长期计划',
  stage: '计划阶段',
  task: '计划任务',
  todo: '独立待办',
  schedule: '日程安排',
}

/** 回收站只展示后端真实软删除记录，恢复成功后由 App 统一刷新数据。 */
export function TrashPage({
  items,
  restoringId,
  onRestore,
  onRestoreMany,
  onDeleteMany,
}: {
  items: TrashItem[]
  restoringId: string | null
  onRestore: (item: TrashItem) => void
  onRestoreMany: (items: TrashItem[]) => Promise<boolean>
  onDeleteMany: (items: TrashItem[]) => Promise<boolean>
}) {
  const [selectedKeys, setSelectedKeys] = useState<string[]>([])
  const [processing, setProcessing] = useState(false)
  const keyOf = (item: TrashItem) => `${item.type}-${item.id}`
  const selectedItems = items.filter((item) => selectedKeys.includes(keyOf(item)))

  useEffect(() => {
    const available = new Set(items.map(keyOf))
    setSelectedKeys((current) => current.filter((key) => available.has(key)))
  }, [items])

  const toggle = (item: TrashItem) => {
    const key = keyOf(item)
    setSelectedKeys((current) => current.includes(key) ? current.filter((value) => value !== key) : [...current, key])
  }

  const runBatch = async (
    action: (selected: TrashItem[]) => Promise<boolean>,
    targetItems: TrashItem[] = selectedItems,
  ) => {
    if (processing || targetItems.length === 0) return
    setProcessing(true)
    try {
      if (await action(targetItems)) setSelectedKeys([])
    } finally {
      setProcessing(false)
    }
  }

  return (
    <div className="trash-page content-page">
      <section className="trash-heading">
        <div>
          <span className="eyebrow">数据恢复</span>
          <h2>回收站</h2>
          <p>删除的数据保留 30 天，可以在这里恢复。</p>
        </div>
        <div className="trash-heading-actions">
          <span className="trash-count"><Trash2 size={16} /> {items.length} 项</span>
          {items.length > 0 && <button className="secondary-button danger-text" type="button" disabled={processing} onClick={() => void runBatch(onDeleteMany, items)}><ListChecks size={15} /> 全选删除</button>}
          {selectedItems.length > 0 && <>
            <button className="secondary-button" type="button" disabled={processing} onClick={() => void runBatch(onRestoreMany)}><RotateCcw size={15} /> 恢复已选（{selectedItems.length}）</button>
            <button className="secondary-button danger-text" type="button" disabled={processing} onClick={() => void runBatch(onDeleteMany)}><Trash2 size={15} /> 彻底删除（{selectedItems.length}）</button>
          </>}
        </div>
      </section>

      {items.length === 0 ? (
        <div className="simple-empty trash-empty">
          <Trash2 size={28} />
          <strong>回收站为空</strong>
          <span>删除的计划、阶段、任务、待办和日程会出现在这里。</span>
        </div>
      ) : (
        <div className="trash-list">
          {items.map((item) => (
            <article className="trash-row" key={`${item.type}-${item.id}`}>
              <button className={`icon-button trash-select-button ${selectedKeys.includes(keyOf(item)) ? 'selected' : ''}`} type="button" onClick={() => toggle(item)} title={selectedKeys.includes(keyOf(item)) ? '取消选择' : '选择记录'} aria-pressed={selectedKeys.includes(keyOf(item))}>{selectedKeys.includes(keyOf(item)) ? <CheckCircle2 size={18} /> : <Circle size={18} />}</button>
              <span className="trash-type">{typeLabels[item.type]}</span>
              <div className="trash-copy">
                <strong>{item.title}</strong>
                <span><Clock3 size={13} /> 删除时间 {item.deletedAt.replace('T', ' ').slice(0, 16)}</span>
              </div>
              <button
                className="secondary-button"
                type="button"
                disabled={restoringId === item.id}
                onClick={() => onRestore(item)}
              >
                <RotateCcw size={15} /> {restoringId === item.id ? '恢复中' : '恢复'}
              </button>
            </article>
          ))}
        </div>
      )}
    </div>
  )
}
