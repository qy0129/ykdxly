import { useEffect, useState } from 'react'
import { ArrowRight, Bell, CalendarDays, CheckSquare2, ChevronDown, Menu, MoreHorizontal, Plus, Search, Target, X } from 'lucide-react'
import type { Plan } from '../../types/planner'
import { plannerApi, type UserProfile } from '../../services/plannerApi'
import { ProgressRing } from '../ui/ProgressRing'
import { navItems, type GlobalSearchItem, type GlobalTargetType, type NotificationEntry, type View } from '../../app/navigation'

const defaultProfile: UserProfile = { displayName: '长路用户', avatarUrl: '' }

function profileInitial(displayName: string) {
  return displayName.trim().slice(0, 1) || '长'
}

/** 应用侧栏和顶部栏只处理导航交互，不持有业务数据。 */
export function Sidebar({
  activeView,
  plansData,
  onViewChange,
  onPlanOpen,
  onCreatePlan,
  mobileOpen,
  onMobileClose,
}: {
  activeView: View
  plansData: Plan[]
  onViewChange: (view: View) => void
  onPlanOpen: (planId: string) => void
  onCreatePlan: () => void
  mobileOpen: boolean
  onMobileClose: () => void
}) {
  const [expandedPlans, setExpandedPlans] = useState<string[]>(['product', 'travel'])
  const [profile, setProfile] = useState<UserProfile>(defaultProfile)
  const [profileDraft, setProfileDraft] = useState<UserProfile>(defaultProfile)
  const [profileOpen, setProfileOpen] = useState(false)
  const [profileSaving, setProfileSaving] = useState(false)

  useEffect(() => {
    void plannerApi.loadProfile().then((value) => {
      setProfile(value)
      setProfileDraft(value)
    }).catch(() => undefined)
  }, [])

  const togglePlan = (planId: string) => {
    setExpandedPlans((current) =>
      current.includes(planId) ? current.filter((id) => id !== planId) : [...current, planId],
    )
  }

  return (
    <>
      <button
        className={'sidebar-scrim ' + (mobileOpen ? 'visible' : '')}
        type="button"
        aria-label="关闭导航"
        onClick={onMobileClose}
      />
      <aside className={'sidebar ' + (mobileOpen ? 'mobile-open' : '')}>
        <div className="brand-row">
          <div className="brand-mark"><Target size={19} strokeWidth={2.4} /></div>
          <div>
            <strong>长路</strong>
            <span>计划工作台</span>
          </div>
          <button className="icon-button mobile-close" type="button" onClick={onMobileClose} title="关闭导航">
            <X size={18} />
          </button>
        </div>

        <nav className="main-nav" aria-label="主要功能">
          {navItems.map((item) => {
            const Icon = item.icon
            return (
              <button
                key={item.id}
                type="button"
                className={activeView === item.id ? 'active' : ''}
                onClick={() => {
                  onViewChange(item.id)
                  onMobileClose()
                }}
              >
                <Icon size={18} strokeWidth={2} />
                <span>{item.label}</span>
              </button>
            )
          })}
        </nav>

        <div className="sidebar-divider" />

        <div className="plan-section-header">
          <div>
            <span className="eyebrow">长期计划</span>
          <strong>{plansData.length} 项正在推进</strong>
        </div>
          <button className="icon-button" type="button" title="添加长期计划" onClick={onCreatePlan}>
            <Plus size={18} />
          </button>
        </div>

        <div className="sidebar-plans">
          {plansData.map((plan) => {
            const expanded = expandedPlans.includes(plan.id)
            return (
              <section className="sidebar-plan" key={plan.id}>
                <button className="plan-summary" type="button" onClick={() => togglePlan(plan.id)}>
                  <ProgressRing value={plan.progress} color={plan.color} />
                  <span className="plan-summary-copy">
                    <strong>{plan.title}</strong>
                    <small>{plan.subtitle}</small>
                  </span>
                  <ChevronDown className={expanded ? 'rotated' : ''} size={17} />
                </button>
                {expanded && (
                  <div className="plan-subitems">
                    {plan.items.map((item) => (
                      <button type="button" key={item.id} onClick={() => onPlanOpen(plan.id)}>
                        <span className="subitem-title">{item.title}</span>
                        <span className="subitem-meta">
                          <span>{item.progress}%</span>
                          <span className="mini-track">
                            <i style={{ width: item.progress + '%', backgroundColor: plan.color }} />
                          </span>
                        </span>
                      </button>
                    ))}
                    <button className="open-plan" type="button" onClick={() => onPlanOpen(plan.id)}>
                      查看完整计划 <ArrowRight size={14} />
                    </button>
                  </div>
                )}
              </section>
            )
          })}
        </div>

        <div className="sidebar-footer">
          <div className="avatar">
            {profile.avatarUrl ? <img src={profile.avatarUrl} alt="" /> : profileInitial(profile.displayName)}
          </div>
          <div><strong>{profile.displayName}</strong><span>本周完成率 81%</span></div>
          <button
            className="icon-button"
            type="button"
            title="用户设置"
            aria-expanded={profileOpen}
            onClick={() => { setProfileDraft(profile); setProfileOpen((value) => !value) }}
          >
            <MoreHorizontal size={18} />
          </button>
          {profileOpen && (
            <section className="profile-popover">
              <div className="profile-popover-heading"><strong>用户资料</strong><span>设置用户名和头像</span></div>
              <label>用户名<input value={profileDraft.displayName} maxLength={100} onChange={(event) => setProfileDraft((current) => ({ ...current, displayName: event.target.value }))} /></label>
              <label>头像地址<input value={profileDraft.avatarUrl} type="url" placeholder="https://..." onChange={(event) => setProfileDraft((current) => ({ ...current, avatarUrl: event.target.value }))} /></label>
              <div className="profile-preview">
                <div className="avatar">{profileDraft.avatarUrl ? <img src={profileDraft.avatarUrl} alt="" /> : profileInitial(profileDraft.displayName)}</div>
                <span>头像预览</span>
              </div>
              <div className="dialog-actions">
                <button className="primary-button" type="button" disabled={profileSaving} onClick={() => {
                  const next = { displayName: profileDraft.displayName.trim() || defaultProfile.displayName, avatarUrl: profileDraft.avatarUrl.trim() }
                  setProfile(next)
                  setProfileSaving(true)
                  void plannerApi.saveProfile(next).then(setProfile).catch(() => undefined).finally(() => { setProfileSaving(false); setProfileOpen(false) })
                }}>保存</button>
                <button className="secondary-button" type="button" disabled={profileSaving} onClick={() => setProfileOpen(false)}>取消</button>
              </div>
            </section>
          )}
        </div>
      </aside>
    </>
  )
}

export function AppHeader({
  title,
  subtitle,
  onMenu,
  onCreateChoice,
  searchItems,
  notifications,
  onOpenItem,
}: {
  title: string
  subtitle: string
  onMenu: () => void
  onCreateChoice: (kind: 'todo' | 'schedule') => void
  searchItems: GlobalSearchItem[]
  notifications: NotificationEntry[]
  onOpenItem: (item: GlobalSearchItem) => void
}) {
  const [createOpen, setCreateOpen] = useState(false)
  const [searchOpen, setSearchOpen] = useState(false)
  const [notificationOpen, setNotificationOpen] = useState(false)
  const [query, setQuery] = useState('')

  const filteredSearchItems = searchItems
    .filter((item) => !query.trim() || (item.title + item.meta).toLowerCase().includes(query.trim().toLowerCase()))
    .slice(0, 8)

  const typeLabel: Record<GlobalTargetType, string> = {
    plan: '计划',
    todo: '待办',
    note: '笔记',
    schedule: '日程',
  }

  const openItem = (item: GlobalSearchItem) => {
    onOpenItem(item)
    setSearchOpen(false)
    setNotificationOpen(false)
  }

  const selectCreate = (kind: 'todo' | 'schedule') => {
    onCreateChoice(kind)
    setCreateOpen(false)
  }

  return (
    <header className="app-header">
      <button className="icon-button menu-button" type="button" onClick={onMenu} title="打开导航">
        <Menu size={20} />
      </button>
      <div className="page-title">
        <h1>{title}</h1>
        <span>{subtitle}</span>
      </div>
      <div className="header-actions">
        <div className="header-popover-wrap">
          <button
            className="icon-button"
            type="button"
            title="搜索"
            onClick={() => { setSearchOpen((value) => !value); setNotificationOpen(false); setCreateOpen(false) }}
            aria-expanded={searchOpen}
          >
            <Search size={19} />
          </button>
          {searchOpen && (
            <section className="header-panel search-panel">
              <div className="panel-search-box"><Search size={15} /><input autoFocus value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索计划、待办、笔记、日程" /></div>
              <div className="panel-list">
                {filteredSearchItems.map((item) => (
                  <button type="button" key={item.type + item.id} onClick={() => openItem(item)}>
                    <span className={'panel-kind kind-' + item.type}>{typeLabel[item.type]}</span>
                    <strong>{item.title}</strong>
                    <small>{item.meta}</small>
                  </button>
                ))}
                {filteredSearchItems.length === 0 && <div className="panel-empty">没有找到相关内容</div>}
              </div>
            </section>
          )}
        </div>
        <div className="header-popover-wrap">
          <button
            className="icon-button notification-button"
            type="button"
            title="通知"
            onClick={() => { setNotificationOpen((value) => !value); setSearchOpen(false); setCreateOpen(false) }}
            aria-expanded={notificationOpen}
          >
            <Bell size={19} />
            {notifications.length > 0 && <i />}
          </button>
          {notificationOpen && (
            <section className="header-panel notification-panel">
              <div className="panel-heading"><strong>提醒中心</strong><span>{notifications.length} 条待处理</span></div>
              <div className="panel-list">
                {notifications.map((item) => (
                  <button type="button" key={item.type + item.id} onClick={() => openItem(item)}>
                    <span className={'panel-kind kind-' + item.type}>{typeLabel[item.type]}</span>
                    <strong>{item.title}</strong>
                    <small>{item.time ? item.time + ' · ' : ''}{item.meta}</small>
                  </button>
                ))}
                {notifications.length === 0 && <div className="panel-empty">暂时没有新的提醒</div>}
              </div>
            </section>
          )}
        </div>
        <div className="create-menu-wrap">
          <button className="primary-button" type="button" onClick={() => { setCreateOpen((value) => !value); setSearchOpen(false); setNotificationOpen(false) }} aria-haspopup="menu" aria-expanded={createOpen}>
            <Plus size={17} /> 新建
          </button>
          {createOpen && (
            <div className="create-menu" role="menu">
              <button type="button" role="menuitem" onClick={() => selectCreate('todo')}><CheckSquare2 size={16} /> 新建待办<span>一次性任务</span></button>
              <button type="button" role="menuitem" onClick={() => selectCreate('schedule')}><CalendarDays size={16} /> 新建日程<span>放入日历</span></button>
            </div>
          )}
        </div>
      </div>
    </header>
  )
}
