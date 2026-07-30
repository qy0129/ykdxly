package com.example.ilink.application.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandRouterTest {

    private final CommandRouter router = new CommandRouter();

    @Test
    void bareNumbersAreReservedForActiveBusinessOrSessionSelections() {
        assertEquals(CommandType.NONE, router.route("1"));
        assertEquals(CommandType.NONE, router.route("2"));
        assertEquals(CommandType.NONE, router.route("3"));
        assertEquals(CommandType.NONE, router.route("4"));
    }

    @Test
    void explicitTextCommandsRemainAvailable() {
        assertEquals(CommandType.NEW_SESSION, router.route("新会话"));
        assertEquals(CommandType.SHOW_MEMORY, router.route("我的记忆"));
        assertEquals(CommandType.SHOW_TASK, router.route("我的任务"));
        assertEquals(CommandType.LIST_SESSIONS, router.route("切换会话"));
    }
}
