package com.xetax.crm.menu.service;

import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.menu.entity.MenuCategory;
import com.xetax.crm.menu.entity.MenuItem;
import com.xetax.crm.menu.entity.MenuStore;
import com.xetax.crm.menu.repository.MenuCategoryRepository;
import com.xetax.crm.menu.repository.MenuItemRepository;
import com.xetax.crm.menu.repository.MenuStoreRepository;
import com.xetax.crm.template.entity.PackInstall;
import com.xetax.crm.template.repository.PackInstallRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * The owner's side of the public menu: which form orders land in, the
 * categories, the items and their offers, and the link and QR code to share.
 *
 * <p>Everything here is catalogue. None of it is ever written as a CRM record
 * — the only records this feature creates are the orders customers place
 * (see {@link MenuOrderService}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class MenuService {

    /** The vertical pack whose installs can take orders. */
    public static final String PACK_KEY = "restaurant";

    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

    private final MenuStoreRepository storeRepository;
    private final MenuCategoryRepository categoryRepository;
    private final MenuItemRepository itemRepository;
    private final PackInstallRepository packInstallRepository;
    private final FormRepo formRepo;
    private final CurrentUserProvider currentUserProvider;

    @Value("${app.upload-dir:./uploads}")
    private String uploadDir;

    @Value("${app.public-base-url:http://localhost:5000}")
    private String publicBaseUrl;

    @Value("${app.api-base-url:http://localhost:8085}")
    private String apiBaseUrl;

    public record StoreUpdate(Long formId, Boolean enabled, String title, String tagline,
                              Boolean dineIn, Boolean takeaway, Boolean delivery) {}

    public record CategoryInput(String name, String description, Integer sortOrder, Boolean active) {}

    public record ItemInput(Long categoryId, String name, String description, BigDecimal price,
                            BigDecimal offerPrice, String offerLabel, Boolean veg,
                            Boolean available, Integer sortOrder) {}

    /* ------------------------------------------------------------ overview */

    /** Everything the menu page needs in one call. The store is created on first visit. */
    public Map<String, Object> overview() {
        MenuStore store = myStore();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("store", storeView(store));
        out.put("orderForms", restaurantForms());
        out.put("categories", categoryRepository.findByStoreIdOrderBySortOrderAscIdAsc(store.getId())
                .stream().map(this::categoryView).toList());
        out.put("items", itemRepository.findByStoreIdOrderBySortOrderAscIdAsc(store.getId())
                .stream().map(this::itemView).toList());
        return out;
    }

    public Map<String, Object> updateStore(StoreUpdate update) {
        MenuStore store = myStore();
        if (update.formId() != null) {
            boolean allowed = restaurantForms().stream()
                    .anyMatch(form -> update.formId().equals(form.get("id")));
            if (!allowed) throw new BadRequestException("Orders can only go into a Restaurant form of yours");
            store.setFormId(update.formId());
        }
        if (update.title() != null) store.setTitle(trimTo(update.title(), 120));
        if (update.tagline() != null) store.setTagline(trimTo(update.tagline(), 255));
        if (update.dineIn() != null) store.setDineIn(update.dineIn());
        if (update.takeaway() != null) store.setTakeaway(update.takeaway());
        if (update.delivery() != null) store.setDelivery(update.delivery());
        if (!store.isDineIn() && !store.isTakeaway() && !store.isDelivery()) {
            throw new BadRequestException("Keep at least one way to order — dine-in, takeaway or delivery");
        }
        if (update.enabled() != null) {
            if (update.enabled() && store.getFormId() == null) {
                throw new BadRequestException("Choose the Restaurant form orders should go into first");
            }
            store.setEnabled(update.enabled());
        }
        return Map.of("store", storeView(storeRepository.save(store)));
    }

    /** New link and QR code; every printed QR and shared link stops working. */
    public Map<String, Object> regenerateKey() {
        MenuStore store = myStore();
        store.setPublicKey(newKey());
        return Map.of("store", storeView(storeRepository.save(store)));
    }

    /* ---------------------------------------------------------- categories */

    public Map<String, Object> createCategory(CategoryInput input) {
        MenuStore store = myStore();
        String name = required(input.name(), "Category name", 80);
        MenuCategory category = MenuCategory.builder()
                .ownerUserId(store.getOwnerUserId())
                .storeId(store.getId())
                .name(name)
                .description(trimTo(input.description(), 255))
                .sortOrder(input.sortOrder() != null ? input.sortOrder()
                        : (int) categoryRepository.countByStoreId(store.getId()))
                .active(input.active() == null || input.active())
                .build();
        return categoryView(categoryRepository.save(category));
    }

    public Map<String, Object> updateCategory(Long id, CategoryInput input) {
        MenuCategory category = myCategory(id);
        if (input.name() != null) category.setName(required(input.name(), "Category name", 80));
        if (input.description() != null) category.setDescription(trimTo(input.description(), 255));
        if (input.sortOrder() != null) category.setSortOrder(input.sortOrder());
        if (input.active() != null) category.setActive(input.active());
        return categoryView(categoryRepository.save(category));
    }

    /** Refuses while items still sit in it — deleting a category never silently deletes dishes. */
    public void deleteCategory(Long id) {
        MenuCategory category = myCategory(id);
        long items = itemRepository.countByCategoryId(category.getId());
        if (items > 0) {
            throw new BadRequestException("Move or delete the " + items + " item"
                    + (items == 1 ? "" : "s") + " in '" + category.getName() + "' first");
        }
        categoryRepository.delete(category);
    }

    /* --------------------------------------------------------------- items */

    public Map<String, Object> createItem(ItemInput input) {
        MenuStore store = myStore();
        MenuItem item = MenuItem.builder()
                .ownerUserId(store.getOwnerUserId())
                .storeId(store.getId())
                .available(true)
                .build();
        apply(item, input, store, true);
        if (input.sortOrder() == null) {
            item.setSortOrder((int) itemRepository.countByStoreIdAndCategoryId(store.getId(), item.getCategoryId()));
        }
        return itemView(itemRepository.save(item));
    }

    public Map<String, Object> updateItem(Long id, ItemInput input) {
        MenuItem item = myItem(id);
        apply(item, input, myStore(), false);
        return itemView(itemRepository.save(item));
    }

    public void deleteItem(Long id) {
        MenuItem item = myItem(id);
        deleteImageFile(item);
        itemRepository.delete(item);
    }

    /** Stores a dish photo and gives it an unguessable public link. */
    public Map<String, Object> uploadImage(Long id, MultipartFile file) {
        MenuItem item = myItem(id);
        if (file == null || file.isEmpty()) throw new BadRequestException("Choose a photo to upload");
        if (file.getSize() > MAX_IMAGE_BYTES) throw new BadRequestException("Photo is too large — 5 MB maximum");
        String mime = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        String extension = switch (mime) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> throw new BadRequestException("Use a JPG, PNG or WebP photo");
        };
        try {
            Path dir = Path.of(uploadDir, "menu", item.getOwnerUserId());
            Files.createDirectories(dir);
            String key = newKey();
            Path target = dir.resolve(key + extension);
            file.transferTo(target.toAbsolutePath());
            deleteImageFile(item);
            item.setImageKey(key);
            item.setImagePath(target.toAbsolutePath().toString());
            item.setImageMime(mime.equals("image/jpg") ? "image/jpeg" : mime);
        } catch (java.io.IOException e) {
            throw new BadRequestException("The photo could not be saved. Please try again.");
        }
        return itemView(itemRepository.save(item));
    }

    public Map<String, Object> removeImage(Long id) {
        MenuItem item = myItem(id);
        deleteImageFile(item);
        item.setImageKey(null);
        item.setImagePath(null);
        item.setImageMime(null);
        return itemView(itemRepository.save(item));
    }

    /* ------------------------------------------------------------- helpers */

    private void apply(MenuItem item, ItemInput input, MenuStore store, boolean creating) {
        if (creating || input.categoryId() != null) {
            if (input.categoryId() == null) throw new BadRequestException("Choose a category");
            MenuCategory category = myCategory(input.categoryId());
            if (!category.getStoreId().equals(store.getId())) {
                throw new BadRequestException("That category is not on this menu");
            }
            item.setCategoryId(category.getId());
        }
        if (creating || input.name() != null) item.setName(required(input.name(), "Item name", 120));
        if (input.description() != null) item.setDescription(trimTo(input.description(), 500));
        if (creating || input.price() != null) {
            if (input.price() == null || input.price().signum() <= 0) {
                throw new BadRequestException("Give the item a price above zero");
            }
            item.setPrice(money(input.price()));
        }
        // An offer is edited as a pair: a blank offer price clears the offer.
        if (creating || input.offerPrice() != null || input.offerLabel() != null) {
            BigDecimal offer = input.offerPrice();
            if (offer != null && offer.signum() <= 0) offer = null;
            if (offer != null && offer.compareTo(item.getPrice()) >= 0) {
                throw new BadRequestException("The offer price must be lower than the regular price");
            }
            item.setOfferPrice(offer == null ? null : money(offer));
            item.setOfferLabel(trimTo(input.offerLabel(), 40));
        }
        if (input.veg() != null || creating) item.setVeg(input.veg());
        if (input.available() != null) item.setAvailable(input.available());
        if (input.sortOrder() != null) item.setSortOrder(input.sortOrder());
    }

    /** The workspace's store, created the first time the menu page is opened. */
    MenuStore myStore() {
        String owner = owner();
        return storeRepository.findFirstByOwnerUserIdOrderByIdAsc(owner).orElseGet(() -> {
            List<Map<String, Object>> forms = restaurantForms();
            Long formId = forms.isEmpty() ? null : (Long) forms.get(0).get("id");
            String title = forms.isEmpty() ? "Our menu" : String.valueOf(forms.get(0).get("name"));
            return storeRepository.save(MenuStore.builder()
                    .ownerUserId(owner)
                    .formId(formId)
                    .publicKey(newKey())
                    .enabled(false)
                    .title(title)
                    .currency("INR")
                    .dineIn(true)
                    .takeaway(true)
                    .delivery(false)
                    .build());
        });
    }

    /** Forms this workspace got from the Restaurant pack and still owns, newest first. */
    List<Map<String, Object>> restaurantForms() {
        String owner = owner();
        Set<Long> seen = new LinkedHashSet<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (PackInstall install : packInstallRepository
                .findByOwnerUserIdAndPackKeyOrderByInstalledAtDesc(owner, PACK_KEY)) {
            if (install.getFormId() == null || !seen.add(install.getFormId())) continue;
            formRepo.findById(install.getFormId())
                    .filter(form -> owner.equals(form.getOwnerUserId()))
                    .ifPresent(form -> out.add(formView(form)));
        }
        return out;
    }

    private Map<String, Object> formView(FormEntity form) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", form.getId());
        row.put("name", form.getName());
        row.put("slug", form.getSlug());
        return row;
    }

    private MenuCategory myCategory(Long id) {
        return categoryRepository.findByIdAndOwnerUserId(id, owner())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
    }

    private MenuItem myItem(Long id) {
        return itemRepository.findByIdAndOwnerUserId(id, owner())
                .orElseThrow(() -> new ResourceNotFoundException("Item not found"));
    }

    private String owner() {
        UUID id = currentUserProvider.currentDataOwnerIdOrNull();
        if (id == null) throw new BadRequestException("Not signed in");
        return id.toString();
    }

    Map<String, Object> storeView(MenuStore store) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", store.getId());
        view.put("formId", store.getFormId());
        view.put("enabled", store.isEnabled());
        view.put("title", store.getTitle());
        view.put("tagline", store.getTagline());
        view.put("currency", store.getCurrency());
        view.put("dineIn", store.isDineIn());
        view.put("takeaway", store.isTakeaway());
        view.put("delivery", store.isDelivery());
        view.put("publicKey", store.getPublicKey());
        view.put("menuUrl", menuUrl(store.getPublicKey()));
        view.put("qrUrl", qrUrl(store.getPublicKey()));
        return view;
    }

    private Map<String, Object> categoryView(MenuCategory category) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", category.getId());
        view.put("name", category.getName());
        view.put("description", category.getDescription());
        view.put("sortOrder", category.getSortOrder());
        view.put("active", category.isActive());
        return view;
    }

    private Map<String, Object> itemView(MenuItem item) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", item.getId());
        view.put("categoryId", item.getCategoryId());
        view.put("name", item.getName());
        view.put("description", item.getDescription());
        view.put("price", item.getPrice());
        view.put("offerPrice", item.getOfferPrice());
        view.put("offerLabel", item.getOfferLabel());
        view.put("effectivePrice", MenuOrderService.effectivePrice(item));
        view.put("veg", item.getVeg());
        view.put("available", item.isAvailable());
        view.put("sortOrder", item.getSortOrder());
        view.put("imageUrl", imageUrl(item));
        return view;
    }

    /** The page customers open — served by the panel, which is where the public route lives. */
    String menuUrl(String key) {
        return trimSlash(publicBaseUrl) + "/menu/" + key;
    }

    /** The QR image — served by the API, public, so the panel can link straight to it. */
    String qrUrl(String key) {
        return trimSlash(apiBaseUrl) + "/api/public/menu/" + key + "/qr.png";
    }

    String imageUrl(MenuItem item) {
        return item.getImageKey() == null ? null
                : trimSlash(apiBaseUrl) + "/api/public/menu/images/" + item.getImageKey();
    }

    private void deleteImageFile(MenuItem item) {
        if (item.getImagePath() == null) return;
        try {
            Files.deleteIfExists(Path.of(item.getImagePath()));
        } catch (Exception e) {
            log.warn("Could not delete menu photo {}: {}", item.getImagePath(), e.getMessage());
        }
    }

    static String newKey() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 32);
    }

    private static String trimSlash(String url) {
        return url == null ? "" : url.trim().replaceAll("/+$", "");
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static String required(String value, String what, int max) {
        String trimmed = trimTo(value, max);
        if (trimmed == null) throw new BadRequestException(what + " is required");
        return trimmed;
    }

    private static String trimTo(String value, int max) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}
