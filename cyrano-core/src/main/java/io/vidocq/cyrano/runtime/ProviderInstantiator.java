/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.cyrano.runtime;

import java.lang.reflect.Method;
import java.util.ServiceLoader;
import java.util.concurrent.Callable;

/**
 * SPI d'instanciation de provider — permet à un adaptateur (ex. {@code cyrano-cdi-vauban})
 * de fournir des instances gérées par CDI au lieu d'une instanciation par réflexion.
 *
 * <p>Spec MP Rest Client 4.0 §4.2.4 : <em>« If using CDI, RestClient implementations must
 * use the BeanManager to obtain providers if they are managed beans. »</em></p>
 *
 * <p>Découverte via {@link ServiceLoader} : un seul {@code ProviderInstantiator} actif
 * (le premier trouvé l'emporte). Si aucun n'est déclaré, {@link #defaultInstantiator()}
 * réfléchit sur le constructeur sans-arg.</p>
 *
 * <p>Le {@link #create(Class)} <strong>doit</strong> retourner {@code null} si la classe
 * n'est pas un bean managé connu — le {@code CyranoClientConfiguration} retombera alors
 * sur l'instanciation réflexive standard.</p>
 *
 * @since 0.1.0 (M4-3)
 */
@FunctionalInterface
public interface ProviderInstantiator {

    /**
     * Tente de créer une instance de {@code componentClass} via le container.
     *
     * @param componentClass classe du provider à instancier (filter, interceptor, mapper...)
     * @return instance gérée, ou {@code null} si le container n'a pas de bean pour cette classe
     */
    Object create(Class<?> componentClass);

    /**
     * Hook optionnel pour entourer une invocation de méthode client (ex. interception CDI).
     * L'implémentation par défaut est transparente.
     */
    default Object aroundInvoke(Object target, Method method, Object[] args, Callable<Object> invocation) throws Exception {
        return invocation.call();
    }

    /**
     * Instantiator « plain Java » — invoque {@link Class#getDeclaredConstructor()}.
     * Utilisé en mode SE pur (sans CDI).
     */
    static ProviderInstantiator defaultInstantiator() {
        return cls -> {
            try {
                var ctor = cls.getDeclaredConstructor();
                ctor.setAccessible(true);
                return ctor.newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("Provider non instanciable : " + cls, e);
            }
        };
    }

    /**
     * Charge l'instantiator courant via {@link ServiceLoader}, ou retourne le default
     * réflexif. Résultat mémoïsable à l'appel ; pas de cache thread-safe imposé ici.
     */
    static ProviderInstantiator current() {
        for (ProviderInstantiator pi : ServiceLoader.load(ProviderInstantiator.class)) {
            return pi;
        }
        return defaultInstantiator();
    }
}

