(() => {
  const $ = (id) => document.getElementById(id);
  const THEME_KEY = "ilink.web.theme";
  const THEMES = new Set(["system", "light", "dark", "graphite", "forest", "ocean", "warm"]);
  const clientId = stableId("ilink.web.client", "client");
  const workspaceId = stableId("ilink.web.workspace", "workspace");
  const state = {
    activeSessionId: "",
    selectedSessionId: "",
    sessions: [],
    databaseAvailable: false,
    files: [],
    filePreviewUrls: new Map(),
    tasksBySession: new Map(),
    activitiesBySession: new Map(),
    seenEventIds: new Set(),
    unreadSessions: new Set(),
    eventSource: null,
    sessionAction: "",
    sessionTarget: null,
    theme: preferredTheme()
  };

  const elements = {
    sidebar: $("sidebar"), backdrop: $("sidebar-backdrop"), sessions: $("session-list"),
    sessionCount: $("session-count"), messages: $("messages"), empty: $("empty-state"),
    taskList: $("task-list"), title: $("conversation-title"), status: $("agent-status"),
    agentDot: $("agent-dot"), storageDot: $("storage-dot"), storageStatus: $("storage-status"),
    input: $("message-input"), fileInput: $("file-input"), tray: $("attachment-tray"),
    send: $("send-button"), stop: $("stop-button"), resume: $("resume-button"),
    uploadStatus: $("upload-status"), activity: $("activity-list"),
    themeButton: $("theme-button"), themeMenu: $("theme-menu"),
    sessionDialog: $("session-dialog"), sessionDialogTitle: $("session-dialog-title"),
    sessionDialogMessage: $("session-dialog-message"), sessionTitleField: $("session-title-field"),
    sessionTitleInput: $("session-title-input"), sessionDialogConfirm: $("session-dialog-confirm")
  };

  applyTheme(state.theme, false);

  $("new-session").addEventListener("click", createSession);
  $("open-sidebar").addEventListener("click", () => toggleSidebar(true));
  $("close-sidebar").addEventListener("click", () => toggleSidebar(false));
  $("open-activity").addEventListener("click", () => toggleActivity(true));
  $("close-activity").addEventListener("click", () => toggleActivity(false));
  elements.backdrop.addEventListener("click", () => toggleSidebar(false));
  $("activity-backdrop").addEventListener("click", () => toggleActivity(false));
  $("attach-button").addEventListener("click", () => elements.fileInput.click());
  elements.fileInput.addEventListener("change", selectFiles);
  $("composer").addEventListener("submit", send);
  elements.stop.addEventListener("click", stopGeneration);
  elements.resume.addEventListener("click", () => {
    const task = selectedTasks().findLast((item) => item.state === "paused");
    if (task) resumeTask(task.requestId);
  });
  $("clear-activity").addEventListener("click", clearActivity);
  elements.themeButton.addEventListener("click", toggleThemeMenu);
  elements.themeMenu.addEventListener("click", selectTheme);
  elements.sessionDialog.addEventListener("close", handleSessionDialogClose);
  $("session-dialog-close").addEventListener("click", () => elements.sessionDialog.close("cancel"));
  elements.sessionTitleInput.addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      event.preventDefault();
      elements.sessionDialog.close("confirm");
    }
  });
  document.addEventListener("click", () => {
    closeSessionMenus();
    closeThemeMenu();
  });
  document.addEventListener("keydown", (event) => {
    if (event.key !== "Escape") return;
    closeSessionMenus();
    closeThemeMenu();
    toggleSidebar(false);
    toggleActivity(false);
  });
  elements.input.addEventListener("input", () => { resizeInput(); updateSendState(); });
  elements.input.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      $("composer").requestSubmit();
    }
  });

  connectEvents();
  Promise.all([loadSessions(), loadTasks()]).finally(updateSelectedState);
  window.setInterval(updateTaskTimers, 250);

  window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => {
    if (state.theme === "system") applyTheme("system", true);
  });

  function preferredTheme() {
    const stored = localStorage.getItem(THEME_KEY) || "system";
    return THEMES.has(stored) ? stored : "system";
  }

  function applyTheme(theme, animate) {
    state.theme = THEMES.has(theme) ? theme : "system";
    document.documentElement.dataset.theme = state.theme;
    document.documentElement.classList.toggle("theme-animated", Boolean(animate));
    elements.themeMenu.querySelectorAll("[data-theme-value]").forEach((button) => {
      const selected = button.dataset.themeValue === state.theme;
      button.setAttribute("aria-checked", String(selected));
    });
    if (animate) window.setTimeout(() => document.documentElement.classList.remove("theme-animated"), 230);
  }

  function toggleThemeMenu(event) {
    event.stopPropagation();
    const opening = elements.themeMenu.hidden;
    closeSessionMenus();
    elements.themeMenu.hidden = !opening;
    elements.themeButton.setAttribute("aria-expanded", String(opening));
  }

  function closeThemeMenu() {
    elements.themeMenu.hidden = true;
    elements.themeButton.setAttribute("aria-expanded", "false");
  }

  function selectTheme(event) {
    event.stopPropagation();
    const button = event.target.closest("[data-theme-value]");
    if (!button) return;
    const theme = button.dataset.themeValue;
    localStorage.setItem(THEME_KEY, theme);
    applyTheme(theme, true);
    closeThemeMenu();
  }

  function iconElement(name, className) {
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("class", "icon" + (className ? " " + className : ""));
    svg.setAttribute("aria-hidden", "true");
    const use = document.createElementNS("http://www.w3.org/2000/svg", "use");
    use.setAttribute("href", "#icon-" + name);
    svg.appendChild(use);
    return svg;
  }

  function stableId(key, prefix) {
    let value = localStorage.getItem(key);
    if (!value) {
      value = prefix + "-" + crypto.randomUUID().replaceAll("-", "");
      localStorage.setItem(key, value);
    }
    return value;
  }

  function requestHeaders(extra) {
    return Object.assign({
      "X-Web-Client-Id": clientId,
      "X-Web-Workspace-Id": workspaceId
    }, extra || {});
  }

  async function api(path, options) {
    const request = options || {};
    const response = await fetch(path, Object.assign({}, request, {
      headers: requestHeaders(request.headers),
      cache: "no-store"
    }));
    if (!response.ok) {
      let message = "请求失败";
      try { message = (await response.json()).error || message; } catch (_) {}
      throw new Error(message);
    }
    const type = response.headers.get("content-type") || "";
    return type.includes("application/json") ? response.json() : response;
  }

  function connectEvents() {
    if (state.eventSource) state.eventSource.close();
    const after = localStorage.getItem("ilink.web.lastEventId") || "0";
    const query = new URLSearchParams({ clientId, workspaceId, after });
    const source = new EventSource("/api/web/events?" + query.toString());
    state.eventSource = source;
    source.addEventListener("open", () => setConnection(true));
    source.addEventListener("error", () => setConnection(false));
    source.addEventListener("agent", (event) => {
      if (event.lastEventId) localStorage.setItem("ilink.web.lastEventId", event.lastEventId);
      if (event.lastEventId && state.seenEventIds.has(event.lastEventId)) return;
      if (event.lastEventId) {
        state.seenEventIds.add(event.lastEventId);
        if (state.seenEventIds.size > 500) state.seenEventIds.delete(state.seenEventIds.values().next().value);
      }
      handleAgentEvent(JSON.parse(event.data), event.lastEventId || "");
    });
  }

  function handleAgentEvent(event, eventId) {
    const metadata = event.metadata || {};
    const sessionId = metadata.sessionId || state.activeSessionId;
    const requestId = metadata.requestId || "";
    let task = requestId ? upsertTask({
      requestId,
      sessionId,
      state: metadata.taskState || (event.type === "status" ? normalizeTaskState(metadata.state) : undefined),
      createdAt: Number(metadata.startedAt) || Date.now(),
      elapsedMs: Number(metadata.elapsedMs) || 0,
      attempt: Number(metadata.attempt) || 1,
      detail: metadata.detail || event.content || "正在处理"
    }) : null;

    if (task && (event.type === "status" || event.type === "tool_activity")) {
      if (event.type === "tool_activity") task.state = "running";
      appendTaskDetail(task, event.content || "处理中", metadata);
      task.updatedAt = Date.now();
      if (metadata.elapsedMs != null) task.elapsedMs = Number(metadata.elapsedMs);
    }

    if (event.type === "completed") {
      if (sessionId === state.selectedSessionId) {
        if (metadata.kind === "image" || metadata.kind === "file") addArtifactMessage(event.content, metadata);
        else if (event.content) addMessage("agent", event.content);
      } else if (sessionId) {
        state.unreadSessions.add(sessionId);
      }
    } else if (event.type === "error") {
      if (task) task.state = metadata.taskState || "failed";
      settleActivity(requestId, "error");
      if (sessionId === state.selectedSessionId) addMessage("agent", event.content || "处理失败，请稍后重试");
      else if (sessionId) state.unreadSessions.add(sessionId);
      addActivity(event.content || "处理失败", "error", sessionId,
        Object.assign({}, metadata, { eventId }));
    } else if (event.type === "tool_activity") {
      addActivity(event.content || "工具执行中", "tool", sessionId,
        Object.assign({}, metadata, { eventId }));
    } else if (event.type === "status" && metadata.taskState === "paused") {
      settleActivity(requestId, "paused");
      addActivity("任务已暂停，可继续", "paused", sessionId,
        Object.assign({}, metadata, { eventId, activityKey: "paused:" + requestId + ":" + (metadata.attempt || 1) }));
    } else if (event.type === "status" && metadata.taskState === "completed") {
      settleActivity(requestId, "ready");
    } else if (event.type === "status") {
      addActivity(event.content || "模型正在分析", "working", sessionId,
        Object.assign({}, metadata, { eventId, activityKey: "working:" + requestId + ":" + (metadata.attempt || 1) }));
    }

    renderTasks();
    renderSessions();
    updateSelectedState();
    if (event.type === "status" && metadata.taskState === "completed"
        && sessionId === state.selectedSessionId) {
      window.setTimeout(() => loadHistory(sessionId, true), 900);
      loadSessions(false);
    }
  }

  function normalizeTaskState(value) {
    if (value === "paused") return "paused";
    if (value === "idle") return "completed";
    return "running";
  }

  async function loadTasks() {
    try {
      const data = await api("/api/web/tasks");
      (data.tasks || []).forEach(upsertTask);
      renderTasks();
    } catch (error) {
      addActivity(error.message, "error");
    }
  }

  function upsertTask(raw) {
    if (!raw || !raw.requestId) return null;
    const existing = findTask(raw.requestId);
    const sessionId = raw.sessionId || (existing && existing.sessionId)
      || state.selectedSessionId || state.activeSessionId;
    if (!sessionId) return null;
    if (existing && existing.sessionId !== sessionId) {
      taskMap(existing.sessionId, false)?.delete(existing.requestId);
    }
    const current = existing || { details: [] };
    const next = Object.assign(current, Object.fromEntries(
      Object.entries(raw).filter((entry) => entry[1] !== undefined)));
    next.sessionId = sessionId;
    next.elapsedMs = Number(next.elapsedMs) || 0;
    next.updatedAt = Date.now();
    next.details = current.details || [];
    taskMap(sessionId, true).set(next.requestId, next);
    return next;
  }

  function taskMap(sessionId, create) {
    if (!sessionId) return null;
    let tasks = state.tasksBySession.get(sessionId);
    if (!tasks && create) {
      tasks = new Map();
      state.tasksBySession.set(sessionId, tasks);
    }
    return tasks || null;
  }

  function allTasks() {
    return Array.from(state.tasksBySession.values()).flatMap((tasks) => Array.from(tasks.values()));
  }

  function findTask(requestId) {
    if (!requestId) return null;
    for (const tasks of state.tasksBySession.values()) {
      const task = tasks.get(requestId);
      if (task) return task;
    }
    return null;
  }

  function appendTaskDetail(task, text, metadata) {
    const elapsed = metadata.elapsedMs == null ? null : Number(metadata.elapsedMs);
    const last = task.details[task.details.length - 1];
    if (last && last.text === text && last.status === metadata.status) return;
    task.details.push({ text, elapsed, status: metadata.status || "running" });
    if (task.details.length > 20) task.details.shift();
  }

  function selectedTasks() {
    return Array.from(taskMap(state.selectedSessionId, false)?.values() || [])
      .sort((a, b) => (a.createdAt || 0) - (b.createdAt || 0));
  }

  function renderTasks() {
    const tasks = selectedTasks().filter((task) => task.state !== "completed").slice(-1);
    elements.taskList.replaceChildren();
    elements.taskList.hidden = tasks.length === 0;
    tasks.forEach((task) => {
      const node = $("task-template").content.firstElementChild.cloneNode(true);
      node.dataset.requestId = task.requestId;
      node.classList.add("is-" + task.state);
      node.querySelector(".task-title").textContent = taskLabel(task);
      node.querySelector(".task-hint").textContent = taskHint(task);
      node.querySelector(".task-elapsed").textContent = formatElapsed(currentElapsed(task));
      node.querySelector(".task-status-icon use").setAttribute("href", "#icon-" + taskIcon(task));
      const details = node.querySelector(".task-details");
      const rows = task.details.length ? task.details : [{ text: task.detail || "等待处理" }];
      rows.forEach((detail) => {
        const row = document.createElement("div");
        row.className = "task-detail";
        const label = document.createElement("span");
        label.textContent = detail.text;
        const time = document.createElement("span");
        time.textContent = detail.elapsed == null ? "" : formatElapsed(detail.elapsed);
        row.append(label, time);
        details.appendChild(row);
      });
      const resume = node.querySelector(".task-resume");
      resume.hidden = task.state !== "paused";
      resume.addEventListener("click", (event) => {
        event.preventDefault();
        resumeTask(task.requestId);
      });
      elements.taskList.appendChild(node);
    });
  }

  function taskLabel(task) {
    if (task.state === "paused") return "任务已暂停，可继续";
    if (task.state === "failed") return task.detail || "处理失败";
    if (task.state === "queued") return task.attempt > 0 ? "等待继续" : "等待处理";
    return task.attempt > 1 ? "正在继续任务" : "正在处理";
  }

  function taskHint(task) {
    if (task.state === "paused") return "已保留进度，展开后可继续";
    if (task.state === "failed") return "展开查看错误摘要";
    return "点击查看处理详情";
  }

  function taskIcon(task) {
    if (task.state === "paused") return "pause";
    if (task.state === "completed") return "check";
    if (task.state === "failed") return "alert";
    return "loader";
  }

  function currentElapsed(task) {
    return task.elapsedMs + (task.state === "running" ? Date.now() - task.updatedAt : 0);
  }

  function updateTaskTimers() {
    elements.taskList.querySelectorAll(".task-entry").forEach((node) => {
      const task = findTask(node.dataset.requestId);
      if (task) node.querySelector(".task-elapsed").textContent = formatElapsed(currentElapsed(task));
    });
    const task = selectedTasks().findLast((item) => item.state === "running" || item.state === "queued");
    if (task) elements.status.textContent = taskLabel(task) + " · " + formatElapsed(currentElapsed(task));
  }

  function formatElapsed(milliseconds) {
    const seconds = Math.max(0, Math.floor(milliseconds / 1000));
    if (seconds < 60) return seconds + " 秒";
    return Math.floor(seconds / 60) + " 分 " + String(seconds % 60).padStart(2, "0") + " 秒";
  }

  async function loadSessions(loadMessages) {
    const shouldLoadMessages = loadMessages !== false;
    try {
      const data = await api("/api/web/sessions");
      state.sessions = data.sessions || [];
      state.databaseAvailable = Boolean(data.databaseAvailable);
      state.activeSessionId = data.activeSessionId || "";
      if (!state.selectedSessionId) state.selectedSessionId = state.activeSessionId;
      renderSessions();
      renderActivities();
      setStorage(state.databaseAvailable);
      if (shouldLoadMessages && state.selectedSessionId) await loadHistory(state.selectedSessionId);
    } catch (error) {
      setConnection(false);
      addActivity(error.message, "error");
    }
  }

  function renderSessions() {
    elements.sessions.replaceChildren();
    elements.sessionCount.textContent = String(state.sessions.length);
    state.sessions.forEach((session) => {
      const row = $("session-template").content.firstElementChild.cloneNode(true);
      const item = row.querySelector(".session-item");
      const more = row.querySelector(".session-more");
      const menu = row.querySelector(".session-menu");
      more.hidden = !state.databaseAvailable;
      item.classList.toggle("is-active", session.sessionId === state.selectedSessionId);
      if (session.sessionId === state.selectedSessionId) item.setAttribute("aria-current", "page");
      item.querySelector("strong").textContent = session.title || "新会话";
      const activeTask = Array.from(taskMap(session.sessionId, false)?.values() || []).find((task) =>
        task.state === "running" || task.state === "queued" || task.state === "paused");
      row.classList.toggle("is-running", Boolean(activeTask && activeTask.state !== "paused"));
      row.classList.toggle("is-paused", Boolean(activeTask && activeTask.state === "paused"));
      row.classList.toggle("has-update", state.unreadSessions.has(session.sessionId));
      if (activeTask) {
        item.querySelector(".session-state use").setAttribute("href",
          activeTask.state === "paused" ? "#icon-pause" : "#icon-loader");
      }
      item.querySelector("small").textContent = activeTask
        ? taskLabel(activeTask) : (session.lastActiveTime || "暂无记录");
      item.addEventListener("click", () => useSession(session));
      more.addEventListener("click", (event) => {
        event.stopPropagation();
        const opening = menu.hidden;
        closeSessionMenus();
        menu.hidden = !opening;
        more.setAttribute("aria-expanded", String(opening));
        if (opening) positionSessionMenu(menu, more);
      });
      menu.addEventListener("click", (event) => event.stopPropagation());
      menu.querySelector('[data-action="rename"]').addEventListener("click", () => openSessionDialog(session, "rename"));
      menu.querySelector('[data-action="delete"]').addEventListener("click", () => openSessionDialog(session, "delete"));
      elements.sessions.appendChild(row);
    });
    const selected = state.sessions.find((session) => session.sessionId === state.selectedSessionId);
    elements.title.textContent = selected ? selected.title : "新会话";
  }

  async function createSession() {
    try {
      const data = await api("/api/web/sessions", { method: "POST" });
      state.activeSessionId = data.sessionId;
      state.selectedSessionId = data.sessionId;
      state.unreadSessions.delete(data.sessionId);
      clearMessages();
      renderActivities();
      await loadSessions(false);
      renderTasks();
      updateSelectedState();
      toggleSidebar(false);
      elements.input.focus();
      addActivity("已创建新会话", "ready");
    } catch (error) {
      addActivity(error.message, "error");
    }
  }

  async function useSession(session) {
    state.selectedSessionId = session.sessionId;
    state.unreadSessions.delete(session.sessionId);
    renderSessions();
    renderTasks();
    renderActivities();
    updateSelectedState();
    toggleSidebar(false);
    try {
      if (session.sessionId !== state.activeSessionId) {
        await api("/api/web/sessions/" + encodeURIComponent(session.sessionId) + "/use", { method: "POST" });
        state.activeSessionId = session.sessionId;
      }
      await loadHistory(session.sessionId);
    } catch (error) {
      addActivity(error.message, "error", session.sessionId);
    }
  }

  function closeSessionMenus() {
    elements.sessions.querySelectorAll(".session-menu").forEach((menu) => { menu.hidden = true; });
    elements.sessions.querySelectorAll(".session-more").forEach((button) => button.setAttribute("aria-expanded", "false"));
  }

  function positionSessionMenu(menu, anchor) {
    const rect = anchor.getBoundingClientRect();
    const width = 132;
    const estimatedHeight = 78;
    const left = Math.max(8, Math.min(window.innerWidth - width - 8, rect.right - width));
    const top = rect.bottom + estimatedHeight + 8 > window.innerHeight
      ? Math.max(8, rect.top - estimatedHeight - 4)
      : rect.bottom + 4;
    menu.style.left = left + "px";
    menu.style.top = top + "px";
  }

  function openSessionDialog(session, action) {
    closeSessionMenus();
    state.sessionAction = action;
    state.sessionTarget = session;
    const deleting = action === "delete";
    elements.sessionDialogTitle.textContent = deleting ? "删除会话" : "重命名会话";
    elements.sessionDialogMessage.textContent = deleting
      ? "删除后，该会话的消息和会话状态将无法恢复。正在运行的任务会先停止。"
      : "输入一个便于识别的会话名称。";
    elements.sessionTitleField.hidden = deleting;
    elements.sessionTitleInput.value = session.title || "";
    elements.sessionDialogConfirm.textContent = deleting ? "删除" : "保存";
    elements.sessionDialogConfirm.classList.toggle("is-danger", deleting);
    elements.sessionDialog.showModal();
    if (!deleting) requestAnimationFrame(() => elements.sessionTitleInput.select());
  }

  async function handleSessionDialogClose() {
    if (elements.sessionDialog.returnValue !== "confirm" || !state.sessionTarget) {
      state.sessionAction = "";
      state.sessionTarget = null;
      return;
    }
    const action = state.sessionAction;
    const session = state.sessionTarget;
    state.sessionAction = "";
    state.sessionTarget = null;
    try {
      if (action === "rename") {
        const title = elements.sessionTitleInput.value.trim();
        if (!title) throw new Error("会话名称不能为空");
        await api("/api/web/sessions/" + encodeURIComponent(session.sessionId), {
          method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ title })
        });
        await loadSessions(false);
        addActivity("会话已重命名", "ready", session.sessionId);
        return;
      }
      const data = await api("/api/web/sessions/" + encodeURIComponent(session.sessionId), { method: "DELETE" });
      state.activeSessionId = data.activeSessionId || "";
      state.selectedSessionId = state.activeSessionId;
      state.tasksBySession.delete(session.sessionId);
      state.activitiesBySession.delete(session.sessionId);
      await loadSessions(true);
      renderTasks();
      addActivity("会话已删除", "ready");
    } catch (error) {
      addActivity(error.message, "error", session.sessionId);
    }
  }

  async function loadHistory(sessionId, preserveIfShort) {
    try {
      const data = await api("/api/web/sessions/" + encodeURIComponent(sessionId) + "/messages");
      if (sessionId !== state.selectedSessionId) return;
      const currentCount = elements.messages.querySelectorAll(".message").length;
      if (preserveIfShort && (data.messages || []).length < currentCount) return;
      clearMessages();
      (data.messages || []).forEach((message) => {
        if ((message.kind === "image" || message.kind === "file") && message.artifactId) {
          addArtifactMessage(message.content, message);
        } else {
          addMessage(message.role === "user" ? "user" : "agent", message.content, false, message.id);
        }
      });
      scrollMessages();
    } catch (error) {
      addActivity(error.message, "error", sessionId);
    }
  }

  async function send(event) {
    event.preventDefault();
    const sessionId = state.selectedSessionId;
    const text = elements.input.value.trim();
    if (!sessionId || (!text && !state.files.length)) return;
    const files = state.files.slice();
    state.files = [];
    renderFiles();
    elements.input.value = "";
    resizeInput();
    updateSendState();

    try {
      for (const file of files) {
        elements.uploadStatus.textContent = "上传 " + file.name;
        addAttachmentMessage(file);
        const data = await api("/api/web/files", {
          method: "POST",
          headers: {
            "Content-Type": file.type || "application/octet-stream",
            "X-File-Name": encodeURIComponent(file.name),
            "X-Session-Id": sessionId
          },
          body: file
        });
        upsertTask({ requestId: data.requestId, sessionId, state: "queued", createdAt: Date.now(), elapsedMs: 0, attempt: 0 });
      }
      elements.uploadStatus.textContent = "";
      if (text) {
        addMessage("user", text);
        const data = await api("/api/web/messages", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            text,
            sessionId,
            syncWechat: Boolean(document.getElementById("sync-wechat")?.checked),
            syncReplies: Boolean(document.getElementById("sync-wechat-replies")?.checked)
          })
        });
        upsertTask({ requestId: data.requestId, sessionId, state: "queued", createdAt: Date.now(), elapsedMs: 0, attempt: 0 });
      }
      renderTasks();
      renderSessions();
      updateSelectedState();
    } catch (error) {
      addActivity(error.message, "error", sessionId);
      elements.uploadStatus.textContent = "";
    }
  }

  async function stopGeneration() {
    if (!state.selectedSessionId) return;
    elements.stop.disabled = true;
    try {
      const data = await api("/api/web/cancel", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ sessionId: state.selectedSessionId })
      });
      (data.tasks || []).forEach(upsertTask);
      if (!data.cancelled) addActivity("当前没有正在处理的任务", "ready", state.selectedSessionId);
    } catch (error) {
      addActivity(error.message, "error", state.selectedSessionId);
    } finally {
      renderTasks();
      updateSelectedState();
    }
  }

  async function resumeTask(requestId) {
    const task = findTask(requestId);
    if (!task) return;
    try {
      const data = await api("/api/web/tasks/" + encodeURIComponent(requestId) + "/resume", { method: "POST" });
      upsertTask(data.task);
      addActivity("任务已继续", "resumed", task.sessionId, {
        requestId, activityKey: "resumed:" + requestId + ":" + (data.task.attempt || 1)
      });
      renderTasks();
      renderSessions();
      updateSelectedState();
    } catch (error) {
      addActivity(error.message, "error", task.sessionId);
    }
  }

  function selectFiles() {
    const incoming = Array.from(elements.fileInput.files);
    const known = new Set(state.files.map((file) => file.name + ":" + file.size));
    incoming.forEach((file) => {
      if (!known.has(file.name + ":" + file.size)) state.files.push(file);
    });
    elements.fileInput.value = "";
    renderFiles();
    updateSendState();
  }

  function renderFiles() {
    elements.tray.replaceChildren();
    elements.tray.hidden = state.files.length === 0;
    state.files.forEach((file, index) => {
      const chip = document.createElement("div");
      chip.className = "attachment-chip";
      if (isImageFile(file)) {
        chip.classList.add("is-image");
        const preview = document.createElement("img");
        preview.className = "attachment-preview";
        preview.src = previewUrlFor(file);
        preview.alt = file.name;
        chip.appendChild(preview);
      }
      const label = document.createElement("span");
      label.className = "attachment-name";
      label.textContent = file.name;
      const remove = document.createElement("button");
      remove.type = "button";
      remove.title = "移除附件";
      remove.setAttribute("aria-label", "移除 " + file.name);
      remove.appendChild(iconElement("x"));
      remove.addEventListener("click", () => {
        state.files.splice(index, 1);
        releasePreviewUrl(file);
        renderFiles();
        updateSendState();
      });
      chip.append(label, remove);
      elements.tray.appendChild(chip);
    });
  }

  function addMessage(role, content, scroll, messageId) {
    elements.empty.hidden = true;
    const message = $("message-template").content.firstElementChild.cloneNode(true);
    message.classList.add(role === "user" ? "is-user" : "is-agent");
    message.querySelector(".message-author").textContent = role === "user" ? "你" : "iLink";
    renderMessageContent(message.querySelector(".message-content"), content, role);
    if (role === "user" && messageId && state.databaseAvailable) {
      const actions = message.querySelector(".message-actions");
      actions.hidden = false;
      message.querySelector(".message-edit").addEventListener("click", () => startMessageEdit(message, messageId, content));
    }
    elements.messages.appendChild(message);
    if (scroll !== false) scrollMessages();
    return message;
  }

  function addAttachmentMessage(file) {
    if (!isImageFile(file)) {
      addMessage("user", file.name);
      return;
    }
    const message = addMessage("user", "", false);
    const container = message.querySelector(".message-content");
    container.classList.add("has-image-attachment");
    const image = document.createElement("img");
    const previewUrl = previewUrlFor(file);
    image.className = "message-attachment-image";
    image.src = previewUrl;
    image.alt = file.name;
    image.addEventListener("load", () => releasePreviewUrl(file), { once: true });
    image.addEventListener("error", () => releasePreviewUrl(file), { once: true });
    const caption = document.createElement("span");
    caption.className = "message-attachment-name";
    caption.textContent = file.name;
    container.replaceChildren(image, caption);
    scrollMessages();
  }

  function isImageFile(file) {
    return Boolean(file && file.type && file.type.startsWith("image/"));
  }

  function previewUrlFor(file) {
    let url = state.filePreviewUrls.get(file);
    if (!url) {
      url = URL.createObjectURL(file);
      state.filePreviewUrls.set(file, url);
    }
    return url;
  }

  function releasePreviewUrl(file) {
    const url = state.filePreviewUrls.get(file);
    if (!url) return;
    URL.revokeObjectURL(url);
    state.filePreviewUrls.delete(file);
  }

  function renderMessageContent(container, content, role) {
    const value = String(content || "");
    const text = document.createElement("div");
    text.className = "message-text";
    if (role === "agent") renderLinkedText(text, value);
    else text.textContent = value;
    container.replaceChildren(text);
    if (role !== "agent") return;

    const links = extractLinks(value);
    if (!links.length) return;
    const list = document.createElement("div");
    list.className = "link-card-list";
    links.forEach((link) => list.appendChild(createLinkCard(link, value)));
    container.appendChild(list);
  }

  function renderLinkedText(container, content) {
    const pattern = /\[([^\]\n]{1,120})\]\((https?:\/\/[^\s)<>，。；：！？、]+)\)|(https?:\/\/[^\s<>"'，。；：！？、]+)/gi;
    let cursor = 0;
    let match;
    while ((match = pattern.exec(content))) {
      container.appendChild(document.createTextNode(content.slice(cursor, match.index)));
      const url = cleanLinkUrl(match[2] || match[3]);
      if (validWebUrl(url)) {
        const link = document.createElement("a");
        link.className = "message-inline-link";
        link.href = url;
        link.target = "_blank";
        link.rel = "noopener noreferrer";
        link.textContent = match[1] || url;
        container.appendChild(link);
      } else {
        container.appendChild(document.createTextNode(match[0]));
      }
      cursor = pattern.lastIndex;
    }
    container.appendChild(document.createTextNode(content.slice(cursor)));
  }

  function extractLinks(content) {
    const links = new Map();
    const markdownPattern = /\[([^\]\n]{1,120})\]\((https?:\/\/[^\s)<>，。；：！？、]+)\)/gi;
    let match;
    while ((match = markdownPattern.exec(content)) && links.size < 4) {
      const url = cleanLinkUrl(match[2]);
      if (validWebUrl(url) && !links.has(url)) links.set(url, { url, title: match[1].trim() });
    }
    const urlPattern = /https?:\/\/[^\s<>"'，。；：！？、]+/gi;
    while ((match = urlPattern.exec(content)) && links.size < 4) {
      const url = cleanLinkUrl(match[0]);
      if (validWebUrl(url) && !links.has(url)) links.set(url, { url, title: "" });
    }
    return Array.from(links.values());
  }

  function cleanLinkUrl(value) {
    return value.replace(/[.,;:!?\]\)}，。；：！？、]+$/g, "");
  }

  function validWebUrl(value) {
    try {
      const url = new URL(value);
      return url.protocol === "http:" || url.protocol === "https:";
    } catch (_) {
      return false;
    }
  }

  function createLinkCard(link, content) {
    const url = new URL(link.url);
    const hostname = url.hostname.replace(/^www\./i, "");
    const card = document.createElement("a");
    card.className = "link-card";
    card.href = link.url;
    card.target = "_blank";
    card.rel = "noopener noreferrer";
    card.setAttribute("aria-label", "打开链接：" + (link.title || hostname));

    const icon = document.createElement("span");
    icon.className = "link-card-icon";
    icon.setAttribute("aria-hidden", "true");
    icon.textContent = siteInitial(hostname);
    const copy = document.createElement("span");
    copy.className = "link-card-copy";
    const title = document.createElement("strong");
    title.className = "link-card-title";
    title.textContent = link.title || hostname;
    const description = document.createElement("span");
    description.className = "link-card-description";
    description.textContent = linkDescription(content, link, hostname);
    const address = document.createElement("span");
    address.className = "link-card-url";
    address.textContent = hostname + (url.pathname === "/" ? "" : url.pathname);
    copy.append(title, description, address);
    const arrow = iconElement("external", "link-card-arrow");
    card.append(icon, copy, arrow);
    return card;
  }

  function siteInitial(hostname) {
    const name = hostname.split(".")[0].replace(/[^a-z0-9]/gi, "");
    return name ? name.slice(0, 2).toUpperCase() : "↗";
  }

  function linkDescription(content, link, hostname) {
    const line = content.split(/\r?\n/).find((item) => item.includes(link.url)) || "";
    const cleaned = line
      .replace(/\[([^\]]+)\]\(https?:\/\/[^\s)<>，。；：！？、]+\)/gi, "$1")
      .replace(/https?:\/\/[^\s<>"'，。；：！？、]+/gi, "")
      .replace(/\s+/g, " ")
      .replace(/^[\s\-–—:：,，.。;；]+|[\s\-–—:：,，.。;；]+$/g, "")
      .trim();
    if (cleaned && cleaned !== link.title) return truncate(cleaned, 120);
    return "访问 " + hostname + " 查看相关内容";
  }

  function truncate(value, maxLength) {
    return value.length <= maxLength ? value : value.slice(0, maxLength - 1) + "…";
  }

  function startMessageEdit(message, messageId, original) {
    if (message.classList.contains("is-editing")) return;
    message.classList.add("is-editing");
    const content = message.querySelector(".message-content");
    const actions = message.querySelector(".message-actions");
    content.hidden = true;
    actions.hidden = true;
    const textarea = document.createElement("textarea");
    textarea.className = "message-edit-area";
    textarea.maxLength = 20000;
    textarea.value = original;
    const toolbar = document.createElement("div");
    toolbar.className = "message-edit-toolbar";
    const cancel = document.createElement("button");
    cancel.type = "button";
    cancel.className = "message-edit-cancel";
    cancel.append(iconElement("x"), document.createTextNode("取消"));
    const save = document.createElement("button");
    save.type = "button";
    save.className = "message-edit-save";
    save.append(iconElement("play"), document.createTextNode("保存并重新运行"));
    toolbar.append(cancel, save);
    message.append(textarea, toolbar);
    const close = () => {
      textarea.remove();
      toolbar.remove();
      content.hidden = false;
      actions.hidden = false;
      message.classList.remove("is-editing");
    };
    cancel.addEventListener("click", close);
    save.addEventListener("click", async () => {
      const text = textarea.value.trim();
      if (!text) return;
      save.disabled = true;
      try {
        const data = await api("/api/web/sessions/" + encodeURIComponent(state.selectedSessionId)
          + "/messages/" + encodeURIComponent(messageId) + "/rerun", {
          method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ text })
        });
        let sibling = message.nextElementSibling;
        while (sibling) {
          const next = sibling.nextElementSibling;
          if (sibling.classList.contains("message")) sibling.remove();
          sibling = next;
        }
        renderMessageContent(content, text, "user");
        close();
        actions.hidden = true;
        upsertTask({ requestId: data.requestId, sessionId: data.sessionId,
          state: "queued", createdAt: Date.now(), elapsedMs: 0, attempt: 0 });
        renderTasks();
        updateSelectedState();
        addActivity("已从修改后的消息重新运行", "rerun", data.sessionId, {
          requestId: data.requestId, activityKey: "rerun:" + data.requestId
        });
      } catch (error) {
        save.disabled = false;
        addActivity(error.message, "error", state.selectedSessionId);
      }
    });
    requestAnimationFrame(() => { textarea.focus(); textarea.select(); });
  }

  function addArtifactMessage(content, metadata) {
    const message = addMessage("agent", content || "", false);
    const container = message.querySelector(".message-content");
    const url = "/api/web/artifacts/" + encodeURIComponent(metadata.artifactId)
      + "?clientId=" + encodeURIComponent(clientId) + "&workspaceId=" + encodeURIComponent(workspaceId);
    if (metadata.kind === "image") {
      const image = document.createElement("img");
      image.src = url;
      image.alt = metadata.fileName || "生成的图片";
      image.loading = "lazy";
      container.appendChild(image);
    }
    const link = document.createElement("a");
    link.className = "artifact-link";
    link.href = url;
    link.target = "_blank";
    link.rel = "noopener";
    if (metadata.kind === "file") link.download = metadata.fileName || "";
    const fileIcon = document.createElement("span");
    fileIcon.className = "artifact-file-icon";
    fileIcon.appendChild(iconElement(metadata.kind === "image" ? "image" : "file"));
    const name = document.createElement("span");
    name.textContent = metadata.fileName || "生成的文件";
    link.append(fileIcon, name, iconElement("download", "artifact-download"));
    container.appendChild(link);
    scrollMessages();
  }

  function clearMessages() {
    Array.from(elements.messages.children).forEach((child) => {
      if (child !== elements.empty) child.remove();
    });
    elements.empty.hidden = false;
  }

  function clearActivity() {
    if (state.selectedSessionId) state.activitiesBySession.delete(state.selectedSessionId);
    renderActivities();
  }

  function addActivity(text, kind, sessionId, metadata) {
    const targetSessionId = sessionId || state.selectedSessionId || state.activeSessionId;
    if (!targetSessionId) return;
    let activities = state.activitiesBySession.get(targetSessionId);
    if (!activities) {
      activities = [];
      state.activitiesBySession.set(targetSessionId, activities);
    }
    const activityKey = metadata && (metadata.activityKey || metadata.eventId);
    if (activityKey && activities.some((entry) => entry.activityKey === String(activityKey))) return;
    activities.unshift({
      text, kind, sessionId: targetSessionId, metadata: Object.assign({}, metadata || {}),
      activityKey: activityKey ? String(activityKey) : "", createdAt: Date.now()
    });
    if (activities.length > 60) activities.length = 60;
    if (targetSessionId === state.selectedSessionId) renderActivities();
  }

  function renderActivities() {
    elements.activity.replaceChildren();
    const activities = state.activitiesBySession.get(state.selectedSessionId) || [];
    const visible = activities.length ? activities : [{
      text: "就绪", kind: "ready", sessionId: state.selectedSessionId, metadata: {},
      activityKey: "", createdAt: Date.now()
    }];
    visible.forEach((activity) => elements.activity.appendChild(createActivityNode(activity)));
  }

  function createActivityNode(activity) {
    const { text, kind, metadata, activityKey, createdAt } = activity;
    const expandable = kind === "tool";
    const item = document.createElement(expandable ? "details" : "div");
    item.className = "activity-entry is-" + kind;
    const marker = document.createElement("span");
    marker.className = "activity-icon";
    const iconName = kind === "error" ? "alert" : kind === "ready" ? "check"
      : kind === "paused" ? "pause" : (kind === "resumed" || kind === "rerun") ? "play"
        : kind === "tool" ? "tool" : "loader";
    marker.appendChild(iconElement(iconName));
    const copy = document.createElement("div");
    const title = document.createElement("strong");
    title.textContent = text;
    const time = document.createElement("time");
    time.textContent = new Intl.DateTimeFormat("zh-CN", {
      hour: "2-digit", minute: "2-digit", second: "2-digit"
    }).format(new Date(createdAt));
    copy.append(title, time);
    if (expandable) {
      const summary = document.createElement("summary");
      summary.append(marker, copy, iconElement("chevron", "activity-chevron"));
      const detail = document.createElement("div");
      detail.className = "activity-detail";
      const values = [];
      const toolName = metadata && (metadata.toolName || metadata.tool);
      if (toolName) values.push("工具：" + toolName);
      if (metadata && metadata.status) values.push("状态：" + metadata.status);
      if (metadata && metadata.elapsedMs != null) values.push("耗时：" + formatElapsed(Number(metadata.elapsedMs)));
      detail.textContent = values.length ? values.join(" · ") : "工具执行事件已记录";
      item.append(summary, detail);
    } else {
      item.append(marker, copy);
    }
    if (activityKey) item.dataset.activityKey = String(activityKey);
    if (metadata && metadata.requestId) item.dataset.requestId = metadata.requestId;
    return item;
  }

  function settleActivity(requestId, finalKind) {
    if (!requestId) return;
    const task = findTask(requestId);
    const collections = task
      ? [state.activitiesBySession.get(task.sessionId) || []]
      : Array.from(state.activitiesBySession.values());
    collections.forEach((activities) => {
      activities.forEach((entry) => {
        if (entry.kind === "working" && entry.metadata.requestId === requestId) entry.kind = finalKind;
      });
    });
    renderActivities();
  }

  function updateSelectedState() {
    const tasks = selectedTasks();
    const active = tasks.findLast((task) => task.state === "running" || task.state === "queued");
    const paused = tasks.findLast((task) => task.state === "paused");
    if (active) setAgentState("working", taskLabel(active) + " · " + formatElapsed(currentElapsed(active)));
    else if (paused) setAgentState("paused", "已暂停 · " + formatElapsed(currentElapsed(paused)));
    else setAgentState("idle", "就绪");
    elements.stop.disabled = !active;
    elements.resume.hidden = !paused || Boolean(active);
    elements.resume.disabled = !paused || Boolean(active);
    updateSendState();
  }

  function setAgentState(stateName, label) {
    elements.status.textContent = label;
    elements.agentDot.className = "agent-dot";
    elements.agentDot.classList.add(stateName === "working" ? "is-working"
      : stateName === "paused" ? "is-paused"
        : stateName === "error" ? "is-error" : "is-online");
  }

  function setConnection(online) {
    elements.storageDot.classList.toggle("is-online", online);
    if (!online) elements.storageStatus.textContent = "连接已断开";
  }

  function setStorage(databaseAvailable) {
    elements.storageDot.classList.add("is-online");
    elements.storageStatus.textContent = databaseAvailable ? "会话已持久化" : "本地临时会话";
  }

  function updateSendState() {
    elements.send.disabled = !state.selectedSessionId || (!elements.input.value.trim() && !state.files.length);
    elements.fileInput.disabled = !state.selectedSessionId;
  }

  function resizeInput() {
    elements.input.style.height = "auto";
    elements.input.style.height = Math.min(elements.input.scrollHeight, 160) + "px";
  }

  function scrollMessages() {
    requestAnimationFrame(() => { elements.messages.scrollTop = elements.messages.scrollHeight; });
  }

  function toggleSidebar(open) {
    elements.sidebar.classList.toggle("is-open", open);
    elements.backdrop.classList.toggle("is-open", open);
  }

  function toggleActivity(open) {
    const panel = document.querySelector(".activity-panel");
    panel.classList.toggle("is-open", open);
    $("activity-backdrop").classList.toggle("is-open", open);
    $("open-activity").setAttribute("aria-expanded", String(open));
  }
})();
