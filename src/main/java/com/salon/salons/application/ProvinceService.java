package com.salon.salons.application;

import java.util.List;

import com.salon.salons.domain.ProvinceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ProvinceService {

	private final ProvinceRepository provinces;

	ProvinceService(ProvinceRepository provinces) {
		this.provinces = provinces;
	}

	public List<ProvinceView> list() {
		return provinces.findAllByOrderByIdAsc()
			.stream()
			.map(province -> new ProvinceView(province.getId(), province.getName()))
			.toList();
	}

}
