/**
 * Copyright 2013 Google Inc.
 * Copyright 2014 Andreas Schildbach
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.zetacoin.protocols.payments;

import com.google.zetacoin.core.*;
import com.google.zetacoin.crypto.TrustStoreLoader;
import com.google.zetacoin.crypto.X509Utils;
import com.google.zetacoin.params.MainNetParams;
import com.google.zetacoin.script.ScriptBuilder;
import com.google.zetacoin.uri.BitcoinURI;
import com.google.zetacoin.utils.Threading;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import org.zetacoin.protocols.payments.Protos;

import javax.annotation.Nullable;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * <p>Provides a standard implementation of the Payment Protocol (BIP 0070)</p>
 *
 * <p>A PaymentSession can be initialized from one of the following:</p>
 *
 * <ul>
 * <li>A {@link BitcoinURI} object that conforms to BIP 0072</li>
 * <li>A url where the {@link Protos.PaymentRequest} can be fetched</li>
 * <li>Directly with a {@link Protos.PaymentRequest} object</li>
 * </ul>
 *
 * <p>If initialized with a BitcoinURI or a url, a network request is made for the payment request object and a
 * ListenableFuture is returned that will be notified with the PaymentSession object after it is downloaded.</p>
 *
 * <p>Once the PaymentSession is initialized, typically a wallet application will prompt the user to confirm that the
 * amount and recipient are correct, perform any additional steps, and then construct a list of transactions to pass to
 * the sendPayment method.</p>
 *
 * <p>Call sendPayment with a list of transactions that will be broadcast. A {@link Protos.Payment} message will be sent
 * to the merchant if a payment url is provided in the PaymentRequest. NOTE: sendPayment does NOT broadcast the
 * transactions to the bitcoin network. Instead it returns a ListenableFuture that will be notified when a
 * {@link Protos.PaymentACK} is received from the merchant. Typically a wallet will show the message to the user
 * as a confirmation message that the payment is now "processing" or that an error occurred, and then broadcast the
 * tx itself later if needed.</p>
 *
 * @author Kevin Greene
 * @author Andreas Schildbach
 * @see <a href="https://github.com/bitcoin/bips/blob/master/bip-0070.mediawiki">BIP 0070</a>
 */
public class PaymentSession {
    private static ListeningExecutorService executor = Threading.THREAD_POOL;
    private NetworkParameters params;
    private final TrustStoreLoader trustStoreLoader;
    private Protos.PaymentRequest paymentRequest;
    private Protos.PaymentDetails paymentDetails;
    private BigInteger totalValue = BigInteger.ZERO;

    /**
     * Stores the calculated PKI verification data, or null if none is available.
     * Only valid after the session is created with verifyPki set to true, or verifyPki() is manually called.
     */
    public PkiVerificationData pkiVerificationData;

    /**
     * Returns a future that will be notified with a PaymentSession object after it is fetched using the provided uri.
     * uri is a BIP-72-style BitcoinURI object that specifies where the {@link Protos.PaymentRequest} object may
     * be fetched in the r= parameter.
     * If the payment request object specifies a PKI method, then the system trust store will
     * be used to verify the signature provided by the payment request. An exception is thrown by the future if the
     * signature cannot be verified.
     */
    public static ListenableFuture<PaymentSession> createFromBitcoinUri(final BitcoinURI uri) throws PaymentRequestException {
        return createFromBitcoinUri(uri, true, null);
    }

    /**
     * Returns a future that will be notified with a PaymentSession object after it is fetched using the provided uri.
     * uri is a BIP-72-style BitcoinURI object that specifies where the {@link Protos.PaymentRequest} object may
     * be fetched in the r= parameter.
     * If verifyPki is specified and the payment request object specifies a PKI method, then the system trust store will
     * be used to verify the signature provided by the payment request. An exception is thrown by the future if the
     * signature cannot be verified.
     */
    public static ListenableFuture<PaymentSession> createFromBitcoinUri(final BitcoinURI uri, final boolean verifyPki)
            throws PaymentRequestException {
        return createFromBitcoinUri(uri, verifyPki, null);
    }

    /**
     * Returns a future that will be notified with a PaymentSession object after it is fetched using the provided uri.
     * uri is a BIP-72-style BitcoinURI object that specifies where the {@link Protos.PaymentRequest} object may
     * be fetched in the r= parameter.
     * If verifyPki is specified and the payment request object specifies a PKI method, then the system trust store will
     * be used to verify the signature provided by the payment request. An exception is thrown by the future if the
     * signature cannot be verified.
     * If trustStoreLoader is null, the system default trust store is used.
     */
    public static ListenableFuture<PaymentSession> createFromBitcoinUri(final BitcoinURI uri, final boolean verifyPki, @Nullable final TrustStoreLoader trustStoreLoader)
            throws PaymentRequestException {
        String url = uri.getPaymentRequestUrl();
        if (url == null)
            throw new PaymentRequestException.InvalidPaymentRequestURL("No payment request URL (r= parameter) in BitcoinURI " + uri);
        try {
            return fetchPaymentRequest(new URI(url), verifyPki, trustStoreLoader);
        } catch (URISyntaxException e) {
            throw new PaymentRequestException.InvalidPaymentRequestURL(e);
        }
    }

    /**
     * Returns a future that will be notified with a PaymentSession object after it is fetched using the provided url.
     * url is an address where the {@link Protos.PaymentRequest} object may be fetched.
     * If verifyPki is specified and the payment request object specifies a PKI method, then the system trust store will
     * be used to verify the signature provided by the payment request. An exception is thrown by the future if the
     * signature cannot be verified.
     */
    public static ListenableFuture<PaymentSession> createFromUrl(final String url) throws PaymentRequestException {
        return createFromUrl(url, true, null);
    }

    /**
     * Returns a future that will be notified with a PaymentSession object after it is fetched using the provided url.
     * url is an address where the {@link Protos.PaymentRequest} object may be fetched.
     * If the payment request object specifies a PKI method, then the system trust store will
     * be used to verify the signature provided by the payment request. An exception is thrown by the future if the
     * signature cannot be verified.
     */
    public static ListenableFuture<PaymentSession> createFromUrl(final String url, final boolean verifyPki)
            throws PaymentRequestException {
        return createFromUrl(url, verifyPki, null);
    }

    /**
     * Returns a future that will be notified with a PaymentSession object after it is fetched using the provided url.
     * url is an address where the {@link Protos.PaymentRequest} object may be fetched.
     * If the payment request object specifies a PKI method, then the system trust store will
     * be used to verify the signature provided by the payment request. An exception is thrown by the future if the
     * signature cannot be verified.
     * If trustStoreLoader is null, the system default trust store is used.
     */
    public static ListenableFuture<PaymentSession> createFromUrl(final String url, final boolean verifyPki, @Nullable final TrustStoreLoader trustStoreLoader)
            throws PaymentRequestException {
        if (url == null)
            throw new PaymentRequestException.InvalidPaymentRequestURL("null paymentRequestUrl");
        try {
            return fetchPaymentRequest(new URI(url), verifyPki, trustStoreLoader);
        } catch(URISyntaxException e) {
            throw new PaymentRequestException.InvalidPaymentRequestURL(e);
        }
    }

    private static ListenableFuture<PaymentSession> fetchPaymentRequest(final URI uri, final boolean verifyPki, @Nullable final TrustStoreLoader trustStoreLoader) {
        return executor.submit(new Callable<PaymentSession>() {
            @Override
            public PaymentSession call() throws Exception {
                HttpURLConnection connection = (HttpURLConnection)uri.toURL().openConnection();
                connection.setRequestProperty("Accept", "application/zetacoin-paymentrequest");
                connection.setUseCaches(false);
                Protos.PaymentRequest paymentRequest = Protos.PaymentRequest.parseFrom(connection.getInputStream());
                return new PaymentSession(paymentRequest, verifyPki, trustStoreLoader);
            }
        });
    }

    /**
     * Creates a PaymentSession from the provided {@link Protos.PaymentRequest}.
     * Verifies PKI by default.
     */
    public PaymentSession(Protos.PaymentRequest request) throws PaymentRequestException {
        this(request, true, null);
    }

    /**
     * Creates a PaymentSession from the provided {@link Protos.PaymentRequest}.
     * If verifyPki is true, also validates the signature and throws an exception if it fails.
     */
    public PaymentSession(Protos.PaymentRequest request, boolean verifyPki) throws PaymentRequestException {
        this(request, verifyPki, null);
    }

    /**
     * Creates a PaymentSession from the provided {@link Protos.PaymentRequest}.
     * If verifyPki is true, also validates the signature and throws an exception if it fails.
     * If trustStoreLoader is null, the system default trust store is used.
     */
    public PaymentSession(Protos.PaymentRequest request, boolean verifyPki, @Nullable final TrustStoreLoader trustStoreLoader) throws PaymentRequestException {
        this.trustStoreLoader = trustStoreLoader != null ? trustStoreLoader : new TrustStoreLoader.DefaultTrustStoreLoader();
        parsePaymentRequest(request);
        if (verifyPki)
            verifyPki();
    }

    /**
     * Message returned by the merchant in response to a Payment message.
     */
    public class Ack {
        @Nullable private String memo;

        Ack(@Nullable String memo) {
            this.memo = memo;
        }

        /**
         * Returns the memo included by the merchant in the payment ack. This message is typically displayed to the user
         * as a notification (e.g. "Your payment was received and is being processed"). If none was provided, returns
         * null.
         */
        @Nullable public String getMemo() {
            return memo;
        }
    }

    /**
     * Returns the memo included by the merchant in the payment request, or null if not found.
     */
    @Nullable public String getMemo() {
        if (paymentDetails.hasMemo())
            return paymentDetails.getMemo();
        else
            return null;
    }

    /**
     * Returns the total amount of bitcoins requested.
     */
    public BigInteger getValue() {
        return totalValue;
    }

    /**
     * Returns the date that the payment request was generated.
     */
    public Date getDate() {
        return new Date(paymentDetails.getTime() * 1000);
    }

    /**
     * This should always be called before attempting to call sendPayment.
     */
    public boolean isExpired() {
        return paymentDetails.hasExpires() && System.currentTimeMillis() / 1000L > paymentDetails.getExpires();
    }

    /**
     * Returns the payment url where the Payment message should be sent.
     * Returns null if no payment url was provided in the PaymentRequest.
     */
    public @Nullable String getPaymentUrl() {
        if (paymentDetails.hasPaymentUrl())
            return paymentDetails.getPaymentUrl();
        return null;
    }

    /**
     * Returns a {@link Wallet.SendRequest} suitable for broadcasting to the network.
     */
    public Wallet.SendRequest getSendRequest() {
        Transaction tx = new Transaction(params);
        for (Protos.Output output : paymentDetails.getOutputsList())
            tx.addOutput(new TransactionOutput(params, tx, BigInteger.valueOf(output.getAmount()), output.getScript().toByteArray()));
        return Wallet.SendRequest.forTx(tx);
    }

    /**
     * Generates a Payment message and sends the payment to the merchant who sent the PaymentRequest.
     * Provide transactions built by the wallet.
     * NOTE: This does not broadcast the transactions to the bitcoin network, it merely sends a Payment message to the
     * merchant confirming the payment.
     * Returns an object wrapping PaymentACK once received.
     * If the PaymentRequest did not specify a payment_url, returns null and does nothing.
     * @param txns list of transactions to be included with the Payment message.
     * @param refundAddr will be used by the merchant to send money back if there was a problem.
     * @param memo is a message to include in the payment message sent to the merchant.
     */
    public @Nullable ListenableFuture<Ack> sendPayment(List<Transaction> txns, @Nullable Address refundAddr, @Nullable String memo)
            throws PaymentRequestException, VerificationException, IOException {
        Protos.Payment payment = getPayment(txns, refundAddr, memo);
        if (payment == null)
            return null;
        if (isExpired())
            throw new PaymentRequestException.Expired("PaymentRequest is expired");
        URL url;
        try {
            url = new URL(paymentDetails.getPaymentUrl());
        } catch (MalformedURLException e) {
            throw new PaymentRequestException.InvalidPaymentURL(e);
        }
        return sendPayment(url, payment);
    }

    /**
     * Generates a Payment message based on the information in the PaymentRequest.
     * Provide transactions built by the wallet.
     * If the PaymentRequest did not specify a payment_url, returns null.
     * @param txns list of transactions to be included with the Payment message.
     * @param refundAddr will be used by the merchant to send money back if there was a problem.
     * @param memo is a message to include in the payment message sent to the merchant.
     */
    public @Nullable Protos.Payment getPayment(List<Transaction> txns, @Nullable Address refundAddr, @Nullable String memo)
            throws IOException {
        if (!paymentDetails.hasPaymentUrl())
            return null;
        Protos.Payment.Builder payment = Protos.Payment.newBuilder();
        if (paymentDetails.hasMerchantData())
            payment.setMerchantData(paymentDetails.getMerchantData());
        if (refundAddr != null) {
            Protos.Output.Builder refundOutput = Protos.Output.newBuilder();
            refundOutput.setAmount(totalValue.longValue());
            refundOutput.setScript(ByteString.copyFrom(ScriptBuilder.createOutputScript(refundAddr).getProgram()));
            payment.addRefundTo(refundOutput);
        }
        if (memo != null) {
            payment.setMemo(memo);
        }
        for (Transaction txn : txns) {
            txn.verify();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            txn.bitcoinSerialize(o);
            payment.addTransactions(ByteString.copyFrom(o.toByteArray()));
        }
        return payment.build();
    }

    @VisibleForTesting
    protected ListenableFuture<Ack> sendPayment(final URL url, final Protos.Payment payment) {
        return executor.submit(new Callable<Ack>() {
            @Override
            public Ack call() throws Exception {
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/zetacoin-payment");
                connection.setRequestProperty("Accept", "application/zetacoin-paymentack");
                connection.setRequestProperty("Content-Length", Integer.toString(payment.getSerializedSize()));
                connection.setUseCaches(false);
                connection.setDoInput(true);
                connection.setDoOutput(true);

                // Send request.
                DataOutputStream outStream = new DataOutputStream(connection.getOutputStream());
                payment.writeTo(outStream);
                outStream.flush();
                outStream.close();

                // Get response.
                InputStream inStream = connection.getInputStream();
                Protos.PaymentACK.Builder paymentAckBuilder = Protos.PaymentACK.newBuilder().mergeFrom(inStream);
                Protos.PaymentACK paymentAck = paymentAckBuilder.build();
                String memo = null;
                if (paymentAck.hasMemo())
                    memo = paymentAck.getMemo();
                return new Ack(memo);
            }
        });
    }

    /**
     * Information about the X509 signature's issuer and subject.
     */
    public static class PkiVerificationData {
        /** Display name of the payment requestor, could be a domain name, email address, legal name, etc */
        public final String displayName;
        /** SSL public key that was used to sign. */
        public final PublicKey merchantSigningKey;
        /** Object representing the CA that verified the merchant's ID */
        public final TrustAnchor rootAuthority;
        /** String representing the display name of the CA that verified the merchant's ID */
        public final String rootAuthorityName;

        private PkiVerificationData(@Nullable String displayName, PublicKey merchantSigningKey,
                                    TrustAnchor rootAuthority) throws PaymentRequestException.PkiVerificationException {
            try {
                this.displayName = displayName;
                this.merchantSigningKey = merchantSigningKey;
                this.rootAuthority = rootAuthority;
                this.rootAuthorityName = X509Utils.getDisplayNameFromCertificate(rootAuthority.getTrustedCert(), true);
            } catch (CertificateParsingException x) {
                throw new PaymentRequestException.PkiVerificationException(x);
            }
        }
    }

    /**
     * Uses the provided PKI method to find the corresponding public key and verify the provided signature.
     * Returns null if no PKI method was specified in the {@link Protos.PaymentRequest}.
     */
    public @Nullable PkiVerificationData verifyPki() throws PaymentRequestException {
        List<X509Certificate> certs = null;
        try {
            if (pkiVerificationData != null)
                return pkiVerificationData;
            if (paymentRequest.getPkiType().equals("none"))
                // Nothing to verify. Everything is fine. Move along.
                return null;

            String algorithm;
            if (paymentRequest.getPkiType().equals("x509+sha256"))
                algorithm = "SHA256withRSA";
            else if (paymentRequest.getPkiType().equals("x509+sha1"))
                algorithm = "SHA1withRSA";
            else
                throw new PaymentRequestException.InvalidPkiType("Unsupported PKI type: " + paymentRequest.getPkiType());

            Protos.X509Certificates protoCerts = Protos.X509Certificates.parseFrom(paymentRequest.getPkiData());
            if (protoCerts.getCertificateCount() == 0)
                throw new PaymentRequestException.InvalidPkiData("No certificates provided in message: server config error");

            // Parse the certs and turn into a certificate chain object. Cert factories can parse both DER and base64.
            // The ordering of certificates is defined by the payment protocol spec to be the same as what the Java
            // crypto API requires - convenient!
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            certs = Lists.newArrayList();
            for (ByteString bytes : protoCerts.getCertificateList())
                certs.add((X509Certificate) certificateFactory.generateCertificate(bytes.newInput()));
            CertPath path = certificateFactory.generateCertPath(certs);

            // Retrieves the most-trusted CAs from keystore.
            PKIXParameters params = new PKIXParameters(trustStoreLoader.getKeyStore());
            // Revocation not supported in the current version.
            params.setRevocationEnabled(false);

            // Now verify the certificate chain is correct and trusted. This let's us get an identity linked pubkey.
            CertPathValidator validator = CertPathValidator.getInstance("PKIX");
            PKIXCertPathValidatorResult result = (PKIXCertPathValidatorResult) validator.validate(path, params);
            PublicKey publicKey = result.getPublicKey();
            // OK, we got an identity, now check it was used to sign this message.
            Signature signature = Signature.getInstance(algorithm);
            // Note that we don't use signature.initVerify(certs.get(0)) here despite it being the most obvious
            // way to set it up, because we don't care about the constraints specified on the certificates: any
            // cert that links a key to a domain name or other identity will do for us.
            signature.initVerify(publicKey);
            Protos.PaymentRequest.Builder reqToCheck = paymentRequest.toBuilder();
            reqToCheck.setSignature(ByteString.EMPTY);
            signature.update(reqToCheck.build().toByteArray());
            if (!signature.verify(paymentRequest.getSignature().toByteArray()))
                throw new PaymentRequestException.PkiVerificationException("Invalid signature, this payment request is not valid.");

            // Signature verifies, get the names from the identity we just verified for presentation to the user.
            final X509Certificate cert = certs.get(0);
            String displayName = X509Utils.getDisplayNameFromCertificate(cert, true);
            if (displayName == null)
                throw new PaymentRequestException.PkiVerificationException("Could not extract name from certificate");
            // Everything is peachy. Return some useful data to the caller.
            PkiVerificationData data = new PkiVerificationData(displayName, publicKey, result.getTrustAnchor());
            // Cache the result so we don't have to re-verify if this method is called again.
            pkiVerificationData = data;
            return data;
        } catch (InvalidProtocolBufferException e) {
            // Data structures are malformed.
            throw new PaymentRequestException.InvalidPkiData(e);
        } catch (CertificateException e) {
            // The X.509 certificate data didn't parse correctly.
            throw new PaymentRequestException.PkiVerificationException(e);
        } catch (NoSuchAlgorithmException e) {
            // Should never happen so don't make users have to think about it. PKIX is always present.
            throw new RuntimeException(e);
        } catch (InvalidAlgorithmParameterException e) {
            throw new RuntimeException(e);
        } catch (CertPathValidatorException e) {
            // The certificate chain isn't known or trusted, probably, the server is using an SSL root we don't
            // know about and the user needs to upgrade to a new version of the software (or import a root cert).
            throw new PaymentRequestException.PkiVerificationException(e, certs);
        } catch (InvalidKeyException e) {
            // Shouldn't happen if the certs verified correctly.
            throw new PaymentRequestException.PkiVerificationException(e);
        } catch (SignatureException e) {
            // Something went wrong during hashing (yes, despite the name, this does not mean the sig was invalid).
            throw new PaymentRequestException.PkiVerificationException(e);
        } catch (IOException e) {
            throw new PaymentRequestException.PkiVerificationException(e);
        } catch (KeyStoreException e) {
            throw new RuntimeException(e);
        }
    }

    private void parsePaymentRequest(Protos.PaymentRequest request) throws PaymentRequestException {
        try {
            if (request == null)
                throw new PaymentRequestException("request cannot be null");
            if (request.getPaymentDetailsVersion() != 1)
                throw new PaymentRequestException.InvalidVersion("Version 1 required. Received version " + request.getPaymentDetailsVersion());
            paymentRequest = request;
            if (!request.hasSerializedPaymentDetails())
                throw new PaymentRequestException("No PaymentDetails");
            paymentDetails = Protos.PaymentDetails.newBuilder().mergeFrom(request.getSerializedPaymentDetails()).build();
            if (paymentDetails == null)
                throw new PaymentRequestException("Invalid PaymentDetails");
            if (!paymentDetails.hasNetwork())
                params = MainNetParams.get();
            else
                params = NetworkParameters.fromPmtProtocolID(paymentDetails.getNetwork());
            if (params == null)
                throw new PaymentRequestException.InvalidNetwork("Invalid network " + paymentDetails.getNetwork());
            if (paymentDetails.getOutputsCount() < 1)
                throw new PaymentRequestException.InvalidOutputs("No outputs");
            for (Protos.Output output : paymentDetails.getOutputsList()) {
                if (output.hasAmount())
                    totalValue = totalValue.add(BigInteger.valueOf(output.getAmount()));
            }
            // This won't ever happen in practice. It would only happen if the user provided outputs
            // that are obviously invalid. Still, we don't want to silently overflow.
            if (totalValue.compareTo(NetworkParameters.MAX_MONEY) > 0)
                throw new PaymentRequestException.InvalidOutputs("The outputs are way too big.");
        } catch (InvalidProtocolBufferException e) {
            throw new PaymentRequestException(e);
        }
    }

    /** Returns the protobuf that this object was instantiated with. */
    public Protos.PaymentRequest getPaymentRequest() {
        return paymentRequest;
    }

    /** Returns the protobuf that describes the payment to be made. */
    public Protos.PaymentDetails getPaymentDetails() {
        return paymentDetails;
    }
}
