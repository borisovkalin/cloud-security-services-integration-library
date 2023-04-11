/**
 * SPDX-FileCopyrightText: 2018-2023 SAP SE or an SAP affiliate company and Cloud Security Client Java contributors
 * <p>
 * SPDX-License-Identifier: Apache-2.0
 */
package sample.spring.xsuaa;

import com.sap.cloud.security.config.ClientIdentity;
import com.sap.cloud.security.test.api.SecurityTestContext;
import com.sap.cloud.security.test.extension.XsuaaExtension;
import com.sap.cloud.security.token.Token;
import com.sap.cloud.security.token.TokenClaims;
import com.sap.cloud.security.xsuaa.client.OAuth2ServiceException;
import com.sap.cloud.security.xsuaa.client.OAuth2TokenResponse;
import com.sap.cloud.security.xsuaa.client.XsuaaOAuth2TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import sample.spring.xsuaa.config.TokenBrokerTestConfiguration;

import java.net.URI;
import java.util.Collection;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.AdditionalMatchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests the filter chain defined in SecurityConfiguration with calls to the {@link TestController} endpoint.
 * Overrides the default XsuaaOAuth2TokenService, that is used by the TokenBrokerResolver to fetch tokens, with a stub.
 * The tokens are generated by a {@link com.sap.cloud.security.test.JwtGenerator} and validated with a matching JWKS
 * from the local server of {@link XsuaaExtension}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TokenBrokerTestConfiguration.class)
@ExtendWith(XsuaaExtension.class)
public class SecurityConfigurationTest {
	/** users for which the TokenBrokerResolver returns stubbed results. */
	private enum User {MISSING_SCOPES, WRONG_SCOPE, VALID_SCOPE, VALID_SCOPE_ON_OTHER_ZONE}
	private Collection<Token> tokens;
	private static final String OTHER_ZONE_ID = "OTHER_ZONE"; // arbitrary value to identify a tenant zone
	private static final String CORRECT_PASSWORD = "CORRECT"; // arbitrary value used for all users
	@Autowired
	private MockMvc mockMvc;
	@MockBean
	private XsuaaOAuth2TokenService xsuaaTokenService;

	/** Configures the xsuaaTokenService with stub responses.
	 *  For each user, a generated access token is returned if the user's name, password and zone are correct.
	 *  For each user, an exception is thrown when a wrong password is used.
	 *  For Each user, an exception is thrown when he tries to fetch a token from the wrong zone (For the sake of testing,
	 *  no user exists on more than one zone).
	 */
	@BeforeEach
	void stubTokenService(SecurityTestContext context) throws OAuth2ServiceException {
		if(tokens == null) {
			tokens = Stream.of(
						createScopedToken(context, User.MISSING_SCOPES),
						createScopedToken(context, User.WRONG_SCOPE, "WrongScope"),
						createScopedToken(context, User.VALID_SCOPE, "Display"),
						createScopedToken(context, OTHER_ZONE_ID, User.VALID_SCOPE_ON_OTHER_ZONE,"Display")
					).collect(Collectors.toList());

			for(Token t : tokens) {
				when(xsuaaTokenService.retrieveAccessTokenViaPasswordGrant(any(URI.class), any(ClientIdentity.class),
						eq(t.getClaimAsString(TokenClaims.USER_NAME)),eq(CORRECT_PASSWORD), eq(t.getClaimAsString(TokenClaims.XSUAA.ZONE_ID)), any(), anyBoolean()
				)).thenReturn(new OAuth2TokenResponse(t.getTokenValue(), Long.MAX_VALUE, null));

				when(xsuaaTokenService.retrieveAccessTokenViaPasswordGrant(any(URI.class), any(ClientIdentity.class),
						eq(t.getClaimAsString(TokenClaims.USER_NAME)), not(eq(CORRECT_PASSWORD)), any(), any(), anyBoolean()
				)).thenThrow(new OAuth2ServiceException("Invalid password."));

				when(xsuaaTokenService.retrieveAccessTokenViaPasswordGrant(any(URI.class), any(ClientIdentity.class),
						eq(t.getClaimAsString(TokenClaims.USER_NAME)), any(), not(eq(t.getClaimAsString(TokenClaims.XSUAA.ZONE_ID))), any(), anyBoolean()
				)).thenThrow(new OAuth2ServiceException("User does not exist in this zone."));
			}

			when(xsuaaTokenService.retrieveAccessTokenViaPasswordGrant(any(URI.class), any(ClientIdentity.class),
					argThat(userName -> Stream.of(User.values()).noneMatch(u -> u.name().equals(userName))),
					any(), any(), any(), anyBoolean()
			)).thenThrow(new OAuth2ServiceException("User does not exist."));
		}
	}

	/** Generates an access token tailored to the given user with the given scopes without zone id. */
	private Token createScopedToken(SecurityTestContext context, User user,  String... scopes) {
		return createScopedToken(context, null, user, scopes);
	}

	/** Generates an access token tailored to the given user in the given zone with the given scopes. */
	private Token createScopedToken(SecurityTestContext context, String zoneId, User user,  String... scopes) {
		return context.getPreconfiguredJwtGenerator()
				.withLocalScopes(scopes)
				.withClaimValue(TokenClaims.USER_NAME, user.name())
				.withClaimValue(TokenClaims.XSUAA.ZONE_ID, zoneId)
				.createToken();
	}

	@Test
	void rejectsNonExistingUsers() throws Exception {
		String userName = "MADE_UP_USER";
		assertThatThrownBy(() -> User.valueOf(userName)).isInstanceOf(IllegalArgumentException.class);

		mockMvc.perform(get("/fetchToken").with(httpBasic(userName, CORRECT_PASSWORD)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsWrongPasswords() throws Exception {
		final String WRONG_PASSWORD = "NOT " + CORRECT_PASSWORD;
		assertNotEquals(WRONG_PASSWORD, CORRECT_PASSWORD);

		for(User u: User.values()) {
			mockMvc.perform(get("/fetchToken").with(httpBasic(u.name(), WRONG_PASSWORD)))
					.andExpect(status().isUnauthorized());
		}
	}

	@Test
	void rejectsTokenWithoutScopes() throws Exception {
		mockMvc.perform(get("/fetchToken").with(httpBasic(User.MISSING_SCOPES.name(), CORRECT_PASSWORD)))
				.andExpect(status().isForbidden());
	}

	@Test
	void rejectsTokenWithWrongScope() throws Exception {
		mockMvc.perform(get("/fetchToken").with(httpBasic(User.WRONG_SCOPE.name(), CORRECT_PASSWORD)))
				.andExpect(status().isForbidden());
	}

	@Test
	void acceptsTokenWithValidScope() throws Exception {
		mockMvc.perform(get("/fetchToken").with(httpBasic(User.VALID_SCOPE.name(), CORRECT_PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("claims." + TokenClaims.USER_NAME).value(User.VALID_SCOPE.name()));
	}

	@Test
	void respectsZoneId() throws Exception {
		mockMvc.perform(get("/fetchToken")
						.with(httpBasic(User.VALID_SCOPE_ON_OTHER_ZONE.name(), CORRECT_PASSWORD))
						.header(TokenBrokerResolver.ZONE_ID_HEADER, OTHER_ZONE_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("claims." + TokenClaims.USER_NAME).value(User.VALID_SCOPE_ON_OTHER_ZONE.name()))
				.andExpect(jsonPath("claims." + TokenClaims.XSUAA.ZONE_ID).value(OTHER_ZONE_ID));

		mockMvc.perform(get("/fetchToken")
						.with(httpBasic(User.VALID_SCOPE.name(), CORRECT_PASSWORD))
						.header(TokenBrokerResolver.ZONE_ID_HEADER, OTHER_ZONE_ID))
				.andExpect(status().isUnauthorized()); // user does not exist on this zone

		mockMvc.perform(get("/fetchToken")
						.with(httpBasic(User.VALID_SCOPE_ON_OTHER_ZONE.name(), CORRECT_PASSWORD)))
				.andExpect(status().isUnauthorized()); // zone id for this user is missing in header
	}
}
