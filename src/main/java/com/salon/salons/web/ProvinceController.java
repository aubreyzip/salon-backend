package com.salon.salons.web;

import java.util.List;

import com.salon.salons.application.ProvinceService;
import com.salon.salons.application.ProvinceView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public reference data for address forms.
 */
@RestController
@RequestMapping("/api/v1/public/provinces")
class ProvinceController {

	private final ProvinceService provinces;

	ProvinceController(ProvinceService provinces) {
		this.provinces = provinces;
	}

	@GetMapping
	List<ProvinceView> list() {
		return provinces.list();
	}

}
