package com.android.exchange.eas;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Xml;

import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.service.EmailServiceProxy;
import com.android.emailcommon.service.HostAuthCompat;
import com.android.exchange.CommandStatusException;
import com.android.exchange.Eas;
import com.android.exchange.EasResponse;
import com.android.mail.utils.LogUtils;

import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.entity.StringEntity;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class EasAutoDiscover extends EasOperation {

    public final static int RESULT_OK = 1;
    public final static int RESULT_SC_UNAUTHORIZED = RESULT_OP_SPECIFIC_ERROR_RESULT - 0;
    public final static int RESULT_REDIRECT = RESULT_OP_SPECIFIC_ERROR_RESULT - 1;
    public final static int RESULT_BAD_RESPONSE = RESULT_OP_SPECIFIC_ERROR_RESULT - 2;
    public final static int RESULT_FATAL_SERVER_ERROR = RESULT_OP_SPECIFIC_ERROR_RESULT - 3;

    private final static String TAG = LogUtils.TAG;

    private static final String AUTO_DISCOVER_SCHEMA_PREFIX =
            "http://schemas.microsoft.com/exchange/autodiscover/mobilesync/";
    private static final String AUTO_DISCOVER_PAGE = "/autodiscover/autodiscover.xml";

    // Set of string constants for parsing the autodiscover response.
    // TODO: Merge this into Tags.java? It's not quite the same but conceptually belongs there.
    private static final String ELEMENT_NAME_SERVER = "Server";
    private static final String ELEMENT_NAME_TYPE = "Type";
    private static final String ELEMENT_NAME_MOBILE_SYNC = "MobileSync";
    private static final String ELEMENT_NAME_URL = "Url";
    private static final String ELEMENT_NAME_SETTINGS = "Settings";
    private static final String ELEMENT_NAME_ACTION = "Action";
    private static final String ELEMENT_NAME_ERROR = "Error";
    private static final String ELEMENT_NAME_REDIRECT = "Redirect";
    private static final String ELEMENT_NAME_USER = "User";
    private static final String ELEMENT_NAME_EMAIL_ADDRESS = "EMailAddress";
    private static final String ELEMENT_NAME_DISPLAY_NAME = "DisplayName";
    private static final String ELEMENT_NAME_RESPONSE = "Response";
    private static final String ELEMENT_NAME_AUTODISCOVER = "Autodiscover";

    private final String mUri;
    private final String mUsername;
    private final String mPassword;
    private HostAuth mHostAuth;
    private String mRedirectUri;

    public EasAutoDiscover(final Context context, final String uri, final String username,
                           final String password) {
        // We don't actually need an account or a hostAuth, but the EasServerConnection requires
        // one. Just create dummy values.
        super(context, -1);
        mUri = uri;
        mUsername = username;
        mPassword = password;
        mHostAuth = new HostAuth();
        mHostAuth.mLogin = mUsername;
        mHostAuth.mPassword = mPassword;
        mHostAuth.mPort = 443;
        mHostAuth.mProtocol = Eas.PROTOCOL;
        mHostAuth.mFlags = HostAuth.FLAG_SSL | HostAuth.FLAG_AUTHENTICATE;
        setAccount(new Account(), mHostAuth);
    }

    protected String getRequestUri() {
        return mUri;
    }

    public static String getDomain(final String login) {
        final int amp = login.indexOf('@');
        if (amp < 0) {
            return null;
        }
        return login.substring(amp + 1);
    }

    public static String createUri(final String domain) {
        return "https://" + domain + AUTO_DISCOVER_PAGE;
    }

    public static String createAlternateUri(final String domain) {
        return "https://autodiscover." + domain + AUTO_DISCOVER_PAGE;
    }

    @Override
    protected String getCommand() {
        return null;
    }

    @Override
    protected HttpEntity getRequestEntity() throws IOException, MessageInvalidException {
        try {
            final XmlSerializer s = Xml.newSerializer();
            final ByteArrayOutputStream os = new ByteArrayOutputStream(1024);
            s.setOutput(os, "UTF-8");
            s.startDocument("UTF-8", false);
            s.startTag(null, "Autodiscover");
            s.attribute(null, "xmlns", AUTO_DISCOVER_SCHEMA_PREFIX + "requestschema/2006");
            s.startTag(null, "Request");
            s.startTag(null, "EMailAddress").text(mUsername).endTag(null, "EMailAddress");
            s.startTag(null, "AcceptableResponseSchema");
            s.text(AUTO_DISCOVER_SCHEMA_PREFIX + "responseschema/2006");
            s.endTag(null, "AcceptableResponseSchema");
            s.endTag(null, "Request");
            s.endTag(null, "Autodiscover");
            s.endDocument();
            return new StringEntity(os.toString());
        } catch (final IOException e) {
            // For all exception types, we can simply punt on autodiscover.
        } catch (final IllegalArgumentException e) {
        } catch (final IllegalStateException e) {
        }
        return null;
    }

    public String getRedirectUri() {
        return mRedirectUri;
    }

    @Override
    protected int handleResponse(final EasResponse response) throws
            IOException, CommandStatusException {
        // resp is either an authentication error, or a good response.
        final int code = response.getStatus();

        if (response.isRedirectError()) {
            final String loc = response.getRedirectAddress();
            if (loc != null && loc.startsWith("http")) {
                LogUtils.d(TAG, "Posting autodiscover to redirect: " + loc);
                mRedirectUri = loc;
                return RESULT_REDIRECT;
            } else {
                LogUtils.w(TAG, "Invalid redirect %s", loc);
                return RESULT_FATAL_SERVER_ERROR;
            }
        }

        if (code == HttpStatus.SC_UNAUTHORIZED) {
            LogUtils.w(TAG, "Autodiscover received SC_UNAUTHORIZED");
            return RESULT_SC_UNAUTHORIZED;
        } else if (code != HttpStatus.SC_OK) {
            // We'll try the next address if this doesn't work
            LogUtils.d(TAG, "Bad response code when posting autodiscover: %d", code);
            return RESULT_BAD_RESPONSE;
        } else {
            mHostAuth = parseAutodiscover(response);
            if (mHostAuth != null) {
                // Fill in the rest of the HostAuth
                // We use the user name and password that were successful during
                // the autodiscover process
                mHostAuth.mLogin = mUsername;
                mHostAuth.mPassword = mPassword;
                // Note: there is no way we can auto-discover the proper client
                // SSL certificate to use, if one is needed.
                mHostAuth.mPort = 443;
                mHostAuth.mProtocol = Eas.PROTOCOL;
                mHostAuth.mFlags = HostAuth.FLAG_SSL | HostAuth.FLAG_AUTHENTICATE;
                return RESULT_OK;
            } else {
                return RESULT_HARD_DATA_FAILURE;
            }
        }
    }

    public Bundle getResultBundle() {
        final Bundle bundle = new Bundle(2);
        final HostAuthCompat hostAuthCompat = new HostAuthCompat(mHostAuth);
        bundle.putParcelable(EmailServiceProxy.AUTO_DISCOVER_BUNDLE_HOST_AUTH,
                hostAuthCompat);
        bundle.putInt(EmailServiceProxy.AUTO_DISCOVER_BUNDLE_ERROR_CODE,
                RESULT_OK);
        return bundle;
    }

    /**
     * Parse the Server element of the server response.
     * @param parser The {@link XmlPullParser}.
     * @param hostAuth The {@link HostAuth} to populate with the results of parsing.
     * @throws XmlPullParserException
     * @throws IOException
     */
    private static void parseServer(final XmlPullParser parser, final HostAuth hostAuth)
            throws XmlPullParserException, IOException {
        boolean mobileSync = false;
        while (true) {
            final int type = parser.next();
            if (type == XmlPullParser.END_TAG && parser.getName().equals(ELEMENT_NAME_SERVER)) {
                break;
            } else if (type == XmlPullParser.START_TAG) {
                final String name = parser.getName();
                if (name.equals(ELEMENT_NAME_TYPE)) {
                    if (parser.nextText().equals(ELEMENT_NAME_MOBILE_SYNC)) {
                        mobileSync = true;
                    }
                } else if (mobileSync && name.equals(ELEMENT_NAME_URL)) {
                    final String url = parser.nextText();
                    if (url != null) {
                        LogUtils.d(TAG, "Autodiscover URL: %s", url);
                        hostAuth.mAddress = Uri.parse(url).getHost();
                    }
                }
            }
        }
    }

    /**
     * Parse the Settings element of the server response.
     * @param parser The {@link XmlPullParser}.
     * @param hostAuth The {@link HostAuth} to populate with the results of parsing.
     * @throws XmlPullParserException
     * @throws IOException
     */
    private static void parseSettings(final XmlPullParser parser, final HostAuth hostAuth)
            throws XmlPullParserException, IOException {
        while (true) {
            final int type = parser.next();
            if (type == XmlPullParser.END_TAG && parser.getName().equals(ELEMENT_NAME_SETTINGS)) {
                break;
            } else if (type == XmlPullParser.START_TAG) {
                final String name = parser.getName();
                if (name.equals(ELEMENT_NAME_SERVER)) {
                    parseServer(parser, hostAuth);
                }
            }
        }
    }

    /**
     * Parse the Action element of the server response.
     * @param parser The {@link XmlPullParser}.
     * @param hostAuth The {@link HostAuth} to populate with the results of parsing.
     * @throws XmlPullParserException
     * @throws IOException
     */
    private static void parseAction(final XmlPullParser parser, final HostAuth hostAuth)
            throws XmlPullParserException, IOException {
        while (true) {
            final int type = parser.next();
            if (type == XmlPullParser.END_TAG && parser.getName().equals(ELEMENT_NAME_ACTION)) {
                break;
            } else if (type == XmlPullParser.START_TAG) {
                final String name = parser.getName();
                if (name.equals(ELEMENT_NAME_ERROR)) {
                    // Should parse the error
                } else if (name.equals(ELEMENT_NAME_REDIRECT)) {
                    LogUtils.d(TAG, "Redirect: " + parser.nextText());
                } else if (name.equals(ELEMENT_NAME_SETTINGS)) {
                    parseSettings(parser, hostAuth);
                }
            }
        }
    }

    /**
     * Parse the User element of the server response.
     * @param parser The {@link XmlPullParser}.
     * @param hostAuth The {@link HostAuth} to populate with the results of parsing.
     * @throws XmlPullParserException
     * @throws IOException
     */
    private static void parseUser(final XmlPullParser parser, final HostAuth hostAuth)
            throws XmlPullParserException, IOException {
        while (true) {
            int type = parser.next();
            if (type == XmlPullParser.END_TAG && parser.getName().equals(ELEMENT_NAME_USER)) {
                break;
            } else if (type == XmlPullParser.START_TAG) {
                String name = parser.getName();
                if (name.equals(ELEMENT_NAME_EMAIL_ADDRESS)) {
                    final String addr = parser.nextText();
                    LogUtils.d(TAG, "Autodiscover, email: %s", addr);
                } else if (name.equals(ELEMENT_NAME_DISPLAY_NAME)) {
                    final String dn = parser.nextText();
                    LogUtils.d(TAG, "Autodiscover, user: %s", dn);
                }
            }
        }
    }

    /**
     * Parse the Response element of the server response.
     * @param parser The {@link XmlPullParser}.
     * @param hostAuth The {@link HostAuth} to populate with the results of parsing.
     * @throws XmlPullParserException
     * @throws IOException
     */
    private static void parseResponse(final XmlPullParser parser, final HostAuth hostAuth)
            throws XmlPullParserException, IOException {
        while (true) {
            final int type = parser.next();
            if (type == XmlPullParser.END_TAG && parser.getName().equals(ELEMENT_NAME_RESPONSE)) {
                break;
            } else if (type == XmlPullParser.START_TAG) {
                final String name = parser.getName();
                if (name.equals(ELEMENT_NAME_USER)) {
                    parseUser(parser, hostAuth);
                } else if (name.equals(ELEMENT_NAME_ACTION)) {
                    parseAction(parser, hostAuth);
                }
            }
        }
    }

    /**
     * Parse the server response for the final {@link HostAuth}.
     * @param resp The {@link EasResponse} from the server.
     * @return The final {@link HostAuth} for this server.
     */
    private static HostAuth parseAutodiscover(final EasResponse resp) {
        // The response to Autodiscover is regular XML (not WBXML)
        try {
            final XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setInput(resp.getInputStream(), "UTF-8");
            if (parser.getEventType() != XmlPullParser.START_DOCUMENT) {
                return null;
            }
            if (parser.next() != XmlPullParser.START_TAG) {
                return null;
            }
            if (!parser.getName().equals(ELEMENT_NAME_AUTODISCOVER)) {
                return null;
            }

            final HostAuth hostAuth = new HostAuth();
            while (true) {
                final int type = parser.nextTag();
                if (type == XmlPullParser.END_TAG && parser.getName()
                        .equals(ELEMENT_NAME_AUTODISCOVER)) {
                    break;
                } else if (type == XmlPullParser.START_TAG && parser.getName()
                        .equals(ELEMENT_NAME_RESPONSE)) {
                    parseResponse(parser, hostAuth);
                    // Valid responses will set the address.
                    if (hostAuth.mAddress != null) {
                        return hostAuth;
                    }
                }
            }
        } catch (final XmlPullParserException e) {
            // Parse error.
        } catch (final IOException e) {
            // Error reading parser.
        }
        return null;
    }
}