package com.xetax.crm.menu.service;

import com.xetax.crm.automation.engine.AutomationEngine;
import com.xetax.crm.automation.enums.AutomationTrigger;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.data_manager.documents.RecordDocument;
import com.xetax.crm.data_manager.dto.RecordRequest;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.entity.FormField;
import com.xetax.crm.data_manager.entity.FormStage;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.data_manager.repository.RecordRepo;
import com.xetax.crm.data_manager.service.FormMetaCache;
import com.xetax.crm.data_manager.validator.DynamicValidationService;
import com.xetax.crm.menu.entity.MenuCategory;
import com.xetax.crm.menu.entity.MenuItem;
import com.xetax.crm.menu.entity.MenuStore;
import com.xetax.crm.menu.repository.MenuCategoryRepository;
import com.xetax.crm.menu.repository.MenuItemRepository;
import com.xetax.crm.menu.repository.MenuStoreRepository;
import com.xetax.crm.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The customer's side: reading the menu and placing an order. Runs without a
 * signed-in user — the store's public key is the only credential, and every
 * lookup is scoped to that store.
 *
 * <p>An order is the one thing this feature writes into the CRM: it becomes a
 * record in the Restaurant form, through the same validation, default stage,
 * RECORD_CREATED automations and bell notification a hosted form uses. Prices
 * are always recomputed here from the menu — whatever the page sends is only
 * an item id and a quantity.
 */
@Service
@RequiredArgsConstructor
public class MenuOrderService {

    static final int MAX_LINES = 50;
    static final int MAX_QUANTITY = 50;

    /** The Restaurant pack's field keys; only the ones the form still has are written. */
    static final String F_NAME = "customer_name";
    static final String F_PHONE = "phone";
    static final String F_ITEMS = "order_items";
    static final String F_AMOUNT = "amount";
    static final String F_TYPE = "order_type";
    static final String F_ADDRESS = "address";

    private final MenuStoreRepository storeRepository;
    private final MenuCategoryRepository categoryRepository;
    private final MenuItemRepository itemRepository;
    private final MenuService menuService;
    private final FormRepo formRepo;
    private final FormMetaCache formMetaCache;
    private final DynamicValidationService validationService;
    private final RecordRepo recordRepo;
    private final AutomationEngine automationEngine;
    private final NotificationService notificationService;

    public record OrderLine(Long itemId, Integer quantity) {}

    public record OrderRequest(List<OrderLine> items, String name, String phone, String orderType,
                               String table, String address, String note) {}

    public record StoredImage(byte[] bytes, String mimeType) {}

    /** What a customer is charged: the offer price when one is running, else the price. */
    public static BigDecimal effectivePrice(MenuItem item) {
        BigDecimal offer = item.getOfferPrice();
        if (offer != null && offer.signum() > 0 && item.getPrice() != null
                && offer.compareTo(item.getPrice()) < 0) {
            return offer;
        }
        return item.getPrice();
    }

    /* ---------------------------------------------------------- the menu */

    public Map<String, Object> storefront(String key) {
        MenuStore store = requireOpenStore(key);
        List<MenuCategory> categories = categoryRepository.findByStoreIdOrderBySortOrderAscIdAsc(store.getId())
                .stream().filter(MenuCategory::isActive).toList();
        Map<Long, List<MenuItem>> itemsByCategory = itemRepository.findByStoreIdOrderBySortOrderAscIdAsc(store.getId())
                .stream().collect(Collectors.groupingBy(MenuItem::getCategoryId, LinkedHashMap::new, Collectors.toList()));

        List<Map<String, Object>> sections = new ArrayList<>();
        for (MenuCategory category : categories) {
            List<MenuItem> items = itemsByCategory.getOrDefault(category.getId(), List.of());
            if (items.isEmpty()) continue; // an empty heading is noise to a customer
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("id", category.getId());
            section.put("name", category.getName());
            section.put("description", category.getDescription());
            section.put("items", items.stream().map(this::publicItem).toList());
            sections.add(section);
        }

        List<String> orderTypes = new ArrayList<>();
        if (store.isDineIn()) orderTypes.add("DINE_IN");
        if (store.isTakeaway()) orderTypes.add("TAKEAWAY");
        if (store.isDelivery()) orderTypes.add("DELIVERY");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", store.getTitle());
        out.put("tagline", store.getTagline());
        out.put("currency", store.getCurrency() == null ? "INR" : store.getCurrency());
        out.put("orderTypes", orderTypes);
        out.put("categories", sections);
        return out;
    }

    private Map<String, Object> publicItem(MenuItem item) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", item.getId());
        view.put("name", item.getName());
        view.put("description", item.getDescription());
        view.put("price", item.getPrice());
        BigDecimal effective = effectivePrice(item);
        boolean onOffer = effective.compareTo(item.getPrice()) < 0;
        view.put("offerPrice", onOffer ? effective : null);
        view.put("offerLabel", item.getOfferLabel());
        view.put("veg", item.getVeg());
        view.put("available", item.isAvailable());
        view.put("imageUrl", menuService.imageUrl(item));
        return view;
    }

    /* --------------------------------------------------------- the order */

    public Map<String, Object> placeOrder(String key, OrderRequest request) {
        MenuStore store = requireOpenStore(key);
        if (request == null) throw new BadRequestException("Your order is empty");
        FormEntity form = formRepo.findById(store.getFormId())
                .filter(f -> store.getOwnerUserId().equals(f.getOwnerUserId()))
                .orElseThrow(() -> new BadRequestException("This menu is not taking orders right now"));

        String name = clean(request.name(), 80);
        if (name == null) throw new BadRequestException("Please enter your name");
        String phone = mobileNumber(request.phone());

        String orderType = orderTypeLabel(store, request.orderType());
        String address = clean(request.address(), 400);
        if ("Delivery".equals(orderType) && address == null) {
            throw new BadRequestException("Please enter the delivery address");
        }
        String table = "Dine-in".equals(orderType) ? clean(request.table(), 20) : null;
        String note = clean(request.note(), 300);

        // Merge repeated lines and bound the quantities before touching the menu.
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (OrderLine line : request.items() == null ? List.<OrderLine>of() : request.items()) {
            if (line == null || line.itemId() == null) continue;
            int quantity = line.quantity() == null ? 1 : line.quantity();
            if (quantity < 1) continue;
            quantities.merge(line.itemId(), quantity, Integer::sum);
        }
        if (quantities.isEmpty()) throw new BadRequestException("Your order is empty");
        if (quantities.size() > MAX_LINES) throw new BadRequestException("That is too many different items for one order");
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            if (entry.getValue() > MAX_QUANTITY) {
                throw new BadRequestException("At most " + MAX_QUANTITY + " of one item per order");
            }
        }

        Map<Long, MenuItem> items = itemRepository.findByStoreIdAndIdIn(store.getId(), quantities.keySet())
                .stream().collect(Collectors.toMap(MenuItem::getId, item -> item));
        Set<Long> activeCategories = categoryRepository.findByStoreIdOrderBySortOrderAscIdAsc(store.getId())
                .stream().filter(MenuCategory::isActive).map(MenuCategory::getId).collect(Collectors.toSet());

        String currency = store.getCurrency() == null ? "INR" : store.getCurrency();
        BigDecimal total = BigDecimal.ZERO;
        List<String> lines = new ArrayList<>();
        if (table != null) lines.add("Table " + table);
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            MenuItem item = items.get(entry.getKey());
            if (item == null || !activeCategories.contains(item.getCategoryId())) {
                throw new BadRequestException("One of the items is no longer on the menu — please refresh");
            }
            if (!item.isAvailable()) {
                throw new BadRequestException("'" + item.getName() + "' is sold out — please remove it");
            }
            BigDecimal unit = effectivePrice(item);
            BigDecimal lineTotal = unit.multiply(BigDecimal.valueOf(entry.getValue()));
            total = total.add(lineTotal);
            String offer = unit.compareTo(item.getPrice()) < 0 && item.getOfferLabel() != null
                    ? " (" + item.getOfferLabel() + ")" : "";
            lines.add(entry.getValue() + " × " + item.getName() + " — " + format(currency, lineTotal) + offer);
        }
        lines.add("Total: " + format(currency, total));
        if (note != null) lines.add("Note: " + note);

        // Only the keys this form still has — an owner may have edited the pack's form.
        List<FormField> fields = formMetaCache.getFields(form.getId());
        Set<String> keys = fields.stream().map(FormField::getFieldKey).collect(Collectors.toSet());
        Map<String, Object> data = new LinkedHashMap<>();
        putIf(keys, data, F_NAME, name);
        putIf(keys, data, F_PHONE, phone);
        putIf(keys, data, F_ITEMS, String.join("\n", lines));
        putIf(keys, data, F_AMOUNT, total);
        putIf(keys, data, F_TYPE, orderType);
        putIf(keys, data, F_ADDRESS, address);

        RecordRequest recordRequest = new RecordRequest();
        recordRequest.setData(data);
        Map<String, Object> validated = validationService.validate(recordRequest, fields);

        FormStage defaultStage = formMetaCache.getStages(form.getId()).stream()
                .filter(s -> Boolean.TRUE.equals(s.getIsDefault()))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("This menu is not taking orders right now"));

        RecordDocument saved = recordRepo.save(RecordDocument.builder()
                .formId(form.getId())
                .stageId(defaultStage.getId())
                .data(validated)
                .createdBy("public-menu")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());

        automationEngine.execute(AutomationTrigger.RECORD_CREATED, form, saved);

        notificationService.push(form.getOwnerUserId(), form.getOwnerUserId(), "RECORD_CREATED",
                "New order — " + format(currency, total),
                name + " · " + orderType + (table != null ? " · Table " + table : ""),
                "/app/records/" + form.getSlug() + "/" + saved.getId());

        String reference = saved.getId() == null ? ""
                : saved.getId().substring(Math.max(0, saved.getId().length() - 6)).toUpperCase();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("reference", reference);
        out.put("total", total);
        out.put("message", "Order placed! We will confirm it shortly.");
        return out;
    }

    /* ---------------------------------------------------------- photos */

    public Optional<StoredImage> image(String imageKey) {
        if (imageKey == null || imageKey.isBlank()) return Optional.empty();
        return itemRepository.findByImageKey(imageKey).flatMap(item -> {
            try {
                return Optional.of(new StoredImage(Files.readAllBytes(Path.of(item.getImagePath())),
                        item.getImageMime() == null ? "image/jpeg" : item.getImageMime()));
            } catch (Exception e) {
                return Optional.empty();
            }
        });
    }

    /** True when a key names any store, open or not — enough to print its QR code. */
    public boolean exists(String key) {
        return key != null && storeRepository.findByPublicKey(key).isPresent();
    }

    /* --------------------------------------------------------- helpers */

    private MenuStore requireOpenStore(String key) {
        MenuStore store = key == null ? null : storeRepository.findByPublicKey(key).orElse(null);
        if (store == null || !store.isEnabled() || store.getFormId() == null) {
            throw new ResourceNotFoundException("Menu not found");
        }
        return store;
    }

    /** Maps the page's order type to the Restaurant form's option, refusing a disabled one. */
    private static String orderTypeLabel(MenuStore store, String orderType) {
        String type = orderType == null ? "" : orderType.trim().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "DINE_IN" -> {
                if (!store.isDineIn()) throw new BadRequestException("Dine-in orders are not taken here");
                yield "Dine-in";
            }
            case "TAKEAWAY" -> {
                if (!store.isTakeaway()) throw new BadRequestException("Takeaway orders are not taken here");
                yield "Takeaway";
            }
            case "DELIVERY" -> {
                if (!store.isDelivery()) throw new BadRequestException("Delivery is not available here");
                yield "Delivery";
            }
            default -> throw new BadRequestException("Choose dine-in, takeaway or delivery");
        };
    }

    /**
     * The form's PHONE field accepts exactly ten digits, while customers type
     * "+91 98765 43210" or "098765…". Reduce what they typed to the ten-digit
     * number rather than rejecting a perfectly good order over formatting.
     */
    static String mobileNumber(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("\\D", "");
        if (digits.length() == 12 && digits.startsWith("91")) digits = digits.substring(2);
        else if (digits.length() == 11 && digits.startsWith("0")) digits = digits.substring(1);
        if (digits.isEmpty()) throw new BadRequestException("Please enter your phone number");
        if (digits.length() != 10) throw new BadRequestException("Enter a 10-digit mobile number");
        return digits;
    }

    private static void putIf(Set<String> keys, Map<String, Object> data, String key, Object value) {
        if (value != null && keys.contains(key)) data.put(key, value);
    }

    static String format(String currency, BigDecimal amount) {
        BigDecimal value = amount.stripTrailingZeros();
        String number = value.scale() <= 0 ? value.toPlainString() : amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        return ("INR".equalsIgnoreCase(currency) ? "₹" : currency + " ") + number;
    }

    private static String clean(String value, int max) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}
