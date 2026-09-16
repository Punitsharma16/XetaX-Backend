package com.xetax.crm.menu.service;

import com.xetax.crm.automation.engine.AutomationEngine;
import com.xetax.crm.automation.enums.AutomationTrigger;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.data_manager.documents.RecordDocument;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.entity.FormField;
import com.xetax.crm.data_manager.entity.FormStage;
import com.xetax.crm.data_manager.enums.FieldType;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.data_manager.repository.RecordRepo;
import com.xetax.crm.data_manager.service.FormMetaCache;
import com.xetax.crm.data_manager.validator.*;
import com.xetax.crm.menu.entity.MenuCategory;
import com.xetax.crm.menu.entity.MenuItem;
import com.xetax.crm.menu.entity.MenuStore;
import com.xetax.crm.menu.repository.MenuCategoryRepository;
import com.xetax.crm.menu.repository.MenuItemRepository;
import com.xetax.crm.menu.repository.MenuStoreRepository;
import com.xetax.crm.menu.service.MenuOrderService.OrderLine;
import com.xetax.crm.menu.service.MenuOrderService.OrderRequest;
import com.xetax.crm.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A public order is the only thing this feature writes into the CRM, and it
 * carries money. These pin that the total is the menu's, never the page's;
 * that only orderable items get through; and that the record written passes
 * the form's REAL validation — the Restaurant pack's phone field accepts ten
 * digits only, which a mocked validator would have hidden.
 */
class MenuOrderServiceTest {

    private MenuStore store;
    private FormEntity form;
    private List<FormField> fields;
    private final List<MenuItem> items = new ArrayList<>();
    private final List<MenuCategory> categories = new ArrayList<>();

    private MenuStoreRepository storeRepository;
    private RecordRepo recordRepo;
    private AutomationEngine automationEngine;
    private NotificationService notificationService;
    private MenuOrderService service;

    @BeforeEach
    void setUp() {
        store = MenuStore.builder().ownerUserId("owner-1").formId(10L).publicKey("KEY")
                .enabled(true).title("Spice Hut").currency("INR")
                .dineIn(true).takeaway(true).delivery(false).build();
        store.setId(1L);

        form = FormEntity.builder().name("Restaurant Orders").slug("restaurant-orders").ownerUserId("owner-1").build();
        form.setId(10L);

        // The Restaurant pack's own fields.
        fields = new ArrayList<>(List.of(
                field("customer_name", "Customer name", FieldType.TEXT, true),
                field("phone", "Phone", FieldType.PHONE, true),
                field("order_items", "Order items", FieldType.TEXTAREA, true),
                field("amount", "Amount", FieldType.NUMBER, false),
                field("order_type", "Order type", FieldType.SELECT, false),
                field("address", "Address", FieldType.TEXTAREA, false)));

        categories.add(category(1L, "Starters", true));
        categories.add(category(2L, "Staff only", false));

        items.add(item(11L, 1L, "Paneer Tikka", "240", null, null, true));
        items.add(item(12L, 1L, "Sweet Lassi", "80", "60", "25% OFF", true));
        items.add(item(13L, 1L, "Tomato Soup", "120", null, null, false));
        items.add(item(14L, 2L, "Secret Dish", "300", null, null, true));

        storeRepository = mock(MenuStoreRepository.class);
        when(storeRepository.findByPublicKey("KEY")).thenAnswer(inv -> Optional.of(store));

        MenuCategoryRepository categoryRepository = mock(MenuCategoryRepository.class);
        when(categoryRepository.findByStoreIdOrderBySortOrderAscIdAsc(1L)).thenReturn(categories);

        MenuItemRepository itemRepository = mock(MenuItemRepository.class);
        when(itemRepository.findByStoreIdOrderBySortOrderAscIdAsc(1L)).thenReturn(items);
        when(itemRepository.findByStoreIdAndIdIn(eq(1L), anyCollection())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(1);
            return items.stream().filter(i -> ids.contains(i.getId())).toList();
        });

        FormRepo formRepo = mock(FormRepo.class);
        when(formRepo.findById(10L)).thenAnswer(inv -> Optional.of(form));

        FormMetaCache formMetaCache = mock(FormMetaCache.class);
        when(formMetaCache.getFields(10L)).thenAnswer(inv -> fields);
        when(formMetaCache.getStages(10L)).thenReturn(List.of(
                FormStage.builder().id(100L).name("Placed").isDefault(true).build(),
                FormStage.builder().id(101L).name("Confirmed").isDefault(false).build()));

        recordRepo = mock(RecordRepo.class);
        when(recordRepo.save(any())).thenAnswer(inv -> {
            RecordDocument doc = inv.getArgument(0);
            doc.setId("665f0000000000000abc12ef");
            return doc;
        });

        automationEngine = mock(AutomationEngine.class);
        notificationService = mock(NotificationService.class);

        // The real validation chain, exactly as a hosted form uses it.
        DynamicValidationService validation = new DynamicValidationServiceImpl(
                new RequiredFieldValidator(), new UnknownFieldValidator(),
                new DefaultValueValidator(), new FieldTypeValidator());

        service = new MenuOrderService(storeRepository, categoryRepository, itemRepository,
                mock(MenuService.class), formRepo, formMetaCache, validation, recordRepo,
                automationEngine, notificationService);
    }

    private static FormField field(String key, String label, FieldType type, boolean required) {
        return FormField.builder().fieldKey(key).label(label).fieldType(type).required(required).build();
    }

    private static MenuCategory category(Long id, String name, boolean active) {
        MenuCategory category = MenuCategory.builder().ownerUserId("owner-1").storeId(1L).name(name).active(active).build();
        category.setId(id);
        return category;
    }

    private static MenuItem item(Long id, Long categoryId, String name, String price,
                                 String offer, String label, boolean available) {
        MenuItem item = MenuItem.builder().ownerUserId("owner-1").storeId(1L).categoryId(categoryId)
                .name(name).price(new BigDecimal(price))
                .offerPrice(offer == null ? null : new BigDecimal(offer))
                .offerLabel(label).available(available).build();
        item.setId(id);
        return item;
    }

    private static OrderRequest order(String type, OrderLine... lines) {
        return new OrderRequest(List.of(lines), "Asha", "9876543210", type, null, null, null);
    }

    private RecordDocument savedRecord() {
        ArgumentCaptor<RecordDocument> captor = ArgumentCaptor.forClass(RecordDocument.class);
        verify(recordRepo).save(captor.capture());
        return captor.getValue();
    }

    /* ------------------------------------------------------------ pricing */

    @Test
    void aRunningOfferIsWhatTheCustomerPays() {
        assertEquals(new BigDecimal("60"), MenuOrderService.effectivePrice(items.get(1)));
        assertEquals(new BigDecimal("240"), MenuOrderService.effectivePrice(items.get(0)));
    }

    @Test
    void anOfferThatIsNotCheaperIsIgnored() {
        MenuItem item = item(99L, 1L, "Odd", "100", "150", "Promo", true);
        assertEquals(new BigDecimal("100"), MenuOrderService.effectivePrice(item));
        item.setOfferPrice(BigDecimal.ZERO);
        assertEquals(new BigDecimal("100"), MenuOrderService.effectivePrice(item));
    }

    @Test
    void moneyReadsTheWayARestaurantWritesIt() {
        assertEquals("₹540", MenuOrderService.format("INR", new BigDecimal("540.00")));
        assertEquals("₹99.50", MenuOrderService.format("INR", new BigDecimal("99.5")));
        assertEquals("USD 12", MenuOrderService.format("USD", new BigDecimal("12")));
    }

    /* ------------------------------------------------------ a good order */

    @Test
    void anOrderBecomesARecordInTheRestaurantForm() {
        OrderRequest request = new OrderRequest(
                List.of(new OrderLine(11L, 2), new OrderLine(12L, 1)),
                "Asha", "9876543210", "DINE_IN", "5", null, "Less spicy");

        Map<String, Object> result = service.placeOrder("KEY", request);

        RecordDocument record = savedRecord();
        assertEquals(10L, record.getFormId());
        assertEquals(100L, record.getStageId(), "lands in the default stage");
        assertEquals("public-menu", record.getCreatedBy());

        Map<String, Object> data = record.getData();
        assertEquals("Asha", data.get("customer_name"));
        assertEquals("9876543210", data.get("phone"));
        assertEquals("Dine-in", data.get("order_type"));
        assertEquals(0, new BigDecimal("540").compareTo((BigDecimal) data.get("amount")),
                "2 × 240 + 1 × 60 on offer");

        String lines = (String) data.get("order_items");
        assertTrue(lines.startsWith("Table 5"), lines);
        assertTrue(lines.contains("2 × Paneer Tikka — ₹480"), lines);
        assertTrue(lines.contains("1 × Sweet Lassi — ₹60 (25% OFF)"), lines);
        assertTrue(lines.contains("Total: ₹540"), lines);
        assertTrue(lines.contains("Note: Less spicy"), lines);

        assertEquals(true, result.get("ok"));
        assertEquals("BC12EF", result.get("reference"), "the last six characters of the record id");
        verify(automationEngine).execute(eq(AutomationTrigger.RECORD_CREATED), eq(form), eq(record));
        verify(notificationService).push(eq("owner-1"), eq("owner-1"), eq("RECORD_CREATED"),
                contains("₹540"), contains("Table 5"), eq("/app/records/restaurant-orders/665f0000000000000abc12ef"));
    }

    @Test
    void theSameDishTwiceIsOneLine() {
        service.placeOrder("KEY", order("TAKEAWAY", new OrderLine(11L, 1), new OrderLine(11L, 2)));
        String lines = (String) savedRecord().getData().get("order_items");
        assertTrue(lines.contains("3 × Paneer Tikka — ₹720"), lines);
    }

    @Test
    void aTableNumberOnlyMattersForDineIn() {
        OrderRequest request = new OrderRequest(List.of(new OrderLine(11L, 1)),
                "Asha", "9876543210", "TAKEAWAY", "5", null, null);
        service.placeOrder("KEY", request);
        String lines = (String) savedRecord().getData().get("order_items");
        assertFalse(lines.contains("Table"), lines);
        assertEquals("Takeaway", savedRecord().getData().get("order_type"));
    }

    @Test
    void aZeroQuantityLineIsDropped() {
        service.placeOrder("KEY", order("TAKEAWAY", new OrderLine(11L, 1), new OrderLine(12L, 0)));
        String lines = (String) savedRecord().getData().get("order_items");
        assertFalse(lines.contains("Lassi"), lines);
    }

    /* --------------------------------------------------- the phone number */

    @Test
    void thePhoneIsReducedToTheTenDigitsTheFormAccepts() {
        assertEquals("9876543210", MenuOrderService.mobileNumber("+91 98765 43210"));
        assertEquals("9876543210", MenuOrderService.mobileNumber("098765-43210"));
        assertEquals("9876543210", MenuOrderService.mobileNumber("98765 43210"));
    }

    @Test
    void aFormattedPhoneStillPlacesTheOrder() {
        OrderRequest request = new OrderRequest(List.of(new OrderLine(11L, 1)),
                "Asha", "+91 98765 43210", "TAKEAWAY", null, null, null);
        assertDoesNotThrow(() -> service.placeOrder("KEY", request));
        assertEquals("9876543210", savedRecord().getData().get("phone"));
    }

    @Test
    void aNumberThatIsNotAMobileIsRefusedClearly() {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> MenuOrderService.mobileNumber("12345"));
        assertTrue(e.getMessage().contains("10-digit"));
        assertThrows(BadRequestException.class, () -> MenuOrderService.mobileNumber("  "));
    }

    /* ----------------------------------------------- what cannot get through */

    @Test
    void aSoldOutDishIsRefused() {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> service.placeOrder("KEY", order("TAKEAWAY", new OrderLine(13L, 1))));
        assertTrue(e.getMessage().contains("sold out"));
        verify(recordRepo, never()).save(any());
    }

    @Test
    void aDishInAHiddenCategoryIsRefused() {
        assertThrows(BadRequestException.class,
                () -> service.placeOrder("KEY", order("TAKEAWAY", new OrderLine(14L, 1))));
        verify(recordRepo, never()).save(any());
    }

    @Test
    void aDishFromAnotherMenuIsRefused() {
        // Not in this store, so the store-scoped lookup never returns it.
        assertThrows(BadRequestException.class,
                () -> service.placeOrder("KEY", order("TAKEAWAY", new OrderLine(999L, 1))));
    }

    @Test
    void anEmptyOrderIsRefused() {
        assertThrows(BadRequestException.class, () -> service.placeOrder("KEY", order("TAKEAWAY")));
        assertThrows(BadRequestException.class, () -> service.placeOrder("KEY", null));
    }

    @Test
    void anAbsurdQuantityIsRefused() {
        assertThrows(BadRequestException.class,
                () -> service.placeOrder("KEY", order("TAKEAWAY", new OrderLine(11L, 51))));
    }

    @Test
    void anOrderTypeTheRestaurantDoesNotOfferIsRefused() {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> service.placeOrder("KEY", order("DELIVERY", new OrderLine(11L, 1))));
        assertTrue(e.getMessage().contains("Delivery"));
    }

    @Test
    void deliveryNeedsAnAddress() {
        store.setDelivery(true);
        assertThrows(BadRequestException.class,
                () -> service.placeOrder("KEY", order("DELIVERY", new OrderLine(11L, 1))));

        OrderRequest withAddress = new OrderRequest(List.of(new OrderLine(11L, 1)),
                "Asha", "9876543210", "DELIVERY", null, "12 MG Road, Pune", null);
        service.placeOrder("KEY", withAddress);
        assertEquals("12 MG Road, Pune", savedRecord().getData().get("address"));
    }

    @Test
    void aNameIsRequired() {
        OrderRequest request = new OrderRequest(List.of(new OrderLine(11L, 1)),
                " ", "9876543210", "TAKEAWAY", null, null, null);
        assertThrows(BadRequestException.class, () -> service.placeOrder("KEY", request));
    }

    /* ------------------------------------------------------ the store's state */

    @Test
    void aSwitchedOffMenuDoesNotExist() {
        store.setEnabled(false);
        assertThrows(ResourceNotFoundException.class,
                () -> service.placeOrder("KEY", order("TAKEAWAY", new OrderLine(11L, 1))));
        assertThrows(ResourceNotFoundException.class, () -> service.storefront("KEY"));
    }

    @Test
    void anUnknownKeyDoesNotExist() {
        assertThrows(ResourceNotFoundException.class, () -> service.storefront("NOPE"));
    }

    @Test
    void anOrderNeverLandsInSomeoneElsesForm() {
        form.setOwnerUserId("someone-else");
        assertThrows(BadRequestException.class,
                () -> service.placeOrder("KEY", order("TAKEAWAY", new OrderLine(11L, 1))));
        verify(recordRepo, never()).save(any());
    }

    @Test
    void anEditedFormOnlyGetsTheFieldsItStillHas() {
        // The owner deleted the address field from their Restaurant form.
        fields.removeIf(f -> f.getFieldKey().equals("address"));
        store.setDelivery(true);
        OrderRequest request = new OrderRequest(List.of(new OrderLine(11L, 1)),
                "Asha", "9876543210", "DELIVERY", null, "12 MG Road", null);

        assertDoesNotThrow(() -> service.placeOrder("KEY", request));
        assertFalse(savedRecord().getData().containsKey("address"));
    }

    /* --------------------------------------------------------- the menu */

    @Test
    void theMenuShowsOnlyWhatACustomerCanSee() {
        categories.add(category(3L, "Empty heading", true));
        Map<String, Object> menu = service.storefront("KEY");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = (List<Map<String, Object>>) menu.get("categories");
        assertEquals(List.of("Starters"), sections.stream().map(s -> s.get("name")).toList(),
                "hidden and empty categories are left out");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> starters = (List<Map<String, Object>>) sections.get(0).get("items");
        assertEquals(3, starters.size(), "a sold-out dish stays visible");

        Map<String, Object> lassi = starters.get(1);
        assertEquals(new BigDecimal("60"), lassi.get("offerPrice"));
        assertNull(starters.get(0).get("offerPrice"), "no offer, no offer price");
        assertEquals(false, starters.get(2).get("available"));

        assertEquals(List.of("DINE_IN", "TAKEAWAY"), menu.get("orderTypes"));
        assertEquals("Spice Hut", menu.get("title"));
    }
}
