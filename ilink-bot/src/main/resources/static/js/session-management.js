(() => {
  const token = location.pathname.split("/").filter(Boolean).at(-1);
  const state = { data: null, selectedSessionId: "" };
  const $ = (id) => document.getElementById(id);
  const api = (path = "") => `/api/sessions/${encodeURIComponent(token)}${path}`;

  $("create-session").addEventListener("click", createSession);
  $("activate-session").addEventListener("click", activateSelectedSession);

  async function loadOverview(preferredSessionId = state.selectedSessionId) {
    try {
      const response = await fetch(api(), { cache: "no-store" });
      if (!response.ok) throw new Error("读取失败");
      state.data = await response.json();
      if (!state.data.ready) {
        $("workspace").hidden = true;
        $("empty-state").hidden = false;
        $("empty-state").querySelector("p").textContent = state.data.message;
        return;
      }
      $("empty-state").hidden = true;
      $("workspace").hidden = false;
      renderOverview();
      const target = preferredSessionId || state.data.profile?.activeSessionId || state.data.sessions?.[0]?.sessionId;
      if (target) await selectSession(target);
      else renderEmptyConversation("该用户暂时没有已保存的会话。");
    } catch (error) { showToast("无法读取会话数据，请检查数据库连接"); }
  }

  function renderOverview() {
    const profile = state.data.profile || {};
    $("create-session").disabled = !state.data.databaseAvailable;
    $("profile-list").replaceChildren(profileRow("人格", profile.persona || "默认"), profileRow("常用地点", profile.location || "未设置"));
    const memories = $("memory-list"); memories.replaceChildren();
    if (!state.data.memories?.length) memories.innerHTML = '<p class="memory-empty">暂无长期记忆</p>';
    else state.data.memories.forEach((memory) => {
      const item = document.createElement("div"); item.className = "memory";
      const value = document.createElement("div"); value.textContent = memory.value;
      const meta = document.createElement("small"); meta.textContent = `${memory.type || "资料"} · 重要度 ${memory.importance}`;
      item.append(value, meta); memories.appendChild(item);
    });
    renderFiles();
    $("storage-note").textContent = state.data.databaseAvailable ? "数据已从数据库读取。" : "数据库当前不可用，历史会话无法跨重启查看。";
    renderSessionList();
  }

  function renderFiles() {
    const list = $("file-list"); list.replaceChildren();
    if (!state.data.files?.length) { list.innerHTML = '<p class="memory-empty">暂无由 Bot 保存的文件</p>'; return; }
    state.data.files.forEach((file) => {
      const item = document.createElement("a"); item.className = "file-item"; item.href = file.url; item.target = "_blank"; item.rel = "noopener";
      const kind = document.createElement("span"); kind.className = "file-kind"; kind.textContent = file.type.toUpperCase();
      const copy = document.createElement("span"); const name = document.createElement("strong"); name.textContent = file.name;
      const meta = document.createElement("small"); meta.textContent = `${formatBytes(file.size)} · ${file.modifiedAt}`;
      copy.append(name, meta); item.append(kind, copy); list.appendChild(item);
    });
  }

  function profileRow(label, value) { const row = document.createElement("div"); const key = document.createElement("dt"); const content = document.createElement("dd"); key.textContent = label; content.textContent = value; row.append(key, content); return row; }
  function renderSessionList() {
    const list = $("session-list"); list.replaceChildren();
    if (!state.data.sessions?.length) { list.innerHTML = '<p class="session-empty">暂无已保存会话</p>'; return; }
    state.data.sessions.forEach((session) => {
      const item = document.createElement("button"); item.type = "button"; item.className = `session-item${session.sessionId === state.selectedSessionId ? " active" : ""}`;
      const title = document.createElement("strong"); title.textContent = session.title;
      const time = document.createElement("small"); time.textContent = `${session.status === "ACTIVE" ? "当前会话 · " : ""}${session.lastActiveTime || session.createdTime || ""}`;
      item.append(title, time); item.addEventListener("click", () => selectSession(session.sessionId)); list.appendChild(item);
    });
  }

  async function selectSession(sessionId) {
    state.selectedSessionId = sessionId; renderSessionList(); $("activate-session").disabled = !state.data.databaseAvailable;
    try {
      const response = await fetch(api(`/${encodeURIComponent(sessionId)}/messages`), { cache: "no-store" });
      if (!response.ok) throw new Error("读取消息失败");
      const data = await response.json();
      $("conversation-title").textContent = data.title; $("conversation-meta").textContent = "当前聊天记录";
      const list = $("message-list"); list.replaceChildren();
      if (!data.messages?.length) { renderEmptyConversation("该会话没有可展示的消息。"); return; }
      data.messages.forEach((message) => {
        const item = document.createElement("article"); const role = String(message.role || "assistant").toLowerCase(); item.className = `message ${role === "user" ? "user" : "assistant"}`;
        const label = document.createElement("span"); label.className = "role"; label.textContent = role === "user" ? "用户" : "助手";
        const content = document.createElement("div"); content.textContent = message.content; item.append(label, content); list.appendChild(item);
      });
      list.scrollTop = list.scrollHeight;
    } catch (error) { renderEmptyConversation("无法读取该会话消息。"); showToast("读取消息失败"); }
  }

  function renderEmptyConversation(text) { $("conversation-title").textContent = "聊天记录"; $("conversation-meta").textContent = ""; $("message-list").innerHTML = `<p class="placeholder">${escapeHtml(text)}</p>`; }
  async function createSession() {
    try {
      const response = await fetch(api("/create"), { method: "POST" });
      if (!response.ok) throw new Error("创建失败"); const data = await response.json(); showToast("已新建会话"); await loadOverview(data.sessionId);
    } catch (error) { showToast("新建会话失败"); }
  }
  async function activateSelectedSession() {
    if (!state.selectedSessionId) return;
    try {
      const response = await fetch(api(`/${encodeURIComponent(state.selectedSessionId)}/activate`), { method: "POST" });
      if (!response.ok) throw new Error("切换失败"); showToast("已切换当前会话"); await loadOverview(state.selectedSessionId);
    } catch (error) { showToast("切换会话失败"); }
  }
  function escapeHtml(value) { const box = document.createElement("div"); box.textContent = value; return box.innerHTML; }
  function formatBytes(size) { if (size < 1024) return `${size} B`; if (size < 1024 * 1024) return `${Math.ceil(size / 1024)} KB`; return `${(size / 1024 / 1024).toFixed(1)} MB`; }
  function showToast(text) { const toast = $("toast"); toast.textContent = text; toast.classList.add("show"); clearTimeout(showToast.timer); showToast.timer = setTimeout(() => toast.classList.remove("show"), 2200); }
  loadOverview();
})();
