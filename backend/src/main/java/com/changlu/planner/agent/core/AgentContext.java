package com.changlu.planner.agent.core;

import com.changlu.planner.shared.database.Database;
import java.util.UUID;

public record AgentContext(UUID runId, Database.Context identity, String channel) {}
