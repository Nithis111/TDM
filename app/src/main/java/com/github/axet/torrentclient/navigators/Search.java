package com.github.axet.torrentclient.navigators;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.AppCompatImageButton;
import android.text.Html;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.github.axet.androidlibrary.net.HttpClient;
import com.github.axet.androidlibrary.widgets.HeaderGridView;
import com.github.axet.androidlibrary.widgets.ThemeUtils;
import com.github.axet.androidlibrary.widgets.UnreadCountDrawable;
import com.github.axet.androidlibrary.widgets.WebViewCustom;
import com.github.axet.torrentclient.R;
import com.github.axet.torrentclient.activities.MainActivity;
import com.github.axet.torrentclient.app.MainApplication;
import com.github.axet.torrentclient.app.SearchEngine;
import com.github.axet.torrentclient.app.Storage;
import com.github.axet.torrentclient.dialogs.BrowserDialogFragment;
import com.github.axet.torrentclient.dialogs.LoginDialogFragment;
import com.github.axet.torrentclient.net.HttpProxyClient;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cz.msebera.android.httpclient.client.CookieStore;
import cz.msebera.android.httpclient.client.methods.AbstractExecutionAwareRequest;
import cz.msebera.android.httpclient.cookie.Cookie;
import cz.msebera.android.httpclient.impl.client.BasicCookieStore;
import cz.msebera.android.httpclient.impl.client.CloseableHttpClient;
import cz.msebera.android.httpclient.impl.client.HttpClientBuilder;
import libtorrent.Libtorrent;

import static com.github.axet.torrentclient.navigators.Crawl.CRAWL_SHOW;
import static com.github.axet.torrentclient.navigators.Crawl.EN;
import static com.github.axet.torrentclient.navigators.Crawl.getLong;

public class Search extends BaseAdapter implements DialogInterface.OnDismissListener,
        UnreadCountDrawable.UnreadCount, MainActivity.NavigatorInterface,
        SharedPreferences.OnSharedPreferenceChangeListener {
    public static final String TAG = Search.class.getSimpleName();

    public static String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_11_6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/52.0.2743.116 Safari/537.36";
    public static int MESSAGE_AUTOCLOSE = 5; // seconds

    Context context;
    MainActivity main;
    ArrayList<SearchItem> list = new ArrayList<>();

    HashMap<SearchItem, DownloadImageTask> downloadsItems = new HashMap<>();
    HashMap<View, DownloadImageTask> downloadsViews = new HashMap<>();

    BrowserDialogFragment dialog;

    Thread thread;
    Looper threadLooper;

    HttpProxyClient http;
    WebViewCustom web;
    SearchEngine engine;
    Map<String, String> engine_favs;
    Handler handler;

    String lastSearch; // last search request
    String lastLogin;// last login user name

    AlertDialog error;
    ArrayList<String> message = new ArrayList<>();

    // search header
    View header;
    ViewGroup message_panel;
    Runnable message_panel_progress = new Runnable() {
        @Override
        public void run() {
            messageProgress();
        }
    };
    ProgressBar header_progress; // progressbar / button
    View header_stop; // stop image
    View header_search; // search button
    TextView searchText;

    ViewGroup toolbar;
    View toolbar_news;
    View toolbar_favs;
    TextView toolbar_favs_name;
    int toolbarIndex = -1;

    // footer data
    View footer;
    View footer_next; // load next button
    View footer_buttons;
    View footer_remove;
    TextView footer_remove_text;
    View footer_add;
    TextView footer_add_text;
    ProgressBar footer_progress; // progress bar / button
    View footer_stop; // stop image

    // 'load more' button helpers
    Map<String, String> nextSearch;
    String next;
    String nextType;
    String nextText;
    ArrayList<String> nextLast = new ArrayList<>();

    HeaderGridView grid;
    View gridView;

    HtmlUpdateListener htmlupdate;

    Crawl.CrawlDbHelper db;

    public static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    public static String trim(String str) {
        String[] ss = new String[]{"\u00a0", "\ufffc"};
        for (String s : ss) {
            str = str.replaceAll(s, " ");
        }
        str = str.replaceAll("  ", " ");
        str = str.replaceAll("\n\n", "\n");
        return str.trim();
    }

    public static Long matcherLong(String html, String q, Long d) {
        String s = matcher(html, q, null);
        if (s == null || s.isEmpty())
            return d;
        try {
            return Long.valueOf(trim(s));
        } catch (NumberFormatException ignore) {
            return d;
        }
    }

    public static String matcherUrl(String url, String html, String q, String d) {
        String m = matcher(html, q, null);

        if (m == null || m.isEmpty())
            return d;

        try {
            URL u = new URL(url);
            u = new URL(u, m);
            m = u.toString();
            return m;
        } catch (MalformedURLException e) {
        }

        return d;
    }

    public static String matcherHtml(String html, String q, String d) {
        if (q == null)
            return d;

        String all = "(.*)";
        String regex = "regex\\((.*)\\)";

        String r = null;
        Pattern p = Pattern.compile(all + ":" + regex, Pattern.DOTALL);
        Matcher m = p.matcher(q);
        if (m.matches()) {
            q = m.group(1);
            r = m.group(2);
        } else { // then for regex only
            p = Pattern.compile(regex, Pattern.DOTALL);
            m = p.matcher(q);
            if (m.matches()) {
                q = null;
                r = m.group(1);
            }
        }

        String a = "";

        if (q == null || q.isEmpty()) {
            a = html;
        } else {
            Document doc1 = Jsoup.parse(html, "", Parser.xmlParser());
            Elements list1 = doc1.select(q);
            if (list1.size() > 0) {
                a = list1.get(0).outerHtml();
            }
        }

        if (r != null) {
            Pattern p1 = Pattern.compile(r, Pattern.DOTALL);
            Matcher m1 = p1.matcher(a);
            if (m1.matches()) {
                for (int i = 1; i <= m1.groupCount(); i++) { // optional groups support
                    a = m1.group(i);
                    if (a != null)
                        return a;
                }
            } else {
                a = ""; // tell we did not find any regex match
            }
        }
        return a;
    }

    public static String matcher(String html, String q, String d) {
        String a = matcherHtml(html, q, null);
        if (a == null)
            return d;
        return trim(Html.fromHtml(a).toString());
    }

    public static class SearchItem {
        public Boolean fav;
        public String title;
        public String image;
        public String details;
        public String details_html;
        public String magnet;
        public String date;
        public String size;
        public Long seed;
        public Long leech;
        public String torrent;
        public Long downloads; // downloads from last update / month (pepend on site)
        public Long downloads_total; // total downloads

        public Long id; // database id
        public long last; // last update ms
        public String html; // source html
        public Bitmap imageBitmap; // bitmap image
        public Map<String, String> search; // search engine entry
        public String base; // source url
        public boolean update; // did we called update?

        public SearchItem() {
        }

        public SearchItem(Map<String, String> s, String url, String html) {
            this.search = s;
            this.base = url;
            this.html = html;
            this.fav = false; // TODO add checkbox add new items to favs
            update(s, url, html);
        }

        public void update(Map<String, String> s, String url, String html) {
            SearchItem item = this;
            item.title = matcher(html, s.get("title"), item.title);
            item.image = matcherUrl(url, html, s.get("image"), item.image);
            item.magnet = matcher(html, s.get("magnet"), item.magnet);
            item.torrent = matcherUrl(url, html, s.get("torrent"), item.torrent);
            item.date = matcher(html, s.get("date"), item.date);
            item.size = matcher(html, s.get("size"), item.size);
            item.seed = matcherLong(html, s.get("seed"), item.seed);
            item.leech = matcherLong(html, s.get("leech"), item.leech);
            item.downloads = matcherLong(html, s.get("downloads"), item.downloads);
            item.downloads_total = matcherLong(html, s.get("downloads_total"), item.downloads_total);
            item.details = matcherUrl(url, html, s.get("details"), item.details);
            item.details_html = matcherHtml(html, s.get("details_html"), item.details_html);
        }

        public void update(SearchItem old) {
            SearchItem item = this;
            if (item.fav == null)
                item.fav = old.fav;
            if (item.title == null)
                item.title = old.title;
            if (item.image == null)
                item.image = old.image;
            if (item.magnet == null)
                item.magnet = old.magnet;
            if (item.torrent == null)
                item.torrent = old.torrent;
            if (item.date == null)
                item.date = old.date;
            if (item.size == null)
                item.size = old.size;
            if (item.seed == null)
                item.seed = old.seed;
            if (item.leech == null)
                item.leech = old.leech;
            if (item.downloads == null)
                item.downloads = old.downloads;
            if (item.downloads_total == null)
                item.downloads_total = old.downloads_total;
            if (item.details == null)
                item.details = old.details;
            if (item.details_html == null)
                item.details_html = old.details_html;
        }

        public Object get(String name) {
            switch (name) {
                case "title":
                    return title;
                case "image":
                    return image;
                case "magnet":
                    return magnet;
                case "torrent":
                    return torrent;
                case "date":
                    return date;
                case "size":
                    return size;
                case "seed":
                    return seed;
                case "leech":
                    return leech;
                case "downloads":
                    return downloads;
                case "download_total":
                    return downloads_total;
                case "details":
                    return details;
                case "details_html":
                    return details_html;
                default:
                    return null;
            }
        }

        public boolean needUpdate(Map<String, String> update) {
            for (String k : update.keySet()) {
                if (k.startsWith("_")) // disabled / comment fields
                    continue;
                if (get(k) == null) { // ignore already filled values
                    return true;
                }
            }
            return false;
        }

        public String toString() {
            return title;
        }
    }

    public class Inject {
        // do not make it public, old phones conflict with method name
        String json;
        String html;

        public Inject() {
        }

        public Inject(String json) {
            this.json = json;
        }

        @JavascriptInterface
        public void result(String html) {
            Log.d(TAG, "result()");
            this.html = html;
        }

        @JavascriptInterface
        public String json() {
            Log.d(TAG, "json()");
            return json;
        }
    }

    class DownloadImageTask extends AsyncTask<SearchItem, Void, Bitmap> {
        SearchItem item;
        public HashSet<View> views = new HashSet<>(); // one task can set multiple ImageView's, except reused ones
        Bitmap result;
        HttpProxyClient httpImages; // keep separated, to make requestCancel work

        public DownloadImageTask(View v) {
            httpImages = new HttpProxyClient() {
                @Override
                protected CloseableHttpClient build(HttpClientBuilder builder) {
                    builder.setUserAgent(Search.USER_AGENT); // search requests shold go from desktop browser
                    return super.build(builder);
                }
            };
            httpImages.update(context);
            httpImages.setCookieStore(http.getCookieStore());
            views.add(v);
        }

        void loadImage() {
            for (int i = 0; i < 3; i++) {
                try {
                    HttpClient.DownloadResponse w = httpImages.getResponse(null, item.image);
                    w.download();
                    if (w.getError() != null)
                        throw new RuntimeException(w.getError() + " : " + w.getUrl());
                    byte[] buf = w.getBuf();
                    result = BitmapFactory.decodeByteArray(buf, 0, buf.length);
                } catch (RuntimeException e) {
                    Log.e(TAG, "DownloadImageTask", e);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ee) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        protected Bitmap doInBackground(SearchItem... items) {
            item = items[0];

            try {
                detailsLoad(httpImages, item);
            } catch (RuntimeException e) {
                post(e);
                return null;
            }

            if (item.image == null || item.image.isEmpty())
                return null;

            loadImage();

            return result;
        }

        void setImage() {
            if (result != null) {
                item.imageBitmap = result;
            }
            for (View i : views)
                updateView(item, i);
        }

        protected void onPostExecute(Bitmap result) {
            item.update = true;
            downloadsItems.remove(item);
            for (View i : views)
                downloadsViews.remove(i);
            setImage();
        }
    }

    public interface HtmlUpdateListener {
        void update(String html);
    }

    public Search(MainActivity m) {
        this.main = m;
        this.context = m;
        this.handler = new Handler();
        this.db = new Crawl.CrawlDbHelper(m);

        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);

        http = new HttpProxyClient() {
            @Override
            protected CloseableHttpClient build(HttpClientBuilder builder) {
                builder.setUserAgent(Search.USER_AGENT); // search requests shold go from desktop browser
                return super.build(builder);
            }
        };
        http.update(context);

        shared.registerOnSharedPreferenceChangeListener(this);
    }

    public void setEngine(SearchEngine engine) {
        this.engine = engine;
        this.engine_favs = engine.getMap("favs");
    }

    public SearchEngine getEngine() {
        return engine;
    }

    public void load(String state) {
        CookieStore cookieStore = http.getCookieStore();
        if (cookieStore == null) {
            cookieStore = new BasicCookieStore();
            http.setCookieStore(cookieStore);
        }
        cookieStore.clear();

        if (state.isEmpty())
            return;

        try {
            byte[] buf = Base64.decode(state, Base64.DEFAULT);
            ByteArrayInputStream bos = new ByteArrayInputStream(buf);
            ObjectInputStream oos = new ObjectInputStream(bos);
            int count = oos.readInt();
            for (int i = 0; i < count; i++) {
                Cookie c = (Cookie) oos.readObject();
                cookieStore.addCookie(c);
            }
        } catch (Exception e) {
            Log.d(TAG, "bad cookie", e); // ignore restoring cookies
        }
    }

    public String save() {
        CookieStore cookieStore = http.getCookieStore();
        // do not save cookies between restarts for non login
        if (cookieStore != null && engine.getMap("login") != null) {
            List<Cookie> cookies = cookieStore.getCookies();
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ObjectOutputStream oos = new ObjectOutputStream(bos);
                oos.writeInt(cookies.size());
                for (Cookie c : cookies) {
                    oos.writeObject(c);
                }
                oos.flush();
                byte[] buf = bos.toByteArray();
                return Base64.encodeToString(buf, Base64.DEFAULT);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return "";
    }

    @Override
    public void install(final HeaderGridView list) {
        this.grid = list;

        list.setAdapter(null); // old phones crash to addHeader

        LayoutInflater inflater = LayoutInflater.from(context);

        header = inflater.inflate(R.layout.search_header, null, false);
        footer = inflater.inflate(R.layout.search_footer, null, false);

        footer_progress = (ProgressBar) footer.findViewById(R.id.search_footer_progress);
        footer_stop = footer.findViewById(R.id.search_footer_stop);
        footer_buttons = footer.findViewById(R.id.search_footer_buttons);
        footer_next = footer.findViewById(R.id.search_footer_next);
        footer_next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                request(new Runnable() {
                    @Override
                    public void run() {
                        search(nextSearch, nextType, next, nextText, new Runnable() {
                            @Override
                            public void run() {
                                requestCancel(); // destory looper thread
                            }
                        });
                    }
                }, null);
                updateFooterButtons();
            }
        });
        footer_remove = footer.findViewById(R.id.search_footer_remove);
        footer_remove.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
                builder.setTitle(R.string.remove_favorites);
                builder.setMessage(getContext().getString(R.string.remove_favorites_text, (long) footer_remove_text.getTag()));
                builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {

                    }
                });
                builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        footerRemove();
                    }
                });
                builder.show();
            }
        });
        footer_remove_text = (TextView) footer_remove.findViewById(R.id.search_footer_remove_name);
        footer_add = footer.findViewById(R.id.search_footer_add);
        footer_add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
                builder.setTitle(R.string.add_favorites);
                builder.setMessage(getContext().getString(R.string.add_favorites_text, (long) footer_add_text.getTag()));
                builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {

                    }
                });
                builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        footerAdd();
                    }
                });
                builder.show();
            }
        });
        footer_add_text = (TextView) footer_add.findViewById(R.id.search_footer_add_name);
        footer_progress.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestCancel();
            }
        });
        updateFooterButtons();

        message_panel = (ViewGroup) header.findViewById(R.id.search_header_message_panel);
        if (message.size() == 0) {
            message_panel.setVisibility(View.GONE);
        } else {
            message_panel.setVisibility(View.VISIBLE);
            message_panel.removeAllViews();
            for (int i = 0; i < message.size(); i++) {
                final String msg = message.get(i);

                final View v = inflater.inflate(R.layout.search_message, null);
                message_panel.addView(v);
                TextView text = (TextView) v.findViewById(R.id.search_header_message_text);
                text.setText(msg);
                ProgressBar p = (ProgressBar) v.findViewById(R.id.search_header_message_progress);
                p.setProgress(0);
                p.setTag(0);

                View message_close = v.findViewById(R.id.search_header_message_close);
                message_close.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View vv) {
                        message.remove(msg);
                        message_panel.removeView(v);
                        main.updateUnread();
                        notifyDataSetChanged();

                        if (message.size() == 0) {
                            message_panel.setVisibility(View.GONE);
                        }
                    }
                });
            }
        }
        messageProgress();

        searchText = (TextView) header.findViewById(R.id.search_header_text);
        header_search = header.findViewById(R.id.search_header_search);
        header_progress = (ProgressBar) header.findViewById(R.id.search_header_progress);
        header_stop = header.findViewById(R.id.search_header_stop);

        searchText.setText(lastSearch);
        searchText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    header_search.performClick();
                    return true;
                }
                return false;
            }
        });

        header_progress.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestCancel();
            }
        });

        updateHeaderButtons();

        final Map<String, String> search = engine.getMap("search");
        header_search.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearList();
                selectToolbar(toolbar.findViewById(R.id.search_header_toolbar_search));
                request(new Runnable() {
                    @Override
                    public void run() {
                        search(search, searchText.getText().toString(), new Runnable() {
                            @Override
                            public void run() {
                                requestCancel(); // destory looper thread
                            }
                        });
                    }
                }, null);
            }
        });

        View home = header.findViewById(R.id.search_header_home);
        home.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (dialog != null)
                    return;

                Map<String, String> home = Search.this.engine.getMap("home");

                String url = home.get("get");
                String head = home.get("head");
                String js = home.get("js");
                String js_post = home.get("js_post");

                BrowserDialogFragment d = BrowserDialogFragment.create(head, url, http.getCookies(), js, js_post);
                dialog = d;
                d.show(main.getSupportFragmentManager(), "");
            }
        });

        View login = header.findViewById(R.id.search_header_login);
        login.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (dialog != null)
                    return;

                Map<String, String> login = Search.this.engine.getMap("login");

                String url = login.get("details");
                String head = login.get("details_head");

                String l = null;
                String p = null;

                if (login.get("post") != null) {
                    l = login.get("post_login");
                    p = login.get("post_password");
                }

                // TODO get

                if (l == null && p == null) {
                    LoginDialogFragment d = LoginDialogFragment.create(head, url, http.getCookies());
                    dialog = d;
                    d.show(main.getSupportFragmentManager(), "");
                } else {
                    LoginDialogFragment d = LoginDialogFragment.create(head, url, http.getCookies(), lastLogin);
                    dialog = d;
                    d.show(main.getSupportFragmentManager(), "");
                }
            }
        });

        if (engine.getMap("login") == null) {
            login.setVisibility(View.GONE);
            Map<String, String> h = Search.this.engine.getMap("home");
            if (h != null)
                home.setVisibility(View.VISIBLE);
            else
                home.setVisibility(View.GONE);
        } else {
            login.setVisibility(View.VISIBLE);
            home.setVisibility(View.GONE);
        }

        toolbar = (ViewGroup) header.findViewById(R.id.search_header_toolbar);
        toolbar_news = header.findViewById(R.id.search_header_toolbar_news);
        toolbar_favs = header.findViewById(R.id.search_header_toolbar_favs);
        toolbar_favs_name = (TextView) toolbar_favs.findViewById(R.id.search_header_toolbar_favs_name);
        final View toolbar_search = header.findViewById(R.id.search_header_toolbar_search);
        final Map<String, String> news = engine.getMap("news");
        final Map<String, String> top = engine.getMap("top");
        if (news == null && top == null) {
            toolbar.setVisibility(View.GONE);
        }
        if (engine_favs == null) {
            toolbar_favs.setVisibility(View.GONE);
        } else {
            updateFavCount();
            toolbar_favs.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clearList();
                    selectToolbar(toolbar.findViewById(R.id.search_header_toolbar_favs));
                    favsLoad(engine_favs, engine_favs.get("get"));
                }
            });
        }
        toolbar_news.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearList();
                selectToolbar(toolbar_news);
                request(new Runnable() {
                    @Override
                    public void run() {
                        search(news, null, new Runnable() {
                            @Override
                            public void run() {
                                requestCancel(); // destory looper thread
                            }
                        });
                    }
                }, null);
            }
        });
        if (news == null) {
            toolbar_news.setVisibility(View.GONE);
        }
        for (int i = toolbar.getChildCount() - 1; i >= 0; i--) {
            View v = toolbar.getChildAt(i);
            if (v.getId() == R.id.search_header_toolbar_tops) {
                toolbar.removeView(v);
            }
        }
        if (top != null) {
            String type;
            final Map<String, String> post = engine.getMap(top.get(type = "post"));
            loadTops(top, type, post);
            final Map<String, String> get = engine.getMap(top.get(type = "get"));
            loadTops(top, type, get);
            final Map<String, String> json_post = engine.getMap(top.get(type = "json_post"));
            loadTops(top, type, json_post);
            final Map<String, String> json_get = engine.getMap(top.get(type = "json_get"));
            loadTops(top, type, json_get);
        }

        if (toolbarIndex != -1) {
            if (toolbarIndex < toolbar.getChildCount()) // when we update engine and it has less items
                selectToolbar(toolbar.getChildAt(toolbarIndex));
        }

        list.addHeaderView(header);
        list.addFooterView(footer);

        list.setAdapter(this);

        gridUpdate();

        handler.post(new Runnable() {
            @Override
            public void run() {
                // hide keyboard on search completed
                InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.showSoftInputFromInputMethod(searchText.getWindowToken(), 0);
            }
        });

        openDefaultInstall();
    }

    void openDefaultInstall() {
        if (getCount() == 0)
            openDefault();
    }

    void openDefault() {
        Map<String, String> home = engine.getMap("home");
        String d = home.get("default");
        if (d == null || d.isEmpty())
            return;
        String[] dd = d.split("/");
        String g = dd[0];
        if (g.equals("news")) {
            toolbar_news.performClick();
        }
        if (g.equals("tops")) {
            String p = dd[1];
            for (int i = 0; i < toolbar.getChildCount(); i++) {
                View v = toolbar.getChildAt(i);
                String t = (String) v.getTag();
                if (t != null && t.equals(p)) {
                    v.performClick();
                }
            }
        }
        if (g.equals("favs")) {
            toolbar_favs.performClick();
        }
    }

    void messageProgress() {
        final int DELAY = 10;
        if (message_panel.getChildCount() == 0)
            return;
        View c = message_panel.getChildAt(0);
        View message_close = c.findViewById(R.id.search_header_message_close);
        ProgressBar v = (ProgressBar) c.findViewById(R.id.search_header_message_progress);
        int t = (int) v.getTag();
        t++;
        int p = t * DELAY * 100 / MESSAGE_AUTOCLOSE / 1000;
        v.setTag(t);
        v.setProgress(p);
        if (p >= 100) {
            message_close.performClick();
        }
        handler.postDelayed(message_panel_progress, DELAY);
    }

    void updateFavCount() {
        toolbar_favs_name.setText("" + db.favsCount(engine.getName()));
    }

    void gridUpdate() {
        if (gridView != null) {
            grid.setNumColumns(GridView.AUTO_FIT);
            grid.setColumnWidth(gridView.getLayoutParams().width);
            grid.setStretchMode(GridView.STRETCH_SPACING);
            grid.requestLayout();
        } else { // restore original xml
            gridRestore();
        }
    }

    void gridRestore() {
        grid.setNumColumns(1);
        grid.setColumnWidth(GridView.AUTO_FIT);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.requestLayout();
    }

    @Override
    public void remove(HeaderGridView list) {
        lastSearch = searchText.getText().toString();
        list.removeHeaderView(header);
        list.removeFooterView(footer);
        gridRestore();
        handler.removeCallbacks(message_panel_progress);
    }

    void loadTops(final Map<String, String> top, final String type, Map<String, String> tops) {
        if (tops == null)
            return;

        LayoutInflater inflater = LayoutInflater.from(getContext());

        for (String key : tops.keySet()) {
            final String url = tops.get(key);
            View v = inflater.inflate(R.layout.search_rating, null);
            v.setTag(key);
            TextView text = (TextView) v.findViewById(R.id.search_header_toolbar_tops_name);
            text.setText(key);
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clearList();
                    selectToolbar(v);
                    request(new Runnable() {
                        @Override
                        public void run() {
                            search(top, type, url, null, new Runnable() {
                                @Override
                                public void run() {
                                    requestCancel(); // destory looper thread
                                }
                            });
                        }
                    }, null);
                }
            });
            int i;
            for (i = 0; i < toolbar.getChildCount(); i++) {
                View c = toolbar.getChildAt(i);
                if (c.getId() == R.id.search_header_toolbar_favs) {
                    break;
                }
            }
            toolbar.addView(v, i);
        }
    }

    void selectToolbar(View v) {
        toolbarIndex = -1;
        for (int i = 0; i < toolbar.getChildCount(); i++) {
            View c = toolbar.getChildAt(i);
            AppCompatImageButton cc = getCheckBox(c);
            if (c == v) {
                toolbarIndex = i;
                int[] states = new int[]{
                        android.R.attr.state_checked,
                };
                cc.setImageState(states, false);
            } else {
                int[] states = new int[]{
                        -android.R.attr.state_checked,
                };
                cc.setImageState(states, false);
            }
        }
    }

    AppCompatImageButton getCheckBox(View v) {
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = getCheckBox(g.getChildAt(i));
                if (c != null) {
                    return (AppCompatImageButton) c;
                }
            }
        }
        if (v instanceof AppCompatImageButton) {
            return (AppCompatImageButton) v;
        }
        return null;
    }

    void updateHeaderButtons() {
        if (thread == null) {
            header_progress.setVisibility(View.INVISIBLE);
            header_stop.setVisibility(View.INVISIBLE);
            header_search.setVisibility(View.VISIBLE);
        } else {
            header_progress.setVisibility(View.VISIBLE);
            header_stop.setVisibility(View.VISIBLE);
            header_search.setVisibility(View.INVISIBLE);
        }
    }

    void updateFooterButtons() {
        Thread thread = this.thread;

        if (next == null) {
            footer.setVisibility(View.GONE);
        } else {
            footer.setVisibility(View.VISIBLE);
        }

        if (thread == null) {
            footer_buttons.setVisibility(View.VISIBLE);
            if (engine_favs != null) {
                long c;
                if (engine_favs == nextSearch) {
                    c = db.favsCount(engine.getName());
                    footer_add.setVisibility(View.INVISIBLE);
                } else {
                    c = list.size();
                    footer_add.setVisibility(View.VISIBLE);
                }
                footer_add_text.setText(getContext().getString(R.string.footer_add_name, c));
                footer_add_text.setTag(c);
                footer_remove_text.setText(getContext().getString(R.string.footer_remove_name, c));
                footer_remove_text.setTag(c);
            } else {
                footer_add.setVisibility(View.INVISIBLE);
                footer_remove.setVisibility(View.INVISIBLE);
            }
            footer_progress.setVisibility(View.GONE);
            footer_stop.setVisibility(View.GONE);
        } else {
            footer_buttons.setVisibility(View.GONE);
            footer_progress.setVisibility(View.VISIBLE);
            footer_stop.setVisibility(View.VISIBLE);
        }
    }

    void requestCancel(final AbstractExecutionAwareRequest r) {
        if (r != null) {
            Thread thread = new Thread(new Runnable() { // network on main thread
                @Override
                public void run() {
                    r.abort();
                }
            }, "Abort Thread");
            thread.start();
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    void requestCancel() {
        boolean i = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
            i = true;
        }
        requestCancel(http.getRequest());
        if (threadLooper != null) {
            threadLooper.quit();
            threadLooper = null;
            i = true;
        }
        if (i)
            Log.d(TAG, "interrupt");
    }

    void clearDownloads() {
        for (View item : downloadsViews.keySet()) {
            DownloadImageTask t = downloadsViews.get(item);
            t.cancel(true);
        }
        downloadsViews.clear();
        for (SearchItem item : downloadsItems.keySet()) {
            DownloadImageTask t = downloadsItems.get(item);
            t.cancel(true);
        }
        downloadsItems.clear();
    }

    void clearList() {
        clearDownloads();
        Search.this.list.clear();
        Search.this.next = null;
        Search.this.nextLast.clear();
        Search.this.nextText = null;
        footer.setVisibility(View.GONE);
        notifyDataSetChanged();
    }

    void request(final Runnable run, final Runnable done) {
        requestCancel();

        header_progress.setVisibility(View.VISIBLE);
        header_stop.setVisibility(View.VISIBLE);
        header_search.setVisibility(View.GONE);

        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Looper.prepare();
                    threadLooper = Looper.myLooper();
                    run.run();
                    Looper.loop();
                } catch (final RuntimeException e) {
                    if (thread != null) // ignore errors on abort()
                        post(e);
                } finally {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            Log.d(TAG, "Destory Web");

                            if (web != null) {
                                web.destroy();
                                web = null;
                            }
                            // we are this thread, clear it
                            thread = null;
                            threadLooper = null;

                            updateHeaderButtons();
                            updateFooterButtons();

                            if (done != null)
                                done.run();
                        }
                    });
                    Log.d(TAG, "Thread Exit");
                }
            }
        }, "Search Request");
        thread.start();
    }

    public Context getContext() {
        return context;
    }

    public void update() {
        notifyDataSetChanged();
    }

    public void close() {
        requestCancel();
        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);
        shared.unregisterOnSharedPreferenceChangeListener(this);
        clearDownloads();
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        this.dialog = null;

        if (dialog instanceof LoginDialogFragment.Result) {
            final LoginDialogFragment.Result l = (LoginDialogFragment.Result) dialog;
            if (l.browser) {
                if (l.clear) {
                    http.clearCookies();
                }
                if (l.cookies != null && !l.cookies.isEmpty())
                    http.addCookies(l.cookies);
            } else if (l.ok) {
                request(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            lastLogin = l.login;
                            login(l.login, l.pass, new Runnable() {
                                @Override
                                public void run() {
                                    requestCancel(); // destory looper thread
                                    main.getEngines().save();
                                }
                            });
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }, null);
            }
        }

        if (dialog instanceof BrowserDialogFragment.Result) {
            final BrowserDialogFragment.Result l = (BrowserDialogFragment.Result) dialog;
            if (htmlupdate != null && l.html != null && !l.html.isEmpty()) {
                htmlupdate.update(l.html);
                htmlupdate = null;
            }
        }

        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return list.size();
    }

    @Override
    public SearchItem getItem(int i) {
        return list.get(i);
    }

    @Override
    public long getItemId(int i) {
        return i;
    }

    @Override
    public View getView(final int position, View convertView, final ViewGroup parent) {
        LayoutInflater inflater = LayoutInflater.from(getContext());

        if (convertView != null) {
            if (gridView != null) {
                if ((int) convertView.getTag() != R.layout.search_item_grid)
                    convertView = null;
            } else {
                if ((int) convertView.getTag() != R.layout.search_item_list)
                    convertView = null;
            }
        }

        if (convertView == null) {
            if (gridView != null) {
                convertView = inflater.inflate(R.layout.search_item_grid, parent, false);
                convertView.setTag(R.layout.search_item_grid);
            } else {
                convertView = inflater.inflate(R.layout.search_item_list, parent, false);
                convertView.setTag(R.layout.search_item_list);
            }
        }

        final SearchItem item = getItem(position);

        DownloadImageTask task = downloadsViews.get(convertView);
        if (task != null) { // reuse imageview
            task.views.remove(convertView);
        }
        task = downloadsItems.get(item);
        if (task != null) { // add new ImageView to populate on finish
            task.views.add(convertView);
        }
        if (!item.update) {
            boolean needDownloadImage = item.image != null && item.imageBitmap == null;
            boolean needCallUpdate = item.search.get("update") != null;
            if (needDownloadImage || needCallUpdate) {
                if (task == null) {
                    task = new DownloadImageTask(convertView);
                    task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, item); // RejectedExecutionException
                }
                downloadsItems.put(item, task);
                downloadsViews.put(convertView, task);
            }
        }

        updateView(item, convertView);

        return convertView;
    }

    boolean updateImage(SearchItem item) { // pending image extraction?
        String update = item.search.get("update");
        if (update == null || update.isEmpty())
            return false;
        Map<String, String> s = engine.getMap(update);
        String image = s.get("image");
        if (image == null || image.isEmpty())
            return false;
        return true;
    }

    void updateView(final SearchItem item, View convertView) {
        View frame = convertView.findViewById(R.id.search_item_frame);
        View updateProgress = convertView.findViewById(R.id.update_progress);
        ImageView image = (ImageView) convertView.findViewById(R.id.search_item_image);

        if (downloadsItems.containsKey(item)) {
            updateProgress.setVisibility(View.VISIBLE);
        } else {
            updateProgress.setVisibility(View.GONE);
        }

        if (item.image != null || updateImage(item)) {
            frame.setVisibility(View.VISIBLE);
            if (item.imageBitmap == null) { // downloading
                image.setImageDrawable(ContextCompat.getDrawable(getContext(), R.drawable.ic_crop_original_black_24dp));
            } else {
                image.setImageBitmap(item.imageBitmap);
            }
        } else {
            frame.setVisibility(View.GONE);
        }

        TextView date = (TextView) convertView.findViewById(R.id.search_item_date);
        if (item.date == null || item.date.isEmpty()) {
            date.setVisibility(View.GONE);
        } else {
            date.setVisibility(View.VISIBLE);
            date.setText(item.date);
        }

        TextView size = (TextView) convertView.findViewById(R.id.search_item_size);
        if (item.size == null || item.size.isEmpty()) {
            size.setVisibility(View.GONE);
        } else {
            size.setVisibility(View.VISIBLE);
            size.setText(item.size);
        }

        TextView seed = (TextView) convertView.findViewById(R.id.search_item_seed);
        if (item.seed == null) {
            seed.setVisibility(View.GONE);
        } else {
            seed.setVisibility(View.VISIBLE);
            seed.setText(context.getString(R.string.seed_tab) + " " + item.seed);
        }

        TextView leech = (TextView) convertView.findViewById(R.id.search_item_leech);
        if (item.leech == null) {
            leech.setVisibility(View.GONE);
        } else {
            leech.setVisibility(View.VISIBLE);
            leech.setText(context.getString(R.string.leech_tab) + " " + item.leech);
        }

        TextView text = (TextView) convertView.findViewById(R.id.search_item_name);
        text.setText(item.title);

        final ImageView fav = (ImageView) convertView.findViewById(R.id.search_item_fav);
        fav.setVisibility(View.GONE);
        if (engine_favs != null) {
            fav.setVisibility(View.VISIBLE);
            fav.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    item.fav = !item.fav;
                    favsSave(item);
                    updateFav(item, fav);
                    updateFavCount();
                }
            });
            updateFav(item, fav);
        }

        ImageView magnet = (ImageView) convertView.findViewById(R.id.search_item_magnet);
        magnet.setEnabled(false);
        magnet.setColorFilter(Color.GRAY);
        magnet.setVisibility(View.GONE);
        if (item.magnet != null) {
            magnet.setVisibility(View.VISIBLE);
            magnet.setEnabled(true);
            magnet.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    main.addMagnet(item.magnet);
                }
            });
            magnet.setColorFilter(ThemeUtils.getThemeColor(getContext(), R.attr.colorAccent));
        }

        ImageView torrent = (ImageView) convertView.findViewById(R.id.search_item_torrent);
        torrent.setEnabled(false);
        torrent.setColorFilter(Color.GRAY);
        torrent.setVisibility(View.GONE);
        if (item.torrent != null) {
            torrent.setVisibility(View.VISIBLE);
            torrent.setEnabled(true);
            torrent.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    request(new Runnable() {
                        @Override
                        public void run() {
                            final HttpClient.DownloadResponse w = http.getResponse(item.base, item.torrent);
                            w.download();
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (w.getError() != null) {
                                        Error(w.getError());
                                    } else {
                                        Storage.Torrent tt = main.addTorrentFromBytes(w.getBuf());
                                        if (tt == null)
                                            return;
                                        String filter = item.search.get("torrent_filter");
                                        if (filter != null) {
                                            Libtorrent.torrentFilesCheckAll(tt.t, false);
                                            Libtorrent.torrentFilesCheckFilter(tt.t, filter, true);
                                        }
                                    }
                                    requestCancel();  // destory looper thread
                                }
                            });
                        }
                    }, null);
                }
            });
            torrent.setColorFilter(ThemeUtils.getThemeColor(getContext(), R.attr.colorAccent));
        }

        if (item.details != null) {
            convertView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (dialog != null)
                        return;

                    final String url = item.details;

                    String head = nextSearch.get("details_head");
                    String js = nextSearch.get("details_js");
                    String js_post = nextSearch.get("details_js_post");

                    BrowserDialogFragment d = BrowserDialogFragment.create(head, url, http.getCookies(), js, js_post);
                    final String update = item.search.get("update");
                    if (update != null && !update.isEmpty()) {
                        final Map<String, String> details = engine.getMap(update);
                        htmlupdate = new HtmlUpdateListener() {
                            @Override
                            public void update(String html) {
                                detailsList(item, details, url, html);
                            }
                        };
                    }
                    dialog = d;
                    d.show(main.getSupportFragmentManager(), "");
                }
            });
        }

        if (item.details_html != null) {
            convertView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (dialog != null)
                        return;

                    String head = nextSearch.get("details_head");
                    String js = nextSearch.get("details_js");
                    String js_post = nextSearch.get("details_js_post");

                    BrowserDialogFragment d = BrowserDialogFragment.createHtml(item.base, head, item.details_html, js, js_post);
                    dialog = d;
                    d.show(main.getSupportFragmentManager(), "");
                }
            });
        }
    }

    void updateFav(SearchItem item, ImageView fav) {
        if (item.fav)
            fav.setImageResource(R.drawable.ic_star_black_24dp);
        else
            fav.setImageResource(R.drawable.ic_star_border_black_24dp);
    }

    public void inject(final String url, final HttpClient.DownloadResponse html, String js, String js_post, final Inject exec) {
        if (web != null) {
            web.destroy();
        }
        web = inject(http, url, html, js, js_post, exec);
    }

    public WebViewCustom inject(HttpProxyClient http, final String url, final HttpClient.DownloadResponse html, String js, String js_post, final Inject exec) {
        Log.d(TAG, "inject()");

        String func = "main: {\n  function result() {\n    torrentclient.result(document.documentElement.outerHTML);\n  }\n\n";
        String result = ";\n\n  result();\n}";

        String script = null;
        if (js != null) {
            script = js;
        }
        String script_post = null;
        if (js_post != null) {
            script_post = func + js_post + result;
        } else if (js != null) {
            script = func + script + result;
        } else { // we must have result() called no matter what
            script = func + result;
        }

        final WebViewCustom web = new WebViewCustom(context) {
            @Override
            public boolean onConsoleMessage(String msg, int lineNumber, String sourceID) {
                if (BrowserDialogFragment.logIgnore(msg))
                    return super.onConsoleMessage(msg, lineNumber, sourceID);
                if (sourceID == null || sourceID.isEmpty() || sourceID.startsWith(INJECTS_URL)) {
                    Error(msg + "\nLine:" + lineNumber + "\n" + formatInjectError(sourceID, lineNumber));
                } else if (exec.json != null) { // we uploaded json, then html errors is our responsability
                    String[] lines = this.getHtml().split("\n");
                    int t = lineNumber - 1;
                    String line = "";
                    if (t > 0 && t < lines.length)
                        line = "\n" + lines[t];
                    Search.this.post(msg + "\nLine:" + lineNumber + line);
                }
                return super.onConsoleMessage(msg, lineNumber, sourceID);
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                Error(message);
                return super.onJsAlert(view, url, message, result);
            }

            @Override
            protected String loadBase(Document doc) {
                Elements links = doc.select("meta[http-equiv=refresh]");
                if (!links.isEmpty()) { // do not inject redirect pages
                    return doc.outerHtml();
                }
                return super.loadBase(doc);
            }
        };
        web.setHttpClient(http);
        web.setInject(script);
        web.setInjectPost(script_post);
        web.addJavascriptInterface(exec, "torrentclient");
        web.getSettings().setAllowFileAccess(true);
        web.getSettings().setJavaScriptCanOpenWindowsAutomatically(true);
        // Uncaught SecurityError: Failed to read the 'cookie' property from 'Document': Cookies are disabled inside 'data:' URLs.
        // called when page loaded with loadData()
        if (html == null)
            web.loadHtmlWithBaseURL(url, "", url);
        else
            web.loadHtmlWithBaseURL(url, html, url);

        return web;
    }

    public void login(String login, String pass, final Runnable done) throws IOException {
        final Map<String, String> s = engine.getMap("login");

        final String post = s.get("post");
        if (post != null) {
            String l = s.get("post_login");
            String p = s.get("post_password");
            HashMap<String, String> map = new HashMap<>();
            if (l != null)
                map.put(l, login);
            if (p != null)
                map.put(p, pass);
            String pp = s.get("post_params");
            if (pp != null) {
                String[] params = pp.split(";");
                for (String param : params) {
                    String[] m = param.split("=");
                    map.put(URLDecoder.decode(m[0].trim(), MainApplication.UTF8), URLDecoder.decode(m[1].trim(), MainApplication.UTF8));
                }
            }
            final HttpClient.DownloadResponse html = http.postResponse(null, post, map);
            html.download();

            final String js = s.get("js");
            final String js_post = s.get("js_post");
            if (js != null || js_post != null) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        inject(post, html, js, js_post, new Inject() {
                            @JavascriptInterface
                            public void result(String html) {
                                super.result(html);
                                handler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (done != null)
                                            done.run();
                                    }
                                });
                            }

                            @JavascriptInterface
                            public String json() {
                                return super.json();
                            }
                        });
                    }
                });
                return;
            }
        }

        // TODO get

        if (done != null)
            done.run();
    }

    public void search(Map<String, String> s, String search, final Runnable done) {
        String url;
        String type;

        String post = s.get(type = "post");
        if (post != null) {
            url = post;
            search(s, type, url, search, done);
            return;
        }

        String get = s.get(type = "get");
        if (get != null) {
            if (search != null) {
                try {
                    String query = URLEncoder.encode(search, MainApplication.UTF8);
                    url = get.replaceAll("%%QUERY%%", query);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else {
                url = get;
            }
            search(s, type, url, search, done);
            return;
        }

        String json_get = s.get(type = "json_get");
        if (json_get != null) {
            if (search != null) {
                try {
                    String query = URLEncoder.encode(search, MainApplication.UTF8);
                    url = json_get.replaceAll("%%QUERY%%", query);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else {
                url = json_get;
            }
            search(s, type, url, search, done);
            return;
        }

        String json_post = s.get(type = "json_post");
        if (json_post != null) {
            url = json_post;
            search(s, type, url, search, done);
            return;
        }
    }

    public void search(final Map<String, String> s, String type, final String url, final String search, final Runnable done) {
        String select = gridUpdate(s);
        if (select.equals("crawl")) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    gridUpdate();
                    searchCrawl(s, search, url, done);
                }
            });
            return;
        }

        HttpClient.DownloadResponse html = null;
        String json = null;

        if (type.equals("post")) {
            String t = s.get("post_search");
            HashMap<String, String> map = new HashMap<>();
            if (search != null) {
                map.put(t, search);
            }
            String pp = s.get("post_params");
            if (pp != null) {
                String[] params = pp.split(";");
                for (String param : params) {
                    String[] m = param.split("=");
                    try {
                        map.put(URLDecoder.decode(m[0].trim(), MainApplication.UTF8), URLDecoder.decode(m[1].trim(), MainApplication.UTF8));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            html = http.postResponse(null, url, map);
            html.download();
        }
        if (type.equals("get")) {
            html = http.getResponse(null, url);
            html.download();
        }
        if (type.equals("json_get")) {
            json = http.get(null, url).trim();
        }
        if (type.equals("json_post")) {
            String t = s.get("json_post_search");
            String[][] data = new String[][]{};
            if (search != null)
                data = new String[][]{{t, search}};
            json = http.post(null, url, data).trim();
        }

        if (html != null) {
            searchHtml(s, type, url, html, done);
            return;
        }
        if (json != null) {
            searchJson(s, type, url, json, done);
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (done != null)
                    done.run();
            }
        });
        throw new RuntimeException("html or json not set");
    }

    public void searchJson(final Map<String, String> s, final String type, final String url, final String json, final Runnable done) {
        this.nextLast.add(url);

        final String js = s.get("js");
        final String js_post = s.get("js_post");
        if (js == null && js_post == null) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (done != null)
                        done.run();
                }
            });
            throw new RuntimeException("js not set");
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                inject(url, null, js, js_post, new Inject(json) {
                    @JavascriptInterface
                    public void result(final String html) {
                        super.result(html);
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    searchList(s, type, url, html);
                                } catch (final RuntimeException e) {
                                    Error(e);
                                } finally {
                                    if (done != null)
                                        done.run();
                                }
                            }
                        });
                    }

                    @JavascriptInterface
                    public String json() {
                        return super.json();
                    }
                });
            }
        });
    }

    public void searchHtml(final Map<String, String> s, final String type, final String url, final HttpClient.DownloadResponse html, final Runnable done) {
        this.nextLast.add(url);

        final String js = s.get("js");
        final String js_post = s.get("js_post");
        if (js != null || js_post != null || html.getError() != null) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    inject(url, html, js, js_post, new Inject() {
                        @JavascriptInterface
                        public void result(final String html) {
                            super.result(html);
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        searchList(s, type, url, html);
                                    } catch (final RuntimeException e) {
                                        Error(e);
                                    } finally {
                                        if (done != null)
                                            done.run();
                                    }
                                }
                            });
                        }

                        @JavascriptInterface
                        public String json() {
                            return super.json();
                        }
                    });
                }
            });
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    searchList(s, type, url, html);
                } catch (final RuntimeException e) {
                    Error(e);
                } finally {
                    if (done != null)
                        done.run();
                }
            }
        });
    }

    void searchList(Map<String, String> s, String type, String url, HttpClient.DownloadResponse html) {
        searchList(s, type, url, html == null ? "" : html.getHtml());
    }

    String gridUpdate(Map<String, String> s) {
        String select = null;
        String l = s.get("list");
        if (l != null) {
            select = l;
            gridView = null;
        }
        String g = s.get("grid");
        if (g != null) {
            select = g;
            LayoutInflater inflater = LayoutInflater.from(getContext());
            gridView = inflater.inflate(R.layout.search_item_grid, grid, false);
        }
        return select;
    }

    // UI thread
    void searchList(Map<String, String> s, String type, String url, String html) {
        String select = gridUpdate(s);
        gridUpdate();

        Document doc = Jsoup.parse(html);
        Elements list = doc.select(select);
        for (int i = 0; i < list.size(); i++) {
            SearchItem item = new SearchItem(s, url, list.get(i).outerHtml());

            // do not add empty items
            if (isEmpty(item.title) && isEmpty(item.magnet) && isEmpty(item.torrent) && isEmpty(item.details))
                continue;

            if (engine_favs != null) {
                Cursor c = db.exist(engine.getName(), item);
                if (c != null) {
                    SearchItem old = db.getSearchItem(s, c);
                    item.update(old);
                    item.fav = old.fav; // force update db value to the list
                }
            }

            this.list.add(item);
        }

        String next = matcherUrl(url, html, s.get("next"), null);
        if (next != null) {
            for (String last : nextLast) {
                if (next.equals(last)) {
                    next = null;
                    break;
                }
            }
        }
        this.next = next;
        this.nextSearch = s;
        this.nextType = type;
        this.nextText = null;

        updateFooterButtons();

        if (list.size() > 0) { // hide keyboard on search sucecful completed
            hideKeyboard();
        }

        notifyDataSetChanged();
    }

    public void post(final Throwable e) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                Error(e);
            }
        });
    }

    public void post(final String msg) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                Error(msg);
            }
        });
    }

    public void Error(final Throwable e) {
        if (!main.isFinishing() && main.active(this)) {
            error = main.Error(e); // log exception
            error.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
                    error = null;
                }
            });
        } else {
            Log.d(TAG, "Exception", e);
            Throwable t = e;
            while (t.getCause() != null)
                t = t.getCause();
            message.add(t.getMessage());
            main.updateUnread();
        }
    }

    public void Error(String msg) {
        if (!main.isFinishing() && main.active(this)) {
            error = main.Error(msg);
            error.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
                    error = null;
                }
            });
        } else {
            message.add(msg);
            main.updateUnread();
        }
    }

    @Override
    public int getUnreadCount() {
        return message.size();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        http.update(context);
    }

    public void hideKeyboard() {
        handler.post(new Runnable() { // not always works on first call, use 'handler'
            @Override
            public void run() {
                InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(searchText.getWindowToken(), 0);
            }
        });
    }

    // delete entry from EngineManager, 'trash' icon
    public void delete() {
        db.getWritableDatabase().delete(Crawl.CrawlEntry.TABLE_NAME, Crawl.CrawlEntry.COLUMN_ENGINE + " == ?", new String[]{engine.getName()});
        db.getWritableDatabase().delete(Crawl.IndexEntry.TABLE_NAME, Crawl.IndexEntry.COL_ENGINE + " == ?", new String[]{engine.getName()});
    }

    void detailsLoad(HttpProxyClient httpImages, final SearchItem item) {
        final String url = item.details;
        if (url == null || url.isEmpty()) {
            return;
        }

        final String update = item.search.get("update");
        if (update == null || update.isEmpty()) {
            return;
        }

        final Map<String, String> details = engine.getMap(update);
        if (!item.needUpdate(details))
            return;

        HttpClient.DownloadResponse html = httpImages.getResponse(null, url);
        html.download();

        detailsLoad(httpImages, item, details, url, html);
    }

    void detailsLoad(final HttpProxyClient httpImages, final SearchItem item, final Map<String, String> details, final String url, final HttpClient.DownloadResponse html) {
        final String js = item.search.get("details_js");
        final String js_post = item.search.get("details_js_post");

        if (js != null || js_post != null) {
            final Object lock = new Object();
            final ArrayList<WebViewCustom> ww = new ArrayList<>();
            Runnable request = new Runnable() {
                @Override
                public void run() {
                    WebViewCustom web = inject(httpImages, url, html, js, js_post, new Inject() {
                        @JavascriptInterface
                        public void result(final String html) {
                            super.result(html);
                            detailsList(item, details, url, html);
                            synchronized (lock) {
                                lock.notifyAll();
                            }
                        }

                        @JavascriptInterface
                        public String json() {
                            return super.json();
                        }
                    });
                    ww.add(web);
                }
            };
            handler.post(request); // web must run on UI thread
            synchronized (lock) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            request = new Runnable() {
                @Override
                public void run() {
                    for (WebViewCustom w : ww)
                        w.destroy();
                }
            };
            handler.post(request); // web must run on UI thread
        } else {
            detailsList(item, details, url, html.getHtml());
        }
    }

    void detailsList(SearchItem item, Map<String, String> s, String url, String html) { // not UI thread
        item.update(s, url, html);
        item.base = url;
        if (engine_favs != null) {
            if (item.fav) {
                favsSave(item);
            }
        }
    }

    void searchCrawl(Map<String, String> s, String search, String order, final Runnable done) {
        String next = null;
        String nextText = null;

        int count = 0;

        if (search != null) {
            search = search.toLowerCase(EN);
            Cursor c = db.getWordMatches(engine.getName(), search, null, order, this.list.size(), CRAWL_SHOW + 1);
            while (c != null) {
                count++;
                if (count > CRAWL_SHOW) {
                    next = order;
                    nextText = search;
                    break;
                }
                SearchItem item = db.getSearchItem(s, c);
                if (item != null)
                    this.list.add(item);
                if (!c.moveToNext())
                    break;
            }
        } else {
            Cursor c = db.search(engine.getName(), order, this.list.size(), CRAWL_SHOW + 1);
            while (c != null) {
                count++;
                if (count > CRAWL_SHOW) {
                    next = order;
                    nextText = null;
                    break;
                }
                SearchItem item = db.getSearchItem(s, c);
                if (item != null)
                    this.list.add(item);
                if (!c.moveToNext())
                    break;
            }
        }

        this.next = next;
        this.nextText = nextText;
        this.nextSearch = s;

        notifyDataSetChanged();

        if (count > 0)
            hideKeyboard();

        if (done != null)
            done.run();
    }

    void favsLoad(Map<String, String> s, String order) {
        String select = gridUpdate(s);
        gridUpdate();

        String next = null;
        String nextText = null;

        int count = 0;

        Cursor c = db.favs(engine.getName(), order, this.list.size(), Crawl.CRAWL_SHOW + 1);
        while (c != null) {
            count++;
            if (count > Crawl.CRAWL_SHOW) {
                next = order;
                nextText = null;
                break;
            }
            SearchItem item = db.getSearchItem(s, c);
            if (item != null)
                this.list.add(item);
            if (!c.moveToNext())
                break;
        }

        this.next = next;
        this.nextText = nextText;
        this.nextSearch = s;
        this.nextType = null;

        updateFooterButtons();

        if (count > 0)
            hideKeyboard();

        notifyDataSetChanged();
    }

    void favsSave(SearchItem item) {
        if (item.id == null) {
            Cursor c = db.exist(engine.getName(), item);
            if (c != null)
                item.id = getLong(c, Crawl.CrawlEntry._ID);
        }
        if (item.id == null) {
            item.id = db.addCrawl(engine.getName(), item);
        } else {
            db.updateCrawl(item.id, item);
        }
    }

    void footerRemove() {
        if (engine_favs == nextSearch) {
            db.favsAllUpdate(engine.getName(), false);
            list.clear();
            next = null;
        } else {
            for (SearchItem item : list) {
                item.fav = false;
                favsSave(item);
            }
        }
        updateFavCount();
        notifyDataSetChanged();
        updateFooterButtons();
    }

    void footerAdd() {
        if (engine_favs == nextSearch) {
            ; // not exist (we cant add anything staying on 'favs' tab)
        } else {
            for (SearchItem item : list) {
                item.fav = true;
                favsSave(item);
            }
        }
        updateFavCount();
        notifyDataSetChanged();
        updateFooterButtons();
    }
}
