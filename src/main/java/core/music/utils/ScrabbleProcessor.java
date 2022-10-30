package core.music.utils;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import core.Chuu;
import core.music.sources.MetadataTrack;
import core.music.sources.youtube.webscrobbler.ChuuYoutubeAudioTrack;
import core.music.sources.youtube.webscrobbler.processers.ChuuAudioTrackInfo;
import core.music.sources.youtube.webscrobbler.processers.Processed;
import core.services.validators.AlbumFinder;
import dao.entities.Album;
import dao.entities.Metadata;
import jdk.incubator.concurrent.StructuredTaskScope;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

public record ScrabbleProcessor(AlbumFinder albumFinder) {
    public static final Cache<String, TrackScrobble> processed = Caffeine.newBuilder()
            .maximumSize(500).build();


    public TrackScrobble processScrobble(@Nullable Metadata metadata, AudioTrack song) {
        return processed.get(song.getIdentifier(), (s) -> newScrobble(metadata, song));
    }

    public TrackScrobble setMetadata(@Nonnull Metadata metadata, @Nonnull AudioTrack song, long current, long total) {
        TrackScrobble prev = processed.getIfPresent(song.getIdentifier());
        assert prev != null;

        TrackScrobble.DurationIndex index = prev.mapDuration(current, total);
        Processed intercepted = prev.processeds().get(index.index() - 1);
        var b = prev.withProcess(prev.processeds().stream().map(z -> z == intercepted ? new Processed(metadata.artist(), metadata.album(), metadata.song()) : z).toList());
        processed.put(song.getIdentifier(), b);
        return b;
    }

    public TrackScrobble setMetadata(@Nonnull Metadata metadata, @Nonnull AudioTrack song, UUID previous) {
        TrackScrobble newMapping = newScrobble(metadata, song);
        newMapping = new TrackScrobble(newMapping.scrobble(), newMapping.processeds(), song.getIdentifier(), previous);
        processed.put(song.getIdentifier(), newMapping);
        return newMapping;
    }

    public TrackScrobble newScrobble(@Nullable Metadata metadata, AudioTrack song) {
        AudioTrackInfo info = song.getInfo();
        InnerScrobble inn = new InnerScrobble(info.author, info.title, null, null, song.getDuration() != Long.MAX_VALUE ? song.getDuration() : null, new ArrayList<>());
        if (song instanceof MetadataTrack spo) {
            inn = inn.withAlbum(spo.getAlbum()).withImage(spo.getImage());
        } else if (info instanceof ChuuAudioTrackInfo chA) {
            inn = inn.fromChuu(chA);
        } else if (song instanceof YoutubeAudioTrack youtubeAudioTrack) {
            if (youtubeAudioTrack instanceof ChuuYoutubeAudioTrack cyat) {
                if (cyat.isSet()) {
                    ChuuAudioTrackInfo newInfo = cyat.newInfo;
                    inn = inn.fromChuu(newInfo);
                } else {
                    try {
                        cyat.processInfo();
                        inn = inn.fromAudioTrack(cyat.getInfo());
                    } catch (Exception e) {
                        Chuu.getLogger().warn("Error processing song that was not yet processed {}", cyat, e);
                    }
                }
            }
        } else {
            inn = inn.withFilter();
        }
        if (inn.artist() != null && inn.song() != null && StringUtils.isBlank(inn.album())) {
            inn = processAlbum(inn);
        }
        return new TrackScrobble(inn
                .withMetadata(metadata), song.getIdentifier());
    }


    public InnerScrobble processAlbum(InnerScrobble innerScrobble) {
        try (var scope = new StructuredTaskScope.ShutdownOnSuccess<Album>()) {
            scope.fork(() -> albumFinder.find(innerScrobble.artist(), innerScrobble.song()).orElseThrow());
            scope.fork(() -> albumFinder.findSpotify(innerScrobble.artist(), innerScrobble.song()).orElseThrow());
            scope.join();
            Album result = scope.result();
            return innerScrobble.withAlbum(result.albumName()).withImage(result.url());
        } catch (InterruptedException | ExecutionException e) {
            Chuu.getLogger().debug("Error processing album", e);
            return innerScrobble;
        }
    }
}
