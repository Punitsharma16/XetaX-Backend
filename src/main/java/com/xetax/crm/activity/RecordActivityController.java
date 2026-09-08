package com.xetax.crm.activity;

import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.data_manager.service.RecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Read side of the record timeline. Visibility rides on
 * {@link RecordService#getById} — the same ownership + view.own walls as the
 * record itself, so whoever can open the record can read its history.
 */
@RestController
@RequestMapping("api/record")
@RequiredArgsConstructor
public class RecordActivityController {

    private final RecordActivityService activityService;
    private final RecordService recordService;
    private final FormRepo formRepo;

    @GetMapping("/{id}/activity")
    public ApiResponse<List<RecordActivity>> activity(@PathVariable String id) {
        var record = recordService.getById(id); // throws if not ours / not visible
        String owner = formRepo.findById(record.getFormId())
                .map(f -> f.getOwnerUserId()).orElse("");
        return ResponseUtil.success("Activity", activityService.listFor(id, owner));
    }
}
