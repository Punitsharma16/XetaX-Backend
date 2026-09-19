package com.xetax.crm.template.controller;

import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.booking.service.BookingService;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.menu.service.MenuService;
import com.xetax.crm.template.entity.PackInstall;
import com.xetax.crm.template.repository.PackInstallRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Which pack-specific pages this workspace should see.
 *
 * <p>A pack brings a page of its own with it: the Restaurant pack brings the
 * online menu, the Hair Salon pack brings the booking diary. Those pages mean
 * nothing to a workspace that never installed the pack — a law firm has no
 * menu — so the sidebar asks here instead of showing them to everybody.
 */
@RestController
@RequestMapping("/api/workspace")
@RequiredArgsConstructor
public class WorkspaceModulesController {

    private final PackInstallRepository installs;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/modules")
    public ApiResponse<Map<String, Boolean>> modules() {
        UUID owner = currentUserProvider.currentDataOwnerIdOrNull();
        if (owner == null) throw new BadRequestException("Not signed in");

        Set<String> installed = installs.findByOwnerUserIdOrderByInstalledAtDesc(owner.toString())
                .stream().map(PackInstall::getPackKey).collect(Collectors.toSet());

        Map<String, Boolean> out = new LinkedHashMap<>();
        out.put("menu", installed.contains(MenuService.PACK_KEY));
        out.put("booking", installed.contains(BookingService.PACK_KEY));
        return ResponseUtil.success("Modules", out);
    }
}
