/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.cyrano.internal;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * Builds the transport SSL setup from the builder's SSL options — MP Rest
 * Client 4.0 §5.6 ({@code sslContext}, {@code trustStore},
 * {@code keyStore}, {@code hostnameVerifier}).
 *
 * <p>The JDK {@link java.net.http.HttpClient} exposes no
 * {@link HostnameVerifier} hook: its hostname check is the TLS endpoint
 * identification performed inside the {@link SSLEngine}. When a custom
 * verifier is configured, endpoint identification is disabled
 * ({@link SSLParameters#setEndpointIdentificationAlgorithm(String)} with an
 * empty algorithm) and the verifier is applied during the handshake by a
 * delegating {@link X509ExtendedTrustManager}: certificate-chain validation
 * first (unchanged), then {@code verifier.verify(peerHost, handshakeSession)}
 * — a {@code false} result fails the handshake, exactly the contract the TCK's
 * {@code SslHostnameVerifierTest} asserts.</p>
 */
final class CyranoSslSupport {

    /** Transport SSL setup: a context, and parameters when they must be overridden. */
    record SslSetup(SSLContext context, SSLParameters parameters) {
    }

    private CyranoSslSupport() {
    }

    /**
     * Returns the SSL setup for the configuration, or {@code null} when no SSL
     * option is set (the transport keeps the JDK defaults).
     */
    static SslSetup build(CyranoClientConfiguration configuration) {
        if (!configuration.hasSslConfiguration()) {
            return null;
        }
        try {
            HostnameVerifier verifier = configuration.getHostnameVerifier();

            SSLContext context;
            if (configuration.getSslContext() != null && verifier == null) {
                // An explicit context is used as-is (TCK SslContextTest).
                context = configuration.getSslContext();
            } else {
                context = buildContext(configuration, verifier);
            }

            SSLParameters parameters = null;
            if (verifier != null) {
                // The delegating trust manager applies the verifier; the
                // built-in endpoint identification would reject the handshake
                // before the verifier could accept it.
                parameters = context.getDefaultSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("");
            }
            return new SslSetup(context, parameters);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Invalid SSL configuration for the rest client", e);
        }
    }

    private static SSLContext buildContext(CyranoClientConfiguration configuration, HostnameVerifier verifier)
            throws GeneralSecurityException {
        KeyManager[] keyManagers = null;
        if (configuration.getKeyStore() != null) {
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            char[] password = configuration.getKeyStorePassword() != null
                    ? configuration.getKeyStorePassword().toCharArray()
                    : new char[0];
            kmf.init(configuration.getKeyStore(), password);
            keyManagers = kmf.getKeyManagers();
        }

        // Trust managers from the configured trust store, or the JDK defaults
        // (cacerts) when only a verifier / key store is set.
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(configuration.getTrustStore());
        TrustManager[] trustManagers = tmf.getTrustManagers();

        if (verifier != null) {
            for (int i = 0; i < trustManagers.length; i++) {
                if (trustManagers[i] instanceof X509ExtendedTrustManager x509) {
                    trustManagers[i] = new HostnameVerifyingTrustManager(x509, verifier);
                }
            }
        }

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers, trustManagers, null);
        return context;
    }

    /**
     * Chain validation delegated untouched, then the user's
     * {@link HostnameVerifier} decides the hostname question against the
     * handshake session of the engine — the only place the JDK client exposes
     * both the peer host and the {@link SSLSession}.
     */
    private static final class HostnameVerifyingTrustManager extends X509ExtendedTrustManager {

        private final X509ExtendedTrustManager delegate;
        private final HostnameVerifier verifier;

        HostnameVerifyingTrustManager(X509ExtendedTrustManager delegate, HostnameVerifier verifier) {
            this.delegate = delegate;
            this.verifier = verifier;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            // Chain-only delegation: the engine-aware variant of the JDK trust
            // manager also performs endpoint identification (the HttpClient
            // forces the HTTPS algorithm on its engines), which would reject
            // the handshake before the user's verifier could accept it. The
            // verifier is the sole authority on the hostname question.
            delegate.checkServerTrusted(chain, authType);
            SSLSession session = engine != null ? engine.getHandshakeSession() : null;
            String host = session != null ? session.getPeerHost() : null;
            if (!verifier.verify(host, session)) {
                throw new CertificateException(
                        "Hostname verification rejected the server certificate for host '" + host + "'");
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
            if (socket instanceof javax.net.ssl.SSLSocket ssl) {
                SSLSession session = ssl.getHandshakeSession();
                String host = session != null ? session.getPeerHost() : null;
                if (!verifier.verify(host, session)) {
                    throw new CertificateException(
                            "Hostname verification rejected the server certificate for host '" + host + "'");
                }
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            delegate.checkClientTrusted(chain, authType, engine);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            delegate.checkClientTrusted(chain, authType, socket);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }
    }
}
