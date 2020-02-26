package dao;

import dao.entities.*;

import java.sql.Connection;
import java.util.List;

interface SQLQueriesDao {


    UniqueWrapper<ArtistPlays> getUniqueArtist(Connection connection, Long guildID, String lastFMID);


    ResultWrapper<UserArtistComparison> similar(Connection connection, List<String> lastfMNames);

    WrapperReturnNowPlaying knows(Connection connection, long artistId, long guildId, int limit);


    UniqueWrapper<ArtistPlays> getCrowns(Connection connection, String lastFmId, long guildID);

    List<UrlCapsule> getGuildTop(Connection connection, Long guildID, int limit);

    int userPlays(Connection con, long artistId, String whom);

    List<LbEntry> crownsLeaderboard(Connection con, long guildID);

    List<LbEntry> uniqueLeaderboard(Connection connection, long guildId);

    int userArtistCount(Connection con, String whom);

    List<LbEntry> artistLeaderboard(Connection con, long guildID);

    List<LbEntry> obscurityLeaderboard(Connection connection, long guildId);

    PresenceInfo getRandomArtistWithUrl(Connection connection);


    StolenCrownWrapper getCrownsStolenBy(Connection connection, String ogUser, String queriedUser, long guildId);

    UniqueWrapper<ArtistPlays> getUserAlbumCrowns(Connection connection, String lastfmID, long guildId);

    List<LbEntry> albumCrownsLeaderboard(Connection con, long guildID);

    ObscuritySummary getUserObscuritPoints(Connection connection, String lastfmid);

    int getRandomCount(Connection connection, Long userId);

    List<GlobalCrown> getGlobalKnows(Connection connection, long artistID);

    void getGlobalRank(Connection connection, String lastfmid);

    UniqueWrapper<ArtistPlays> getGlobalCrowns(Connection connection, String lastFmId);

    UniqueWrapper<ArtistPlays> getGlobalUniques(Connection connection, String lastfmId);

    ResultWrapper<ArtistPlays> getArtistPlayCount(Connection connection, Long guildId);

    ResultWrapper<ArtistPlays> getArtistFrequencies(Connection connection, Long guildId);

    ResultWrapper<ArtistPlays> getGlobalArtistPlayCount(Connection connection);

    ResultWrapper<ArtistPlays> getGlobalArtistFrequencies(Connection connection);

    List<ScrobbledArtist> getAllUsersArtist(Connection connection, long discordId);

    List<LbEntry> matchingArtistCount(Connection connection, long userId, long guildId);
}
