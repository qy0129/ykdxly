package com.example.ilink.capabilities.planning;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 协调同一时刻的待办冲突，所有冲突解决后才统一写入。 */
public final class TodoConflictResolver {

    private static final String DRAFT = "draft";
    private static final String EXISTING = "existing";
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("M月d日 HH:mm");
    private final TodoService todoService;

    public TodoConflictResolver(TodoService todoService) {
        this.todoService = todoService;
    }

    public Resolution begin(String userId, TodoPlan plan) {
        List<TodoConflictState.DraftState> drafts = plan.drafts().stream()
                .map(this::toState)
                .toList();
        TodoConflictState state = new TodoConflictState(drafts, plan.reminderMinutes(),
                plan.supervisionEnabled(), plan.supervisionCadence(), "", List.of(), List.of(), null,
                Map.of(), 0L);
        return advance(userId, state);
    }

    public Resolution reply(String userId, TodoConflictState state, String text) {
        String value = text == null ? "" : text.trim();
        if ("取消".equals(value)) return Resolution.cancelled("已取消这批待办，未做任何修改。");
        if (TodoConflictState.AWAITING_KEEP.equals(state.stage())) {
            int selected = selectedChoice(value, state.choices());
            if (selected < 0) {
                return Resolution.pending(state, "请回复“保留1”或对应的序号，确认哪个事项保留原时间。");
            }
            TodoConflictState.ItemRef kept = state.choices().get(selected);
            List<TodoConflictState.ItemRef> queue = new ArrayList<>(state.choices());
            queue.remove(selected);
            TodoConflictState next = state.awaitingNewTime(queue);
            return Resolution.pending(next, "已保留“" + kept.title() + "”的原时间。请告诉我“"
                    + next.current().title() + "”的新时间。");
        }
        if (!TodoConflictState.AWAITING_NEW_TIME.equals(state.stage()) || state.current() == null) {
            return Resolution.cancelled("这次待办改期状态已失效，请重新发起创建。");
        }

        LocalDateTime parsed = DateTimeParser.parse(value);
        parsed = DateTimeParser.applyPeriodDefault(value, parsed);
        if (parsed == null) {
            return Resolution.pending(state, "没有识别出具体时间，请重新告诉我“"
                    + state.current().title() + "”的新时间。");
        }
        List<String> collisions = collisionTitles(userId, state, state.current(), parsed);
        if (!collisions.isEmpty()) {
            return Resolution.pending(state, "这个时间与“" + String.join("、", collisions)
                    + "”冲突。请为“" + state.current().title() + "”换一个时间。");
        }

        TodoConflictState updated = updateDueAt(state, state.current(), parsed);
        if (!updated.remaining().isEmpty()) {
            TodoConflictState next = updated.awaitingNewTime(updated.remaining());
            return Resolution.pending(next, "已改到 " + parsed.format(DISPLAY_TIME) + "。请继续告诉我“"
                    + next.current().title() + "”的新时间。");
        }
        return advance(userId, updated);
    }

    public boolean acceptsReply(TodoConflictState state, String text) {
        if (state == null) return false;
        String value = text == null ? "" : text.trim();
        if ("取消".equals(value)) return true;
        if (TodoConflictState.AWAITING_KEEP.equals(state.stage())) {
            return value.matches("(?:保留|选|选择)?\\s*\\d+")
                    || value.matches("(?:保留|选|选择)?\\s*第?[一二三四五六七八九十两]+(?:个|项)?")
                    || value.matches("^(?:保留|选|选择).+");
        }
        return TodoConflictState.AWAITING_NEW_TIME.equals(state.stage())
                && (DateTimeParser.parse(value) != null || !looksLikeNewRequest(value));
    }

    private Resolution advance(String userId, TodoConflictState state) {
        Conflict conflict = firstConflict(userId, state);
        if (conflict != null) {
            TodoConflictState pending = state.awaitingKeep(conflict.items());
            StringBuilder prompt = new StringBuilder("检测到 ")
                    .append(conflict.dueAt().format(DISPLAY_TIME)).append(" 有时间冲突：");
            for (int index = 0; index < conflict.items().size(); index++) {
                TodoConflictState.ItemRef item = conflict.items().get(index);
                prompt.append('\n').append(index + 1).append(". ").append(item.title());
                if (EXISTING.equals(item.kind())) prompt.append("（已有待办）");
            }
            prompt.append("\n请回复“保留1”或“保留2”，确认哪个事项保留这个时间。");
            return Resolution.pending(pending, prompt.toString());
        }

        List<TodoItem> rescheduled = new ArrayList<>();
        for (Map.Entry<String, String> update : state.existingDueAtUpdates().entrySet()) {
            TodoItem item = todoService.reschedule(userId, update.getKey(), parse(update.getValue()));
            if (item != null) rescheduled.add(item);
        }
        List<TodoItem> created = todoService.createBatch(userId, toDrafts(state.drafts()), state.reminderMinutes());
        return Resolution.completed(state, created, rescheduled);
    }

    private Conflict firstConflict(String userId, TodoConflictState state) {
        Map<LocalDateTime, List<ScheduledItem>> groups = new LinkedHashMap<>();
        for (int index = 0; index < state.drafts().size(); index++) {
            TodoConflictState.DraftState draft = state.drafts().get(index);
            LocalDateTime dueAt = parse(draft.dueAt());
            if (dueAt != null) add(groups, dueAt,
                    new ScheduledItem(new TodoConflictState.ItemRef(DRAFT, Integer.toString(index), draft.title()), true));
        }
        for (TodoItem todo : todoService.activeItems(userId)) {
            String staged = state.existingDueAtUpdates().get(todo.id());
            LocalDateTime dueAt = staged == null ? todo.dueAt() : parse(staged);
            if (dueAt != null) add(groups, dueAt,
                    new ScheduledItem(new TodoConflictState.ItemRef(EXISTING, todo.id(), todo.title()), staged != null));
        }
        return groups.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .filter(entry -> entry.getValue().stream().anyMatch(ScheduledItem::relevant))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new Conflict(entry.getKey(), entry.getValue().stream().map(ScheduledItem::ref).toList()))
                .findFirst().orElse(null);
    }

    private List<String> collisionTitles(String userId, TodoConflictState state,
                                         TodoConflictState.ItemRef current, LocalDateTime newDueAt) {
        List<String> titles = new ArrayList<>();
        for (int index = 0; index < state.drafts().size(); index++) {
            TodoConflictState.DraftState draft = state.drafts().get(index);
            if (DRAFT.equals(current.kind()) && current.reference().equals(Integer.toString(index))) continue;
            if (newDueAt.equals(parse(draft.dueAt()))) titles.add(draft.title());
        }
        for (TodoItem todo : todoService.activeItems(userId)) {
            if (EXISTING.equals(current.kind()) && current.reference().equals(todo.id())) continue;
            String staged = state.existingDueAtUpdates().get(todo.id());
            LocalDateTime dueAt = staged == null ? todo.dueAt() : parse(staged);
            if (newDueAt.equals(dueAt)) titles.add(todo.title());
        }
        return titles;
    }

    private TodoConflictState updateDueAt(TodoConflictState state, TodoConflictState.ItemRef item,
                                          LocalDateTime dueAt) {
        if (DRAFT.equals(item.kind())) {
            int index = Integer.parseInt(item.reference());
            List<TodoConflictState.DraftState> drafts = new ArrayList<>(state.drafts());
            TodoConflictState.DraftState old = drafts.get(index);
            drafts.set(index, new TodoConflictState.DraftState(
                    old.clientId(), old.sourceText(), old.title(), dueAt.toString()));
            return state.withSchedule(drafts, state.existingDueAtUpdates());
        }
        Map<String, String> updates = new LinkedHashMap<>(state.existingDueAtUpdates());
        updates.put(item.reference(), dueAt.toString());
        return state.withSchedule(state.drafts(), updates);
    }

    private TodoConflictState.DraftState toState(TodoDraft draft) {
        return new TodoConflictState.DraftState(draft.clientId(), draft.sourceText(), draft.title(),
                draft.dueAt() == null ? "" : draft.dueAt().toString());
    }

    private List<TodoDraft> toDrafts(List<TodoConflictState.DraftState> states) {
        return states.stream()
                .map(draft -> new TodoDraft(draft.clientId(), draft.sourceText(), draft.title(), parse(draft.dueAt())))
                .toList();
    }

    private int selectedChoice(String value, List<TodoConflictState.ItemRef> choices) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:保留|选|选择)?\\s*(\\d+)").matcher(value);
        if (matcher.matches()) return validIndex(Integer.parseInt(matcher.group(1)) - 1, choices.size());
        matcher = java.util.regex.Pattern.compile(
                "(?:保留|选|选择)?\\s*第?([一二三四五六七八九十两]+)(?:个|项)?").matcher(value);
        if (matcher.matches()) {
            return validIndex(DateTimeParser.parseChineseNumber(matcher.group(1)) - 1, choices.size());
        }
        for (int index = 0; index < choices.size(); index++) {
            if (value.contains(choices.get(index).title())) return index;
        }
        return -1;
    }

    private int validIndex(int index, int size) {
        return index >= 0 && index < size ? index : -1;
    }

    private boolean looksLikeNewRequest(String text) {
        return text.matches(".*(天气|快递|物流|待办|新闻|路线|导航|日历|提醒|查一下|查询|搜索|帮我规划|点外卖).*" );
    }

    private LocalDateTime parse(String value) {
        return value == null || value.isBlank() ? null : LocalDateTime.parse(value);
    }

    private void add(Map<LocalDateTime, List<ScheduledItem>> groups, LocalDateTime dueAt, ScheduledItem item) {
        groups.computeIfAbsent(dueAt, ignored -> new ArrayList<>()).add(item);
    }

    private record ScheduledItem(TodoConflictState.ItemRef ref, boolean relevant) { }

    private record Conflict(LocalDateTime dueAt, List<TodoConflictState.ItemRef> items) { }

    public record Resolution(TodoConflictState state, List<TodoItem> created, List<TodoItem> rescheduled,
                             String message, boolean completed, boolean cancelled) {
        static Resolution pending(TodoConflictState state, String message) {
            return new Resolution(state, List.of(), List.of(), message, false, false);
        }

        static Resolution completed(TodoConflictState state, List<TodoItem> created, List<TodoItem> rescheduled) {
            return new Resolution(state, List.copyOf(created), List.copyOf(rescheduled), "", true, false);
        }

        static Resolution cancelled(String message) {
            return new Resolution(null, List.of(), List.of(), message, false, true);
        }
    }
}
