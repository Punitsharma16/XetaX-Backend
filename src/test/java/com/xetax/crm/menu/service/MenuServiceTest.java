package com.xetax.crm.menu.service;

import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.menu.entity.MenuCategory;
import com.xetax.crm.menu.entity.MenuItem;
import com.xetax.crm.menu.entity.MenuStore;
import com.xetax.crm.menu.repository.MenuCategoryRepository;
import com.xetax.crm.menu.repository.MenuItemRepository;
import com.xetax.crm.menu.repository.MenuStoreRepository;
import com.xetax.crm.menu.service.MenuService.CategoryInput;
import com.xetax.crm.menu.service.MenuService.ItemInput;
import com.xetax.crm.menu.service.MenuService.StoreUpdate;
import com.xetax.crm.template.entity.PackInstall;
import com.xetax.crm.template.repository.PackInstallRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The owner's catalogue rules: offers, categories, and when a menu may go live. */
class MenuServiceTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private MenuStoreRepository storeRepository;
    private MenuCategoryRepository categoryRepository;
    private MenuItemRepository itemRepository;
    private PackInstallRepository packInstallRepository;
    private MenuService service;
    private MenuCategory starters;

    @BeforeEach
    void setUp() {
        storeRepository = mock(MenuStoreRepository.class);
        categoryRepository = mock(MenuCategoryRepository.class);
        itemRepository = mock(MenuItemRepository.class);
        packInstallRepository = mock(PackInstallRepository.class);
        FormRepo formRepo = mock(FormRepo.class);
        CurrentUserProvider users = mock(CurrentUserProvider.class);
        when(users.currentDataOwnerIdOrNull()).thenReturn(OWNER);

        when(storeRepository.save(any())).thenAnswer(inv -> {
            MenuStore s = inv.getArgument(0);
            if (s.getId() == null) s.setId(1L);
            return s;
        });
        when(categoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PackInstall install = PackInstall.builder().ownerUserId(OWNER.toString()).packKey("restaurant").formId(10L).build();
        when(packInstallRepository.findByOwnerUserIdAndPackKeyOrderByInstalledAtDesc(OWNER.toString(), "restaurant"))
                .thenReturn(List.of(install));
        FormEntity form = FormEntity.builder().name("Restaurant Orders").slug("restaurant-orders")
                .ownerUserId(OWNER.toString()).build();
        form.setId(10L);
        when(formRepo.findById(10L)).thenReturn(Optional.of(form));

        starters = MenuCategory.builder().ownerUserId(OWNER.toString()).storeId(1L).name("Starters").active(true).build();
        starters.setId(5L);
        when(categoryRepository.findByIdAndOwnerUserId(5L, OWNER.toString())).thenReturn(Optional.of(starters));

        service = new MenuService(storeRepository, categoryRepository, itemRepository,
                packInstallRepository, formRepo, users);
        ReflectionTestUtils.setField(service, "uploadDir", System.getProperty("java.io.tmpdir"));
        ReflectionTestUtils.setField(service, "publicBaseUrl", "https://app.xetacrm.pro/");
        ReflectionTestUtils.setField(service, "apiBaseUrl", "https://api.xetacrm.pro");
    }

    private MenuStore existingStore() {
        MenuStore store = MenuStore.builder().ownerUserId(OWNER.toString()).formId(10L).publicKey("KEY")
                .enabled(false).dineIn(true).takeaway(true).delivery(false).currency("INR").build();
        store.setId(1L);
        when(storeRepository.findFirstByOwnerUserIdOrderByIdAsc(OWNER.toString())).thenReturn(Optional.of(store));
        return store;
    }

    /* ------------------------------------------------------- the store */

    @Test
    void theFirstVisitCreatesAnOffMenuPointedAtTheRestaurantForm() {
        when(storeRepository.findFirstByOwnerUserIdOrderByIdAsc(OWNER.toString())).thenReturn(Optional.empty());
        MenuStore store = service.myStore();
        assertFalse(store.isEnabled(), "a new menu is never public until switched on");
        assertEquals(10L, store.getFormId());
        assertEquals("Restaurant Orders", store.getTitle());
        assertEquals(32, store.getPublicKey().length());
    }

    @Test
    void theLinksPointAtThePanelAndTheApi() {
        Map<String, Object> view = service.storeView(existingStore());
        assertEquals("https://app.xetacrm.pro/menu/KEY", view.get("menuUrl"));
        assertEquals("https://api.xetacrm.pro/api/public/menu/KEY/qr.png", view.get("qrUrl"));
    }

    @Test
    void aMenuCannotGoLiveWithoutAFormForOrders() {
        MenuStore store = existingStore();
        store.setFormId(null);
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> service.updateStore(new StoreUpdate(null, true, null, null, null, null, null)));
        assertTrue(e.getMessage().contains("Restaurant form"));
    }

    @Test
    void ordersCanOnlyGoIntoARestaurantFormOfYours() {
        existingStore();
        assertThrows(BadRequestException.class,
                () -> service.updateStore(new StoreUpdate(99L, null, null, null, null, null, null)));
    }

    @Test
    void atLeastOneWayToOrderStaysOn() {
        existingStore();
        assertThrows(BadRequestException.class,
                () -> service.updateStore(new StoreUpdate(null, null, null, null, false, false, false)));
    }

    @Test
    void goingLiveWorksOnceEverythingIsInPlace() {
        MenuStore store = existingStore();
        service.updateStore(new StoreUpdate(10L, true, "Spice Hut", null, null, null, true));
        assertTrue(store.isEnabled());
        assertTrue(store.isDelivery());
        assertEquals("Spice Hut", store.getTitle());
    }

    @Test
    void aNewLinkReplacesTheOldOne() {
        MenuStore store = existingStore();
        service.regenerateKey();
        assertNotEquals("KEY", store.getPublicKey());
    }

    /* ----------------------------------------------------- categories */

    @Test
    void aCategoryNeedsAName() {
        existingStore();
        assertThrows(BadRequestException.class,
                () -> service.createCategory(new CategoryInput("  ", null, null, null)));
    }

    @Test
    void deletingACategoryNeverSilentlyDeletesItsDishes() {
        when(itemRepository.countByCategoryId(5L)).thenReturn(3L);
        BadRequestException e = assertThrows(BadRequestException.class, () -> service.deleteCategory(5L));
        assertTrue(e.getMessage().contains("3 items"));
        verify(categoryRepository, never()).delete(any());
    }

    @Test
    void anEmptyCategoryCanBeDeleted() {
        when(itemRepository.countByCategoryId(5L)).thenReturn(0L);
        service.deleteCategory(5L);
        verify(categoryRepository).delete(starters);
    }

    /* ---------------------------------------------------------- items */

    @Test
    void anItemNeedsAPriceAboveZero() {
        existingStore();
        assertThrows(BadRequestException.class, () -> service.createItem(
                new ItemInput(5L, "Tikka", null, BigDecimal.ZERO, null, null, null, null, null)));
    }

    @Test
    void anOfferMustBeCheaperThanTheRegularPrice() {
        existingStore();
        BadRequestException e = assertThrows(BadRequestException.class, () -> service.createItem(
                new ItemInput(5L, "Tikka", null, new BigDecimal("200"), new BigDecimal("250"), "Promo", null, null, null)));
        assertTrue(e.getMessage().contains("lower"));
    }

    @Test
    void anItemWithAnOfferIsSavedWithBothPrices() {
        existingStore();
        Map<String, Object> view = service.createItem(
                new ItemInput(5L, "Tikka", "Smoky", new BigDecimal("200"), new BigDecimal("150"), "25% OFF", true, null, null));
        assertEquals(new BigDecimal("200.00"), view.get("price"));
        assertEquals(new BigDecimal("150.00"), view.get("offerPrice"));
        assertEquals(new BigDecimal("150.00"), view.get("effectivePrice"));
        assertEquals("25% OFF", view.get("offerLabel"));
        assertEquals(true, view.get("available"), "a new dish is orderable");
    }

    @Test
    void clearingTheOfferPriceEndsTheOffer() {
        existingStore();
        MenuItem item = MenuItem.builder().ownerUserId(OWNER.toString()).storeId(1L).categoryId(5L)
                .name("Tikka").price(new BigDecimal("200")).offerPrice(new BigDecimal("150"))
                .offerLabel("25% OFF").available(true).build();
        item.setId(7L);
        when(itemRepository.findByIdAndOwnerUserId(7L, OWNER.toString())).thenReturn(Optional.of(item));

        service.updateItem(7L, new ItemInput(null, null, null, null, BigDecimal.ZERO, "", null, null, null));
        assertNull(item.getOfferPrice());
        assertNull(item.getOfferLabel());
    }

    @Test
    void anItemMustBeInACategoryOfThisMenu() {
        existingStore();
        starters.setStoreId(2L);
        assertThrows(BadRequestException.class, () -> service.createItem(
                new ItemInput(5L, "Tikka", null, new BigDecimal("200"), null, null, null, null, null)));
    }

    @Test
    void aDishPhotoMustBeAnImage() {
        MenuItem item = MenuItem.builder().ownerUserId(OWNER.toString()).storeId(1L).categoryId(5L)
                .name("Tikka").price(new BigDecimal("200")).available(true).build();
        item.setId(7L);
        when(itemRepository.findByIdAndOwnerUserId(7L, OWNER.toString())).thenReturn(Optional.of(item));

        MockMultipartFile pdf = new MockMultipartFile("file", "menu.pdf", "application/pdf", new byte[]{1, 2});
        assertThrows(BadRequestException.class, () -> service.uploadImage(7L, pdf));

        MockMultipartFile photo = new MockMultipartFile("file", "tikka.jpg", "image/jpeg", new byte[]{1, 2, 3});
        Map<String, Object> view = service.uploadImage(7L, photo);
        assertTrue(String.valueOf(view.get("imageUrl"))
                .startsWith("https://api.xetacrm.pro/api/public/menu/images/"));
        service.removeImage(7L); // tidy the temp file
    }
}
