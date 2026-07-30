package com.example.ilink.capabilities.planning;

import com.example.ilink.platform.persistence.MySqlStore;

import com.example.ilink.capabilities.planning.TodoItem;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 待办事项存储，MySQL 不可用时保留内存降级能力。 */
public final class TodoStore {

    private final Map<String, TodoItem> todos = new ConcurrentHashMap<>();
    private final Set<String> loadedUsers = ConcurrentHashMap.newKeySet();
    private final MySqlStore database;
    private final boolean persistent;

    public TodoStore() {
        this(true);
    }

    public TodoStore(boolean persistent) {
        this.persistent = persistent;
        this.database = persistent ? MySqlStore.getInstance() : null;
    }

    public void save(TodoItem todo) {
        loadedUsers.add(todo.userId());
        todos.put(todo.id(), todo);
        if (persistent) database.saveTodo(todo);
    }

    public List<TodoItem> list(String userId) {
        ensureLoaded(userId);
        return todos.values().stream()
                .filter(todo -> todo.userId().equals(userId))
                .sorted(Comparator.comparing(TodoItem::dueAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    public TodoItem latestActive(String userId) {
        return list(userId).stream()
                .filter(todo -> "pending".equals(todo.status()))
                .max(Comparator.comparing(TodoItem::createdAt))
                .orElse(null);
    }

    private void ensureLoaded(String userId) {
        if (!loadedUsers.add(userId)) return;
        if (persistent) {
            for (TodoItem todo : database.loadTodos(userId)) todos.put(todo.id(), todo);
        }
    }
}
