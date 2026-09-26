package com.xetax.crm.menu.tools;

import com.xetax.crm.menu.service.MenuService;
import com.xetax.crm.team.service.RequiresPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring AI tools for the public online menu.
 *
 * <p>This is the feature where typing beats clicking: a restaurant changing
 * ten prices, or marking a dish unavailable because the kitchen ran out, is a
 * sentence — "paneer tikka aaj band kar do" — and a long scroll through a
 * grid otherwise.
 *
 * <p>Orders placed on the public page arrive as records in the linked form,
 * so reading them is already covered by the record tools; there is nothing
 * order-shaped here on purpose.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MenuTools {

    private final MenuService menuService;

    @Tool(description = """
            READ-ONLY. The whole online menu in one call: the store's name,
            whether it is switched on, its public link, every category and
            every item with its price, offer price and whether it is
            currently available. Read this first — item and category ids come
            from here.
            """)
    @RequiresPermission("forms.view")
    public Map<String, Object> getMenuOverview() {
        try {
            return menuService.overview();
        }
        catch (Exception e) {
            return Map.of("error", safeMessage(e));
        }
    }

    @Tool(description = """
            WRITE. Add a category to the menu, e.g. "Starters" or "Beverages".
            Use ONLY on an explicit request.
            """)
    @RequiresPermission("forms.manage")
    public Map<String, Object> createMenuCategory(
            @ToolParam(description = "Category name") String name,
            @ToolParam(description = "Short description (optional)", required = false)
            String description) {
        try {
            Map<String, Object> out = new LinkedHashMap<>(menuService.createCategory(
                    new MenuService.CategoryInput(name, description, null, Boolean.TRUE)));
            out.put("created", true);
            return out;
        }
        catch (Exception e) {
            return Map.of("created", false, "error", safeMessage(e));
        }
    }

    @Tool(description = """
            WRITE. Add a dish to the menu. categoryId comes from
            getMenuOverview — resolve the category by name yourself rather
            than asking the user for an id. price is the normal price;
            offerPrice is the discounted one if there is an offer. Use ONLY on
            an explicit request.
            """)
    @RequiresPermission("forms.manage")
    public Map<String, Object> createMenuItem(
            @ToolParam(description = "Category id from getMenuOverview") Long categoryId,
            @ToolParam(description = "Dish name") String name,
            @ToolParam(description = "Price") Double price,
            @ToolParam(description = "Description (optional)", required = false) String description,
            @ToolParam(description = "Discounted price (optional)", required = false)
            Double offerPrice,
            @ToolParam(description = "true if vegetarian (optional)", required = false)
            Boolean veg) {
        try {
            Map<String, Object> out = new LinkedHashMap<>(menuService.createItem(
                    new MenuService.ItemInput(categoryId, name, description, decimal(price),
                            decimal(offerPrice), null, veg, Boolean.TRUE, null)));
            out.put("created", true);
            return out;
        }
        catch (Exception e) {
            return Map.of("created", false, "error", safeMessage(e));
        }
    }

    @Tool(description = """
            WRITE. Change a dish: its price, its offer price, or whether it is
            available right now. itemId comes from getMenuOverview. Pass only
            what changes — anything you leave out stays as it is. Setting
            available to false is how a sold-out dish is hidden from
            customers without deleting it. Use ONLY on an explicit request.
            """)
    @RequiresPermission("forms.manage")
    public Map<String, Object> updateMenuItem(
            @ToolParam(description = "Item id from getMenuOverview") Long itemId,
            @ToolParam(description = "New name", required = false) String name,
            @ToolParam(description = "New price", required = false) Double price,
            @ToolParam(description = "New discounted price", required = false) Double offerPrice,
            @ToolParam(description = "false hides it from the menu as sold out",
                    required = false) Boolean available) {
        try {
            Map<String, Object> out = new LinkedHashMap<>(menuService.updateItem(itemId,
                    new MenuService.ItemInput(null, name, null, decimal(price),
                            decimal(offerPrice), null, null, available, null)));
            out.put("updated", true);
            return out;
        }
        catch (Exception e) {
            return Map.of("updated", false, "error", safeMessage(e));
        }
    }

    private static BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? "The menu action could not be completed." : message;
    }
}
