/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.openejb.server.httpd;

import org.apache.openejb.core.security.jaas.UserPrincipal;
import org.apache.openejb.loader.SystemInstance;
import org.apache.openejb.util.ArrayEnumeration;
import org.apache.openejb.util.Logger;

import javax.servlet.AsyncContext;
import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestEvent;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.Part;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static java.util.Collections.singletonList;

/**
 * A class to take care of HTTP Requests.  It parses headers, content, form and url
 * parameters.
 */
public class HttpRequestImpl implements HttpRequest {
    private static final String FORM_URL_ENCODED = "application/x-www-form-urlencoded";
    private static final String TRANSFER_ENCODING = "Transfer-Encoding";
    private static final String CHUNKED = "chunked";
    protected static final String EJBSESSIONID = "EJBSESSIONID";

    // note: no eviction so invalidate has to be called properly
    private static final ConcurrentMap<String, HttpSession> SESSIONS = new ConcurrentHashMap<String, HttpSession>();

    public static final Class<?>[] SERVLET_CONTEXT_INTERFACES = new Class<?>[]{ServletContext.class};
    public static final InvocationHandler SERVLET_CONTEXT_HANDLER = new InvocationHandler() {
        @Override
        public Object invoke(final Object proxy, final java.lang.reflect.Method method, final Object[] args) throws Throwable {
            return null;
        }
    };

    private EndWebBeansListener end;
    private BeginWebBeansListener begin;

    /**
     * 5.1.1    Method
     */
    private String method;

    /**
     * 5.1.2    Request-URI
     */
    private URI uri;

    /**
     * the headers for this page
     */
    private final Map<String, String> headers = new HashMap<String, String>();

    /**
     * the form parameters for this page
     */
    private final Map<String, String> formParams = new HashMap<String, String>();

    /**
     * the URL (or query) parameters for this page
     */
    private final Map<String, List<String>> queryParams = new HashMap<String, List<String>>();

    /**
     * All form and query parameters.  Query parameters override form parameters.
     */
    private final Map<String, List<String>> parameters = new HashMap<String, List<String>>();

    private final Map<String, Part> parts = new HashMap<String, Part>();

    /**
     * Cookies sent from the client
     */
    private Map<String, String> cookies;

    /**
     * the content of the body of the request
     */
    private byte[] body;
    private ServletByteArrayIntputStream in;
    private int length;
    private String contentType;

    /**
     * the address the request came in on
     */
    private final URI socketURI;

    /**
     * Request scoped data which is set and used by application code.
     */
    private final Map<String, Object> attributes = new HashMap<String, Object>();

    private String path = "/";
    private Locale locale = Locale.getDefault();
    private HttpSession session;
    private String encoding = "UTF-8";
    private ServletContext context = null;
    private String contextPath = "";
    private String servletPath = null;

    public HttpRequestImpl(final URI socketURI) {
        this.socketURI = socketURI;
    }

    /**
     * Gets a header based the header name passed in.
     *
     * @param name The name of the header to get
     * @return The value of the header
     */
    public String getHeader(final String name) {
        return headers.get(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        return new ArrayEnumeration(new ArrayList<String>(headers.keySet()));
    }

    @Override
    public Enumeration<String> getHeaders(final String s) {
        return new ArrayEnumeration(Arrays.asList(headers.get(s)));
    }

    @Override
    public int getIntHeader(final String s) {
        return Integer.parseInt(s);
    }

    /**
     * Gets a form parameter based on the name passed in.
     *
     * @param name The name of the form parameter to get
     * @return The value of the parameter
     */
    public String getFormParameter(final String name) {
        return formParams.get(name);
    }

    public Map<String, String> getFormParameters() {
        return new HashMap<String, String>(formParams);
    }

    /**
     * Gets the request method.
     *
     * @return the request method
     */
    public String getMethod() {
        return method;
    }

    @Override
    public Part getPart(final String s) throws IOException, ServletException {
        return parts.get(s);
    }

    @Override
    public Collection<Part> getParts() throws IOException, ServletException {
        return parts.values();
    }

    @Override
    public String getPathInfo() {
        if (servletPath != null) {
            return path.substring(servletPath.length());
        }
        return path;
    }

    @Override
    public String getPathTranslated() {
        return path;
    }

    @Override
    public String getQueryString() {
        final StringBuilder str = new StringBuilder("");
        for (final Map.Entry<String, List<String>> q : queryParams.entrySet()) {
            for (final String v : q.getValue()) {
                str.append(q.getKey()).append("=").append(v).append("&");
            }
        }
        final String out = str.toString();
        if (out.isEmpty()) {
            return out;
        }
        return out.substring(0, out.length() - 1);
    }

    @Override
    public String getRemoteUser() {
        return null; // TODO
    }

    @Override
    public String getRequestedSessionId() {
        if (session != null) {
            return session.getId();
        }
        return null;
    }

    @Override
    public String getRequestURI() {
        return getURI().getRawPath();
    }

    @Override
    public StringBuffer getRequestURL() {
        return new StringBuffer(uri.getScheme() + "://" + uri.getHost() + ":" + uri.getPort() + uri.getPath());
    }

    @Override
    public String getServletPath() {
        if (servletPath != null) {
            return servletPath;
        }
        return getPathInfo();
    }

    public void initServletPath(final String servlet) {
        servletPath = servlet;
    }

    /**
     * Gets the URI for the current URL page.
     *
     * @return the URI
     */
    public URI getURI() {
        return uri;
    }

    public int getContentLength() {
        return length;
    }

    public String getContentType() {
        return contentType;
    }

    @Override
    public DispatcherType getDispatcherType() {
        return DispatcherType.REQUEST;
    }

    public ServletInputStream getInputStream() throws IOException {
        return this.in;
    }

    @Override
    public String getLocalAddr() {
        return getURI().getHost();
    }

    @Override
    public Locale getLocale() {
        return locale;
    }

    @Override
    public Enumeration<Locale> getLocales() {
        return new ArrayEnumeration(Arrays.asList(Locale.getAvailableLocales()));
    }

    @Override
    public String getLocalName() {
        return locale.getLanguage();
    }

    @Override
    public int getLocalPort() {
        return getURI().getPort();
    }

    /*------------------------------------------------------------*/
    /*  Methods for reading in and parsing a request              */
    /*------------------------------------------------------------*/

    /**
     * parses the request into the 3 different parts, request, headers, and body
     *
     * @param input the data input for this page
     * @throws java.io.IOException if an exception is thrown
     */
    protected void readMessage(final InputStream input) throws IOException {
        final DataInput di = new DataInputStream(input);

        readRequestLine(di);
        readHeaders(di);
        readBody(di);

        for (final Map.Entry<String, String> formParameters : getFormParameters().entrySet()) {
            parameters.put(formParameters.getKey(), singletonList(formParameters.getValue()));
        }
        parameters.putAll(queryParams);

        if (headers.containsKey("Cookie")) {
            final String cookie = headers.get("Cookie");
            if (cookie != null) {
                final String[] cookies = cookie.split(";");
                for (final String c : cookies) {
                    final String current = c.trim();
                    if (current.startsWith("EJBSESSIONID=")) {
                        session = SESSIONS.get(current.substring("EJBSESSIONID=".length()));
                    }
                }
            }
        }
    }

    public void print(final Logger log, final boolean formatXml) {
        if (log.isDebugEnabled()) {
            final StringBuilder builder = new StringBuilder();
            builder.append("******************* REQUEST ******************\n");
            builder.append(method).append(" ").append(uri).append("\n");
            for (final Map.Entry<String, String> entry : headers.entrySet()) {
                builder.append(entry).append("\n");
            }
            builder.append("\n");

            final String text = new String(body);
            if (formatXml && OpenEJBHttpServer.isTextXml(headers)) {
                builder.append(OpenEJBHttpServer.reformat(text)).append("\n");
            } else {
                builder.append(text).append("\n");
            }
            builder.append("**********************************************").append("\n");
            log.debug(builder.toString());
        }
    }

    /**
     * reads and parses the request line
     *
     * @param in the input to be read
     * @throws java.io.IOException if an exception is thrown
     */
    private void readRequestLine(final DataInput in) throws IOException {
        final String line;
        try {
            line = in.readLine();
//            System.out.println(line);
        } catch (final Exception e) {
            throw new IOException("Could not read the HTTP Request Line :"
                + e.getClass().getName()
                + " : "
                + e.getMessage());
        }

        final StringTokenizer lineParts = new StringTokenizer(line, " ");
        /* [1] Parse the method */
        parseMethod(lineParts);
        /* [2] Parse the URI */
        parseURI(lineParts);
    }

    /**
     * parses the method for this page
     *
     * @param lineParts a StringTokenizer of the request line
     * @throws java.io.IOException if an exeption is thrown
     */
    private void parseMethod(final StringTokenizer lineParts) throws IOException {
        final String token;
        try {
            token = lineParts.nextToken();
        } catch (final Exception e) {
            throw new IOException("Could not parse the HTTP Request Method :"
                + e.getClass().getName()
                + " : "
                + e.getMessage());
        }

        // in JAXRS you can create your own method
        try { // to control the case
            method = Method.valueOf(token.toUpperCase(Locale.ENGLISH)).name();
        } catch (final Exception e) {
            method = token;
        }
        /*
        if (token.equalsIgnoreCase("GET")) {
            method = Method.GET.name();
        } else if (token.equalsIgnoreCase("POST")) {
            method = Method.POST.name();
        } else if (token.equalsIgnoreCase("PUT")) {
            method = Method.PUT.name();
        } else if (token.equalsIgnoreCase("DELETE")) {
            method = Method.DELETE.name();
        } else if (token.equalsIgnoreCase("HEAD")) {
            method = Method.HEAD.name();
        } else if (token.equalsIgnoreCase("OPTIONS")) {
            method = Method.HEAD.name();
        } else if (token.equalsIgnoreCase("PATCH")) {
            method = Method.PATCH.name();
        } else {
            method = Method.UNSUPPORTED.name();
            throw new IOException("Unsupported HTTP Request Method :" + token);
        }
        */
    }

    /**
     * parses the URI into the different parts
     *
     * @param lineParts a StringTokenizer of the URI
     * @throws java.io.IOException if an exeption is thrown
     */
    private void parseURI(final StringTokenizer lineParts) throws IOException {
        final String token;
        try {
            token = lineParts.nextToken();
        } catch (final Exception e) {
            throw new IOException("Could not parse the HTTP Request Method :"
                + e.getClass().getName()
                + " : "
                + e.getMessage());
        }

        try {
            uri = new URI(socketURI.toString() + token);
        } catch (final URISyntaxException e) {
            throw new IOException("Malformed URI :" + token + " Exception: " + e.getMessage());
        }

        parseQueryParams(uri.getQuery());
    }

    /**
     * parses the URL (or query) parameters
     *
     * @param query the URL (or query) parameters to be parsed
     */
    private void parseQueryParams(final String query) {
        if (query == null)
            return;
        final StringTokenizer parameters = new StringTokenizer(query, "&");

        while (parameters.hasMoreTokens()) {
            final StringTokenizer param = new StringTokenizer(parameters.nextToken(), "=");

            /* [1] Parse the Name */
            if (!param.hasMoreTokens())
                continue;
            final String name = URLDecoder.decode(param.nextToken());
            if (name == null)
                continue;

            final String value;
            /* [2] Parse the Value */
            if (!param.hasMoreTokens()) {
                value = "";
            } else {
                value = URLDecoder.decode(param.nextToken());
            }

            List<String> list = queryParams.get(name);
            if (list == null) {
                list = new LinkedList<String>();
                queryParams.put(name, list);
            }
            list.add(value);
        }
    }

    /**
     * reads the headers from the data input sent from the browser
     *
     * @param in the data input sent from the browser
     * @throws java.io.IOException if an exeption is thrown
     */
    private void readHeaders(final DataInput in) throws IOException {
//        System.out.println("\nREQUEST");
        while (true) {
            // Header Field
            final String hf;

            try {
                hf = in.readLine();
                //System.out.println(hf);
            } catch (final Exception e) {
                throw new IOException("Could not read the HTTP Request Header Field :"
                    + e.getClass().getName()
                    + " : "
                    + e.getMessage());
            }

            if (hf == null || hf.equals("")) {
                break;
            }

            /* [1] parse the name */
            final int colonIndex = hf.indexOf((int) ':');
            final String name = hf.substring(0, colonIndex);
            if (name == null)
                break;

            /* [2] Parse the Value */
            String value = hf.substring(colonIndex + 1, hf.length());
            if (value == null)
                break;
            value = value.trim();
            headers.put(name, value);
        }

        // Update the URI to be what the client sees the the server as.
        final String host = headers.get("Host");
        if (host != null) {
            final String hostName;
            int port = uri.getPort();
            final int idx = host.indexOf(":");
            if (idx >= 0) {
                hostName = host.substring(0, idx);
                try {
                    port = Integer.parseInt(host.substring(idx + 1));
                } catch (final NumberFormatException ignore) {
                }
            } else {
                hostName = host;
            }

            try {
                uri = new URI(uri.getScheme(),
                    uri.getUserInfo(), hostName, port,
                    uri.getPath(), uri.getQuery(),
                    uri.getFragment());
            } catch (final URISyntaxException ignore) {
            }
        }

        //temp-debug-------------------------------------------
        //java.util.Iterator myKeys = headers.keySet().iterator();
        //String temp = null;
        //while(myKeys.hasNext()) {
        //    temp = (String)myKeys.next();
        //    System.out.println("Test: " + temp + "=" + headers.get(temp));
        //}
        //end temp-debug---------------------------------------
    }

    private boolean hasBody() {
        return !method.equals(Method.GET.name()) && !method.equals(Method.DELETE.name())
            && !method.equals(Method.HEAD.name()) && !method.equals(Method.OPTIONS.name());
    }

    /**
     * reads the body from the data input passed in
     *
     * @param in the data input with the body of the page
     * @throws java.io.IOException if an exception is thrown
     */
    private void readBody(final DataInput in) throws IOException {
        //System.out.println("Body Length: " + body.length);
        // Content-type: application/x-www-form-urlencoded
        // or multipart/form-data
        length = parseContentLength();

        contentType = getHeader(HttpRequest.HEADER_CONTENT_TYPE);

        final boolean hasBody = hasBody();
        if (hasBody && FORM_URL_ENCODED.equals(contentType)) {
            final String rawParams;

            try {
                body = readContent(in);
                this.in = new ServletByteArrayIntputStream(body);
                rawParams = new String(body);
            } catch (final Exception e) {
                throw (IOException) new IOException("Could not read the HTTP Request Body: " + e.getMessage()).initCause(e);
            }

            final StringTokenizer parameters = new StringTokenizer(rawParams, "&");
            String name;
            String value;

            while (parameters.hasMoreTokens()) {
                final StringTokenizer param = new StringTokenizer(parameters.nextToken(), "=");

                /* [1] Parse the Name */
                name = URLDecoder.decode(param.nextToken(), "UTF-8");
                if (name == null)
                    break;

                /* [2] Parse the Value */
                if (param.hasMoreTokens()) {
                    value = URLDecoder.decode(param.nextToken(), "UTF-8");
                } else {
                    value = ""; //if there is no token set value to blank string
                }

                if (value == null)
                    value = "";

                formParams.put(name, value);
                //System.out.println(name + ": " + value);
            }
        } else if (hasBody && CHUNKED.equals(headers.get(TRANSFER_ENCODING))) {
            try {
                final ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
                for (String line = in.readLine(); line != null; line = in.readLine()) {
                    // read the size line which is in hex
                    final String sizeString = line.split(";", 2)[0];
                    final int size = Integer.parseInt(sizeString, 16);

                    // if size is 0 we are done
                    if (size == 0) break;

                    // read the chunk and append to byte array
                    final byte[] chunk = new byte[size];
                    in.readFully(chunk);
                    out.write(chunk);

                    // read off the trailing new line characters after the chunk
                    in.readLine();
                }
                body = out.toByteArray();
                this.in = new ServletByteArrayIntputStream(body);
            } catch (final Exception e) {
                throw (IOException) new IOException("Unable to read chunked body").initCause(e);
            }
        } else if (hasBody) {
            // TODO This really is terrible
            body = readContent(in);
            this.in = new ServletByteArrayIntputStream(body);
        } else {
            body = new byte[0];
            this.in = new ServletByteArrayIntputStream(body);
        }

    }

    private byte[] readContent(final DataInput in) throws IOException {
        if (length >= 0) {
            final byte[] body = new byte[length];
            in.readFully(body);
            return body;
        } else {
            final ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
            try {
                boolean atLineStart = true;
                while (true) {
                    byte b = in.readByte();

                    if (b == '\r') {
                        // read the next byte
                        out.write(b);
                        b = in.readByte();
                    }

                    if (b == '\n') {
                        if (atLineStart) {
                            // blank line signals end of data
                            break;
                        }
                        atLineStart = true;
                    } else {
                        atLineStart = false;
                    }
                    out.write(b);
                }
            } catch (final EOFException e) {
                // done reading
            }
            final byte[] body = out.toByteArray();
            return body;
        }
    }

    private int parseContentLength() {
        // Content-length: 384
        final String len = getHeader(HttpRequest.HEADER_CONTENT_LENGTH);
        //System.out.println("readRequestBody Content-Length: " + len);

        int length = -1;
        if (len != null) {
            try {
                length = Integer.parseInt(len);
            } catch (final Exception e) {
                //don't care
            }
        }
        return length;
    }

    @Override
    public boolean authenticate(final HttpServletResponse httpServletResponse) throws IOException, ServletException {
        return true; // TODO?
    }

    @Override
    public String getAuthType() {
        return "BASIC"; // to manage?
    }

    @Override
    public String getContextPath() {
        return contextPath;
    }

    public String extractContextPath() {
        if (SystemInstance.get().getOptions().get("openejb.webservice.old-deployment", false)) {
            return path;
        }

        String uri = getURI().getPath();
        if (uri.startsWith("/")) {
            uri = uri.substring(1);
        }
        final int idx = uri.indexOf("/");
        if (idx < 0) {
            return uri;
        }
        return uri.substring(0, idx);
    }

    @Override
    public Cookie[] getCookies() {
        if (cookies != null) return toCookies(cookies);

        cookies = new HashMap<String, String>();

        final String cookieHeader = getHeader(HEADER_COOKIE);
        if (cookieHeader == null) return toCookies(cookies);

        final StringTokenizer tokens = new StringTokenizer(cookieHeader, ";");
        while (tokens.hasMoreTokens()) {
            final StringTokenizer token = new StringTokenizer(tokens.nextToken(), "=");
            final String name = token.nextToken();
            final String value = token.nextToken();
            cookies.put(name, value);
        }
        return toCookies(cookies);
    }

    protected Map<?, ?> getInternalCookies() {
        if (cookies != null) return cookies;

        cookies = new HashMap<String, String>();

        final String cookieHeader = getHeader(HEADER_COOKIE);
        if (cookieHeader == null) return cookies;

        final StringTokenizer tokens = new StringTokenizer(cookieHeader, ";");
        while (tokens.hasMoreTokens()) {
            final StringTokenizer token = new StringTokenizer(tokens.nextToken(), "=");
            final String name = token.nextToken();
            final String value = token.nextToken();
            cookies.put(name, value);
        }
        return cookies;
    }

    private Cookie[] toCookies(final Map<String, String> cookies) {
        final Cookie[] out = new Cookie[cookies.size()];
        int i = 0;
        for (final Map.Entry<String, String> entry : cookies.entrySet()) {
            out[i++] = new Cookie(entry.getKey(), entry.getValue());
        }
        return out;
    }

    @Override
    public long getDateHeader(final String s) {
        return Long.parseLong(s);
    }

    protected String getCookie(final String name) {
        return (String) getInternalCookies().get(name);
    }

    public HttpSession getSession(final boolean create) {
        if (session == null && create) {
            session = new HttpSessionImpl(SESSIONS);
            final HttpSession previous = SESSIONS.putIfAbsent(session.getId(), session);
            if (previous != null) {
                session = previous;
            }

            if (begin != null) {
                begin.sessionCreated(new HttpSessionEvent(session));
                return new SessionInvalidateListener(session, end);
            }
        }
        return session;
    }

    protected URI getSocketURI() {
        return socketURI;
    }

    @Override
    public Principal getUserPrincipal() {
        return new UserPrincipal("");
    }

    @Override
    public boolean isRequestedSessionIdFromCookie() {
        return false;
    }

    @Override
    public boolean isRequestedSessionIdFromUrl() {
        return false;
    }

    @Override
    public boolean isRequestedSessionIdFromURL() {
        return false;
    }

    @Override
    public boolean isRequestedSessionIdValid() {
        return false;
    }

    @Override
    public boolean isUserInRole(final String s) {
        return true; // TODO?
    }

    @Override
    public void login(final String s, final String s1) throws ServletException {
        // no-op
    }

    @Override
    public void logout() throws ServletException {
        // no-op
    }

    public HttpSession getSession() {
        return getSession(true);
    }

    @Override
    public AsyncContext getAsyncContext() {
        return null;
    }

    public Object getAttribute(final String name) {
        return attributes.get(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return new ArrayEnumeration(new ArrayList<String>(attributes.keySet()));
    }

    @Override
    public String getCharacterEncoding() {
        return encoding;
    }

    public void setAttribute(final String name, final Object value) {
        attributes.put(name, value);
    }

    @Override
    public void setCharacterEncoding(final String s) throws UnsupportedEncodingException {
        encoding = s;
    }

    @Override
    public AsyncContext startAsync() {
        return null;
    }

    @Override
    public AsyncContext startAsync(final ServletRequest servletRequest, final ServletResponse servletResponse) {
        return null;
    }

    public String getParameter(final String name) {
        final List<String> strings = parameters.get(name);
        return strings == null ? null : strings.iterator().next();
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        final Map<String, String[]> params = new HashMap<String, String[]>();
        for (final Map.Entry<String, List<String>> p : parameters.entrySet()) {
            final List<String> values = p.getValue();
            params.put(p.getKey(), values.toArray(new String[values.size()]));
        }
        return params;
    }

    @Override
    public Enumeration<String> getParameterNames() {
        return new ArrayEnumeration(new ArrayList<String>(parameters.keySet()));
    }

    @Override
    public String[] getParameterValues(final String s) {
        final List<String> strings = parameters.get(s);
        return strings == null ? null : strings.toArray(new String[strings.size()]);
    }

    @Override
    public String getProtocol() {
        return uri.getScheme();
    }

    @Override
    public BufferedReader getReader() throws IOException {
        return null;
    }

    @Override
    public String getRealPath(final String s) {
        return path;
    }

    @Deprecated // TODO should be dropped, do we drop axis module as well?
    public Map<String, String> getParameters() {
        final HashMap<String, String> converted = new HashMap<String, String>(parameters.size());
        for (final Map.Entry<String, List<String>> entry : parameters.entrySet()) {
            converted.put(entry.getKey(), entry.getValue().iterator().next());
        }
        return converted;
    }

    public String getRemoteAddr() {
        // todo how do we get this value?
        return null;
    }

    @Override
    public String getRemoteHost() {
        return getURI().getHost();
    }

    @Override
    public int getRemotePort() {
        return getURI().getPort();
    }

    @Override
    public RequestDispatcher getRequestDispatcher(final String s) {
        return null;
    }

    @Override
    public String getScheme() {
        return getURI().getScheme();
    }

    @Override
    public String getServerName() {
        return getURI().getHost();
    }

    @Override
    public int getServerPort() {
        return getURI().getPort();
    }

    @Override
    public ServletContext getServletContext() { // we need a not null value but it is not intended to be used in standalone for now
        if (context == null) {
            context = (ServletContext) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(), SERVLET_CONTEXT_INTERFACES, SERVLET_CONTEXT_HANDLER);
        }
        return context;
    }

    @Override
    public boolean isAsyncStarted() {
        return false;
    }

    @Override
    public boolean isAsyncSupported() {
        return false;
    }

    @Override
    public boolean isSecure() {
        return false; // TODO?
    }

    @Override
    public void removeAttribute(final String s) {
        attributes.remove(s);
    }

    public void setPath(final String path) {
        this.path = path;
    }

    public String requestRawPath() {
        try {
            return new URI(getRequestURI()).getRawPath();
        } catch (final URISyntaxException e) {
            return getRequestURI();
        }
    }

    public void initPathFromContext(final String context) {
        if (!"/".equals(path)) { // already done
            return;
        }

        final String rawPath = requestRawPath();
        if (context != null) {
            if (context.endsWith("/")) {
                final int endIndex = context.length() - 1;
                path = rawPath.substring(endIndex, rawPath.length());
                contextPath = context.substring(0, endIndex);
            } else {
                path = rawPath.substring(context.length(), rawPath.length()); // 1 because of the first /
                contextPath = context;
            }
        }
    }

    public void setEndListener(final EndWebBeansListener end) {
        this.end = end;
    }

    public void setBeginListener(final BeginWebBeansListener begin) {
        this.begin = begin;
    }

    public void init() {
        if (begin != null) {
            begin.requestInitialized(new ServletRequestEvent(getServletContext(), this));
        }
    }

    public void destroy() {
        if (end != null) {
            end.requestDestroyed(new ServletRequestEvent(getServletContext(), this));
        }
    }

    protected static class SessionInvalidateListener extends ServletSessionAdapter {
        private final EndWebBeansListener listener;

        public SessionInvalidateListener(final javax.servlet.http.HttpSession session, final EndWebBeansListener end) {
            super(session);
            listener = end;
        }

        @Override
        public void invalidate() {
            listener.sessionDestroyed(new HttpSessionEvent(session));
        }
    }
}