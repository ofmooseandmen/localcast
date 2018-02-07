/*
Copyright 2018 Cedric Liegeois

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.

    * Redistributions in binary form must reproduce the above
      copyright notice, this list of conditions and the following
      disclaimer in the documentation and/or other materials provided
      with the distribution.

    * Neither the name of the copyright holder nor the names of other
      contributors may be used to endorse or promote products derived
      from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/
package io.omam.halo;

import static io.omam.halo.MulticastDns.CLASS_IN;
import static io.omam.halo.MulticastDns.DOMAIN;
import static io.omam.halo.MulticastDns.QUERYING_INTERVAL;
import static io.omam.halo.MulticastDns.TTL;
import static io.omam.halo.MulticastDns.TYPE_PTR;
import static io.omam.halo.ServiceImpl.instanceNameOf;
import static io.omam.halo.ServiceImpl.registrationTypeOf;
import static java.util.stream.Collectors.groupingBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import io.omam.halo.DnsMessage.Builder;

/**
 * Halo service browser.
 * <p>
 * Network (local domain) is queried at regular interval for pointer records of selected registration types and
 * discovered services are then resolved.
 */
final class HaloBrowser {

    /**
     * Task to query the cache and network about service of the browsed registration type.
     */
    @SuppressWarnings("synthetic-access")
    private final class QueryingTask implements Runnable, ResponseListener {

        /**
         * Constructor.
         */
        QueryingTask() {
            // empty.
        }

        @Override
        public final void responseReceived(final DnsMessage response, final HaloHelper haloHelper) {
            LOGGER.fine(() -> "Handling " + response);
            final Instant now = haloHelper.now();
            pointers(response).forEach((rpn, ptr) -> handleResponse(rpn, ptr, now));
        }

        @Override
        public final void run() {
            final Set<String> rpns = listeners.keySet();
            final Builder b = DnsMessage.query();
            for (final String rpn : rpns) {
                final Collection<ServiceImpl> rservices = services.get(rpn).values();
                b.addQuestion(new DnsQuestion(rpn, TYPE_PTR, CLASS_IN));
                final Instant now = halo.now();
                final Optional<Instant> onow = Optional.of(now);
                rservices.forEach(s -> b.addAnswer(
                        new PtrRecord(s.registrationPointerName(), CLASS_IN, TTL, now, s.serviceName()), onow));
            }
            halo.sendMessage(b.get());
        }

        /**
         * Handles answers for the given registration pointer name.
         *
         * @param rpn registration pointer name
         * @param pointers PTR records
         * @param now current instant
         */
        private void handleResponse(final String rpn, final Collection<PtrRecord> pointers, final Instant now) {
            final Map<String, ServiceImpl> rservices = services.get(rpn);
            final Collection<BrowserListener> rlisteners = listeners.get(rpn);
            for (final PtrRecord ptr : pointers) {
                final String serviceName = ptr.target();
                final String skey = serviceName.toLowerCase();
                if (ptr.isExpired(now)) {
                    LOGGER.fine(() -> "Service [" + serviceName + "] is down");
                    final Future<?> f = rFutures.remove(skey);
                    if (f != null) {
                        f.cancel(true);
                    }
                    final ServiceImpl s = rservices.remove(skey);
                    if (s != null) {
                        rlisteners.forEach(l -> l.down(s));
                    }
                } else if (!rservices.containsKey(skey)) {
                    LOGGER.fine(() -> "Discovered [" + serviceName + "]");

                    final Optional<String> instanceName = instanceNameOf(serviceName);
                    final Optional<String> registrationType = registrationTypeOf(serviceName);

                    if (instanceName.isPresent() && registrationType.isPresent()) {
                        final ServiceImpl s = new ServiceImpl(instanceName.get(), registrationType.get());
                        final Future<?> f = res.submit(new ResolvingTask(rpn, s));
                        rFutures.put(s.serviceName().toLowerCase(), f);
                    } else {
                        LOGGER.warning(() -> "Could not decode service name [" + serviceName + "]");
                    }
                }
            }
        }

        /**
         * Extracts all PTR records related to browsed service types.
         *
         * @param response DNS response
         * @return map of PTR records indexed by browsed registration pointer name in lower case
         */
        private Map<String, List<PtrRecord>> pointers(final DnsMessage response) {
            final Set<String> rpns = listeners.keySet();
            return response
                .answers()
                .stream()
                .filter(r -> r.type() == TYPE_PTR && rpns.contains(r.name().toLowerCase()))
                .map(r -> (PtrRecord) r)
                .collect(groupingBy(r -> r.name().toLowerCase()));
        }

    }

    /**
     * Task to resolve services that have been discovered during query.
     */
    private final class ResolvingTask implements Runnable {

        /** registration pointer name of the service being resolved. */
        private final String rpn;

        /** service to resolve. */
        private final ServiceImpl s;

        /**
         * Constructor.
         *
         * @param registrationPointerName registration pointer name of the service being resolved
         * @param service service to resolve
         */
        ResolvingTask(final String registrationPointerName, final ServiceImpl service) {
            rpn = registrationPointerName;
            s = service;
        }

        @SuppressWarnings("synthetic-access")
        @Override
        public final void run() {
            final boolean resolved = s.resolve(halo, Duration.ofSeconds(6));
            final String skey = s.serviceName().toLowerCase();
            rFutures.remove(skey);
            if (resolved) {
                LOGGER.fine(() -> "Resolved " + s);
                services.get(rpn).put(skey, s);
                final Collection<BrowserListener> rlisteners = listeners.get(rpn);
                rlisteners.forEach(l -> l.up(s));
            } else {
                LOGGER.warning(() -> "Could not resolve " + s);
            }
        }

    }

    /** logger. */
    private static final Logger LOGGER = Logger.getLogger(HaloBrowser.class.getName());

    /** halo helper. */
    private final HaloHelper halo;

    /** listeners, indexed by registration pointer name. */
    private final Map<String, Collection<BrowserListener>> listeners;

    /**
     * resolved services, indexed by registration pointer name, indexed by service name.
     */
    private final Map<String, Map<String, ServiceImpl>> services;

    /** querying task. */
    private final QueryingTask qt;

    /** discoverer executor service. */
    private final ScheduledExecutorService qes;

    /** resolver executor service. */
    private final ExecutorService res;

    /** future representing the querying task. */
    private Future<?> qFuture;

    /** futures representing resolving task indexed by service name in lower case. */
    private final Map<String, Future<?>> rFutures;

    /**
     * Constructor.
     *
     * @param haloHelper halo helper
     */
    HaloBrowser(final HaloHelper haloHelper) {
        halo = haloHelper;
        listeners = new ConcurrentHashMap<>();
        services = new ConcurrentHashMap<>();
        qt = new QueryingTask();
        qes = Executors.newSingleThreadScheduledExecutor(new HaloThreadFactory("discoverer"));
        res = Executors.newCachedThreadPool(new HaloThreadFactory("resolver"));
        qFuture = null;
        rFutures = new ConcurrentHashMap<>();
    }

    /**
     * Returns the registration pointer name for the given registration type.
     *
     * @param registrationType registration type
     * @return registration pointer name (lower case)
     */
    private static String toRpn(final String registrationType) {
        return (registrationType + DOMAIN + ".").toLowerCase();
    }

    /**
     * Adds the given listener for the given service registration type.
     * <p>
     * Services already discovered and resolved are notified to the listener.
     *
     * @param registrationType service registration type
     * @param listener listener
     */
    final void addListener(final String registrationType, final BrowserListener listener) {
        Objects.requireNonNull(registrationType);
        Objects.requireNonNull(listener);
        final String rpn = toRpn(registrationType);
        final Collection<BrowserListener> rls = listeners.computeIfAbsent(rpn, k -> new CopyOnWriteArrayList<>());
        final Map<String, ServiceImpl> rs = services.computeIfAbsent(rpn, k -> new ConcurrentHashMap<>());
        rs.values().forEach(listener::up);
        rls.add(listener);
    }

    /**
     * Removes the given listener for the given registration type.
     *
     * @param registrationType service registration type
     * @param listener listener
     */
    final void removeListener(final String registrationType, final BrowserListener listener) {
        Objects.requireNonNull(registrationType);
        Objects.requireNonNull(listener);
        final String rpn = toRpn(registrationType);
        final Collection<BrowserListener> rls = listeners.get(rpn);
        if (rls != null && rls.size() > 1) {
            rls.remove(listener);
        } else if (rls != null && rls.size() == 1) {
            listeners.remove(rpn);
        } else {
            LOGGER.warning(() -> registrationType + " is not being browsed.");
        }
    }

    /**
     * Starts browsing for services.
     * <p>
     * If this browser is already started this method has no effect.
     */
    final void start() {
        if (qFuture == null) {
            halo.addResponseListener(qt);
            qFuture = qes.scheduleAtFixedRate(qt, QUERYING_INTERVAL.toMillis(), QUERYING_INTERVAL.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Stops browsing for services.
     */
    final void stop() {
        halo.removeResponseListener(qt);
        if (qFuture != null) {
            qFuture.cancel(true);
        }
        qes.shutdownNow();
        res.shutdownNow();
    }

}
