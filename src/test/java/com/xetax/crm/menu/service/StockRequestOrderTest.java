package com.xetax.crm.menu.service;

import com.xetax.crm.automation.engine.AutomationEngine;
import com.xetax.crm.automation.enums.AutomationTrigger;
import com.xetax.crm.data_manager.documents.RecordDocument;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.entity.FormField;
import com.xetax.crm.data_manager.entity.FormStage;
import com.xetax.crm.data_manager.enums.FieldType;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.data_manager.repository.RecordRepo;
import com.xetax.crm.data_manager.service.FormMetaCache;
import com.xetax.crm.data_manager.validator.DefaultValueValidator;
import com.xetax.crm.data_manager.validator.DynamicValidationService;
import com.xetax.crm.data_manager.validator.DynamicValidationServiceImpl;
import com.xetax.crm.data_manager.validator.FieldTypeValidator;
import com.xetax.crm.data_manager.validator.RequiredFieldValidator;
import com.xetax.crm.data_manager.validator.UnknownFieldValidator;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The same catalogue page, pointed at a stores team's form.
 *
 * <p>A restaurant order is one record with the lines written into a text
 * field. A stores form has no such field — item and quantity are single and
 * required — so each line becomes its own request, which is what the
 * Requested → Approved → Ordered pipeline is built to carry. The records are
 * written through the form's REAL validator, so a mapping that misses a
 * required field fails here rather than in production.
 */
class StockRequestOrderTest {

    private List<FormField> fields;
    private final List<MenuItem> items = new ArrayList<>();
    private RecordRepo recordRepo;
    private AutomationEngine automationEngine;
    private NotificationService notificationService;
    private MenuOrderService service;

    @BeforeEach
    void setUp() {
        MenuStore store = MenuStore.builder().ownerUserId("owner-1").formId(10L).publicKey("KEY")
                .enabled(true).title("Stores").currency("INR")
                .dineIn(true).takeaway(false).delivery(false).build();
        store.setId(1L);

        FormEntity form = FormEntity.builder().name("Inventory / Procurement")
                .slug("inventory-procurement").ownerUserId("owner-1").build();
        form.setId(10L);

        // The Inventory pack's own fields.
        fields = new ArrayList<>(List.of(
                field("item_name", "Item Name", FieldType.TEXT, true),
                field("sku", "SKU", FieldType.TEXT, false),
                field("quantity", "Quantity", FieldType.NUMBER, true),
                field("supplier", "Supplier", FieldType.TEXT, false),
                field("current_stock", "Current Stock", FieldType.NUMBER, false),
                field("reorder_level", "Reorder Level", FieldType.NUMBER, false),
                field("unit_price", "Unit Price", FieldType.NUMBER, false),
                field("requested_by", "Requested By", FieldType.TEXT, false),
                field("requester_phone", "Requester Phone", FieldType.TEXT, false),
                field("notes", "Notes", FieldType.TEXTAREA, false)));

        List<MenuCategory> categories = List.of(category(1L, "Consumables", true));
        items.add(item(11L, 1L, "A4 Paper Ream", "240", true));
        items.add(item(12L, 1L, "Blue Pens (box)", "80", true));

        MenuStoreRepository storeRepository = mock(MenuStoreRepository.class);
        when(storeRepository.findByPublicKey("KEY")).thenReturn(Optional.of(store));

        MenuCategoryRepository categoryRepository = mock(MenuCategoryRepository.class);
        when(categoryRepository.findByStoreIdOrderBySortOrderAscIdAsc(1L)).thenReturn(categories);

        MenuItemRepository itemRepository = mock(MenuItemRepository.class);
        when(itemRepository.findByStoreIdAndIdIn(eq(1L), anyCollection())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(1);
            return items.stream().filter(i -> ids.contains(i.getId())).toList();
        });

        FormRepo formRepo = mock(FormRepo.class);
        when(formRepo.findById(10L)).thenReturn(Optional.of(form));

        FormMetaCache formMetaCache = mock(FormMetaCache.class);
        when(formMetaCache.getFields(10L)).thenAnswer(inv -> fields);
        when(formMetaCache.getStages(10L)).thenReturn(List.of(
                FormStage.builder().id(100L).name("Requested").isDefault(true).build(),
                FormStage.builder().id(101L).name("Approved").isDefault(false).build()));

        recordRepo = mock(RecordRepo.class);
        when(recordRepo.save(any())).thenAnswer(inv -> {
            RecordDocument doc = inv.getArgument(0);
            doc.setId("665f0000000000000abc12ef");
            return doc;
        });

        automationEngine = mock(AutomationEngine.class);
        notificationService = mock(NotificationService.class);

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
        MenuCategory c = MenuCategory.builder().ownerUserId("owner-1").storeId(1L).name(name).active(active).build();
        c.setId(id);
        return c;
    }

    private static MenuItem item(Long id, Long categoryId, String name, String price, boolean available) {
        MenuItem i = MenuItem.builder().ownerUserId("owner-1").storeId(1L).categoryId(categoryId)
                .name(name).price(new BigDecimal(price)).available(available).build();
        i.setId(id);
        return i;
    }

    private Map<String, Object> order(OrderLine... lines) {
        return service.placeOrder("KEY", new OrderRequest(
                // A stores page has no fulfilment mode; collection is the one that fits.
                List.of(lines), "Asha", "9876543210", "DINE_IN", null, null, "before Friday"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> savedRecords() {
        ArgumentCaptor<RecordDocument> captor = ArgumentCaptor.forClass(RecordDocument.class);
        verify(recordRepo, times(countSaves())).save(captor.capture());
        return captor.getAllValues().stream().map(RecordDocument::getData).toList();
    }

    private int countSaves() {
        return org.mockito.Mockito.mockingDetails(recordRepo).getInvocations().stream()
                .filter(i -> "save".equals(i.getMethod().getName())).toList().size();
    }

    // ------------------------------------------------- one record per line

    @Test
    void eachProductBecomesItsOwnRequest() {
        order(new OrderLine(11L, 3), new OrderLine(12L, 2));

        List<Map<String, Object>> saved = savedRecords();
        assertEquals(2, saved.size());
        assertEquals("A4 Paper Ream", saved.get(0).get("item_name"));
        assertEquals("Blue Pens (box)", saved.get(1).get("item_name"));
    }

    @Test
    void theQuantityIsTheOneThatWasPicked() {
        order(new OrderLine(11L, 3), new OrderLine(12L, 2));

        List<Map<String, Object>> saved = savedRecords();
        assertEquals(3, ((Number) saved.get(0).get("quantity")).intValue());
        assertEquals(2, ((Number) saved.get(1).get("quantity")).intValue());
    }

    @Test
    void repeatedLinesAreMergedIntoOneRequest() {
        order(new OrderLine(11L, 2), new OrderLine(11L, 3));

        List<Map<String, Object>> saved = savedRecords();
        assertEquals(1, saved.size());
        assertEquals(5, ((Number) saved.get(0).get("quantity")).intValue());
    }

    @Test
    void theRequesterIsCarriedOnEveryLine() {
        order(new OrderLine(11L, 1), new OrderLine(12L, 1));

        for (Map<String, Object> record : savedRecords()) {
            assertEquals("Asha", record.get("requested_by"));
            assertEquals("9876543210", record.get("requester_phone"));
            assertEquals("before Friday", record.get("notes"));
        }
    }

    @Test
    void thePriceOnTheCatalogueIsWhatIsRecorded() {
        order(new OrderLine(11L, 3));

        assertEquals(0, new BigDecimal("240").compareTo(
                new BigDecimal(savedRecords().get(0).get("unit_price").toString())));
    }

    @Test
    void restaurantOnlyFieldsAreNeverWritten() {
        order(new OrderLine(11L, 1));

        Map<String, Object> record = savedRecords().get(0);
        for (String key : List.of("customer_name", "phone", "order_items", "amount", "order_type", "address")) {
            assertFalse(record.containsKey(key), key + " is not a field of this form");
        }
    }

    // --------------------------------------------------- pipeline and page

    @Test
    void everyRequestStartsInTheFormsFirstStage() {
        order(new OrderLine(11L, 1), new OrderLine(12L, 1));

        ArgumentCaptor<RecordDocument> captor = ArgumentCaptor.forClass(RecordDocument.class);
        verify(recordRepo, times(2)).save(captor.capture());
        for (RecordDocument doc : captor.getAllValues()) {
            assertEquals(100L, doc.getStageId());
        }
    }

    @Test
    void everyRequestRunsTheRecordCreatedAutomations() {
        order(new OrderLine(11L, 1), new OrderLine(12L, 1));

        verify(automationEngine, times(2))
                .execute(eq(AutomationTrigger.RECORD_CREATED), any(), any());
    }

    @Test
    void theTotalIsThePriceTimesTheQuantity() {
        Map<String, Object> out = order(new OrderLine(11L, 2), new OrderLine(12L, 1));

        // 2 x 240 + 1 x 80
        assertEquals(0, new BigDecimal("560").compareTo((BigDecimal) out.get("total")));
    }

    @Test
    void theReplyNamesEveryRequestRaised() {
        Map<String, Object> out = order(new OrderLine(11L, 1), new OrderLine(12L, 1));

        assertEquals(true, out.get("ok"));
        assertTrue(out.get("message").toString().startsWith("2 requests raised"));
        assertTrue(out.get("reference").toString().contains(","));
    }

    @Test
    void oneLineReadsAsOneRequest() {
        Map<String, Object> out = order(new OrderLine(11L, 1));

        assertTrue(out.get("message").toString().startsWith("Request raised"));
        assertFalse(out.get("reference").toString().contains(","));
    }

    @Test
    void theOwnerIsToldOnce() {
        order(new OrderLine(11L, 1), new OrderLine(12L, 1));

        verify(notificationService).push(anyString(), anyString(), eq("RECORD_CREATED"),
                eq("2 new stock requests"), anyString(), anyString());
    }
}
