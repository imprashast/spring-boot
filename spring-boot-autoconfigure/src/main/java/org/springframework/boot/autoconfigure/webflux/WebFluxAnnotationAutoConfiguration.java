/*
 * Copyright 2012-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.autoconfigure.webflux;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.ConditionalOnEnabledResourceChain;
import org.springframework.boot.autoconfigure.web.ResourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.GenericConverter;
import org.springframework.format.Formatter;
import org.springframework.format.FormatterRegistry;
import org.springframework.http.CacheControl;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.web.reactive.DispatcherHandler;
import org.springframework.web.reactive.config.DelegatingWebFluxConfiguration;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.config.ResourceChainRegistration;
import org.springframework.web.reactive.config.ResourceHandlerRegistration;
import org.springframework.web.reactive.config.ResourceHandlerRegistry;
import org.springframework.web.reactive.config.ViewResolverRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurationSupport;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.resource.AppCacheManifestTransformer;
import org.springframework.web.reactive.resource.GzipResourceResolver;
import org.springframework.web.reactive.resource.ResourceResolver;
import org.springframework.web.reactive.resource.VersionResourceResolver;
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver;
import org.springframework.web.reactive.result.view.ViewResolver;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;

/**
 * {@link EnableAutoConfiguration Auto-configuration} for {@link EnableWebFlux WebFlux}.
 *
 * @author Brian Clozel
 * @author Rob Winch
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass({DispatcherHandler.class, HttpHandler.class})
@ConditionalOnMissingBean({RouterFunction.class, HttpHandler.class})
@AutoConfigureAfter(ReactiveWebServerAutoConfiguration.class)
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE + 10)
public class WebFluxAnnotationAutoConfiguration {

	@Configuration
	@ConditionalOnMissingBean(WebFluxConfigurationSupport.class)
	@EnableConfigurationProperties({ResourceProperties.class, WebFluxProperties.class})
	@Import(DelegatingWebFluxConfiguration.class)
	public static class WebFluxConfig implements WebFluxConfigurer {

		private static final Log logger = LogFactory.getLog(WebFluxConfig.class);

		private final ResourceProperties resourceProperties;

		private final WebFluxProperties webFluxProperties;

		private final ListableBeanFactory beanFactory;

		private final List<HandlerMethodArgumentResolver> argumentResolvers;

		private final ResourceHandlerRegistrationCustomizer resourceHandlerRegistrationCustomizer;

		private final List<ViewResolver> viewResolvers;


		public WebFluxConfig(ResourceProperties resourceProperties,
				WebFluxProperties webFluxProperties, ListableBeanFactory beanFactory,
				ObjectProvider<List<HandlerMethodArgumentResolver>> resolvers,
				ObjectProvider<ResourceHandlerRegistrationCustomizer> resourceHandlerRegistrationCustomizer,
				ObjectProvider<List<ViewResolver>> viewResolvers) {
			this.resourceProperties = resourceProperties;
			this.webFluxProperties = webFluxProperties;
			this.beanFactory = beanFactory;
			this.argumentResolvers = resolvers.getIfAvailable();
			this.resourceHandlerRegistrationCustomizer = resourceHandlerRegistrationCustomizer.getIfAvailable();
			this.viewResolvers = viewResolvers.getIfAvailable();
		}

		@Override
		public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
			if (this.argumentResolvers != null) {
				resolvers.addAll(this.argumentResolvers);
			}
		}

		@Override
		public void addResourceHandlers(ResourceHandlerRegistry registry) {
			if (!this.resourceProperties.isAddMappings()) {
				logger.debug("Default resource handling disabled");
				return;
			}
			Integer cachePeriod = this.resourceProperties.getCachePeriod();
			if (!registry.hasMappingForPattern("/webjars/**")) {
				ResourceHandlerRegistration registration = registry
						.addResourceHandler("/webjars/**")
						.addResourceLocations("classpath:/META-INF/resources/webjars/");
				if (cachePeriod != null) {
					registration.setCacheControl(CacheControl.maxAge(cachePeriod, TimeUnit.SECONDS));
				}
				customizeResourceHandlerRegistration(registration);
			}
			String staticPathPattern = this.webFluxProperties.getStaticPathPattern();
			if (!registry.hasMappingForPattern(staticPathPattern)) {
				ResourceHandlerRegistration registration = registry.addResourceHandler(staticPathPattern)
						.addResourceLocations(this.resourceProperties.getStaticLocations());
				if (cachePeriod != null) {
					registration.setCacheControl(CacheControl.maxAge(cachePeriod, TimeUnit.SECONDS));
				}
				customizeResourceHandlerRegistration(registration);
			}
		}

		@Override
		public void configureViewResolvers(ViewResolverRegistry registry) {
			if (this.viewResolvers != null) {
				AnnotationAwareOrderComparator.sort(this.viewResolvers);
				this.viewResolvers.forEach(resolver -> registry.viewResolver(resolver));
			}
		}

		@Override
		public void addFormatters(final FormatterRegistry registry) {
			for (Converter<?, ?> converter : getBeansOfType(Converter.class)) {
				registry.addConverter(converter);
			}
			for (GenericConverter converter : getBeansOfType(GenericConverter.class)) {
				registry.addConverter(converter);
			}
			for (Formatter<?> formatter : getBeansOfType(Formatter.class)) {
				registry.addFormatter(formatter);
			}
		}

		private <T> Collection<T> getBeansOfType(Class<T> type) {
			return this.beanFactory.getBeansOfType(type).values();
		}

		private void customizeResourceHandlerRegistration(
				ResourceHandlerRegistration registration) {
			if (this.resourceHandlerRegistrationCustomizer != null) {
				this.resourceHandlerRegistrationCustomizer.customize(registration);
			}

		}
	}

	@Configuration
	@Import(WebFluxConfig.class)
	public static class WebHttpHandlerConfiguration implements ApplicationContextAware {

		private ApplicationContext applicationContext;

		@Override
		public void setApplicationContext(ApplicationContext applicationContext)
				throws BeansException {
			this.applicationContext = applicationContext;
		}

		@Bean
		public HttpHandler httpHandler() {
			return WebHttpHandlerBuilder.applicationContext(this.applicationContext).build();
		}
	}

	@Configuration
	@ConditionalOnEnabledResourceChain
	static class ResourceChainCustomizerConfiguration {

		@Bean
		public ResourceChainResourceHandlerRegistrationCustomizer resourceHandlerRegistrationCustomizer() {
			return new ResourceChainResourceHandlerRegistrationCustomizer();
		}
	}

	interface ResourceHandlerRegistrationCustomizer {

		void customize(ResourceHandlerRegistration registration);

	}

	private static class ResourceChainResourceHandlerRegistrationCustomizer
			implements ResourceHandlerRegistrationCustomizer {

		@Autowired
		private ResourceProperties resourceProperties = new ResourceProperties();

		@Override
		public void customize(ResourceHandlerRegistration registration) {
			ResourceProperties.Chain properties = this.resourceProperties.getChain();
			configureResourceChain(properties,
					registration.resourceChain(properties.isCache()));
		}

		private void configureResourceChain(ResourceProperties.Chain properties,
				ResourceChainRegistration chain) {
			ResourceProperties.Strategy strategy = properties.getStrategy();
			if (strategy.getFixed().isEnabled() || strategy.getContent().isEnabled()) {
				chain.addResolver(getVersionResourceResolver(strategy));
			}
			if (properties.isGzipped()) {
				chain.addResolver(new GzipResourceResolver());
			}
			if (properties.isHtmlApplicationCache()) {
				chain.addTransformer(new AppCacheManifestTransformer());
			}
		}

		private ResourceResolver getVersionResourceResolver(
				ResourceProperties.Strategy properties) {
			VersionResourceResolver resolver = new VersionResourceResolver();
			if (properties.getFixed().isEnabled()) {
				String version = properties.getFixed().getVersion();
				String[] paths = properties.getFixed().getPaths();
				resolver.addFixedVersionStrategy(version, paths);
			}
			if (properties.getContent().isEnabled()) {
				String[] paths = properties.getContent().getPaths();
				resolver.addContentVersionStrategy(paths);
			}
			return resolver;
		}

	}
}
