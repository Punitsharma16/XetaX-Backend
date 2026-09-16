package com.xetax.crm.menu.repository;

import com.xetax.crm.menu.entity.MenuStore;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MenuStoreRepository extends JpaRepository<MenuStore, Long> {

    Optional<MenuStore> findFirstByOwnerUserIdOrderByIdAsc(String ownerUserId);

    Optional<MenuStore> findByPublicKey(String publicKey);
}
