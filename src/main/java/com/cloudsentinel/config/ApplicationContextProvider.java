package com.cloudsentinel.config;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * Static accessor for the Spring ApplicationContext, used by non-Spring-managed classes
 * (e.g., {@link ReadOnlyInterceptor} which is instantiated as a static field) that need
 * to access Spring beans like {@link BlockedOperationAudit}.
 */
@Component
public class ApplicationContextProvider implements ApplicationContextAware {

    private static ApplicationContext context;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        context = applicationContext;
    }

    public static <T> T getBean(Class<T> beanClass) {
        return context != null ? context.getBean(beanClass) : null;
    }
}
