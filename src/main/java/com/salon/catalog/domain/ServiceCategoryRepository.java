package com.salon.catalog.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceCategoryRepository extends JpaRepository<ServiceCategory, Integer> {

	List<ServiceCategory> findAllByOrderBySortOrderAsc();

}
