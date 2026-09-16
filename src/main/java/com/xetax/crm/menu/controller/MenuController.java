package com.xetax.crm.menu.controller;

import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.menu.service.MenuService;
import com.xetax.crm.team.service.RequiresPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * The owner's menu page: store settings, categories, items and offers. This is
 * catalogue only — nothing here creates a CRM record.
 */
@RestController
@RequestMapping("/api/menu")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;

    @GetMapping
    @RequiresPermission("forms.view")
    public ApiResponse<Map<String, Object>> overview() {
        return ResponseUtil.success("Menu", menuService.overview());
    }

    @PutMapping("/store")
    @RequiresPermission("forms.manage")
    public ApiResponse<Map<String, Object>> updateStore(@RequestBody MenuService.StoreUpdate update) {
        return ResponseUtil.success("Menu settings saved", menuService.updateStore(update));
    }

    @PostMapping("/store/regenerate-key")
    @RequiresPermission("forms.manage")
    public ApiResponse<Map<String, Object>> regenerateKey() {
        return ResponseUtil.success("New link created", menuService.regenerateKey());
    }

    @PostMapping("/categories")
    @RequiresPermission("forms.manage")
    public ApiResponse<Map<String, Object>> createCategory(@RequestBody MenuService.CategoryInput input) {
        return ResponseUtil.success("Category added", menuService.createCategory(input));
    }

    @PutMapping("/categories/{id}")
    @RequiresPermission("forms.manage")
    public ApiResponse<Map<String, Object>> updateCategory(@PathVariable Long id,
                                                           @RequestBody MenuService.CategoryInput input) {
        return ResponseUtil.success("Category saved", menuService.updateCategory(id, input));
    }

    @DeleteMapping("/categories/{id}")
    @RequiresPermission("forms.manage")
    public ApiResponse<Void> deleteCategory(@PathVariable Long id) {
        menuService.deleteCategory(id);
        return ResponseUtil.success("Category deleted");
    }

    @PostMapping("/items")
    @RequiresPermission("forms.manage")
    public ApiResponse<Map<String, Object>> createItem(@RequestBody MenuService.ItemInput input) {
        return ResponseUtil.success("Item added", menuService.createItem(input));
    }

    @PutMapping("/items/{id}")
    @RequiresPermission("forms.manage")
    public ApiResponse<Map<String, Object>> updateItem(@PathVariable Long id,
                                                       @RequestBody MenuService.ItemInput input) {
        return ResponseUtil.success("Item saved", menuService.updateItem(id, input));
    }

    @DeleteMapping("/items/{id}")
    @RequiresPermission("forms.manage")
    public ApiResponse<Void> deleteItem(@PathVariable Long id) {
        menuService.deleteItem(id);
        return ResponseUtil.success("Item deleted");
    }

    @PostMapping("/items/{id}/image")
    @RequiresPermission("forms.manage")
    public ApiResponse<Map<String, Object>> uploadImage(@PathVariable Long id,
                                                        @RequestParam("file") MultipartFile file) {
        return ResponseUtil.success("Photo saved", menuService.uploadImage(id, file));
    }

    @DeleteMapping("/items/{id}/image")
    @RequiresPermission("forms.manage")
    public ApiResponse<Map<String, Object>> removeImage(@PathVariable Long id) {
        return ResponseUtil.success("Photo removed", menuService.removeImage(id));
    }
}
