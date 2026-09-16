package com.xetax.crm.menu.repository;

import com.xetax.crm.menu.entity.MenuCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MenuCategoryRepository extends JpaRepository<MenuCategory, Long> {

    List<MenuCategory> findByStoreIdOrderBySortOrderAscIdAsc(Long storeId);

    Optional<MenuCategory> findByIdAndOwnerUserId(Long id, String ownerUserId);

    long countByStoreId(Long storeId);
}
