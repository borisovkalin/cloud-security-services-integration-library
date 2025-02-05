/**
 * SPDX-FileCopyrightText: 2018-2023 SAP SE or an SAP affiliate company and Cloud Security Client Java contributors
 * <p>
 * SPDX-License-Identifier: Apache-2.0
 */
package testservice.api.v1;

import com.sap.cloud.security.xsuaa.autoconfiguration.XsuaaAutoConfiguration;
import com.sap.cloud.security.xsuaa.mock.JWTUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import testservice.api.MockXsuaaServerConfiguration;
import testservice.api.XsuaaITApplication;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = {
		XsuaaITApplication.class,
		TestController.class, XsuaaAutoConfiguration.class,
})
@AutoConfigureMockMvc
@ActiveProfiles("test.api.v1")
class XsuaaTokenValidationTest extends MockXsuaaServerConfiguration {
	@Autowired
	MockMvc mvc;

	private static BearerTokenRequestPostProcessor bearerToken(String token) {
		return new BearerTokenRequestPostProcessor(token);
	}

	@Test
	void testToken_testdomain() throws Exception {
		 this.mvc.perform(get("/user").with(bearerToken(JWTUtil.createJWT("/saml.txt",
		 "testdomain", "tenant", null))))
		 .andExpect(status().isOk()).andExpect(content().string(containsString("user:Mustermann")));
	}

	@Test
	void testToken_otherdomain() throws Exception {
		this.mvc.perform(get("/user").with(bearerToken(JWTUtil.createJWT("/saml.txt",
				"otherdomain",  "othertenant", null))))
				.andExpect(status().isOk()).andExpect(content().string(containsString("user:Mustermann")));
	}

	@Test
	void test_Scope() throws Exception {
		this.mvc.perform(get("/scope").with(bearerToken(JWTUtil.createJWT("/saml.txt",
				"otherdomain", "othertenant", null))))
				.andExpect(status().isOk());
	}

	@Test
	void test_clientcredentialstoken() throws Exception {
		this.mvc.perform(
				get("/clientCredentialsToken")
						.with(bearerToken(JWTUtil.createJWT("/saml.txt", "uaa",
								"legacy-token-key"))))
				.andExpect(status().isOk()).andExpect(
						content().string(containsString(".ewogICJqdGkiOiAiOGU3YjNiMDAtNzc1MS00YjQ2LTliMWEtNWE0NmEyY")));
	}

	@Test
	void test_insufficientScopedToken_isUnauthorized() throws Exception {
		this.mvc.perform(
				get("/clientCredentialsToken")
						.with(bearerToken(
								JWTUtil.createJWT("/insufficient_scoped.txt", "uaa", "legacy-token-key"))))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void test_expiredToken_isUnauthorized() throws Exception {
		this.mvc.perform(
				get("/clientCredentialsToken")
						.with(bearerToken(JWTUtil.createJWT("/expired.txt", "uaa", "legacy-token-key"))))
				.andExpect(status().isUnauthorized());
	}

	private record BearerTokenRequestPostProcessor(String token) implements RequestPostProcessor {

		@Override
			public MockHttpServletRequest postProcessRequest(MockHttpServletRequest request) {
				request.addHeader("Authorization", "Bearer " + this.token);
				return request;
			}
		}

}
