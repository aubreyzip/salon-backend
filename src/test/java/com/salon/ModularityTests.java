package com.salon;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

	ApplicationModules modules = ApplicationModules.of(SalonBackendApplication.class);

	@Test
	void verifiesModularStructure() {
		modules.verify();
	}

}
