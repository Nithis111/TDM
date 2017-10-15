package com.github.axet.torrentclient.fragments;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.database.DataSetObserver;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.github.axet.androidlibrary.widgets.OpenFileDialog;
import com.github.axet.torrentclient.R;
import com.github.axet.torrentclient.activities.MainActivity;
import com.github.axet.torrentclient.app.MainApplication;
import com.github.axet.wget.SpeedInfo;

import java.util.ArrayList;
import java.util.List;

import libtorrent.Libtorrent;
import libtorrent.Tracker;
import libtorrent.WebSeedUrl;

public class TrackersFragment extends Fragment implements MainActivity.TorrentFragmentInterface {
    View v;
    View header;

    View webSeedsText;
    ListView webSeeds;
    WebSeedsAdapter ws;
    TextView dhtLast;
    TextView pex;
    TextView lpd;
    View add;
    View empty;

    Files files;
    ListView list;

    class Files extends BaseAdapter {
        ArrayList<Tracker> ff = new ArrayList<>();

        @Override
        public int getCount() {
            return ff.size();
        }

        @Override
        public Tracker getItem(int i) {
            return ff.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public boolean isEmpty() {
            return false; // show header if list empty
        }

        @Override
        public View getView(final int i, View view, ViewGroup viewGroup) {
            LayoutInflater inflater = LayoutInflater.from(getContext());

            if (view == null) {
                view = inflater.inflate(R.layout.torrent_trackers_item, viewGroup, false);
            }

            final long t = getArguments().getLong("torrent");

            View trash = view.findViewById(R.id.torrent_trackers_trash);
            trash.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
                    builder.setTitle(R.string.delete_tracker);
                    builder.setMessage(ff.get(i).getAddr() + "\n\n" + getContext().getString(R.string.are_you_sure));
                    builder.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.cancel();
                            Libtorrent.torrentTrackerRemove(t, ff.get(i).getAddr());
                            update();
                        }
                    });
                    builder.setNegativeButton(R.string.no, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.cancel();
                        }
                    });
                    builder.show();
                }
            });

            TextView url = (TextView) view.findViewById(R.id.torrent_trackers_url);
            TextView lastAnnounce = (TextView) view.findViewById(R.id.torrent_trackers_lastannounce);
            TextView nextAnnounce = (TextView) view.findViewById(R.id.torrent_trackers_nextannounce);
            TextView lastScrape = (TextView) view.findViewById(R.id.torrent_trackers_lastscrape);

            Tracker f = getItem(i);

            url.setText(f.getAddr());

            String scrape = MainApplication.formatDate(f.getLastScrape());

            if (f.getLastScrape() != 0)
                scrape += " (S:" + f.getSeeders() + " L:" + f.getLeechers() + " D:" + f.getDownloaded() + ")";

            String ann = MainApplication.formatDate(f.getLastAnnounce());

            if (f.getError() != null && !f.getError().isEmpty()) {
                ann += " (" + f.getError() + ")";
            } else {
                if (f.getLastAnnounce() != 0)
                    ann += " (P:" + f.getPeers() + ")";
            }
            MainApplication.setTextNA(lastAnnounce, ann);
            MainApplication.setDate(nextAnnounce, f.getNextAnnounce());
            MainApplication.setTextNA(lastScrape, scrape);

            return view;
        }

        void update() {
            final long t = getArguments().getLong("torrent");
            ff.clear();
            long l = Libtorrent.torrentTrackersCount(t);
            for (long i = 0; i < l; i++) {
                Tracker tt = Libtorrent.torrentTrackers(t, i);
                String url = tt.getAddr();
                if (url.equals("PEX")) {
                    MainApplication.setTextNA(pex, Libtorrent.torrentActive(t) ? tt.getPeers() + "" : "");
                    continue;
                }
                if (url.equals("LPD")) {
                    MainApplication.setTextNA(lpd, Libtorrent.torrentActive(t) ? tt.getPeers() + "" : "");
                    continue;
                }
                if (url.equals("DHT")) {
                    String str = MainApplication.formatDate(tt.getLastAnnounce());
                    if (tt.getError() != null && !tt.getError().isEmpty())
                        str += " (" + tt.getError() + ")";
                    else {
                        if (tt.getLastAnnounce() != 0)
                            str += " (P: " + tt.getPeers() + ")";
                    }
                    MainApplication.setTextNA(dhtLast, str);
                    continue;
                }
                ff.add(tt);
            }
            notifyDataSetChanged();
        }
    }

    public static class WebSeedExt {
        WebSeedUrl ws;
        SpeedInfo downloaded;

        public WebSeedExt(WebSeedUrl w) {
            ws = w;
        }
    }

    class WebSeedsAdapter extends BaseAdapter {
        ArrayList<WebSeedExt> webseeds = new ArrayList<>();

        public WebSeedsAdapter() {
            final long t = getArguments().getLong("torrent");
            for (int k = 0; k < Libtorrent.torrentWebSeedsCount(t); k++) {
                WebSeedUrl ws = Libtorrent.torrentWebSeeds(t, k);
                webseeds.add(new WebSeedExt(ws));
            }
        }

        @Override
        public int getCount() {
            return webseeds.size();
        }

        @Override
        public WebSeedExt getItem(int i) {
            return webseeds.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public boolean isEmpty() {
            return false; // show header if list empty
        }

        @Override
        public View getView(final int i, View view, ViewGroup viewGroup) {
            LayoutInflater inflater = LayoutInflater.from(getContext());

            if (view == null) {
                view = inflater.inflate(R.layout.torrent_trackers_webseed, viewGroup, false);
            }

            TextView url = (TextView) view.findViewById(R.id.webseed_url);
            TextView text = (TextView) view.findViewById(R.id.webseed_text);
            TextView error = (TextView) view.findViewById(R.id.webseed_error);

            WebSeedExt f = getItem(i);

            text.setText("");

            url.setText(f.ws.getUrl());
            String err = f.ws.getError();
            if (err != null && !err.isEmpty()) {
                error.setText(err);
                error.setVisibility(View.VISIBLE);
            } else {
                error.setVisibility(View.GONE);
                if (f.downloaded != null) {
                    text.setText(MainApplication.formatSize(getContext(), f.downloaded.getAverageSpeed()) + getContext().getString(R.string.per_second));
                }
            }


            return view;
        }

        void update() {
            final long t = getArguments().getLong("torrent");
            boolean a = Libtorrent.torrentActive(t);
            for (WebSeedExt w : ws.webseeds) {
                if (a) {
                    if (w.downloaded == null) {
                        w.downloaded = new SpeedInfo();
                        w.downloaded.start(w.ws.getDownloaded());
                    } else {
                        w.downloaded.step(w.ws.getDownloaded());
                    }
                } else {
                    w.downloaded = null;
                }
            }
            notifyDataSetChanged();
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        v = inflater.inflate(R.layout.torrent_trackers, list, false);
        list = (ListView) v.findViewById(R.id.list);
        header = inflater.inflate(R.layout.torrent_trackers_header, list, false);

        files = new Files();

        list.addHeaderView(header);
        list.setAdapter(files);

        empty = v.findViewById(R.id.empty_list);

        add = header.findViewById(R.id.torrent_trackers_add);
        dhtLast = (TextView) header.findViewById(R.id.torrent_trackers_dht_last);
        pex = (TextView) header.findViewById(R.id.torrent_trackers_pex);
        lpd = (TextView) header.findViewById(R.id.torrent_trackers_lpd);

        final long t = getArguments().getLong("torrent");

        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                final OpenFileDialog.EditTextDialog e = new OpenFileDialog.EditTextDialog(getContext());
                e.setTitle(getContext().getString(R.string.add_tracker));
                e.setText("");
                e.setPositiveButton(new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Libtorrent.torrentTrackerAdd(t, e.getText());
                        update();
                    }
                });
                e.show();
            }
        });

        webSeedsText = v.findViewById(R.id.trackers_webseeds_text);
        webSeeds = (ListView) v.findViewById(R.id.trackers_webseeds);

        ws = new WebSeedsAdapter();
        webSeeds.setAdapter(ws);
        if (ws.webseeds.size() == 0) {
            webSeedsText.setVisibility(View.GONE);
            webSeeds.setVisibility(View.GONE);
        }

        update();

        return v;
    }

    @Override
    public void update() {
        files.update();

        if (files.getCount() == 0)
            empty.setVisibility(View.VISIBLE);
        else
            empty.setVisibility(View.GONE);

        ws.update();
    }

    @Override
    public void close() {
    }
}
