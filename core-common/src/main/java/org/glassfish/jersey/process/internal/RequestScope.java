/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010-2012 Oracle and/or its affiliates. All rights reserved.
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
package org.glassfish.jersey.process.internal;

import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.hk2.api.ActiveDescriptor;
import org.glassfish.hk2.api.Context;
import org.glassfish.hk2.api.ServiceHandle;
import org.glassfish.hk2.utilities.AbstractActiveDescriptor;
import org.glassfish.hk2.utilities.BuilderHelper;
import org.glassfish.jersey.internal.inject.AbstractModule;
import org.glassfish.jersey.internal.util.ExtendedLogger;
import org.glassfish.jersey.internal.util.LazyUid;

import com.google.common.base.Objects;

import javax.inject.Singleton;

import static com.google.common.base.Preconditions.checkState;

/**
 * Scopes a single request/response processing execution on a single thread.
 * <p>
 * To execute a code inside of the request scope use one of the {@code runInScope(...)}
 * methods and supply the task encapsulating the code that should be executed in the scope.
 * </p>
 * <p>
 * Example:
 * </p>
 * <pre>
 * &#064;Inject
 * RequestScope requestScope;
 *
 * ...
 *
 * requestScope.runInScope(new Runnable() {
 *     &#064;Override
 *     public void run() {
 *          System.out.println("This is execute in the request scope...");
 *     }
 * });
 * </pre>
 * <p>
 * An instance of the request scope can be suspended and retrieved via a call to
 * {@link RequestScope#suspendCurrent} method. This instance can be later
 * used to resume the same request scope and run another task in the same scope:
 * </p>
 * <pre>
 *  Instance requestScopeInstance =
 *      requestScope.runInScope(new Callable&lt;Instance&gt;() {
 *          &#064;Override
 *          public Instance call() {
 *              // This is execute in the new request scope.
 *
 *              // The following call will cause that the
 *              // RequestScope.Instance will not be released
 *              // automatically and we will have to release
 *              // it explicitly at the end.
 *              return requestScope.suspendCurrent();
 *          }
 *      });
 *
 *  requestScope.runInScope(requestScopeInstance, new Runnable() {
 *
 *      &#064;Override
 *      public void run() {
 *          // This is execute in the same request scope as code above.
 *      }
 *  });
 *
 *  // we must release the scope instance explicitly
 *  requestScopeInstance.release();
 * </pre>
 * <p>
 * In the previous example the {@link RequestScope.Instance request scope instance}
 * was suspended and retrieved which also informs {@code requestScope} that it
 * should not automatically release the instance once the running task is finished.
 * The {@code requestScopeInstance} is then used to initialize the next
 * request-scoped execution. The second task will run in the same request scope as the
 * first task. At the end the suspended {@code requestScopeInstance} must be
 * manually {@link RequestScope.Instance#release released}. Not releasing the instance
 * could cause memory leaks. Please note that calling {@link RequestScope#suspendCurrent}
 * does not retrieve an immutable snapshot of the current request scope but
 * a live reference to the internal {@link RequestScope.Instance request scope instance}
 * which may change it's state during each request-scoped task execution for
 * which this scope instance is used.
 * </p>
 *
 * @author Marek Potociar (marek.potociar at oracle.com)
 * @author Miroslav Fuksa (miroslav.fuksa at oracle.com)
 */
@Singleton
public class RequestScope implements Context<RequestScoped> {

    private static final ExtendedLogger logger = new ExtendedLogger(Logger.getLogger(RequestScope.class.getName()), Level.FINEST);

    /**
     * A thread local copy of the current scope instance.
     */
    private ThreadLocal<Instance> currentScopeInstance = new ThreadLocal<Instance>();

    @Override
    public Class<? extends Annotation> getScope() {
        return RequestScoped.class;
    }

    @Override
    public <U> U findOrCreate(ActiveDescriptor<U> activeDescriptor, ServiceHandle<?> root) {
        Instance instance = current();

        U retVal = instance.get(activeDescriptor);
        if (retVal == null) {
            retVal = activeDescriptor.create(root);
            instance.put(activeDescriptor, retVal);
        }
        return retVal;
    }

    @Override
    public boolean containsKey(ActiveDescriptor<?> descriptor) {
        Instance instance = current();
        return instance.contains(descriptor);
    }

    @Override
    public boolean supportsNullCreation() {
        return true;
    }

    public <U> U find(ActiveDescriptor<U> activeDescriptor) {
        Instance instance = current();

        return instance.get(activeDescriptor);
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public void shutdown() {
        currentScopeInstance = null;
    }

    public static class Module extends AbstractModule {

        @Override
        protected void configure() {
            AbstractActiveDescriptor descriptor = BuilderHelper.createConstantDescriptor(new RequestScope());
            descriptor.addContractType(RequestScope.class);
            descriptor.setScope(Singleton.class.getName());
            bind(descriptor);
        }
    }

    public Instance current() {
        Instance scopeInstance = currentScopeInstance.get();
        checkState(scopeInstance != null, "Not inside a request scope.");
        return scopeInstance;
    }

    /**
     * Get the current {@link RequestScope.Instance request scope instance}
     * and mark it as suspended. This call prevents automatic
     * {@link RequestScope.Instance#release() release} of the scope instance
     * once the task that runs in the scope has finished.
     * <p>
     * The returned scope instance may be used to run additional task(s) in the
     * same request scope using one of the {@code #runInScope(Instance, ...)} methods.
     * </p>
     * <p>
     * Note that the returned instance must be {@link RequestScope.Instance#release()
     * released} manually once not needed anymore to prevent memory leaks.
     * </p>
     *
     * @return currently active {@link RequestScope.Instance request scope instance}
     *         that was suspended or {@code null} if the thread is not currently running
     *         in an active request scope.
     */
    public Instance suspendCurrent() {
        final Instance scopeInstance = currentScopeInstance.get();
        if (scopeInstance == null) {
            return null;
        }
        try {
            return scopeInstance.getReference();
        } finally {
            logger.debugLog("Returned a new reference of the request scope instance {0}", scopeInstance);
        }
    }

    /**
     * Creates a new instance of the {@link RequestScope.Instance request scope instance}.
     * This instance can be then used to run task in the request scope. Returned instance
     * is suspended by default and must therefore be closed explicitly as it is shown in
     * the following example:
     * <pre>
     * Instance instance = requestScope.createInstance();
     * requestScope.runInScope(instance, someRunnableTask);
     * instance.release();
     * </pre>
     *
     * @return New suspended request scope instance.
     */
    public Instance createInstance() {
        return new Instance();
    }

    /**
     * This interface extends {@link Callable} interface but removes the
     * exception from {@code call} declaration.
     * <p>
     * This convenience interface may be used in places where a task
     * producing a result needs to be executed in the request scope but no
     * {@link Exception checked exceptions} are thrown during the task
     * execution.
     * </p>
     *
     * @param <T> type of the produced result.
     */
    public interface Producer<T> extends Callable<T> {
        @Override
        T call();
    }

    /**
     * Runs the {@link Runnable task} in the request scope initialized from the
     * {@link RequestScope.Instance scope instance}. The {@link RequestScope.Instance
     * scope instance} is NOT released by the method (this must be done explicitly). The
     * current thread might be already in any request scope and in that case the scope
     * will be changed to the scope defined by the {@link RequestScope.Instance scope
     * instance}. At the end of the method the request scope is returned to its original
     * state.
     *
     * @param scopeInstance The request scope instance from which the request scope will
     *                      be initialized.
     * @param task          Task to be executed.
     */
    public void runInScope(Instance scopeInstance, Runnable task) {
        Instance oldInstance = currentScopeInstance.get();
        try {
            currentScopeInstance.set(scopeInstance.getReference());
            task.run();
        } finally {
            currentScopeInstance.set(oldInstance);
            scopeInstance.release();
        }
    }

    /**
     * Runs the {@link Runnable task} in the new request scope. The current thread might
     * be already in any request scope and in that case the scope will be changed to the
     * scope defined by the {@link RequestScope.Instance scope instance}. At the end of
     * the method the request scope is returned to its original state. The newly created
     * {@link RequestScope.Instance scope instance} will be implicitly released at the end
     * of the method call except the task will call
     * {@link RequestScope#suspendCurrent}.
     *
     * @param task Task to be executed.
     */
    public void runInScope(Runnable task) {
        Instance oldInstance = currentScopeInstance.get();
        Instance instance = createInstance();
        try {
            currentScopeInstance.set(instance);
            task.run();
        } finally {
            currentScopeInstance.set(oldInstance);
            instance.release();
        }
    }

    /**
     * Runs the {@link Callable task} in the request scope initialized from the
     * {@link RequestScope.Instance scope instance}. The {@link RequestScope.Instance
     * scope instance} is NOT released by the method (this must be done explicitly). The
     * current thread might be already in any request scope and in that case the scope
     * will be changed to the scope defined by the {@link RequestScope.Instance scope
     * instance}. At the end of the method the request scope is returned to its original
     * state.
     *
     * @param scopeInstance The request scope instance from which the request scope will
     *                      be initialized.
     * @param task          Task to be executed.
     * @param <T>           {@code task} result type.
     * @return result returned by the {@code task}.
     * @throws Exception Exception thrown by the {@code task}.
     */
    public <T> T runInScope(Instance scopeInstance, Callable<T> task) throws Exception {
        Instance oldInstance = currentScopeInstance.get();
        try {
            currentScopeInstance.set(scopeInstance.getReference());
            return task.call();
        } finally {
            currentScopeInstance.set(oldInstance);
            scopeInstance.release();
        }
    }

    /**
     * Runs the {@link Callable task} in the new request scope. The current thread might
     * be already in any request scope and in that case the scope will be changed to the
     * scope defined by the {@link RequestScope.Instance scope instance}. At the end of
     * the method the request scope is returned to its original state. The newly created
     * {@link RequestScope.Instance scope instance} will be implicitly released at the end
     * of the method call except the task will call
     * {@link RequestScope#suspendCurrent}.
     *
     * @param task Task to be executed.
     * @param <T>  {@code task} result type.
     * @return result returned by the {@code task}.
     * @throws Exception Exception thrown by the {@code task}.
     */
    public <T> T runInScope(Callable<T> task) throws Exception {
        Instance oldInstance = currentScopeInstance.get();
        Instance instance = createInstance();
        try {
            currentScopeInstance.set(instance);
            return task.call();
        } finally {
            currentScopeInstance.set(oldInstance);
            instance.release();
        }
    }

    /**
     * Runs the {@link RequestScope.Producer task} in the request scope initialized
     * from the {@link RequestScope.Instance scope instance}.
     * The {@link RequestScope.Instance scope instance} is NOT released by the method (this
     * must be done explicitly). The current thread might be already in any request scope
     * and in that case the scope will be changed to the scope defined by the
     * {@link RequestScope.Instance scope instance}. At the end of the method the request
     * scope is returned to its original state.
     *
     * @param scopeInstance The request scope instance from which the request scope will
     *                      be initialized.
     * @param task          Task to be executed.
     * @param <T>           {@code task} result type.
     * @return result returned by the {@code task}
     */
    public <T> T runInScope(Instance scopeInstance, Producer<T> task) {
        Instance oldInstance = currentScopeInstance.get();
        try {
            currentScopeInstance.set(scopeInstance.getReference());
            return task.call();
        } finally {
            currentScopeInstance.set(oldInstance);
            scopeInstance.release();
        }
    }

    /**
     * Runs the {@link RequestScope.Producer task} in the new request scope. The
     * current thread might be already in any request scope and in that case the scope
     * will be changed to the scope defined by the {@link RequestScope.Instance scope
     * instance}. At the end of the method the request scope is returned to its original
     * state. The newly created {@link RequestScope.Instance scope instance} will be
     * implicitly released at the end of the method call except the task will call
     * {@link RequestScope#suspendCurrent}.
     *
     * @param task Task to be executed.
     * @param <T>  {@code task} result type.
     * @return result returned by the {@code task}.
     */
    public <T> T runInScope(Producer<T> task) {
        Instance oldInstance = currentScopeInstance.get();
        Instance instance = createInstance();
        try {
            currentScopeInstance.set(instance);
            return task.call();
        } finally {
            currentScopeInstance.set(oldInstance);
            instance.release();
        }
    }

    /**
     * Implementation of the request scope instance.
     */
    public static final class Instance {
        /*
         * Scope instance UUID.
         *
         * For performance reasons, it's only generated if toString() method is invoked,
         * e.g. as part of some low-level logging.
         */

        private final LazyUid id = new LazyUid();
        /**
         * A map of injectable instances in this scope.
         */
        private final Map<ActiveDescriptor<?>, Object> store;
        /**
         * Holds the number of snapshots of this scope.
         */
        private final AtomicInteger referenceCounter;

        private Instance() {
            this.store = new HashMap<ActiveDescriptor<?>, Object>();
            this.referenceCounter = new AtomicInteger(1);
        }

        private Instance getReference() {
            // TODO: replace counter with a phantom reference + reference queue-based solution
            referenceCounter.incrementAndGet();
            return this;
        }

        @SuppressWarnings("unchecked")
        public <T> T get(ActiveDescriptor<T> inhabitant) {
            return (T) store.get(inhabitant);
        }

        @SuppressWarnings("unchecked")
        public <T> T put(ActiveDescriptor<T> descriptor, T value) {
            checkState(!store.containsKey(descriptor), "An instance for the descriptor %s was "
                    + "already seeded in this scope. Old instance: %s New instance: %s", descriptor, store.get(descriptor), value);

            return (T) store.put(descriptor, value);
        }

        public <T> boolean contains(ActiveDescriptor<T> provider) {
            return store.containsKey(provider);
        }

        public void release() {
            if (referenceCounter.decrementAndGet() < 1) {
                try {
                    store.clear();
                } finally {
                    logger.debugLog("Released scope instance {0}", this);
                }
            }
        }

        @Override
        public String toString() {
            return Objects.toStringHelper(this).add("id", id.value()).add("referenceCounter", referenceCounter.get())
                    .add("store size", store.size()).toString();
        }
    }
}
