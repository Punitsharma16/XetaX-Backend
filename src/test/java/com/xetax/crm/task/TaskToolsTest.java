package com.xetax.crm.task;

import com.xetax.crm.task.tools.TaskTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Asked to "create a task to call on the number 9034908545 for tomorrow 10
 * am", the assistant replied "I don't have a way to create a task directly
 * from this interface" and offered to build an automation instead — while the
 * panel had a Tasks section the whole time. It simply had no task tools.
 */
class TaskToolsTest {

    private TaskService taskService;
    private TaskTools tools;

    @BeforeEach
    void setUp() {
        taskService = mock(TaskService.class);
        tools = new TaskTools(taskService);
        when(taskService.create(any())).thenAnswer(call -> {
            TaskItem saved = call.getArgument(0);
            saved.setId(7L);
            saved.setStatus("OPEN");
            return saved;
        });
    }

    private TaskItem captureCreated() {
        ArgumentCaptor<TaskItem> captor = ArgumentCaptor.forClass(TaskItem.class);
        verify(taskService).create(captor.capture());
        return captor.getValue();
    }

    @Test
    void itCreatesTheTaskTheUserAskedFor() {
        Map<String, Object> result = tools.createTask("Call 9034908545", "2026-09-27T04:30:00Z",
                null, null, null, null, null, null);

        assertEquals(true, result.get("created"));
        assertEquals("Call 9034908545", captureCreated().getTitle());
    }

    @Test
    void aDueTimeIsStoredInUtcLikeThePanelStoresIt() {
        // 10 am IST is 04:30 UTC. The column is a LocalDateTime holding UTC —
        // the panel posts "...Z" and Jackson drops the offset — and the
        // reminder scheduler compares against the server clock. Writing local
        // time here would fire every assistant-made reminder 5.5 hours late.
        tools.createTask("Call Rahul", "2026-09-27T04:30:00Z",
                null, null, null, null, null, null);

        assertEquals(LocalDateTime.of(2026, 9, 27, 4, 30), captureCreated().getDueAt());
    }

    @Test
    void aTimeWithAnOffsetIsConvertedNotTruncated() {
        // A model that answers in +05:30 still means the same instant.
        tools.createTask("Call Rahul", "2026-09-27T10:00:00+05:30",
                null, null, null, null, null, null);

        assertEquals(LocalDateTime.of(2026, 9, 27, 4, 30), captureCreated().getDueAt());
    }

    @Test
    void aTaskWithNoDeadlineIsAllowed() {
        tools.createTask("Read the new pricing doc", null, null, null, null, null, null, null);

        assertNull(captureCreated().getDueAt());
    }

    @Test
    void aDueTimeThatIsNotATimeIsReportedNotStored() {
        Map<String, Object> result = tools.createTask("Call Rahul", "tomorrow 10am",
                null, null, null, null, null, null);

        assertEquals(false, result.get("created"));
        assertTrue(String.valueOf(result.get("error")).contains("ISO-8601"));
        verify(taskService, never()).create(any());
    }

    @Test
    void itCanHangTheTaskOffTheRecordTheUserWasLookingAt() {
        tools.createTask("Call about the site visit", "2026-09-27T04:30:00Z", "He asked for 2BHK",
                "rec-123", null, "Rahul Sharma", null, null);

        TaskItem created = captureCreated();
        assertEquals("rec-123", created.getRecordId());
        assertEquals("Rahul Sharma", created.getLinkedName());
        assertEquals("He asked for 2BHK", created.getNotes());
    }

    @Test
    void anAbsentReminderFlagIsOffRatherThanNull() {
        // The columns are NOT NULL; a null here would fail at the database.
        tools.createTask("Call Rahul", null, null, null, null, null, null, null);

        TaskItem created = captureCreated();
        assertFalse(created.getRemindEmail());
        assertFalse(created.getRemindWhatsApp());
    }

    @Test
    void aServiceRefusalComesBackAsAMessageNotAnException() {
        // doThrow, not when(...): when() would run the existing answer with a
        // null argument just to set the stub up.
        org.mockito.Mockito.doThrow(
                        new RuntimeException("A due date/time is required for reminders"))
                .when(taskService).create(any());

        Map<String, Object> result = tools.createTask("Call Rahul", null, null, null, null, null,
                true, null);

        assertEquals(false, result.get("created"));
        assertEquals("A due date/time is required for reminders", result.get("error"));
    }

    @Test
    void itReadsTheUsersOwnList() {
        TaskItem task = new TaskItem();
        task.setId(3L);
        task.setTitle("Call Rahul");
        task.setStatus("OPEN");
        task.setDueAt(LocalDateTime.of(2026, 9, 27, 4, 30));
        when(taskService.myTasks("OPEN")).thenReturn(List.of(task));

        Map<String, Object> result = tools.getMyTasks(null);

        assertEquals(1, result.get("count"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> listed = (List<Map<String, Object>>) result.get("tasks");
        assertEquals("Call Rahul", listed.get(0).get("title"));
        // Marked as UTC so the model can convert it back for the user.
        assertEquals("2026-09-27T04:30Z", listed.get(0).get("dueAt"));
    }

    @Test
    void finishingATaskIsTheDefaultAndReopeningIsExplicit() {
        when(taskService.setDone(any(Long.class), any(Boolean.class))).thenReturn(new TaskItem());

        tools.completeTask(3L, null);
        verify(taskService).setDone(3L, true);

        tools.completeTask(3L, false);
        verify(taskService).setDone(3L, false);
    }
}
