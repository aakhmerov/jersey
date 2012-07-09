/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.jersey.server.internal.inject;

import org.glassfish.hk2.api.Factory;
import org.glassfish.hk2.api.Injectee;
import org.glassfish.hk2.api.InjectionResolver;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.jersey.internal.inject.ContextInjectionResolver;
import org.glassfish.jersey.internal.inject.Providers;
import org.glassfish.jersey.server.model.Parameter;
import org.glassfish.jersey.server.model.Parameter.Source;
import org.glassfish.jersey.server.spi.internal.ValueFactoryProvider;


import javax.inject.Inject;
import javax.inject.Singleton;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Set;


/**
 * Value factory provider that delegates the injection target lookup to
 * the underlying injection provider (HK2).
 *
 * @author Marek Potociar (marek.potociar at oracle.com)
 */
@Singleton
class DelegatedInjectionValueFactoryProvider implements ValueFactoryProvider {

    private final ContextInjectionResolver resolver;

    @Inject
    public DelegatedInjectionValueFactoryProvider(ServiceLocator services) {
        ContextInjectionResolver result = null;
        for (InjectionResolver r : Providers.getProviders(services, InjectionResolver.class)) {
            if (ContextInjectionResolver.class.isInstance(r)) {
                result = ContextInjectionResolver.class.cast(r);
                break;
            }
        }
        resolver = result;
    }

    @Override
    public Factory<?> getValueFactory(final Parameter parameter) {
        final Source paramSource = parameter.getSource();
        if (paramSource == Parameter.Source.CONTEXT || paramSource == Parameter.Source.UNKNOWN) {
            return new Factory<Object>() {
                @Override
                public Object provide() {
                    return resolver.resolve(getInjectee(parameter), null);
                }

                @Override
                public void dispose(Object instance) {
                    //not used
                }
            };
        }
        return null;
    }

    @Override
    public PriorityType getPriority() {
        return Priority.LOW;
    }

    private static Injectee getInjectee(final Parameter parameter) {
        return new Injectee() {
            @Override
            public Type getRequiredType() {
                return parameter.getType();
            }

            @Override
            public Set<Annotation> getRequiredQualifiers() {
                return Collections.emptySet();
            }

            @Override
            public int getPosition() {
                return 0;
            }

            @Override
            public Class<?> getInjecteeClass() {
                return parameter.getRawType();
            }

            @Override
            public AnnotatedElement getParent() {
                return null;
            }

            @Override
            public boolean isOptional() {
                return false;
            }

            @Override
            public boolean isSelf() {
                return false;
            }
        };
    }
}
