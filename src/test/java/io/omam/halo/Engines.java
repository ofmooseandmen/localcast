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

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

import io.cucumber.java.After;
import io.cucumber.java.en.Given;

/**
 * Steps pertaining to the creation of the JmDNS or Halo engine.
 */
@SuppressWarnings("javadoc")
public final class Engines {

    private JmDNS jmdns;

    private Halo halo;

    /**
     * Constructor.
     */
    public Engines() {
        // empty.
    }

    static Map<String, String> attributes(final ServiceInfo actual) {
        final Map<String, String> atts = new HashMap<>();
        for (final Enumeration<String> keys = actual.getPropertyNames(); keys.hasMoreElements();) {
            final String key = keys.nextElement();
            final String value = actual.getPropertyString(key);
            atts.put(key, value);
        }
        return atts;
    }

    static RegisterableService toHalo(final ServiceDetails sd) throws UnknownHostException {
        /*
         * fake hostname and IP to trigger probing conflict.
         */
        final Optional<String> hostname = sd.hostname();
        final Optional<InetAddress> ip = hostname.isPresent()
                ? Optional.of(InetAddress.getByName("2001:0db8:85a3:0000:0000:8a2e:0370:7334"))
                : Optional.empty();
        final RegisterableService.Builder s = RegisterableService
            .create(sd.instanceName(), sd.registrationType(), sd.port())
            .attributes(toHalo(sd.text()));
        hostname.ifPresent(h -> s.hostname(h));
        ip.ifPresent(i -> s.ipv6Address((Inet6Address) i));
        return s.get();
    }

    static Attributes toHalo(final String attributeKey) {
        /* for some reason JmDNS returns true if the attribute has no value. */
        return Attributes.create().with(attributeKey, "true", StandardCharsets.UTF_8).get();
    }

    static ServiceInfo toJmdns(final ServiceDetails sd) {
        assertFalse(sd.hostname().isPresent());
        return ServiceInfo
            .create(sd.registrationType() + "local.", sd.instanceName(), sd.port(), 0, 0, toJmdns(sd.text()));
    }

    static Map<String, String> toJmdns(final String attributes) {
        final Map<String, String> atts = new HashMap<>();
        /* for some reason JmDNS returns true if the attribute has no value. */
        atts.put(attributes, "true");
        return atts;
    }

    @After
    public final void after() throws Exception {
        try {
            if (jmdns != null) {
                jmdns.close();
            }
        } finally {
            if (halo != null) {
                halo.close();
            }
        }
    }

    @Given("a {string} instance has been created")
    public final void givenInstanceCreated(final String engine) throws IOException {
        if (engine.equals("Halo")) {
            if (halo != null) {
                throw new AssertionError("Halo already created");
            }
            halo = Halo.allNetworkInterfaces(Clock.systemDefaultZone());
        } else if (engine.equals("JmDNS")) {
            if (jmdns != null) {
                throw new AssertionError("JmDNS already created");
            }
            jmdns = JmDNS.create();
        } else {
            throw new AssertionError("Unsupported engine " + engine);
        }
    }

    final Halo halo() {
        return halo;
    }

    final JmDNS jmdns() {
        return jmdns;
    }

}
