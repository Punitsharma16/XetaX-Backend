package com.xetax.crm.menu.repository;

import com.xetax.crm.menu.entity.MenuItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {

    List<MenuItem> findByStoreIdOrderBySortOrderAscIdAsc(Long storeId);

    Optional<MenuItem> findByIdAndOwnerUserId(Long id, String ownerUserId);

    long countByCategoryId(Long categoryId);

    long countByStoreIdAndCategoryId(Long storeId, Long categoryId);

    /** The items an order names, limited to this store so a key cannot reach another menu. */
    List<MenuItem> findByStoreIdAndIdIn(Long storeId, Collection<Long> ids);

    Optional<MenuItem> findByImageKey(String imageKey);
}
