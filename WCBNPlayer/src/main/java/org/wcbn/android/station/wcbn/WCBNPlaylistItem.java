package org.wcbn.android.station.wcbn;


import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import org.jsoup.nodes.Element;
import org.wcbn.android.R;
import org.wcbn.android.Utils;

import java.util.List;
import java.util.Objects;

public class WCBNPlaylistItem implements Parcelable {

    private String mTime, mArtist, mTitle, mAlbum, mLabel;

    WCBNPlaylistItem(Element element) {
        setElement(element);
    }

    WCBNPlaylistItem(Parcel in) {
        mTime = in.readString();
        mArtist = in.readString();
        mTitle = in.readString();
        mAlbum = in.readString();
        mLabel = in.readString();
    }

    public void setElement(Element element) {
        List<Element> elements = element.select("td");

        // This is done a bit badly…
        int j = 0;
        for(int i = 0; j < 5 && i < 50; i++) {
            if(!elements.get(i).hasAttr("rowspan")) {
                switch(j) {
                    case 0: mTime = elements.get(i).text().trim(); break;
                    case 1: mArtist = elements.get(i).text().trim(); break;
                    case 2: mTitle = elements.get(i).text().trim(); break;
                    case 3: mAlbum = elements.get(i).text().trim(); break;
                    case 4: mLabel = elements.get(i).text().trim(); break;
                }
                j++;
            }
        }
    }

    public View getView(Context context) {

        LayoutInflater inflater =
                (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        View view = Objects.requireNonNull(inflater).inflate(R.layout.item_playlist, null);

        ((TextView) view.findViewById(R.id.time_text)).setText(mTime);
        ((TextView) view.findViewById(R.id.artist_text)).setText(Utils.capitalizeTitle(mArtist));
        ((TextView) view.findViewById(R.id.song_text)).setText(Utils.capitalizeTitle(mTitle));
        ((TextView) view.findViewById(R.id.album_text)).setText(Utils.capitalizeTitle(mAlbum));

        view.findViewById(R.id.time_text).setSelected(true);
        view.findViewById(R.id.artist_text).setSelected(true);
        view.findViewById(R.id.song_text).setSelected(true);
        view.findViewById(R.id.album_text).setSelected(true);

        return view;
    }

    public static final Parcelable.Creator<WCBNPlaylistItem> CREATOR
            = new Parcelable.Creator<WCBNPlaylistItem>() {
        public WCBNPlaylistItem createFromParcel(Parcel in) {
            return new WCBNPlaylistItem(in);
        }

        public WCBNPlaylistItem[] newArray(int size) {
            return new WCBNPlaylistItem[size];
        }
    };


    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mTime);
        dest.writeString(mArtist);
        dest.writeString(mTitle);
        dest.writeString(mAlbum);
        dest.writeString(mLabel);
    }
}
