package dao.musicbrainz;

import com.neovisionaries.i18n.CountryCode;
import core.exceptions.ChuuServiceException;
import dao.SimpleDataSource;
import dao.entities.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;

public class MusicBrainzServiceImpl implements MusicBrainzService {
    private final SimpleDataSource dataSource;
    private final MbizQueriesDao mbizQueriesDao;

    public MusicBrainzServiceImpl() {
        this.dataSource = new SimpleDataSource(false);
        mbizQueriesDao = new MbizQueriesDaoImpl();
    }


    @Override
    public List<AlbumInfo> listOfYearReleases(List<AlbumInfo> mbiz, Year year) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            if (mbiz.isEmpty()) {
                return new ArrayList<>();
            }
            return mbizQueriesDao.getYearAlbums(connection, mbiz, year);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public List<CountWrapper<AlbumInfo>> listOfYearReleasesWithAverage(List<AlbumInfo> mbiz, Year year) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            if (mbiz.isEmpty()) {
                return new ArrayList<>();
            }
            return mbizQueriesDao.getYearAverage(connection, mbiz, year);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public List<AlbumInfo> listOfCurrentYear(List<AlbumInfo> mbiz) {
        return this.listOfYearReleases(mbiz, Year.now());
    }

    @Override
    public List<AlbumInfo> findArtistByRelease(List<AlbumInfo> releaseInfo, Year year) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            if (releaseInfo.isEmpty()) {
                return new ArrayList<>();
            }

            return mbizQueriesDao.getYearAlbumsByReleaseName(connection, releaseInfo, year);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public List<AlbumInfo> findArtistByReleaseLowerCase(List<AlbumInfo> releaseInfo, Year year) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return mbizQueriesDao.getYearAlbumsByReleaseNameLowerCase(connection, releaseInfo, year);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public List<AlbumInfo> findArtistByReleaseCurrentYear(List<AlbumInfo> releaseInfo) {
        return null;
    }

    @Override
    public Map<Genre, Integer> genreCount(List<AlbumInfo> releaseInfo) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return mbizQueriesDao.genreCount(connection, releaseInfo);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }

    }

    @Override
    public Map<Country, Integer> countryCount(List<ArtistInfo> artistInfo) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return mbizQueriesDao.countryCount(connection, artistInfo);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public List<ArtistUserPlays> getArtistFromCountry(CountryCode country, BlockingQueue<UrlCapsule> queue, long discordId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);

            List<ArtistInfo> artistInfos = queue.stream()
                    .map(capsule -> new ArtistInfo(capsule.getUrl(), capsule.getArtistName(), capsule.getMbid()))
                    .filter(u -> u.getMbid() != null && !u.getMbid().isEmpty())
                    .collect(Collectors.toList());
            if (artistInfos.isEmpty()) {
                return new ArrayList<>();
            }

            List<String> artistFromCountry = mbizQueriesDao.getArtistFromCountry(connection, country, artistInfos);

            return queue.stream().filter(u -> u.getMbid() != null && !u.getMbid().isEmpty() && artistFromCountry.contains(u.getMbid()))
                    .map(x -> new ArtistUserPlays(x.getArtistName(), x.getPlays(), discordId)).collect(Collectors.toList());
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }

    }

    @Override
    public List<Track> getAlbumTrackListMbid(String mbid) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return mbizQueriesDao.getAlbumTrackListMbid(connection, mbid);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public List<Track> getAlbumTrackList(String artist, String album) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return mbizQueriesDao.getAlbumTrackList(connection, artist, album);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public List<Track> getAlbumTrackListLowerCase(String artist, String album) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return mbizQueriesDao.getAlbumTrackListLower(connection, artist, album);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public ArtistMusicBrainzDetails getArtistDetails(ArtistInfo artist) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return mbizQueriesDao.getArtistInfo(connection, artist);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public List<TrackInfo> getAlbumInfoByNames(List<UrlCapsule> urlCapsules) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            return mbizQueriesDao.getAlbumInfoByName(connection, urlCapsules);
        } catch (
                SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public void getAlbumInfoByMbid(List<UrlCapsule> urlCapsules) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            mbizQueriesDao.getAlbumInfoByMbid(connection, urlCapsules);
        } catch (
                SQLException e) {
            throw new ChuuServiceException(e);
        }
    }


    @Override
    public List<CountWrapper<AlbumInfo>> findArtistByReleaseLowerCaseWithAverage(List<AlbumInfo> emptyMbid, Year year) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return mbizQueriesDao.getYearAlbumsByReleaseNameLowerCaseAverage(connection, emptyMbid, year);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }

    }

    @Override
    public List<CountWrapper<AlbumInfo>> findArtistByReleaseWithAverage(List<AlbumInfo> releaseInfo, Year year) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            if (releaseInfo.isEmpty()) {
                return new ArrayList<>();
            }

            return mbizQueriesDao.getYearAlbumsByReleaseNameAverage(connection, releaseInfo, year);
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }
}
