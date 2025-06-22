package com.wenx.v3authserverstarter.config;

import com.wenx.v3authserverstarter.properties.CloudAuthServerProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * 云Web安全配置
 *
 * @author wenx
 * @description
 */
@AutoConfiguration
@ConditionalOnClass(HttpSecurity.class)
@ConditionalOnProperty(prefix = "cloud.auth.server", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableWebSecurity // 启用 Spring Security Web 安全功能
@EnableMethodSecurity // 启用方法级别的安全注解
@EnableConfigurationProperties(CloudAuthServerProperties.class)
@RequiredArgsConstructor
public class CloudWebSecurityConfiguration {

    private final CloudAuthServerProperties properties;
    private final AuthenticationEntryPoint authenticationEntryPoint;
    private final AccessDeniedHandler accessDeniedHandler;
    private final UserDetailsService userDetailsService;

    /**
     * 默认Web安全过滤器链
     */
    @Bean
    @Order(2) // 确保默认Web安全过滤器链在授权服务器链之后处理
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // 禁用CSRF，如果您的应用是无状态的API或由前端框架处理了CSRF
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/error/**",
                                "/actuator/**" // 如果有健康检查等端点
                        ).permitAll()
                        .anyRequest().authenticated() // 其他所有请求都需要认证
                )
                .formLogin(formLogin -> formLogin
                        .loginPage("/login") // 指定登录页面URL
                        .permitAll() // 允许所有人访问登录页面
                )
                .rememberMe(rememberMe -> rememberMe
                        .tokenValiditySeconds(properties.getSecurity().getRememberMeTokenValiditySeconds())
                        .key(properties.getSecurity().getRememberMeKey())
                )
                .logout(logout -> logout
                        .logoutRequestMatcher(new AntPathRequestMatcher("/logout", "GET"))
                        .logoutSuccessUrl("/login?logout")
                        .deleteCookies("JSESSIONID", "remember-me")
                        .permitAll()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                );

        // **重要：在这里配置 DaoAuthenticationProvider。
        // HttpSecurity 会在内部构建自己的 AuthenticationManager，并使用此提供者。**
        http.authenticationProvider(daoAuthenticationProvider()); //

        return http.build();
    }

    /**
     * 密码编码器
     */
    @Bean
    @ConditionalOnMissingBean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(properties.getSecurity().getBcryptStrength());
    }

    /**
     * DaoAuthenticationProvider Bean
     * 这个 Bean 只是一个提供者，而不是一个完整的 AuthenticationManager。
     * 它会被 HttpSecurity 自动发现并使用。
     */
    @Bean
    @ConditionalOnMissingBean
    public DaoAuthenticationProvider daoAuthenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService); // 使用注入的 UserDetailsService
        authProvider.setPasswordEncoder(passwordEncoder()); // 使用 passwordEncoder() Bean
        return authProvider;
    }

    /**
     * 安全上下文登出处理器
     */
    @Bean
    @ConditionalOnMissingBean(LogoutHandler.class)
    public LogoutHandler securityContextLogoutHandler() {
        return new SecurityContextLogoutHandler();
    }
}