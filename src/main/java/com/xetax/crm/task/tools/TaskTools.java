package com.xetax.crm.task.tools;

import com.xetax.crm.task.TaskItem;
import com.xetax.crm.task.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring AI tools for the to-do list.
 *
 * <p>Tasks are the one thing the panel has a whole nav item for that the
 * assistant could not touch: asked to "create a task to call 9034908545
 * tomorrow 10 am" it answered "I don't have a way to create a task directly
 * from this interface" and offered to build an automation instead.
 *
 * <p>Same contract as every other tool set here: the acting user comes from
 * the SecurityContext, the user never sees or supplies an id, and anything
 * destructive happens only when asked for.
 *
 * <p>Times are UTC on the wire. {@code TaskItem.dueAt} is a LocalDateTime
 * holding UTC wall-clock — the panel sends {@code ...Z} and Jackson drops the
 * offset — and the reminder scheduler compares against the server clock. A
 * task written in local time would fire hours late, which is a bug this
 * product already had once and fixed on the panel side.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskTools {

    private final TaskService taskService;

    @Tool(description = """
            READ-ONLY. List the signed-in user's own tasks (to-dos and
            reminders), soonest due first. Argument status: OPEN for pending
            tasks (the default) or DONE for finished ones. Use this for any
            question about what the user has to do, what is pending, what is
            due today or what they have finished.
            """)
    public Map<String, Object> getMyTasks(
            @ToolParam(description = "OPEN or DONE; defaults to OPEN", required = false)
            String status) {
        try {
            List<TaskItem> tasks = taskService.myTasks(status == null ? "OPEN" : status);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("count", tasks.size());
            out.put("tasks", tasks.stream().map(TaskTools::taskMap).toList());
            return out;
        }
        catch (Exception e) {
            return Map.of("error", safeMessage(e));
        }
    }

    @Tool(description = """
            READ-ONLY. List the tasks attached to ONE record. Argument
            recordId: the record's id, as returned by getRecords,
            searchRecords or getRecordDetails — never ask the user for it.
            """)
    public Map<String, Object> getRecordTasks(
            @ToolParam(description = "Record id from getRecords/searchRecords") String recordId) {
        try {
            if (recordId == null || recordId.isBlank()) {
                return Map.of("error", "A record id is required.");
            }
            List<TaskItem> tasks = taskService.forRecord(recordId.trim());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("count", tasks.size());
            out.put("tasks", tasks.stream().map(TaskTools::taskMap).toList());
            return out;
        }
        catch (Exception e) {
            return Map.of("error", safeMessage(e));
        }
    }

    @Tool(description = """
            WRITE. Create a task / reminder for the signed-in user. Use ONLY
            when the user asks for one.

            dueAtIso is an ISO-8601 UTC instant like 2026-09-27T04:30:00Z —
            convert the user's local time yourself, and never invent a date:
            if you do not know what day they mean, ask. A reminder by email or
            WhatsApp REQUIRES a due time.

            To attach the task to a record or a contact, look the entity up
            first (searchRecords / searchMyContacts) and pass its id plus
            linkedName so the task list reads well. Leave both out for a
            personal task.
            """)
    public Map<String, Object> createTask(
            @ToolParam(description = "What has to be done, e.g. 'Call Rahul about the site visit'")
            String title,
            @ToolParam(description = "ISO-8601 UTC due time; omit for a task with no deadline",
                    required = false) String dueAtIso,
            @ToolParam(description = "Extra notes (optional)", required = false) String notes,
            @ToolParam(description = "Record id to attach this task to (optional)",
                    required = false) String recordId,
            @ToolParam(description = "Contact id to attach this task to (optional)",
                    required = false) Long contactId,
            @ToolParam(description = "Name of the linked record/contact, for display (optional)",
                    required = false) String linkedName,
            @ToolParam(description = "Also remind by email at the due time", required = false)
            Boolean remindEmail,
            @ToolParam(description = "Also remind on WhatsApp at the due time", required = false)
            Boolean remindWhatsApp) {
        try {
            TaskItem task = new TaskItem();
            task.setTitle(title == null ? null : title.trim());
            task.setNotes(notes);
            task.setDueAt(parseDueAt(dueAtIso));
            task.setRecordId(blankToNull(recordId));
            task.setContactId(contactId);
            task.setLinkedName(blankToNull(linkedName));
            task.setRemindEmail(Boolean.TRUE.equals(remindEmail));
            task.setRemindWhatsApp(Boolean.TRUE.equals(remindWhatsApp));

            TaskItem created = taskService.create(task);
            Map<String, Object> out = new LinkedHashMap<>(taskMap(created));
            out.put("created", true);
            out.put("note", "The user gets a panel notification when it is due.");
            return out;
        }
        catch (Exception e) {
            return Map.of("created", false, "error", safeMessage(e));
        }
    }

    @Tool(description = """
            WRITE. Mark one of the user's tasks finished, or reopen it.
            Argument taskId: the id from getMyTasks/getRecordTasks. Argument
            done: true to finish (the default), false to reopen. Use ONLY on
            an explicit request.
            """)
    public Map<String, Object> completeTask(
            @ToolParam(description = "Task id from getMyTasks") Long taskId,
            @ToolParam(description = "true to finish, false to reopen; defaults to true",
                    required = false) Boolean done) {
        try {
            TaskItem task = taskService.setDone(taskId, !Boolean.FALSE.equals(done));
            Map<String, Object> out = new LinkedHashMap<>(taskMap(task));
            out.put("updated", true);
            return out;
        }
        catch (Exception e) {
            return Map.of("updated", false, "error", safeMessage(e));
        }
    }

    @Tool(description = """
            WRITE. Delete one of the user's tasks permanently. Argument
            taskId: the id from getMyTasks. Use ONLY when the user explicitly
            asks to delete it — to simply finish a task use completeTask.
            """)
    public Map<String, Object> deleteTask(
            @ToolParam(description = "Task id from getMyTasks") Long taskId) {
        try {
            taskService.delete(taskId);
            return Map.of("deleted", true, "taskId", taskId);
        }
        catch (Exception e) {
            return Map.of("deleted", false, "error", safeMessage(e));
        }
    }

    /**
     * ISO instant -> the UTC wall-clock the column stores.
     *
     * <p>The panel posts {@code 2026-09-22T12:30:00.000Z} and Jackson keeps
     * the digits while dropping the Z, so dueAt is UTC in the database. Doing
     * anything else here would make an assistant-created reminder fire at a
     * different time from an identical one made in the UI.
     */
    private static LocalDateTime parseDueAt(String dueAtIso) {
        if (dueAtIso == null || dueAtIso.isBlank()) {
            return null;
        }
        String value = dueAtIso.trim();
        try {
            return LocalDateTime.ofInstant(Instant.parse(value), ZoneOffset.UTC);
        }
        catch (Exception e) {
            // A model that dropped the offset still meant UTC, because that is
            // what the tool asked for. Better than refusing the whole task.
            try {
                return LocalDateTime.parse(value);
            }
            catch (Exception ignored) {
                throw new IllegalArgumentException(
                        "Could not read the due time '" + value
                                + "' — it must be ISO-8601 UTC, e.g. 2026-09-27T04:30:00Z");
            }
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Map<String, Object> taskMap(TaskItem task) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", task.getId());
        map.put("title", task.getTitle());
        map.put("status", task.getStatus());
        map.put("dueAt", task.getDueAt() == null ? "no deadline" : task.getDueAt() + "Z");
        map.put("notes", task.getNotes());
        map.put("linkedName", task.getLinkedName());
        map.put("recordId", task.getRecordId());
        map.put("contactId", task.getContactId());
        map.put("remindEmail", Boolean.TRUE.equals(task.getRemindEmail()));
        map.put("remindWhatsApp", Boolean.TRUE.equals(task.getRemindWhatsApp()));
        return map;
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? "The task action could not be completed." : message;
    }
}
