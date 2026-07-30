package com.example.ilink.capabilities.inbox.model;

import java.util.List;

/** Inbox 的结构化提取结果。 */
public record ExtractionResult(List<ExtractedTask> tasks, List<ExtractedTime> times,
                               List<String> people, List<String> locations,
                               MessageSummary.MessageType messageType,
                               MessageSummary.Priority overallPriority) {
    public ExtractionResult {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        times = times == null ? List.of() : List.copyOf(times);
        people = people == null ? List.of() : List.copyOf(people);
        locations = locations == null ? List.of() : List.copyOf(locations);
        messageType = messageType == null ? MessageSummary.MessageType.OTHER : messageType;
        overallPriority = overallPriority == null ? MessageSummary.Priority.LOW : overallPriority;
    }

    public static ExtractionResult empty(MessageSummary.MessageType type) {
        return new ExtractionResult(List.of(), List.of(), List.of(), List.of(), type,
                MessageSummary.Priority.LOW);
    }

    public boolean hasTasks() { return !tasks.isEmpty(); }
    public boolean hasTimes() { return !times.isEmpty(); }
    public boolean hasPeople() { return !people.isEmpty(); }
    public boolean hasLocations() { return !locations.isEmpty(); }
    public int taskCount() { return tasks.size(); }
    public int timeCount() { return times.size(); }
}
