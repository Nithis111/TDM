package com.github.axet.torrentclient.fragments;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.database.DataSetObserver;
import android.os.Bundle;
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

import java.util.ArrayList;

import libtorrent.Libtorrent;
import libtorrent.Tracker;

public class TrackersFragment extends Fragment implements MainActivity.TorrentFragmentInterface {
    View v;
    View header;

    View webSeedsText;
    ListView webSeeds;
    TextView dhtLast;
    TextView pex;
    TextView lpd;
    View add;

    ArrayList<Tracker> ff = new ArrayList<>();
    Files files;
    ListView list;

    class Files extends BaseAdapter {
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
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        v = inflater.inflate(R.layout.torrent_trackers, container, false);
        header = v;
        list = (ListView) v.findViewById(R.id.list);
//        list = new ListView(getContext());
//        v = list;

        files = new Files();

//        list.addHeaderView(header);
        list.setAdapter(files);

        View empty = v.findViewById(R.id.empty_list);
        list.setEmptyView(empty);

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

        ArrayList<String> webseeds = new ArrayList<>();
        for (int k = 0; k < Libtorrent.torrentWebSeedsCount(t); k++) {
            webseeds.add(Libtorrent.torrentWebSeeds(t, k));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_list_item_1, webseeds);
        webSeeds.setAdapter(adapter);
        if (webseeds.size() == 0) {
            webSeedsText.setVisibility(View.GONE);
            webSeeds.setVisibility(View.GONE);
        }

        update();

        return v;
    }

    @Override
    public void update() {
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
        files.notifyDataSetChanged();
    }

    @Override
    public void close() {
    }
}
