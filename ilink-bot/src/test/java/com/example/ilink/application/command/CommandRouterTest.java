package com.example.ilink.application.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandRouterTest {

    private final CommandRouter router = new CommandRouter();

    @Test
    void leavesBareNumbersForPendingWorkflows() {
        assertEquals(CommandType.NONE, router.route("1"));
        assertEquals(CommandType.NONE, router.route("2"));
        assertEquals(CommandType.NONE, router.route("3"));
        assertEquals(CommandType.NONE, router.route("4"));
    }

    @Test
    void removesMenuCommandsButKeepsExplicitCommands() {
        assertEquals(CommandType.NONE, router.route("菜单"));
        assertEquals(CommandType.NONE, router.route("/menu"));
        assertEquals(CommandType.NEW_SESSION, router.route("新聊天"));
        assertEquals(CommandType.SHOW_MEMORY, router.route("我的记忆"));
    }
}
