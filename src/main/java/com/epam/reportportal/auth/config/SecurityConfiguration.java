package com.epam.reportportal.auth.config;

import com.drew.lang.Charsets;
import com.epam.reportportal.auth.OAuthSuccessHandler;
import com.epam.reportportal.auth.ReportPortalClient;
import com.epam.reportportal.auth.basic.BasicPasswordAuthenticationProvider;
import com.epam.reportportal.auth.basic.DatabaseUserDetailsService;
import com.epam.reportportal.auth.integration.github.GitHubOAuth2UserService;
import com.epam.reportportal.auth.integration.github.GitHubUserReplicator;
import com.epam.reportportal.auth.integration.ldap.ActiveDirectoryAuthProvider;
import com.epam.reportportal.auth.integration.ldap.LdapAuthProvider;
import com.epam.reportportal.auth.integration.ldap.LdapUserReplicator;
import com.epam.reportportal.auth.oauth.AccessTokenStore;
import com.epam.ta.reportportal.dao.IntegrationRepository;
import com.epam.ta.reportportal.dao.OAuthRegistrationRestrictionRepository;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.DelegatingOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.config.annotation.configurers.ClientDetailsServiceConfigurer;
import org.springframework.security.oauth2.config.annotation.web.configuration.AuthorizationServerConfigurerAdapter;
import org.springframework.security.oauth2.config.annotation.web.configuration.EnableAuthorizationServer;
import org.springframework.security.oauth2.config.annotation.web.configuration.EnableResourceServer;
import org.springframework.security.oauth2.config.annotation.web.configuration.ResourceServerConfigurerAdapter;
import org.springframework.security.oauth2.config.annotation.web.configurers.AuthorizationServerEndpointsConfigurer;
import org.springframework.security.oauth2.config.annotation.web.configurers.AuthorizationServerSecurityConfigurer;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.provider.token.DefaultAccessTokenConverter;
import org.springframework.security.oauth2.provider.token.DefaultTokenServices;
import org.springframework.security.oauth2.provider.token.DefaultUserAuthenticationConverter;
import org.springframework.security.oauth2.provider.token.TokenStore;
import org.springframework.security.oauth2.provider.token.store.JwtAccessTokenConverter;
import org.springframework.security.oauth2.provider.token.store.JwtTokenStore;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Strings.isNullOrEmpty;

@Configuration
public class SecurityConfiguration {

	@Configuration
	@Order(4)
	public static class GlobalWebSecurityConfig extends WebSecurityConfigurerAdapter {

		public static final String SSO_LOGIN_PATH = "/sso/login";

		@Autowired
		private OAuthSuccessHandler successHandler;

		@Autowired
		private IntegrationRepository authConfigRepository;

		@Autowired
		private LdapUserReplicator ldapUserReplicator;

		@Override
		protected final void configure(HttpSecurity http) throws Exception {
			//@formatter:off
        http
                .antMatcher("/**")
                .authorizeRequests()
                .antMatchers(SSO_LOGIN_PATH + "/**", "/epam/**", "/info", "/health", "/api-docs/**")
                .permitAll()
                .anyRequest()
                .authenticated()
                .and()
					.csrf().disable()
				.formLogin().disable()
				.sessionManagement()
                	.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
				.and()
					.httpBasic()
				.and()
					.oauth2Login()
				.clientRegistrationRepository(clientRegistrationRepository)
					  .authorizationEndpoint()
						.baseUri(SSO_LOGIN_PATH)
						  .and()
						.userInfoEndpoint()
						.userService(oauth2UserService())
						  .and()
						.successHandler(successHandler);
        //@formatter:on
		}

		@Autowired
		@Qualifier(value = "mutableClientRegistrationRepository")
		private ClientRegistrationRepository clientRegistrationRepository;

		@Autowired
		private GitHubUserReplicator gitHubUserReplicator;

		@Autowired
		private OAuthRegistrationRestrictionRepository oAuthRegistrationRestrictionRepository;

		private OAuth2UserService<OAuth2UserRequest, OAuth2User> oauth2UserService() {
			List<OAuth2UserService<OAuth2UserRequest, OAuth2User>> services = new LinkedList<>();
			services.add(new GitHubOAuth2UserService(gitHubUserReplicator, oAuthRegistrationRestrictionRepository));
			return new DelegatingOAuth2UserService<>(services);
		}

		@Override
		protected void configure(AuthenticationManagerBuilder auth) throws Exception {
			auth.authenticationProvider(basicPasswordAuthProvider())
					.authenticationProvider(activeDirectoryAuthProvider())
					.authenticationProvider(ldapAuthProvider());
		}

		@Bean
		public AuthenticationProvider activeDirectoryAuthProvider() {
			return new ActiveDirectoryAuthProvider(authConfigRepository, ldapUserReplicator);
		}

		@Bean
		public AuthenticationProvider ldapAuthProvider() {
			return new LdapAuthProvider(authConfigRepository, ldapUserReplicator);
		}

		@Bean
		protected UserDetailsService userDetailsService() {
			DatabaseUserDetailsService service = new DatabaseUserDetailsService();

			return service;
		}

		@Bean
		public AuthenticationProvider basicPasswordAuthProvider() {
			BasicPasswordAuthenticationProvider provider = new BasicPasswordAuthenticationProvider();
			provider.setUserDetailsService(userDetailsService());
			provider.setPasswordEncoder(passwordEncoder());
			return provider;
		}

		public PasswordEncoder passwordEncoder() {
			return new MD5PasswordEncoder();
		}

		@Override
		@Primary
		@Bean
		public AuthenticationManager authenticationManager() throws Exception {
			return super.authenticationManager();
		}
	}

	@Configuration
	@Order(5)
	@EnableAuthorizationServer
	public static class AuthorizationServerConfiguration extends AuthorizationServerConfigurerAdapter {

		private final AuthenticationManager authenticationManager;

		@Autowired
		@Value("${rp.jwt.signing-key}")
		private String signingKey;

		@Autowired
		private DatabaseUserDetailsService userDetailsService;

		@Autowired
		public AuthorizationServerConfiguration(AuthenticationManager authenticationManager) {
			this.authenticationManager = authenticationManager;
		}

		@Override
		public void configure(AuthorizationServerEndpointsConfigurer endpoints) {
			//@formatter:off

			endpoints
					.pathMapping("/oauth/token", "/sso/oauth/token")
					.pathMapping("/oauth/token_key", "/sso/oauth/token_key")
					.pathMapping("/oauth/check_token", "/sso/oauth/check_token")
					.pathMapping("/oauth/authorize", "/sso/oauth/authorize")
					.pathMapping("/oauth/confirm_access", "/sso/oauth/confirm_access")
					.tokenStore(jwtTokenStore())
//					.exceptionTranslator(new OAuthErrorHandler(new ReportPortalExceptionResolver(new DefaultErrorResolver(ExceptionMappings.DEFAULT_MAPPING))))
					.accessTokenConverter(accessTokenConverter())
					.authenticationManager(authenticationManager);
			//@formatter:on
		}

		@Override
		public void configure(ClientDetailsServiceConfigurer clients) throws Exception {
			//@formatter:off
            clients.inMemory()
                    .withClient(ReportPortalClient.ui.name())
                    	.secret("{bcrypt}$2a$10$ka8W./nA2Uiqsd2uOzazdu2lMbipaMB6RJNInB1Y0NMKQzj7plsie")
                    	.authorizedGrantTypes("refresh_token", "password")
                    	.scopes("ui")
						.accessTokenValiditySeconds((int) TimeUnit.DAYS.toSeconds(1))
                    .and()
                    .withClient(ReportPortalClient.api.name())
                    	.secret("apiman")
						.authorizedGrantTypes("password")
                    	.scopes("api")
                    	.accessTokenValiditySeconds(-1)

                    .and()
                    .withClient(ReportPortalClient.internal.name())
                    	.secret("internal_man")
                    	.authorizedGrantTypes("client_credentials").authorities("ROLE_INTERNAL")
                    	.scopes("internal");

            //@formatter:on
		}

		@Override
		public void configure(AuthorizationServerSecurityConfigurer security) {

			security.tokenKeyAccess("hasAuthority('ROLE_INTERNAL')").checkTokenAccess("hasAuthority('ROLE_INTERNAL')");

		}

		@Bean(value = "jwtTokenStore")
		@Primary
		public TokenStore jwtTokenStore() {
			return new JwtTokenStore(accessTokenConverter());
		}

		@Bean
		public JwtAccessTokenConverter accessTokenConverter() {
			JwtAccessTokenConverter converter = new JwtAccessTokenConverter();
			converter.setSigningKey(signingKey);

			DefaultUserAuthenticationConverter defaultUserAuthenticationConverter = new DefaultUserAuthenticationConverter();
			defaultUserAuthenticationConverter.setUserDetailsService(userDetailsService);

			DefaultAccessTokenConverter converter1 = new DefaultAccessTokenConverter();
			converter1.setUserTokenConverter(defaultUserAuthenticationConverter);

			converter.setAccessTokenConverter(converter1);

			return converter;
		}

		@Bean
		@Primary
		public DefaultTokenServices tokenServices() {
			DefaultTokenServices defaultTokenServices = new DefaultTokenServices();
			defaultTokenServices.setTokenStore(jwtTokenStore());
			defaultTokenServices.setSupportRefreshToken(true);
			defaultTokenServices.setAuthenticationManager(authenticationManager);
			defaultTokenServices.setTokenEnhancer(accessTokenConverter());
			return defaultTokenServices;
		}

		@Bean(value = "databaseTokenServices")
		public DefaultTokenServices databaseTokenServices(@Autowired AccessTokenStore accessTokenStore) {
			DefaultTokenServices defaultTokenServices = new DefaultTokenServices();
			defaultTokenServices.setTokenStore(accessTokenStore);
			defaultTokenServices.setSupportRefreshToken(false);
			defaultTokenServices.setAuthenticationManager(authenticationManager);
			return defaultTokenServices;
		}

	}

	@Configuration
	@EnableResourceServer
	public static class ResourceServerAuthConfiguration extends ResourceServerConfigurerAdapter {
		@Override
		public void configure(HttpSecurity http) throws Exception {
			http.requestMatchers()
					.antMatchers("/sso/me/**", "/sso/internal/**", "/settings/**")
					.and()
					.authorizeRequests()
					.antMatchers("/settings/**")
					.hasRole("ADMINISTRATOR")
					.antMatchers("/sso/internal/**")
					.hasRole("INTERNAL")
					.anyRequest()
					.authenticated()
					.and()
					.sessionManagement()
					.sessionCreationPolicy(SessionCreationPolicy.STATELESS);

		}

	}

	public static class MD5PasswordEncoder implements PasswordEncoder {

		private HashFunction hasher = Hashing.md5();

		@Override
		public String encode(CharSequence rawPassword) {
			return hasher.newHasher().putString(rawPassword, Charsets.UTF_8).hash().toString();
		}

		@Override
		public boolean matches(CharSequence rawPassword, String encodedPassword) {
			if (isNullOrEmpty(encodedPassword)) {
				return false;
			}
			return encodedPassword.equals(hasher.newHasher().putString(rawPassword, Charsets.UTF_8).hash().toString());
		}

	}

}
