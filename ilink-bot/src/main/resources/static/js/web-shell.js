(() => {
  const $ = (id) => document.getElementById(id);
  const state = { roots: [], rootId: "", path: "", preview: null, previewUrl: "", wechatEvents: null,
    wechatStatusTimer: 0, wechatReady: false, seen: new Set(), sideView: "", sideViewUrl: "" };
  const clientId = localStorage.getItem("ilink.web.client") || "";
  const workspaceId = localStorage.getItem("ilink.web.workspace") || "";
  const headers = (extra) => Object.assign({ "X-Web-Client-Id": clientId, "X-Web-Workspace-Id": workspaceId }, extra || {});
  const api = async (path, options) => {
    const response = await fetch(path, Object.assign({}, options || {}, { headers: headers((options || {}).headers) }));
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body.error || "请求失败");
    return body;
  };
  const routeToView = (path) => ({ "/web/wechat": "wechat", "/web/files": "files" })[path] || "";

  document.querySelectorAll("[data-route]").forEach((button) => button.addEventListener("click", () => navigate(button.dataset.route)));
  document.querySelectorAll("[data-existing-view]").forEach((button) => button.addEventListener("click", () =>
    openExisting(button.dataset.existingView, button.classList.contains("module-link"))));
  document.querySelectorAll("[data-route-link]").forEach((link) => link.addEventListener("click", (event) => { event.preventDefault(); navigate(link.dataset.routeLink); }));
  window.addEventListener("popstate", () => renderRoute(location.pathname, false));
  $("console-open-sidebar")?.addEventListener("click", () => $("open-sidebar")?.click());
  $("wechat-composer")?.addEventListener("submit", sendWechatMessage);
  $("wechat-login-button")?.addEventListener("click", () => openExisting("login", false, true));
  $("side-view-backdrop")?.addEventListener("click", closeSideView);
  $("side-view-close")?.addEventListener("click", closeSideView);
  $("side-view-fullscreen")?.addEventListener("click", toggleSideViewFullscreen);
  window.addEventListener("message", (event) => {
    if (event.origin !== location.origin || event.data?.type !== "ilink-wechat-connected") return;
    closeSideView();
    if (location.pathname !== "/web/wechat") navigate("/web/wechat");
    else loadWechat();
  });
  $("wechat-input")?.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      $("wechat-composer").requestSubmit();
    }
  });
  $("workspace-root")?.addEventListener("change", () => { state.rootId = $("workspace-root").value; state.path = ""; listWorkspace(); });
  $("workspace-search-button")?.addEventListener("click", searchWorkspace);
  $("workspace-search")?.addEventListener("keydown", (event) => { if (event.key === "Enter") { event.preventDefault(); searchWorkspace(); } });
  $("workspace-edit")?.addEventListener("click", beginEdit);
  $("workspace-send")?.addEventListener("click", prepareSend);
  renderRoute(location.pathname, false);

  function navigate(path) { history.pushState({}, "", path); renderRoute(path, true); }
  function renderRoute(path) {
    if (path !== "/web/wechat") clearWechatStatusTimer();
    if (["/web/plan", "/web/sessions", "/web/login"].includes(path)) {
      showAiWorkspace();
      const view = path.substring("/web/".length);
      document.querySelectorAll(".module-link").forEach((item) =>
        item.classList.toggle("is-active", item.dataset.existingView === view));
      openExisting(view, false, view === "login");
      return;
    }
    closeSideView(false);
    const view = routeToView(path);
    const chat = document.querySelector(".chat-workspace");
    const activity = document.querySelector(".activity-panel");
    const consoleViews = $("console-views");
    document.querySelectorAll(".module-link").forEach((item) => item.classList.toggle("is-active", item.dataset.route === (view ? path : "/web") || item.dataset.existingView === view));
    if (!view) { chat.hidden = false; activity.hidden = false; consoleViews.hidden = true; return; }
    chat.hidden = true; activity.hidden = true; consoleViews.hidden = false;
    document.querySelectorAll(".console-view").forEach((item) => { item.hidden = item.dataset.view !== view; });
    if (view === "wechat") loadWechat();
    if (view === "files") loadRoots();
  }

  function showAiWorkspace() {
    document.querySelector(".chat-workspace").hidden = false;
    document.querySelector(".activity-panel").hidden = false;
    $("console-views").hidden = true;
  }

  async function openExisting(view, updateRoute, forceLogin) {
    if (updateRoute) {
      navigate("/web/" + view);
      return;
    }
    try {
      const navigation = await api("/api/web/navigation");
      let url = view === "wechat" ? navigation.wechatUrl
        : view === "plan" ? navigation.planUrl
        : view === "sessions" ? navigation.sessionsUrl
        : navigation.loginUrl;
      if (!url) throw new Error("对应页面尚未启动");
      if (url === "/web/wechat") navigate(url);
      else {
        if (view === "login" && forceLogin) {
          const loginUrl = new URL(url, location.origin);
          loginUrl.searchParams.set("force", "1");
          url = loginUrl.pathname + loginUrl.search;
        }
        openSideView(view, url);
      }
    } catch (error) {
      window.alert(error.message);
    }
  }

  function openSideView(view, url) {
    const titles = { plan: "七日计划", sessions: "微信会话管理", login: "微信扫码登录" };
    state.sideView = view;
    state.sideViewUrl = url;
    $("side-view-title").textContent = titles[view] || "辅助页面";
    $("side-view-frame").src = url;
    $("side-view-external").href = url;
    $("side-view-panel").classList.add("is-open");
    $("side-view-panel").setAttribute("aria-hidden", "false");
    $("side-view-backdrop").classList.add("is-open");
  }

  function closeSideView(clearFrame = true) {
    const panel = $("side-view-panel");
    panel.classList.remove("is-open", "is-fullscreen");
    panel.setAttribute("aria-hidden", "true");
    $("side-view-backdrop").classList.remove("is-open");
    $("side-view-fullscreen").setAttribute("aria-pressed", "false");
    if (clearFrame) $("side-view-frame").src = "about:blank";
    state.sideView = "";
    state.sideViewUrl = "";
  }

  function toggleSideViewFullscreen() {
    const panel = $("side-view-panel");
    const fullscreen = panel.classList.toggle("is-fullscreen");
    $("side-view-fullscreen").setAttribute("aria-pressed", String(fullscreen));
    $("side-view-fullscreen").title = fullscreen ? "退出全屏" : "全屏显示";
  }

  async function loadWechat() {
    try {
      const status = await api("/api/web/wechat/activate", { method: "POST" });
      if (!status.connected || !status.paired) {
        const navigation = await api("/api/web/navigation");
        window.location.assign(navigation.loginUrl);
        return;
      }
      state.wechatReady = Boolean(status.ready);
      $("wechat-connection").classList.remove("is-error");
      $("wechat-connection").textContent = status.detail || (state.wechatReady ? "微信已连接" : "微信已登录");
      setWechatComposerReady(state.wechatReady);
      renderWechatMessages((await api("/api/web/wechat/messages")).messages || []);
      connectWechatEvents();
      if (!state.wechatReady) scheduleWechatStatusRefresh();
    } catch (error) { $("wechat-connection").textContent = error.message; }
  }

  function setWechatComposerReady(ready) {
    const input = $("wechat-input");
    const send = $("wechat-send");
    if (input) {
      input.disabled = !ready;
      input.placeholder = ready ? "通过微信 Bot 继续任务" : "请先在手机微信中向 Bot 发送一条消息";
    }
    if (send) send.disabled = !ready;
  }

  function scheduleWechatStatusRefresh(delay = 1500) {
    clearWechatStatusTimer();
    if (location.pathname !== "/web/wechat") return;
    state.wechatStatusTimer = window.setTimeout(() => {
      state.wechatStatusTimer = 0;
      loadWechat();
    }, delay);
  }

  function clearWechatStatusTimer() {
    if (!state.wechatStatusTimer) return;
    window.clearTimeout(state.wechatStatusTimer);
    state.wechatStatusTimer = 0;
  }

  async function sendWechatMessage(event) {
    event.preventDefault();
    const input = $("wechat-input");
    const text = input.value.trim();
    if (!text) return;
    input.value = "";
    $("wechat-send").disabled = true;
    try {
      await api("/api/web/wechat/messages", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ text }) });
    } catch (error) {
      input.value = text;
      window.alert(error.message);
    } finally {
      $("wechat-send").disabled = !state.wechatReady;
      if (state.wechatReady) input.focus();
    }
  }
  function connectWechatEvents() {
    if (state.wechatEvents) return;
    const query = new URLSearchParams({ clientId, workspaceId, scope: "wechat", after: localStorage.getItem("ilink.web.wechat.lastEventId") || "0" });
    const source = new EventSource("/api/web/events?" + query.toString()); state.wechatEvents = source;
    source.addEventListener("agent", (event) => {
      localStorage.setItem("ilink.web.wechat.lastEventId", event.lastEventId || "0");
      const payload = JSON.parse(event.data); const meta = payload.metadata || {};
      if (meta.integrationType === "sync_failed") {
        $("wechat-connection").textContent = payload.content || "微信同步失败";
        $("wechat-connection").classList.add("is-error");
        return;
      }
      const messageKey = meta.messageId || event.lastEventId;
      if (meta.integrationType !== "message" || state.seen.has(messageKey)) return;
      state.seen.add(messageKey); appendWechatMessage({ messageId: meta.messageId, content: payload.content, source: meta.source, syncState: meta.syncState, createdAtMillis: meta.createdAtMillis || Date.now() });
      if (meta.source === "wechat_input" && !state.wechatReady) scheduleWechatStatusRefresh(100);
    });
    source.addEventListener("error", () => { /* EventSource reconnects and replays by id. */ });
  }
  function renderWechatMessages(messages) { const host = $("wechat-messages"); host.replaceChildren(); messages.forEach((message) => { if (message.messageId) state.seen.add(message.messageId); appendWechatMessage(message); }); scrollWechatToBottom(); }
  function appendWechatMessage(message) { const host = $("wechat-messages"); const item = document.createElement("article"); const userMessage = message.source === "wechat_input" || message.source === "web_input"; item.className = "mirror-message " + (userMessage ? "is-user" : "is-bot"); const title = document.createElement("strong"); title.textContent = labelFor(message.source, message.syncState); const content = document.createElement("p"); content.textContent = message.content || "（附件）"; const time = document.createElement("time"); time.textContent = new Date(message.createdAtMillis || Date.now()).toLocaleString(); item.append(title, content, time); host.append(item); scrollWechatToBottom(); }
  function scrollWechatToBottom() { const host = $("wechat-messages"); host.scrollTop = host.scrollHeight; }
  function labelFor(source, stateName) { return (({ wechat_input: "微信输入", web_input: "Web 同步输入", bot_reply: "Bot 回复", file: "微信文件" })[source] || "系统") + (stateName === "failed" ? " · 失败" : ""); }

  async function loadRoots() {
    try { const data = await api("/api/web/workspace/roots"); state.roots = data.roots || []; const select = $("workspace-root"); select.replaceChildren(); state.roots.forEach((root) => { const option = document.createElement("option"); option.value = root.id; option.textContent = root.name; select.append(option); }); state.rootId = select.value || ""; if (!state.rootId) { $("workspace-entries").textContent = "尚未配置 workspace.roots。"; return; } await listWorkspace(); } catch (error) { $("workspace-entries").textContent = error.message; }
  }
  async function listWorkspace() { const data = await api("/api/web/workspace/list?" + new URLSearchParams({ rootId: state.rootId, path: state.path })); $("workspace-path").textContent = "/" + state.path; renderEntries(data.entries || []); }
  async function searchWorkspace() { const q = $("workspace-search").value.trim(); if (!q) return listWorkspace(); const data = await api("/api/web/workspace/search?" + new URLSearchParams({ rootId: state.rootId, q })); $("workspace-path").textContent = "搜索：" + q; renderEntries(data.entries || []); }
  function renderEntries(entries) { const host = $("workspace-entries"); host.replaceChildren(); entries.forEach((entry) => { const button = document.createElement("button"); button.className = "workspace-entry"; const name = document.createElement("span"); name.textContent = (entry.directory ? "文件夹 · " : "文件 · ") + entry.name; const meta = document.createElement("small"); meta.textContent = entry.directory ? entry.path : formatBytes(entry.size); button.append(name, meta); button.addEventListener("click", () => entry.directory ? (state.path = entry.path, listWorkspace()) : previewFile(entry.path)); host.append(button); }); if (!entries.length) host.textContent = "没有匹配的文件。"; }
  async function previewFile(path) { try { const preview = await api("/api/web/workspace/preview?" + new URLSearchParams({ rootId: state.rootId, path })); state.preview = preview; $("workspace-file-name").textContent = preview.name; $("workspace-meta").textContent = preview.contentType + " · " + formatBytes(preview.size) + " · " + preview.path; $("workspace-editor").hidden = true; $("workspace-edit").disabled = !preview.text; $("workspace-send").disabled = false; $("workspace-confirm").hidden = true; await renderWorkspacePreview(preview); } catch (error) { $("workspace-content").hidden = false; $("workspace-content").textContent = error.message; } }
  async function renderWorkspacePreview(preview) { if (state.previewUrl) URL.revokeObjectURL(state.previewUrl); state.previewUrl = ""; const media = $("workspace-media"); media.replaceChildren(); media.hidden = true; const content = $("workspace-content"); if (preview.text) { content.hidden = false; content.textContent = preview.content; return; } if (preview.contentType.startsWith("image/") || preview.contentType === "application/pdf") { const response = await fetch("/api/web/workspace/content?" + new URLSearchParams({ rootId: state.rootId, path: preview.path }), { headers: headers() }); if (!response.ok) throw new Error("文件预览加载失败"); state.previewUrl = URL.createObjectURL(await response.blob()); const element = preview.contentType.startsWith("image/") ? document.createElement("img") : document.createElement("iframe"); element.src = state.previewUrl; element.title = preview.name; if (element.tagName === "IMG") element.alt = preview.name; media.append(element); media.hidden = false; content.hidden = true; return; } content.hidden = false; content.textContent = "该文件类型不支持内嵌预览，可在确认后发送到已绑定微信。"; }
  function beginEdit() { if (!state.preview?.text) return; $("workspace-editor").value = state.preview.content; $("workspace-editor").hidden = false; $("workspace-content").hidden = true; $("workspace-media").hidden = true; const host = $("workspace-confirm"); host.hidden = false; host.replaceChildren(commandButton("预览修改", prepareWrite)); }
  async function prepareWrite() { try { const result = await api("/api/web/workspace/prepare-write", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ rootId: state.rootId, path: state.preview.path, content: $("workspace-editor").value }) }); confirmArea(result.summary, "确认写入", async () => { await api("/api/web/workspace/confirm-write", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ token: result.token }) }); await previewFile(state.preview.path); }, { before: result.before, after: result.after }); } catch (error) { confirmArea(error.message); } }
  async function prepareSend() { if (!state.preview) return; try { const result = await api("/api/web/workspace/prepare-send", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ rootId: state.rootId, path: state.preview.path }) }); confirmArea("将发送 " + result.fileName + "（" + formatBytes(result.size) + "）到已绑定微信。", "确认发送", async () => { await api("/api/web/workspace/confirm-send", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ token: result.token }) }); confirmArea("文件已发送到微信。"); }); } catch (error) { confirmArea(error.message); } }
  function confirmArea(text, label, action, comparison) { const host = $("workspace-confirm"); host.hidden = false; host.replaceChildren(); const copy = document.createElement("p"); copy.textContent = text; host.append(copy); if (comparison) { const details = document.createElement("details"); const summary = document.createElement("summary"); summary.textContent = "查看修改前后内容"; const before = document.createElement("pre"); before.textContent = "修改前\n" + comparison.before; const after = document.createElement("pre"); after.textContent = "修改后\n" + comparison.after; details.append(summary, before, after); host.append(details); } if (action) host.append(commandButton(label, action)); }
  function commandButton(label, action) { const button = document.createElement("button"); button.className = "primary-command"; button.type = "button"; button.textContent = label; button.addEventListener("click", action); return button; }
  function formatBytes(value) { return value < 1024 ? value + " B" : value < 1048576 ? (value / 1024).toFixed(1) + " KB" : (value / 1048576).toFixed(1) + " MB"; }
})();
