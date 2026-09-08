package com.xetax.crm.task;

import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Personal productivity — every signed-in member manages their own tasks. */
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    @GetMapping
    public ApiResponse<List<TaskItem>> mine(@RequestParam(defaultValue = "OPEN") String status) {
        return ResponseUtil.success("Tasks", taskService.myTasks(status));
    }

    @GetMapping("/contact/{contactId}")
    public ApiResponse<List<TaskItem>> forContact(@PathVariable Long contactId) {
        return ResponseUtil.success("Tasks", taskService.forContact(contactId));
    }

    @GetMapping("/record/{recordId}")
    public ApiResponse<List<TaskItem>> forRecord(@PathVariable String recordId) {
        return ResponseUtil.success("Tasks", taskService.forRecord(recordId));
    }

    @PostMapping
    public ApiResponse<TaskItem> create(@RequestBody TaskItem task) {
        return ResponseUtil.success("Task created", taskService.create(task));
    }

    @PostMapping("/{id}/done")
    public ApiResponse<TaskItem> done(@PathVariable Long id, @RequestParam(defaultValue = "true") boolean value) {
        return ResponseUtil.success("Task updated", taskService.setDone(id, value));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        taskService.delete(id);
        return ResponseUtil.success("Task deleted");
    }
}
