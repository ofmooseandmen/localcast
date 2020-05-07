/*
Copyright 2018 - 2020 Cedric Liegeois

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

import static io.omam.halo.HaloProperties.RESOLUTION_TIMEOUT;
import static io.omam.halo.HaloProperties.TTL;

import java.io.IOException;
import java.net.NetworkInterface;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;

/**
 * A multicast DNS Service Discovery, supporting {@link Service named service} registration, resolution and
 * browsing.
 * <p>
 * <strong>Registration</strong>
 *
 * <pre>
 * <code>
 * try (final Halo halo = Halo.allNetworkInterfaces(Clock.systemDefaultZone())) {
 *     // allowing service instance name to be changed and with a default TTL of 1 hour.
 *     Service service = halo.register(Service.create("Foo Bar", "_http._udp.", (short) 8009).get());
 *     // registered service is returned.
 *     System.err.println(service);
 *
 *     // registering again the service instance and registration type will return a service
 *     // with an instance name of "Foo Bar (2)".
 *     service = halo.register(Service.create("Foo Bar", "_http._udp.", (short) 8010).get());
 *     System.err.println(service);
 *
 *     // not allowing service instance name to be changed will throw an IOException at this point.
 *     halo.register(Service.create("Foo Bar", "_http._udp.", (short) 8011).get(), false);
 * }
 * </code>
 * </pre>
 *
 * <p>
 * <strong>Resolution</strong>
 *
 * <pre>
 * <code>
 * try (final Halo halo = Halo.allNetworkInterfaces(Clock.systemDefaultZone())) {
 *     // default timeout of 6 seconds.
 *     Optional&lt;Service&gt; service = halo.resolve("Foo Bar", "_http._udp.");
 *     // Optional contains the service if it could be resolved, empty otherwise.
 *     System.err.println(service);
 *
 *     // user defined timeout.
 *     service = halo.resolve("Foo Bar", "_http._udp.", Duration.ofSeconds(1));
 *     System.err.println(service);
 * }
 * </code>
 * </pre>
 *
 * <p>
 * <strong>Browsing</strong>
 *
 * <pre>
 * <code>
 * // browse registration types.
 * try (final Halo halo = Halo.allNetworkInterfaces(Clock.systemDefaultZone())) {
 *     final Browser browser = halo.browse(System.err::printLn);
 *
 *     // Wait for some services to be registered on the network...
 *     Thread.sleep(5000);
 *
 *     browser.close();
 * }
 *
 * // browse services for a given registration type.
 * try (final Halo halo = Halo.allNetworkInterfaces(Clock.systemDefaultZone())) {
 *     final ServiceBrowserListener l = new ServiceBrowserListener() {
 *
 *         &#64;Override
 *         public final void serviceDown(final Service service) {
 *             System.err.println(service + " is down!!!!!");
 *         }
 *
 *         &#64;Override
 *         public final void serviceUp(final Service service) {
 *             System.err.println(service + " is up!!!!!");
 *         }
 *     };
 *
 *     final Browser browser = halo.browse("_http._udp.", l);
 *
 *     // Wait for some services to be registered on the network...
 *     Thread.sleep(5000);
 *
 *     browser.close();
 *
 * }
 * </code>
 * </pre>
 *
 * <p id="configuration">
 * <strong>Configuration</strong>: see {@link HaloProperties}.
 */
public interface Halo extends AutoCloseable {

    /**
     * Returns a new {@link Halo} instance sending/receiving mDNS messages on all network interfaces on this
     * machine.
     * <p>
     * The {@link NetworkInterface#isLoopback() loopback interface} is used only if no other network interface is
     * {@link NetworkInterface#isUp() up}.
     *
     * @param clock the clock providing access to the current instant for a time zone
     * @return a new {@link Halo}
     * @throws IOException in case of I/O error
     */
    public static Halo allNetworkInterfaces(final Clock clock) throws IOException {
        return new HaloImpl(clock, Collections.emptyList());
    }

    /**
     * Returns a new {@link Halo} instance sending/receiving mDNS messages on the given network interface(s) on
     * this machine.
     * <p>
     * The {@link NetworkInterface#isLoopback() loopback interface} is used only if no other network interface is
     * {@link NetworkInterface#isUp() up}.
     *
     * @param clock the clock providing access to the current instant for a time zone
     * @param nic network interface
     * @param nics other network interface
     * @return a new {@link Halo}
     * @throws IOException in case of I/O error
     */
    public static Halo networkInterfaces(final Clock clock, final NetworkInterface nic,
            final NetworkInterface... nics) throws IOException {
        final Collection<NetworkInterface> allNics = new ArrayList<>();
        allNics.add(nic);
        for (final NetworkInterface n : nics) {
            allNics.add(n);
        }
        return new HaloImpl(clock, allNics);
    }

    /**
     * Browses for registration types on the <strong>local</strong> domain.
     * <p>
     * This method relies on the following <a href="#configuration">properties</a>:
     * <ul>
     * <li>{@code io.omam.halo.querying.delay}
     * <li>{@code io.omam.halo.querying.interval}
     * <li>{@code io.omam.halo.querying.number}
     * </ul>
     *
     * @param listener the listener
     * @return a {@code Browser} to terminate the browsing operation
     */
    Browser browse(final RegistrationTypeBrowserListener listener);

    /**
     * Browses for services of the <strong>local</strong> domain having the given registration type.
     * <p>
     * The given listener is invoked whenever changes in the availability of service having the given registration
     * type are discovered.
     * <p>
     * This method relies on the following <a href="#configuration">properties</a>:
     * <ul>
     * <li>{@code io.omam.halo.resolution.timeout}
     * <li>{@code io.omam.halo.resolution.interval}
     * <li>{@code io.omam.halo.querying.delay}
     * <li>{@code io.omam.halo.querying.interval}
     * <li>{@code io.omam.halo.querying.number}
     * </ul>
     *
     * @param registrationType service type (IANA) and transport protocol (udp or tcp), e.g. {@code _ftp._tcp.} or
     *            {@code _http._udp.}
     * @param listener the listener
     * @return a {@code Browser} to terminate the browsing operation
     */
    Browser browse(final String registrationType, final ServiceBrowserListener listener);

    /**
     * Closes {@code Halo}: {@link #deregisterAll() de-register all services} and closes the datagram channel. Once
     * an instance of {@code Halo} has been closed it is not available for further processing.
     */
    @Override
    void close();

    /**
     * De-registers the given service.
     * <p>
     * This method performs no function, nor does it throw an exception, if the given service was not previously
     * registered or has already been de-registered.
     * <p>
     * This method relies on the following <a href="#configuration">properties</a>:
     * <ul>
     * <li>{@code io.omam.halo.cancellation.interval}
     * <li>{@code io.omam.halo.cancellation.number}
     * <li>{@code io.omam.halo.ttl.expiry}
     * </ul>
     *
     * @param service service to de-register
     * @throws IOException if the service cannot be de-registered for any reason
     */
    void deregister(final RegisteredService service) throws IOException;

    /**
     * De-registers all services.
     * <p>
     * This method relies on the following <a href="#configuration">properties</a>:
     * <ul>
     * <li>{@code io.omam.halo.cancellation.interval}
     * <li>{@code io.omam.halo.cancellation.number}
     * <li>{@code io.omam.halo.ttl.expiry}
     * </ul>
     *
     * @throws IOException if any service cannot be de-registered for any reason
     */
    void deregisterAll() throws IOException;

    /**
     * Registers the given service on the <strong>local</strong> domain with the default TTL.
     * <p>
     * The {@link RegisterableService#instanceName() instance name} of the service will be changed to be unique if
     * possible.
     * <p>
     * This method relies on the following <a href="#configuration">properties</a>:
     * <ul>
     * <li>{@code io.omam.halo.probing.timeout}
     * <li>{@code io.omam.halo.probing.interval}
     * <li>{@code io.omam.halo.probing.number}
     * <li>{@code io.omam.halo.ttl.default}
     * </ul>
     *
     * @see #register(RegisterableService, Duration, boolean)
     * @param service service to register
     * @return the service that was successfully registered (instance name may have been changed)
     * @throws IOException if the service cannot be registered for any reason
     */
    default RegisteredService register(final RegisterableService service) throws IOException {
        return register(service, true);
    }

    /**
     * Registers the given service on the <strong>local</strong> domain with the default TTL.
     * <p>
     * If {@code allowNameChange} is {@code true} the {@link RegisterableService#instanceName() instance name} of
     * the service will be changed to be unique if possible.
     * <p>
     * This method relies on the following <a href="#configuration">properties</a>:
     * <ul>
     * <li>{@code io.omam.halo.probing.timeout}
     * <li>{@code io.omam.halo.probing.interval}
     * <li>{@code io.omam.halo.probing.number}
     * <li>{@code io.omam.halo.ttl.default}
     * </ul>
     *
     * @see #register(RegisterableService, Duration, boolean)
     * @param service service to register
     * @param allowNameChange {@code true} if {@link RegisterableService#instanceName() instance name} can be
     *            changed to be made unique
     * @return the service that was successfully registered (instance name may have been changed)
     * @throws IOException if the service cannot be registered for any reason
     */
    default RegisteredService register(final RegisterableService service, final boolean allowNameChange)
            throws IOException {
        return register(service, TTL, allowNameChange);
    }

    /**
     * Registers the given service on the <strong>local</strong> domain with the given TTL.
     * <p>
     * If {@code allowNameChange} is {@code true} the {@link RegisterableService#instanceName() instance name} of
     * the service will be changed to be unique if possible.
     * <p>
     * This method relies on the following <a href="#configuration">properties</a>:
     * <ul>
     * <li>{@code io.omam.halo.probing.timeout}
     * <li>{@code io.omam.halo.probing.interval}
     * <li>{@code io.omam.halo.probing.number}
     * </ul>
     *
     * @param service service to register
     * @param ttl service time-to-live
     * @param allowNameChange {@code true} if {@link RegisterableService#instanceName() instance name} can be
     *            changed to be made unique
     * @return the service that was successfully registered (instance name may have been changed)
     * @throws IOException if the service cannot be registered for any reason
     */
    RegisteredService register(final RegisterableService service, final Duration ttl,
            final boolean allowNameChange) throws IOException;

    /**
     * Resolves a service of the <strong>local</strong> domain by its instance name and registration type to a
     * target host, port and text record if it exits.
     * <p>
     * This method relies on the following <a href="#configuration">properties</a>:
     * <ul>
     * <li>{@code io.omam.halo.resolution.timeout}
     * <li>{@code io.omam.halo.resolution.interval}
     * </ul>
     *
     * @see #resolve(String, String, Duration)
     * @param instanceName the service instance name, a human-readable string, e.g. {@code Living Room Printer}
     * @param registrationType service type (IANA) and transport protocol (udp or tcp), e.g. {@code _ftp._tcp.} or
     *            {@code _http._udp.}
     * @return the resolved service unless the timeout expired
     */
    default Optional<ResolvedService> resolve(final String instanceName, final String registrationType) {
        return resolve(instanceName, registrationType, RESOLUTION_TIMEOUT);
    }

    /**
     * Resolves a service of the <strong>local</strong> domain by its instance name and registration type to a
     * target host, port and text record if it exits.
     * <p>
     * This method relies on the following <a href="#configuration">properties</a>:
     * <ul>
     * <li>{@code io.omam.halo.resolution.interval}
     * </ul>
     *
     * @param instanceName the service instance name, a human-readable string, e.g. {@code Living Room Printer}
     * @param registrationType service type (IANA) and transport protocol (udp or tcp), e.g. {@code _ftp._tcp.} or
     *            {@code _http._udp.}
     * @param timeout for resolution
     * @return the resolved service unless the timeout expired
     */
    Optional<ResolvedService> resolve(final String instanceName, final String registrationType,
            final Duration timeout);

}
