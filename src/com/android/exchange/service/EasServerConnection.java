package com.android.exchange.service;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Base64;

import com.android.emailcommon.Device;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.utility.EmailClientConnectionManager;
import com.android.exchange.Eas;
import com.android.exchange.EasResponse;

import org.apache.http.HttpEntity;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.conn.params.ConnManagerPNames;
import org.apache.http.conn.params.ConnPerRoute;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;

import java.io.IOException;
import java.net.URI;

/**
 * Base class for communicating with an EAS server. Anything that needs to send messages to the
 * server can subclass this to get access to the {@link #sendHttpClientPost} family of functions.
 */
public abstract class EasServerConnection {
    /**
     * Timeout for establishing a connection to the server.
     */
    private static final long CONNECTION_TIMEOUT = 20 * DateUtils.SECOND_IN_MILLIS;

    /**
     * Timeout for http requests after the connection has been established.
     */
    protected static final long COMMAND_TIMEOUT = 30 * DateUtils.SECOND_IN_MILLIS;

    private static final String DEVICE_TYPE = "Android";
    private static final String USER_AGENT = DEVICE_TYPE + '/' + Build.VERSION.RELEASE + '-' +
        Eas.CLIENT_VERSION;


    private static final ConnPerRoute sConnPerRoute = new ConnPerRoute() {
        @Override
        public int getMaxForRoute(final HttpRoute route) {
            return 8;
        }
    };

    protected final Context mContext;

    // Bookkeeping for interrupting a POST. This is primarily for use by Ping (there's currently
    // no mechanism for stopping a sync).
    // Access to these variables should be synchronized on this.
    private HttpPost mPendingPost = null;
    private boolean mStopped = false;

    protected EasServerConnection(final Context context) {
        mContext = context;
    }

    // TODO: Don't make a new one each time.
    private EmailClientConnectionManager getClientConnectionManager(final HostAuth hostAuth) {
        final HttpParams params = new BasicHttpParams();
        params.setIntParameter(ConnManagerPNames.MAX_TOTAL_CONNECTIONS, 25);
        params.setParameter(ConnManagerPNames.MAX_CONNECTIONS_PER_ROUTE, sConnPerRoute);
        final boolean ssl = hostAuth.shouldUseSsl();
        final int port = hostAuth.mPort;
        return EmailClientConnectionManager.newInstance(mContext, params, hostAuth);
    }

    private HttpClient getHttpClient(final EmailClientConnectionManager connectionManager,
            final long timeout) {
        final HttpParams params = new BasicHttpParams();
        HttpConnectionParams.setConnectionTimeout(params, (int)(CONNECTION_TIMEOUT));
        HttpConnectionParams.setSoTimeout(params, (int)(timeout));
        HttpConnectionParams.setSocketBufferSize(params, 8192);
        return new DefaultHttpClient(connectionManager, params);
    }

    private String makeAuthString(final HostAuth hostAuth) {
        final String cs = hostAuth.mLogin + ":" + hostAuth.mPassword;
        return "Basic " + Base64.encodeToString(cs.getBytes(), Base64.NO_WRAP);
    }

    private String makeUserString(final HostAuth hostAuth) {
        String deviceId = "";
        try {
            deviceId = Device.getDeviceId(mContext);
        } catch (final IOException e) {
            // TODO: Make Device.getDeviceId not throw IOException, if possible.
            // Otherwise use a better deviceId default.
            deviceId = "0";
        }
        return "&User=" + Uri.encode(hostAuth.mLogin) + "&DeviceId=" +
                deviceId + "&DeviceType=" + DEVICE_TYPE;
    }

    private String makeBaseUriString(final HostAuth hostAuth) {
        return EmailClientConnectionManager.makeScheme(hostAuth.shouldUseSsl(),
                hostAuth.shouldTrustAllServerCerts(), hostAuth.mClientCertAlias) +
                "://" + hostAuth.mAddress + "/Microsoft-Server-ActiveSync";
    }

    private String makeUriString(final HostAuth hostAuth, final String cmd, final String extra) {
        String uriString = makeBaseUriString(hostAuth);
        if (cmd != null) {
            uriString += "?Cmd=" + cmd + makeUserString(hostAuth);
        }
        if (extra != null) {
            uriString += extra;
        }
        return uriString;
    }

    /**
     * Get the protocol version for an account, or a default if we can't determine it.
     * @param account The account whose protocol version we want to get.
     * @return The protocol version for account, as a String.
     */
    protected String getProtocolVersion(final Account account) {
        if (account != null && account.mProtocolVersion != null) {
            return account.mProtocolVersion;
        }
        return Eas.DEFAULT_PROTOCOL_VERSION;
    }

    /**
     * Set standard HTTP headers, using a policy key if required
     * @param account The Account for which we are communicating.
     * @param hostAuth the HostAuth for account.
     * @param method the method we are going to send
     * @param usePolicyKey whether or not a policy key should be sent in the headers
     */
    private void setHeaders(final Account account, final HostAuth hostAuth,
            final HttpRequestBase method, final boolean usePolicyKey) {
        method.setHeader("Authorization", makeAuthString(hostAuth));
        method.setHeader("MS-ASProtocolVersion", getProtocolVersion(account));
        method.setHeader("User-Agent", USER_AGENT);
        method.setHeader("Accept-Encoding", "gzip");
        if (usePolicyKey) {
            // If there's an account in existence, use its key; otherwise (we're creating the
            // account), send "0".  The server will respond with code 449 if there are policies
            // to be enforced
            String key = "0";
            if (account != null) {
                final String accountKey = account.mSecuritySyncKey;
                if (!TextUtils.isEmpty(accountKey)) {
                    key = accountKey;
                }
            }
            method.setHeader("X-MS-PolicyKey", key);
        }
    }

    /**
     * Send a POST request to the server.
     * @param account The {@link Account} for which we're sending the POST.
     * @param hostAuth The {@link HostAuth} for account.
     * @param cmd The command we're sending to the server.
     * @param entity The {@link HttpEntity} containing the payload of the message.
     * @param timeout The timeout for this POST.
     * @return The response from the Exchange server.
     * @throws IOException
     */
    protected EasResponse sendHttpClientPost(final Account account, final HostAuth hostAuth,
            String cmd, final HttpEntity entity, final long timeout) throws IOException {
        final EmailClientConnectionManager connectionManager = getClientConnectionManager(hostAuth);
        final HttpClient client = getHttpClient(connectionManager, timeout);
        final boolean isPingCommand = cmd.equals("Ping");

        // Split the mail sending commands
        String extra = null;
        boolean msg = false;
        if (cmd.startsWith("SmartForward&") || cmd.startsWith("SmartReply&")) {
            final int cmdLength = cmd.indexOf('&');
            extra = cmd.substring(cmdLength);
            cmd = cmd.substring(0, cmdLength);
            msg = true;
        } else if (cmd.startsWith("SendMail&")) {
            msg = true;
        }

        final String us = makeUriString(hostAuth, cmd, extra);
        final HttpPost method = new HttpPost(URI.create(us));
        // Send the proper Content-Type header; it's always wbxml except for messages when
        // the EAS protocol version is < 14.0
        // If entity is null (e.g. for attachments), don't set this header
        final String protocolVersion = getProtocolVersion(account);
        final Double protocolVersionDouble = Eas.getProtocolVersionDouble(protocolVersion);
        if (msg && (protocolVersionDouble < Eas.SUPPORTED_PROTOCOL_EX2010_DOUBLE)) {
            method.setHeader("Content-Type", "message/rfc822");
        } else if (entity != null) {
            method.setHeader("Content-Type", "application/vnd.ms-sync.wbxml");
        }
        setHeaders(account, hostAuth, method, !isPingCommand);
        // NOTE
        // The next lines are added at the insistence of $VENDOR, who is seeing inappropriate
        // network activity related to the Ping command on some networks with some servers.
        // This code should be removed when the underlying issue is resolved
        if (isPingCommand) {
            method.setHeader("Connection", "close");
        }
        method.setEntity(entity);

        synchronized (this) {
            if (mStopped) {
                mStopped = false;
                // If this gets stopped after the POST actually starts, it throws an IOException.
                // Therefore if we get stopped here, let's throw the same sort of exception, so
                // callers can just equate IOException with the "this POST got killed for some
                // reason".
                throw new IOException("Sync was stopped before POST");
            }
           mPendingPost = method;
        }
        try {
            return EasResponse.fromHttpRequest(connectionManager, client, method);
        } finally {
            synchronized (this) {
                mPendingPost = null;
            }
        }
    }

    protected EasResponse sendHttpClientPost(final Account account, final HostAuth hostAuth,
            final String cmd, final byte[] bytes, final long timeout) throws IOException {
        return sendHttpClientPost(account, hostAuth, cmd, new ByteArrayEntity(bytes), timeout);
    }

    protected EasResponse sendHttpClientPost(final Account account, final HostAuth hostAuth,
            final String cmd, final byte[] bytes) throws IOException {
        return sendHttpClientPost(account, hostAuth, cmd, bytes, COMMAND_TIMEOUT);
    }

    /**
     * Stop the current request. If we're in the middle of the POST, abort it, otherwise prevent
     * the next POST from happening. This second part is necessary in cases where the stop request
     * happens while we're setting up the POST but before we're actually in it.
     * TODO: We also want to do something reasonable if the stop request comes in after the POST
     * responds.
     */
    public synchronized void stop() {
        if (mPendingPost != null) {
            mPendingPost.abort();
        } else {
            mStopped = true;
        }
    }
}