package com.github.axet.torrentclient.net;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;

import com.github.axet.androidlibrary.widgets.OptimizationPreferenceCompat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import javax.net.SocketFactory;

import cz.msebera.android.httpclient.HttpHost;
import cz.msebera.android.httpclient.HttpRequest;
import cz.msebera.android.httpclient.conn.socket.ConnectionSocketFactory;
import cz.msebera.android.httpclient.conn.socket.PlainConnectionSocketFactory;
import cz.msebera.android.httpclient.conn.ssl.SSLConnectionSocketFactory;
import cz.msebera.android.httpclient.protocol.HttpContext;

public class TorProxy implements Proxy {
    public static final String NAME = "tor";

    public final static String ORBOT_PACKAGE_NAME = "org.torproject.android";
    public final static String ACTION_STATUS = "org.torproject.android.intent.action.STATUS";

    public final static String ACTION_START = "org.torproject.android.intent.action.START";

    public final static String EXTRA_STATUS = "org.torproject.android.intent.extra.STATUS";
    public final static String EXTRA_HTTP_PROXY = "org.torproject.android.intent.extra.HTTP_PROXY";
    public final static String EXTRA_HTTP_PROXY_HOST = "org.torproject.android.intent.extra.HTTP_PROXY_HOST";
    public final static String EXTRA_HTTP_PROXY_PORT = "org.torproject.android.intent.extra.HTTP_PROXY_PORT";
    public final static String EXTRA_SOCKS_PROXY = "org.torproject.android.intent.extra.SOCKS_PROXY";
    public final static String EXTRA_SOCKS_PROXY_HOST = "org.torproject.android.intent.extra.SOCKS_PROXY_HOST";
    public final static String EXTRA_SOCKS_PROXY_PORT = "org.torproject.android.intent.extra.SOCKS_PROXY_PORT";
    public final static String EXTRA_PACKAGE_NAME = "org.torproject.android.intent.extra.PACKAGE_NAME";

    public final static String STATUS_ON = "ON";
    public final static String STATUS_STARTING = "STARTING";
    public final static String STATUS_STOPPING = "STOPPING";

    public static String HOST_DEFAULT = "127.0.0.1";
    public static String PORT_DEFAULT = "9050"; // like Privoxy!

    TorReceiver receiver;
    java.net.Proxy proxy;

    public static void status(Context context) {
        Intent i = new Intent(ACTION_STATUS);
        i.setPackage(ORBOT_PACKAGE_NAME);
        i.putExtra(EXTRA_PACKAGE_NAME, context.getPackageName());
        context.sendBroadcast(i);
    }

    public static void start(Context context) {
        Intent i = new Intent(ACTION_START);
        i.setPackage(ORBOT_PACKAGE_NAME);
        context.sendBroadcast(i);
    }

    public static boolean isOrbotInstalled(Context context) {
        return isAppInstalled(context, ORBOT_PACKAGE_NAME);
    }

    private static boolean isAppInstalled(Context context, String uri) {
        try {
            PackageManager pm = context.getPackageManager();
            pm.getPackageInfo(uri, PackageManager.GET_ACTIVITIES);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static class TorReceiver extends BroadcastReceiver {
        Context context;
        String status = "";
        String host = HOST_DEFAULT;
        String port = PORT_DEFAULT;

        public TorReceiver(Context context) {
            this.context = context;
            IntentFilter ff = new IntentFilter(ACTION_STATUS);
            context.registerReceiver(this, ff);
            status(context);
        }

        public void close() {
            context.unregisterReceiver(this);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (a.equals(ACTION_STATUS)) {
                String status = intent.getStringExtra(EXTRA_STATUS);
                if (!status.equals(this.status)) {
                    this.status = status;
                    if (status.equals(STATUS_ON)) {
                        host = intent.getStringExtra(EXTRA_SOCKS_PROXY_HOST);
                        if (host == null)
                            host = HOST_DEFAULT;
                        port = intent.getStringExtra(EXTRA_SOCKS_PROXY_PORT);
                        if (port == null)
                            port = PORT_DEFAULT;
                        onStart();
                    } else {
                        onStop();
                    }
                }
            }
        }

        public void onStart() {
        }

        public void onStop() {
        }
    }

    public class Orichid implements ConnectionSocketFactory {
        ConnectionSocketFactory base;

        public Orichid(ConnectionSocketFactory b) {
            base = b;
        }

        @Override
        public Socket createSocket(HttpContext context) throws IOException {
            return new Socket(proxy);
        }

        @Override
        public Socket connectSocket(int connectTimeout, Socket sock, HttpHost host, InetSocketAddress remoteAddress, InetSocketAddress localAddress, HttpContext context) throws IOException {
            return base.connectSocket(connectTimeout, sock, host, remoteAddress, localAddress, context);
        }
    }

    public TorProxy(Context context, final HttpProxyClient c) {
        receiver = new TorReceiver(context) {
            @Override
            public void onStart() {
                super.onStart();
                update();
            }
        };
        update();
        c.http.base = new Orichid(PlainConnectionSocketFactory.getSocketFactory());
        c.https.base = new Orichid(SSLConnectionSocketFactory.getSocketFactory());
        status(context);
    }

    void update() {
        proxy = new java.net.Proxy(java.net.Proxy.Type.SOCKS, new InetSocketAddress(receiver.host, Integer.valueOf(receiver.port)));
    }

    @Override
    public void close() {
        if (receiver != null) {
            receiver.close();
            receiver = null;
        }
    }

    @Override
    public void filter(HttpRequest request, HttpContext context) {
        if (!receiver.status.equals(STATUS_ON)) {
            start(this.receiver.context);
        }
    }
}
