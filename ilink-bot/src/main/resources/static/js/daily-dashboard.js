(function () {
    "use strict";

    const state = { data: null, demo: false, dashboardLoading: false, weatherPollTimer: null };
    const token = location.pathname.split("/").filter(Boolean).at(-1) || "";
    const $ = (id) => document.getElementById(id);
    const weatherBackground = createWeatherBackground($("weather-canvas"));

    function createWeatherBackground(canvas) {
        const context = canvas.getContext("2d");
        const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
        const particles = Array.from({ length: 180 }, (_, index) => ({
            x: (index * 47.3) % 1,
            y: (index * 83.7) % 1,
            depth: 0.35 + ((index * 17) % 65) / 100,
            phase: (index * 1.73) % (Math.PI * 2)
        }));
        let width = 1;
        let height = 1;
        let dpr = 1;
        let particleLimit = 180;
        let current = defaultVisual();
        let target = current;
        let transitionStarted = 0;
        let frame = 0;
        let visible = !document.hidden;

        function defaultVisual() {
            return {
                conditionGroup: "unknown", day: true, cloudCover: 0.38,
                precipitation: 0, precipitationProbability: 0,
                windSpeed: 0, windDirection: 0
            };
        }

        function normalize(visual) {
            const groups = new Set(["clear", "cloudy", "rain", "snow", "fog", "storm", "unknown"]);
            return {
                conditionGroup: groups.has(visual.conditionGroup) ? visual.conditionGroup : "unknown",
                day: visual.day !== false,
                cloudCover: clamp(Number(visual.cloudCover), 0, 1),
                precipitation: Math.max(0, Number(visual.precipitation) || 0),
                precipitationProbability: clamp(Number(visual.precipitationProbability), 0, 100),
                windSpeed: Math.max(0, Number(visual.windSpeed) || 0),
                windDirection: Number(visual.windDirection) || 0
            };
        }

        function clamp(value, min, max) {
            return Math.max(min, Math.min(max, value));
        }

        function resize() {
            const bounds = canvas.getBoundingClientRect();
            width = Math.max(1, bounds.width);
            height = Math.max(1, bounds.height);
            dpr = Math.min(window.devicePixelRatio || 1, 1.5);
            particleLimit = width < 600 ? 100 : 180;
            canvas.width = Math.round(width * dpr);
            canvas.height = Math.round(height * dpr);
            context.setTransform(dpr, 0, 0, dpr, 0, 0);
            draw(performance.now());
        }

        function setState(visual) {
            if (!visual || visual.ready === false) return;
            target = normalize(visual);
            if (reducedMotion.matches) {
                current = target;
                draw(performance.now());
                return;
            }
            transitionStarted = performance.now();
            start();
        }

        function interpolate(from, to, progress) {
            const value = (key) => from[key] + (to[key] - from[key]) * progress;
            return {
                conditionGroup: progress < 0.5 ? from.conditionGroup : to.conditionGroup,
                day: progress < 0.5 ? from.day : to.day,
                cloudCover: value("cloudCover"),
                precipitation: value("precipitation"),
                precipitationProbability: value("precipitationProbability"),
                windSpeed: value("windSpeed"),
                windDirection: value("windDirection")
            };
        }

        function start() {
            if (!visible || frame) return;
            frame = requestAnimationFrame(loop);
        }

        function loop(timestamp) {
            frame = 0;
            if (!visible) return;
            const progress = transitionStarted
                ? clamp((timestamp - transitionStarted) / 1600, 0, 1)
                : 1;
            current = interpolate(current, target, progress);
            if (progress >= 1) transitionStarted = 0;
            draw(timestamp);
            start();
        }

        function palette(visual) {
            if (!visual.day) return ["#03050a", "#0b1422", "#111e2d"];
            switch (visual.conditionGroup) {
                case "rain": return ["#07111b", "#102338", "#1b3348"];
                case "storm": return ["#05080e", "#111a28", "#17273a"];
                case "snow": return ["#0c1722", "#1d3444", "#33485a"];
                case "fog": return ["#111820", "#253441", "#40505d"];
                case "cloudy": return ["#091521", "#183149", "#2c4b60"];
                case "clear": return ["#071426", "#153a61", "#416b8d"];
                default: return ["#080e16", "#142638", "#263c4d"];
            }
        }

        function draw(timestamp) {
            context.clearRect(0, 0, width, height);
            const colors = palette(current);
            const sky = context.createLinearGradient(0, 0, 0, height);
            sky.addColorStop(0, colors[0]);
            sky.addColorStop(0.55, colors[1]);
            sky.addColorStop(1, colors[2]);
            context.globalAlpha = 0.76;
            context.fillStyle = sky;
            context.fillRect(0, 0, width, height);
            context.globalAlpha = 1;

            if (current.day) {
                const lightX = width * (0.68 + Math.sin(timestamp * 0.00036) * 0.12);
                const lightY = height * (0.08 + Math.cos(timestamp * 0.00027) * 0.04);
                const light = context.createRadialGradient(lightX, lightY, 0, lightX, lightY, width * 0.72);
                light.addColorStop(0, "rgba(154, 191, 218, 0.18)");
                light.addColorStop(1, "rgba(154, 191, 218, 0)");
                context.fillStyle = light;
                context.fillRect(0, 0, width, height);
            }

            drawClouds(timestamp);
            if (current.conditionGroup === "rain" || current.conditionGroup === "storm") drawRain(timestamp);
            if (current.conditionGroup === "snow") drawSnow(timestamp);
            if (current.conditionGroup === "fog") drawFog(timestamp);
            if (current.conditionGroup === "storm") drawStorm(timestamp);
        }

        function drawClouds(timestamp) {
            const amount = clamp(current.cloudCover + (current.conditionGroup === "rain" ? 0.25 : 0), 0.08, 1);
            const count = Math.round(2 + amount * 5);
            const drift = timestamp * (0.012 + current.windSpeed * 0.00024);
            context.save();
            context.globalAlpha = 0.06 + amount * 0.1;
            for (let index = 0; index < count; index++) {
                const baseWidth = width * (0.18 + (index % 3) * 0.08);
                const x = ((index * width * 0.27 + drift * (index % 2 ? 1 : -0.7)) % (width + baseWidth * 2)) - baseWidth;
                const y = height * (0.16 + (index % 4) * 0.13);
                context.fillStyle = index % 2 ? "#8aa2b5" : "#536c82";
                context.beginPath();
                context.ellipse(x, y, baseWidth, height * 0.055, 0, 0, Math.PI * 2);
                context.ellipse(x + baseWidth * 0.34, y - height * 0.035, baseWidth * 0.56, height * 0.07, 0, 0, Math.PI * 2);
                context.fill();
            }
            context.restore();
        }

        function drawRain(timestamp) {
            const intensity = clamp(current.precipitation * 18 + current.precipitationProbability / 55, 0.15, 1);
            const count = Math.min(particleLimit, Math.round(35 + intensity * 70));
            const lean = Math.sin((current.windDirection + 180) * Math.PI / 180) * (5 + current.windSpeed * 0.12);
            context.save();
            context.strokeStyle = "rgba(172, 204, 226, 0.28)";
            context.lineWidth = 0.7;
            for (let index = 0; index < count; index++) {
                const particle = particles[index];
                const speed = 0.08 + particle.depth * 0.12;
                const x = (particle.x * width + timestamp * lean * 0.045 * speed) % (width + 40) - 20;
                const y = (particle.y * height + timestamp * speed * 0.42) % (height + 80) - 40;
                context.beginPath();
                context.moveTo(x, y);
                context.lineTo(x + lean * 0.7, y + 10 + particle.depth * 12);
                context.stroke();
            }
            context.restore();
        }

        function drawSnow(timestamp) {
            const count = Math.min(particleLimit, Math.round(22 + current.precipitationProbability * 0.55));
            context.save();
            context.fillStyle = "rgba(210, 225, 235, 0.42)";
            for (let index = 0; index < count; index++) {
                const particle = particles[index];
                const x = (particle.x * width + Math.sin(timestamp * 0.0015 + particle.phase) * 14) % width;
                const y = (particle.y * height + timestamp * (0.036 + particle.depth * 0.054)) % height;
                context.beginPath();
                context.arc(x, y, 0.8 + particle.depth * 1.4, 0, Math.PI * 2);
                context.fill();
            }
            context.restore();
        }

        function drawFog(timestamp) {
            context.save();
            context.globalAlpha = 0.1;
            for (let index = 0; index < 3; index++) {
                const gradient = context.createLinearGradient(0, height * (0.35 + index * 0.16), width, height * (0.42 + index * 0.16));
                gradient.addColorStop(0, "rgba(190, 207, 216, 0)");
                gradient.addColorStop(0.5, "rgba(190, 207, 216, 0.32)");
                gradient.addColorStop(1, "rgba(190, 207, 216, 0)");
                context.fillStyle = gradient;
                context.fillRect(0, height * (0.28 + index * 0.16) + Math.sin(timestamp * 0.0006 + index) * 8, width, height * 0.12);
            }
            context.restore();
        }

        function drawStorm(timestamp) {
            const pulse = Math.pow(Math.max(0, Math.sin(timestamp * 0.00022)), 20) * 0.08;
            if (pulse <= 0) return;
            context.fillStyle = `rgba(190, 211, 225, ${pulse})`;
            context.fillRect(0, 0, width, height);
        }

        function visibilityChanged(isVisible) {
            visible = isVisible;
            if (!visible && frame) {
                cancelAnimationFrame(frame);
                frame = 0;
            }
            if (visible) {
                resize();
                start();
            }
        }

        window.addEventListener("resize", resize, { passive: true });
        reducedMotion.addEventListener?.("change", () => {
            if (reducedMotion.matches) {
                current = target;
                draw(performance.now());
            } else {
                transitionStarted = performance.now();
                start();
            }
        });
        resize();
        start();
        return { setState, visibilityChanged };
    }

    async function loadDashboard() {
        if (state.dashboardLoading) return;
        state.dashboardLoading = true;
        const refresh = $("refresh-button");
        refresh.classList.add("loading");
        if (token.endsWith(".html")) {
            state.data = demoData();
            state.demo = true;
            refresh.classList.remove("loading");
            state.dashboardLoading = false;
            render(state.data);
            return;
        }
        try {
            const response = await fetch(`/api/daily/${encodeURIComponent(token)}`, { cache: "no-store" });
            if (!response.ok) throw new Error("dashboard unavailable");
            state.data = await response.json();
            state.demo = false;
        } catch (error) {
            state.data = demoData();
            state.demo = true;
        } finally {
            refresh.classList.remove("loading");
            state.dashboardLoading = false;
        }
        render(state.data);
        scheduleWeatherPoll();
    }

    function scheduleWeatherPoll() {
        clearTimeout(state.weatherPollTimer);
        state.weatherPollTimer = null;
        if (state.demo || document.hidden || state.data?.weather?.visual?.ready !== false) return;
        state.weatherPollTimer = setTimeout(refreshWeatherOnly, 2200);
    }

    async function refreshWeatherOnly() {
        if (state.demo || document.hidden) return;
        try {
            const response = await fetch(`/api/weather/${encodeURIComponent(token)}`, { cache: "no-store" });
            if (!response.ok) throw new Error("weather unavailable");
            const weather = await response.json();
            if (state.data) state.data.weather = weather;
            renderWeather(weather);
            scheduleWeatherPoll();
        } catch (error) {
            scheduleWeatherPoll();
        }
    }

    function render(data) {
        $("month-name").textContent = data.monthName;
        $("week-number").textContent = data.weekNumber;
        $("header-week").textContent = data.weekNumber;
        $("rail-theme").textContent = data.theme;
        $("main-theme").textContent = data.theme;
        $("day-number").textContent = String(data.dayNumber).padStart(2, "0");
        $("month-year").textContent = data.monthYear;
        $("weekday").textContent = data.weekday;
        $("keyword").textContent = data.keyword;
        $("updated-at").textContent = data.generatedAt;
        renderQuotes(data.quotes);
        renderSummary(data.summary);
        renderWeek(data.days);
        renderMobileAgenda(data.days);
        renderTodos(data.todos);
        renderWeather(data.weather);
        renderTrend(data.trend);
    }

    function renderQuotes(quotes) {
        const list = $("quote-list");
        list.replaceChildren();
        quotes.forEach((quote, index) => {
            const item = document.createElement("div");
            item.className = "quote-item";
            item.style.animationDelay = `${index * 70}ms`;
            item.innerHTML = `<b></b>${escapeHtml(quote)}`;
            list.appendChild(item);
        });
    }

    function renderSummary(summary) {
        $("total-count").textContent = summary.total;
        $("completed-count").textContent = summary.completed;
        $("remaining-count").textContent = `${summary.remaining} 项剩余`;
        $("completion-rate").textContent = `${summary.completionRate}%`;
        $("focus-time").textContent = summary.focusLabel;
        $("sleep-time").textContent = summary.sleepLabel;
        $("completion-ring").style.setProperty("--rate", `${summary.completionRate * 3.6}deg`);
        requestAnimationFrame(() => { $("rail-progress").style.width = `${summary.completionRate}%`; });
    }

    function renderWeek(days) {
        const schedule = $("week-schedule");
        schedule.replaceChildren();
        const axis = document.createElement("div");
        axis.className = "time-axis";
        for (let hour = 7; hour <= 23; hour++) {
            const label = document.createElement("span");
            label.textContent = `${String(hour).padStart(2, "0")}:00`;
            axis.appendChild(label);
        }
        const timelines = document.createElement("div");
        timelines.className = "timeline-columns";

        days.forEach((day, dayIndex) => {
            const header = document.createElement("header");
            header.className = `day-header${day.today ? " today" : ""}`;
            header.style.gridColumn = String(dayIndex + 2);
            header.innerHTML = `<span>周${day.weekday}</span><strong>${day.dayNumber}</strong>`;
            const allDay = document.createElement("div");
            allDay.className = "all-day-lane";
            allDay.style.gridColumn = String(dayIndex + 2);
            const track = document.createElement("div");
            track.className = `day-track${day.today ? " today" : ""}`;
            let timedIndex = 0;
            day.items.forEach((item) => {
                if (!item.time) {
                    const block = document.createElement("div");
                    block.className = `all-day-item${item.status === "completed" ? " completed" : ""}`;
                    block.textContent = item.title;
                    if (item.kind === "plan" && item.interactive) {
                        block.classList.add("interactive");
                        block.title = "标记计划任务完成";
                        block.setAttribute("role", "button");
                        block.tabIndex = 0;
                        block.addEventListener("click", () => completePlanTask(item.id, block));
                        block.addEventListener("keydown", (event) => {
                            if (event.key === "Enter" || event.key === " ") completePlanTask(item.id, block);
                        });
                    }
                    allDay.appendChild(block);
                    return;
                }
                const block = document.createElement("div");
                block.className = `schedule-item ${item.kind} ${item.priority}${item.status === "completed" ? " completed" : ""}`;
                const minutes = timeMinutes(item.time) - 7 * 60;
                const top = Math.max(0, Math.min(16 * 34, minutes / 60 * 34));
                const height = Math.max(26, Math.min(84, item.durationMinutes / 60 * 34));
                block.style.top = `${top}px`;
                block.style.height = `${height}px`;
                block.style.animationDelay = `${(dayIndex * 4 + timedIndex) * 45}ms`;
                block.innerHTML = `<time>${escapeHtml(item.time)}</time><b>${escapeHtml(item.title)}</b>`;
                if (item.actionUrl) {
                    block.classList.add("linked");
                    block.title = "打开导航";
                    block.addEventListener("click", () => window.open(item.actionUrl, "_blank", "noopener"));
                }
                track.appendChild(block);
                timedIndex++;
            });
            if (day.today) addCurrentLine(track);
            schedule.append(header, allDay);
            timelines.appendChild(track);
        });
        schedule.append(axis, timelines);
    }

    function addCurrentLine(track) {
        const now = new Date();
        const minutes = now.getHours() * 60 + now.getMinutes() - 7 * 60;
        if (minutes < 0 || minutes > 16 * 60) return;
        const line = document.createElement("div");
        line.className = "current-line";
        line.style.top = `${minutes / 60 * 34}px`;
        track.appendChild(line);
    }

    function renderMobileAgenda(days) {
        const agenda = $("mobile-agenda");
        agenda.replaceChildren();
        days.forEach((day) => {
            const section = document.createElement("section");
            section.className = "agenda-day";
            section.innerHTML = `<header class="agenda-day-header"><strong>${day.dayNumber}</strong><span>星期${day.weekday}</span><em>${day.today ? "TODAY" : ""}</em></header>`;
            if (!day.items.length) {
                section.insertAdjacentHTML("beforeend", '<p class="agenda-empty">这一天还没有安排。</p>');
            } else {
                const items = document.createElement("div");
                items.className = "agenda-items";
                day.items.forEach((item) => {
                    const row = document.createElement("div");
                    row.className = `agenda-item ${item.kind}${item.status === "completed" ? " completed" : ""}`;
                    row.innerHTML = `<time>${escapeHtml(item.time || "全天")}</time><strong>${escapeHtml(item.title)}</strong><small>${escapeHtml(item.label)}</small>`;
                    if (item.actionUrl) {
                        row.classList.add("linked");
                        row.title = "打开导航";
                        row.addEventListener("click", () => window.open(item.actionUrl, "_blank", "noopener"));
                    } else if (item.kind === "plan" && item.interactive) {
                        row.classList.add("linked");
                        row.title = "标记计划任务完成";
                        row.addEventListener("click", () => completePlanTask(item.id, row));
                    }
                    items.appendChild(row);
                });
                section.appendChild(items);
            }
            agenda.appendChild(section);
        });
    }

    function renderTodos(todos) {
        const list = $("todo-list");
        list.replaceChildren();
        $("todo-badge").textContent = todos.length;
        if (!todos.length) {
            list.innerHTML = '<p class="empty-state">今天没有悬而未决的待办，给自己留一点完整时间。</p>';
            return;
        }
        todos.forEach((todo) => {
            const label = document.createElement("label");
            label.className = `todo-item${todo.overdue ? " overdue" : ""}`;
            const checkbox = document.createElement("input");
            checkbox.type = "checkbox";
            checkbox.setAttribute("aria-label", `完成待办：${todo.title}`);
            checkbox.addEventListener("change", () => completeTodo(todo.id, label, checkbox));
            const copy = document.createElement("span");
            copy.className = "todo-copy";
            copy.innerHTML = `<strong>${escapeHtml(todo.title)}</strong><span>${escapeHtml(todo.dueLabel)}</span>`;
            label.append(checkbox, copy);
            list.appendChild(label);
        });
    }

    async function completeTodo(id, row, checkbox) {
        checkbox.disabled = true;
        if (state.demo) {
            row.style.opacity = "0.35";
            showToast("已在预览中标记完成");
            return;
        }
        try {
            const response = await fetch(`/api/todos/${encodeURIComponent(token)}/${encodeURIComponent(id)}/complete`, { method: "POST" });
            if (!response.ok) throw new Error("complete failed");
            showToast("待办已完成");
            await loadDashboard();
        } catch (error) {
            checkbox.checked = false;
            checkbox.disabled = false;
            showToast("暂时没有完成这项待办");
        }
    }

    async function completePlanTask(id, row) {
        if (row.dataset.loading === "true") return;
        row.dataset.loading = "true";
        if (state.demo) {
            row.classList.add("completed");
            showToast("已在预览中标记完成");
            return;
        }
        try {
            const response = await fetch(`/api/plan-tasks/${encodeURIComponent(token)}/${encodeURIComponent(id)}/complete`, { method: "POST" });
            if (!response.ok) throw new Error("complete failed");
            showToast("计划任务已完成");
            await loadDashboard();
        } catch (error) {
            delete row.dataset.loading;
            showToast("暂时无法完成这项计划任务");
        }
    }

    function renderWeather(weather) {
        $("weather-location").textContent = weather.location;
        $("weather-headline").textContent = weather.headline;
        $("weather-temperature").textContent = weather.temperature;
        $("weather-rain").textContent = weather.rain;
        $("weather-status").textContent = weather.available ? "实时" : "待设置";
        $("weather-details").textContent = weather.details;
        weatherBackground.setState(weather.visual);
    }

    function renderTrend(trend) {
        const grid = document.querySelector(".chart-grid");
        const labels = $("trend-labels");
        grid.replaceChildren();
        labels.replaceChildren();
        [16, 40, 64].forEach((y) => {
            const line = document.createElementNS("http://www.w3.org/2000/svg", "line");
            line.setAttribute("x1", "0"); line.setAttribute("x2", "720");
            line.setAttribute("y1", y); line.setAttribute("y2", y);
            grid.appendChild(line);
        });
        const maxPlanned = Math.max(60, ...trend.map((point) => point.plannedMinutes));
        const maxCompleted = Math.max(1, ...trend.map((point) => point.completed));
        const planned = [];
        const completed = [];
        trend.forEach((point, index) => {
            const x = 18 + index * 114;
            planned.push(`${x},${70 - point.plannedMinutes / maxPlanned * 52}`);
            completed.push(`${x},${70 - point.completed / maxCompleted * 42}`);
            const text = document.createElementNS("http://www.w3.org/2000/svg", "text");
            text.setAttribute("x", x); text.setAttribute("y", "86");
            text.textContent = `周${point.label}`;
            labels.appendChild(text);
        });
        $("planned-line").setAttribute("points", planned.join(" "));
        $("completed-line").setAttribute("points", completed.join(" "));
    }

    function timeMinutes(value) {
        const [hour, minute] = value.split(":").map(Number);
        return hour * 60 + minute;
    }

    function showToast(message) {
        const toast = $("toast");
        toast.textContent = message;
        toast.classList.add("show");
        clearTimeout(showToast.timer);
        showToast.timer = setTimeout(() => toast.classList.remove("show"), 1800);
    }

    function escapeHtml(value) {
        return String(value ?? "").replace(/[&<>'"]/g, (char) => ({
            "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;"
        })[char]);
    }

    function demoData() {
        const today = new Date();
        const weekdays = ["日", "一", "二", "三", "四", "五", "六"];
        const quotes = ["把今天过得具体一点", "缓慢坚定，也是一种速度", "先完成，再完美", "专注当下，不追赶噪音", "所有积累都不会白费", "保持清醒，也保留热爱", "今天结束前，再向前一步"];
        const samples = [
            [{ title: "整理今日优先级", time: "08:30", kind: "todo", label: "待办", status: "completed", priority: "medium", durationMinutes: 35 }, { title: "第一段深度工作", time: "09:30", kind: "plan", label: "计划", status: "completed", priority: "high", durationMinutes: 120 }, { title: "检查项目细节", time: "15:00", kind: "calendar", label: "工作", status: "pending", priority: "high", durationMinutes: 75 }],
            [{ title: "完成日报页面联调", time: "10:00", kind: "plan", label: "计划", status: "pending", priority: "high", durationMinutes: 120 }, { title: "散步与放松", time: "19:00", kind: "calendar", label: "生活", status: "pending", priority: "low", durationMinutes: 50 }],
            [{ title: "整理下一阶段需求", time: "", kind: "todo", label: "待办", status: "pending", priority: "medium", durationMinutes: 45 }],
            [{ title: "阅读技术资料", time: "20:00", kind: "plan", label: "计划", status: "pending", priority: "medium", durationMinutes: 80 }],
            [],
            [{ title: "本周复盘", time: "21:00", kind: "calendar", label: "学习", status: "pending", priority: "medium", durationMinutes: 60 }],
            [{ title: "规划下周", time: "20:30", kind: "plan", label: "计划", status: "pending", priority: "medium", durationMinutes: 60 }]
        ];
        const days = Array.from({ length: 7 }, (_, index) => {
            const date = new Date(today); date.setDate(today.getDate() + index);
            return { date: date.toISOString().slice(0, 10), dayNumber: date.getDate(), weekday: weekdays[date.getDay()], today: index === 0, items: samples[index].map((item, itemIndex) => ({ id: `demo-${index}-${itemIndex}`, interactive: item.kind === "todo", ...item })) };
        });
        return {
            generatedAt: today.toLocaleTimeString("zh-CN", { hour12: false }),
            dayNumber: today.getDate(), monthYear: `${today.getFullYear()}.${String(today.getMonth() + 1).padStart(2, "0")}`,
            monthName: today.toLocaleString("en-US", { month: "long" }).toUpperCase(), weekday: `星期${weekdays[today.getDay()]}`,
            weekNumber: 30, theme: "深夜专注计划", keyword: "清醒 / 专注 / 完成", quotes,
            summary: { total: 12, completed: 8, remaining: 4, completionRate: 68, focusLabel: "6小时40分钟", sleepLabel: "7小时20分钟" },
            todos: [{ id: "demo-a", title: "完成日报页面联调", dueLabel: "今天 18:00", overdue: false }, { id: "demo-b", title: "整理下一阶段需求", dueLabel: "明天 10:00", overdue: false }, { id: "demo-c", title: "复盘本周开发进度", dueLabel: "7月27日 21:00", overdue: false }],
            days,
            weather: {
                available: true, location: "杭州", headline: "多云转小雨", temperature: "24℃ 至 31℃", rain: "45%",
                details: "当前温度：29℃ · 湿度：78%，风速：8 km/h",
                visual: {
                    ready: true, conditionGroup: "rain", day: true, cloudCover: 0.72,
                    precipitation: 0.25, precipitationProbability: 45, windSpeed: 8, windDirection: 225
                }
            },
            trend: days.map((day, index) => ({ label: day.weekday, plannedMinutes: [180, 220, 90, 160, 60, 120, 100][index], completed: [3, 2, 1, 2, 0, 1, 1][index] }))
        };
    }

    $("refresh-button").addEventListener("click", loadDashboard);
    loadDashboard();
    setInterval(() => {
        if (!document.hidden && !state.demo) loadDashboard();
    }, 30000);
    document.addEventListener("visibilitychange", () => {
        weatherBackground.visibilityChanged(!document.hidden);
        if (!document.hidden && !state.demo) {
            loadDashboard();
            scheduleWeatherPoll();
        }
    });
})();
