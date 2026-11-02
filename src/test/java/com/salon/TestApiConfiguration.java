package com.salon;

import com.salon.RecordingMessagingConfiguration.RecordingEmailGateway;
import com.salon.identity.domain.UserAccountRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@TestConfiguration(proxyBeanMethods = false)
class TestApiConfiguration {

	@Bean
	TestApi testApi(MockMvc mvc, RecordingEmailGateway emails, UserAccountRepository users,
			TransactionTemplate transactionTemplate) {
		return new TestApi(mvc, emails, users, transactionTemplate);
	}

}
