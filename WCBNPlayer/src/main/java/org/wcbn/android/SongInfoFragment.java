package org.wcbn.android;

import android.app.Service;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import net.moraleboost.streamscraper.Stream;

import org.wcbn.android.station.Station;

/**
 * Displays information about the current track.
 */
public class SongInfoFragment extends Fragment implements UiFragment {

    private TextView mSongText, mArtistText, mDescriptionText;
    private StreamService mService;
    private Station mStation;
    private StreamExt mStream;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.fragment_songinfo, null);
        mSongText = view.findViewById(R.id.song_text);
        mArtistText = view.findViewById(R.id.artist_text);
        mDescriptionText = view.findViewById(R.id.description_text);

        mSongText.setSelected(true);
        mArtistText.setSelected(true);
        mDescriptionText.setSelected(true);

        if(mStation != null && mStream != null) {
            mSongText.setText(mStation.getSongName(mStream, getActivity()));
            mArtistText.setText(mStation.getArtistName(mStream, getActivity()));
            mDescriptionText.setText(mStation.getDescription((mStream), getActivity()));
        }

        else if(savedInstanceState != null && savedInstanceState.containsKey("song") &&
                savedInstanceState.containsKey("artist") &&
                savedInstanceState.containsKey("description")) {
            mSongText.setText(savedInstanceState.getString("song"));
            mArtistText.setText(savedInstanceState.getString("artist"));
            mDescriptionText.setText(savedInstanceState.getString("description"));
        }

        return view;
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        if(mStation != null && mStream != null) {
            savedInstanceState.putString("song", (String) mSongText.getText());
            savedInstanceState.putString("artist", (String) mArtistText.getText());
            savedInstanceState.putString("description", (String) mDescriptionText.getText());
        }

        super.onSaveInstanceState(savedInstanceState);
    }

    @Override
    public void handleMediaError(MediaPlayer mp, int what, int extra) {

    }

    @Override
    public void handleMediaPlay() {

    }

    @Override
    public void handleMediaPause() {

    }

    @Override
    public void handleMediaStop() {

    }

    @Override
    public void handleUpdateTrack(Stream stream, Station station, Bitmap albumArt) {
        mSongText.setText(station.getSongName(((StreamExt) stream), mService));
        mArtistText.setText(station.getArtistName(((StreamExt) stream), mService));
        mDescriptionText.setText(station.getDescription(((StreamExt) stream), mService));

        mStation = station;
        mStream = new StreamExt();
        if(mService.getStream() != null)
            mStream.merge(stream);
    }

    @Override
    public void setService(Service service) {
        mService = (StreamService) service;

        mStation = mService.getStation();
        mStream = new StreamExt();
        if(mService.getStream() != null) {
            mStream.merge(mService.getStream());

            mSongText.setText(mStation.getSongName(mStream, getActivity()));
            mArtistText.setText(mStation.getArtistName(mStream, getActivity()));
            mDescriptionText.setText(mStation.getDescription(mStream, getActivity()));
        }
    }
}
