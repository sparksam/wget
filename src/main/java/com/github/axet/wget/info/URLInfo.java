package com.github.axet.wget.info;

import com.github.axet.wget.RetryWrap;
import com.github.axet.wget.WGet;
import com.github.axet.wget.info.ex.DownloadRetry;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import rx.Observable;
import rx.Observer;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * URLInfo - keep all information about source in one place. Thread safe.
 *
 * @author axet
 */
public class URLInfo extends BrowserInfo {
    /**
     * connect socket timeout
     */
    static public final int CONNECT_TIMEOUT = 10000;
    /**
     * read socket timeout
     */
    static public final int READ_TIMEOUT = 10000;
    /**
     * source url
     */
    private URL source;
    /**
     * have been extracted?
     */
    private boolean extract = false;
    /**
     * null if size is unknown, which means we unable to restore downloads or do multi thread downlaods
     */
    private Long length;
    /**
     * does server support for the range param?
     */
    private boolean range;
    /**
     * null if here is no such file or other error
     */
    private String contentType;
    /**
     * come from Content-Disposition: attachment; filename="fname.ext"
     */
    private String contentFilename;
    // set cookie
    private String cookie;
    /**
     * download state
     */
    private States state;
    /**
     * downloading error / retry error
     */
    private Throwable exception;
    /**
     * retrying delay;
     */
    private int delay;
    private ProxyInfo proxy;

    public URLInfo(URL source) {
        this.source = source;
    }

    public HttpURLConnection openConnection() throws IOException {
        URL url = getSource();

        HttpURLConnection conn;

        if (getProxy() != null) {
            conn = (HttpURLConnection) url.openConnection(getProxy().proxy);
        } else {
            conn = (HttpURLConnection) url.openConnection();
        }

        if (cookie != null)
            conn.setRequestProperty("Cookie", cookie);

        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);

        conn.setRequestProperty("User-Agent", getUserAgent());
        if (getReferer() != null)
            conn.setRequestProperty("Referer", getReferer().toExternalForm());

        return conn;
    }

    public void extract() {
        extract(new AtomicBoolean(false), Observable.create(Observer::onCompleted));
    }

    public void extract(final AtomicBoolean stop, final rx.Observable notify) {
        try {
            HttpURLConnection conn;

            conn = RetryWrap.wrap(stop, new RetryWrap.WrapReturn<HttpURLConnection>() {
                URL url = source;

                @Override
                public void proxy() {
                    getProxy().set();
                }

                @Override
                public HttpURLConnection download() throws IOException {
                    return download(new URLInfo(url));
                }

                HttpURLConnection download(URLInfo url) throws IOException {
                    setState(States.EXTRACTING);
                    notify.subscribe();

                    try {
                        return meta(extractRange(url));
                    } catch (DownloadRetry e) {
                        throw e;
                    } catch (RuntimeException e) {
                        return meta(extractNormal(url));
                    }
                }

                HttpURLConnection meta(HttpURLConnection conn) throws IOException {
                    String[] values = (conn.getContentType() == null ? "" : conn.getContentType()).split(";");
                    String contentType = values[0];

                    if (contentType.equals("text/html")) {
                        String html = WGet.getHtml(conn, stop);
                        Document doc = Jsoup.parse(html);
                        Element link = doc.select("meta[http-equiv=refresh]").first();
                        if (link != null) {
                            String content = link.attr("content");
                            if (!content.isEmpty()) {
                                String[] vv = content.split(";");
                                if (vv.length > 1) {
                                    String urlmeta = vv[1];
                                    String[] url = urlmeta.split("url=");
                                    if (url.length > 1) {
                                        URLInfo info = new URLInfo(new URL(url[1]));
                                        info.setCookie(conn.getHeaderField("Set-cookie"));
                                        info.setReferer(getSource());
                                        HttpURLConnection conn2 = download(info);

                                        setReferer(info.getReferer());
                                        source = info.getSource();
                                        if (info.getCookie() != null)
                                            setCookie(info.getCookie()); // TODO sholud we merge cookies?

                                        return conn2;
                                    }
                                }
                            }
                        }
                    }

                    return conn;
                }

                @Override
                public void retry(int d, Throwable ee) {
                    setDelay(d, ee);
                    notify.subscribe();
                }

                @Override
                public void moved(URL u) {
                    setReferer(url);

                    url = u;

                    setState(States.RETRYING);
                    notify.subscribe();
                }
            });

            setContentType(conn.getContentType());

            String contentDisposition = conn.getHeaderField("Content-Disposition");
            if (contentDisposition != null) {
                // i support for two forms with and without quotes:
                //
                // 1) contentDisposition="attachment;filename="ap61.ram"";
                // 2) contentDisposition="attachment;filename=ap61.ram";

                Pattern cp = Pattern.compile("filename=[\"]*([^\"]*)[\"]*");
                Matcher cm = cp.matcher(contentDisposition);
                if (cm.find())
                    setContentFilename(cm.group(1));
            }

            setEmpty(true);

            setState(States.EXTRACTING_DONE);
            notify.subscribe();
        } catch (RuntimeException e) {
            setState(States.ERROR, e);

            throw e;
        }
    }

    synchronized public boolean empty() {
        return !extract;
    }

    synchronized public void setEmpty(boolean b) {
        extract = b;
    }

    // if range failed - do plain download with no retrys's
    protected HttpURLConnection extractRange(URLInfo url) throws IOException {
        HttpURLConnection conn = url.openConnection();

        // may raise an exception if not supported by server
        conn.setRequestProperty("Range", "bytes=" + 0 + "-" + 0);

        RetryWrap.check(conn);

        String range = conn.getHeaderField("Content-Range");
        if (range == null)
            throw new RuntimeException("range not supported");

        Pattern p = Pattern.compile("bytes \\d+-\\d+/(\\d+)");
        Matcher m = p.matcher(range);
        if (m.find()) {
            setLength(new Long(m.group(1)));
        } else {
            throw new RuntimeException("range not supported");
        }

        this.setRange(true);

        return conn;
    }

    // if range failed - do plain download with no retrys's
    protected HttpURLConnection extractNormal(URLInfo url) throws IOException {
        HttpURLConnection conn = url.openConnection();

        setRange(false);

        RetryWrap.check(conn);

        int len = conn.getContentLength();
        if (len >= 0) {
            setLength(new Long(len));
        }

        return conn;
    }

    synchronized public String getContentType() {
        return contentType;
    }

    synchronized public void setContentType(String ct) {
        contentType = ct;
    }

    synchronized public Long getLength() {
        return length;
    }

    synchronized public void setLength(Long l) {
        length = l;
    }

    synchronized public URL getSource() {
        return source;
    }

    synchronized public String getContentFilename() {
        return contentFilename;
    }

    synchronized public void setContentFilename(String f) {
        contentFilename = f;
    }

    synchronized public States getState() {
        return state;
    }

    synchronized public void setState(States state) {
        this.state = state;
        this.exception = null;
        this.delay = 0;
    }

    synchronized public void setState(States state, Throwable e) {
        this.state = state;
        this.exception = e;
        this.delay = 0;
    }

    synchronized public Throwable getException() {
        return exception;
    }

    synchronized protected void setException(Throwable exception) {
        this.exception = exception;
    }

    synchronized public int getDelay() {
        return delay;
    }

    synchronized public void setDelay(int delay, Throwable e) {
        this.delay = delay;
        this.exception = e;
        this.state = States.RETRYING;
    }

    synchronized public boolean getRange() {
        return range;
    }

    synchronized public void setRange(boolean range) {
        this.range = range;
    }

    synchronized public ProxyInfo getProxy() {
        return proxy;
    }

    synchronized public void setProxy(ProxyInfo proxy) {
        this.proxy = proxy;
    }

    synchronized public String getCookie() {
        return cookie;
    }

    synchronized public void setCookie(String cookie) {
        this.cookie = cookie;
    }

    /**
     * Notify States
     */
    public enum States {
        EXTRACTING, EXTRACTING_DONE, DOWNLOADING, RETRYING, STOP, ERROR, DONE;
    }

}
