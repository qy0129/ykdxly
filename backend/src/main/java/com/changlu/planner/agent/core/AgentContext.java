package com.changlu.planner.agent.core;

import com.changlu.planner.shared.database.Database;
import com.google.gson.JsonObject;
import java.util.UUID;

public record AgentContext(UUID runId, Database.Context identity, String channel, JsonObject input) {}
