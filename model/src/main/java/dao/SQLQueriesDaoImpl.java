package dao;

import dao.entities.*;
import dao.exceptions.ChuuServiceException;
import dao.utils.Order;
import org.apache.commons.collections4.ListValuedMap;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;
import org.apache.commons.lang3.tuple.Pair;
import org.intellij.lang.annotations.Language;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.sql.*;
import java.time.Instant;
import java.time.Year;
import java.util.*;
import java.util.stream.Collectors;

import static dao.UpdaterDaoImpl.preparePlaceHolders;

public class SQLQueriesDaoImpl extends BaseDAO implements SQLQueriesDao {


    @Override
    public void getGlobalRank(Connection connection, String lastfmId) {
        // TODO ONE DAY
    }

    @Override
    public UniqueWrapper<ArtistPlays> getGlobalCrowns(Connection connection, String lastfmId, int threshold, boolean includeBottedUsers, long ownerId) {
        List<ArtistPlays> returnList = new ArrayList<>();
        long discordId = 0;

        String queryString =
                """
                        SELECT
                            main.orden,
                            b.name
                        FROM
                            (SELECT
                                 a.artist_id,
                                 a.orden
                             FROM
                                 (SELECT
                                      artist_id,
                                      playnumber AS orden
                                  FROM
                                      scrobbled_artist a
                                  WHERE
                                      a.lastfm_id = ?
                                      AND playnumber >= ?
                                 ) a
                                     JOIN (SELECT
                                               artist_id,
                                               max(playnumber) AS orden
                                           FROM
                                               scrobbled_artist in_a JOIN user u ON in_a.lastfm_id = u.lastfm_id
                                           WHERE ? OR NOT u.botted_account
                                           GROUP BY artist_id
                                 ) AS b
                                          ON a.artist_id = b.artist_id AND a.orden = b.orden
                            ) AS main
                                JOIN artist b ON main.artist_id = b.id
                        ORDER BY
                            orden DESC
                        """;

        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setString(i++, lastfmId);
            preparedStatement.setInt(i++, threshold);
            preparedStatement.setBoolean(i, includeBottedUsers);


            ResultSet resultSet = preparedStatement.executeQuery();


            while (resultSet.next()) {

                String artist = resultSet.getString(2);
                int plays = resultSet.getInt(1);
                returnList.add(new ArtistPlays(artist, plays));

            }
        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException(e);
        }
        return new UniqueWrapper<>(returnList.size(), discordId, lastfmId, returnList);

    }

    @Override
    public UniqueWrapper<ArtistPlays> getGlobalUniques(Connection connection, String lastfmId) {

        String queryString = """
                SELECT
                    name,
                    playnumber
                FROM
                    (
                        SELECT
                            b.playnumber,
                            b.artist_id
                        FROM
                            (
                                SELECT
                                    artist_id
                                FROM
                                    scrobbled_artist a
                                WHERE
                                      a.playnumber > 2
                                  AND lastfm_id = ?
                            ) temp
                                JOIN
                                scrobbled_artist b ON temp.artist_id = b.artist_id AND b.playnumber > 2
                        GROUP BY
                            b.artist_id
                        HAVING
                            count(*) = 1
                    ) main
                        JOIN artist c ON main.artist_id = c.id
                ORDER BY
                    playnumber DESC;
                """;

        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setString(i, lastfmId);
            ResultSet resultSet = preparedStatement.executeQuery();


            List<ArtistPlays> returnList = new ArrayList<>();
            long discordId = 0;
            while (resultSet.next()) {
                String name = resultSet.getString("name");
                int countA = resultSet.getInt("playNumber");

                returnList.add(new ArtistPlays(name, countA));

            }
            return new UniqueWrapper<>(returnList.size(), discordId, lastfmId, returnList);


        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
        }
        return null;
    }

    @Override
    public ResultWrapper<ArtistPlays> getArtistPlayCount(Connection connection, Long guildId) {
        String queryBody =
                "FROM  (SELECT artist_id,playnumber " +
                "FROM scrobbled_artist a" +
                " JOIN user b  " +
                " ON a.lastfm_id = b.lastfm_id " +
                " JOIN user_guild c " +
                " ON b.discord_id = c.discord_id" +
                " WHERE c.guild_id = ?) main" +
                " JOIN artist b ON" +
                " main.artist_id = b.id ";

        String normalQuery = "SELECT b.name, sum(playNumber) AS orden " + queryBody + " GROUP BY main.artist_id ORDER BY orden DESC  Limit 200";
        String countQuery = "Select sum(playnumber) " + queryBody;
        return getArtistPlaysResultWrapper(connection, guildId, normalQuery, countQuery);
    }

    @Nonnull
    private ResultWrapper<ArtistPlays> getArtistPlaysResultWrapper(Connection connection, Long guildId, String normalQuery, String countQuery) {
        try (PreparedStatement preparedStatement2 = connection.prepareStatement(countQuery)) {
            preparedStatement2.setLong(1, guildId);

            ResultSet resultSet = preparedStatement2.executeQuery();
            if (!resultSet.next()) {
                throw new ChuuServiceException();
            }
            int rows = resultSet.getInt(1);
            try (PreparedStatement preparedStatement = connection.prepareStatement(normalQuery)) {
                preparedStatement.setLong(1, guildId);
                return getArtistPlaysResultWrapper(rows, preparedStatement);
            }
        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public ResultWrapper<ArtistPlays> getArtistsFrequencies(Connection connection, Long guildId) {
        String queryBody =
                "FROM  (SELECT artist_id " +
                "FROM scrobbled_artist a" +
                " JOIN user b  " +
                " ON a.lastfm_id = b.lastfm_id " +
                " JOIN user_guild c " +
                " ON b.discord_id = c.discord_id" +
                " WHERE c.guild_id = ?) main" +
                " JOIN artist b ON" +
                " main.artist_id = b.id ";

        String normalQuery = "SELECT b.name, count(*) AS orden " + queryBody + " GROUP BY b.id ORDER BY orden DESC  Limit 200";
        String countQuery = "Select count(*) " + queryBody;
        return getArtistPlaysResultWrapper(connection, guildId, normalQuery, countQuery);
    }

    @Override
    public List<TagPlays> getServerTags(Connection connection, Long guildId, boolean doCount) {
        @Language("MariaDB") String query =
                "SELECT tag, " + (doCount ? "count(*)" : "sum(a.playnumber)") + " AS orden\n" +
                "FROM scrobbled_artist a\n" +
                "rtist a\n" +
                "         JOIN user b  " +
                " ON a.lastfm_id = b.lastfm_id " +
                " JOIN user_guild c " +
                " ON b.discord_id = c.discord_id" +
                " join artist_tags d on a.artist_id = d.artist_id " +
                " WHERE c.guild_id = ? " +
                " GROUP BY tag ORDER BY orden DESC limit 200";

        List<TagPlays> returnList = new ArrayList<>();
        try (PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setLong(1, guildId);
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                String name = resultSet.getString("tag");
                int count = resultSet.getInt("orden");
                returnList.add(new TagPlays(name, count));
            }
        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException(e);
        }

        return returnList;
    }

    @Nonnull
    private ResultWrapper<ArtistPlays> getArtistPlaysResultWrapper(int rows, PreparedStatement preparedStatement) throws SQLException {
        ResultSet resultSet;
        resultSet = preparedStatement.executeQuery();
        List<ArtistPlays> returnList = new ArrayList<>();
        while (resultSet.next()) {
            String name = resultSet.getString("name");
            int count = resultSet.getInt("orden");
            returnList.add(new ArtistPlays(name, count));
        }

        return new ResultWrapper<>(rows, returnList);
    }


    @Override
    public ResultWrapper<ArtistPlays> getGlobalArtistPlayCount(Connection connection) {
        String queryString =
                "FROM  scrobbled_artist a" +
                " JOIN artist b " +
                " ON a.artist_id = b.id ";


        String normalQuery = "SELECT b.name, sum(playNumber) AS orden " + queryString + "     GROUP BY artist_id  ORDER BY orden DESC  Limit 200";
        String countQuery = "Select sum(playNumber) " + queryString;

        return getArtistPlaysResultWrapper(connection, normalQuery, countQuery);

    }

    @Nonnull
    private ResultWrapper<ArtistPlays> getArtistPlaysResultWrapper(Connection connection, String normalQuery, String countQuery) {
        int rows;
        try (PreparedStatement preparedStatement2 = connection.prepareStatement(countQuery)) {

            ResultSet resultSet = preparedStatement2.executeQuery();
            if (!resultSet.next()) {
                throw new ChuuServiceException();
            }
            rows = resultSet.getInt(1);
            try (PreparedStatement preparedStatement = connection.prepareStatement(normalQuery)) {


                return getArtistPlaysResultWrapper(rows, preparedStatement);
            }
        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public ResultWrapper<ArtistPlays> getGlobalArtistFrequencies(Connection connection) {
        String queryString =
                "FROM  scrobbled_artist a" +
                " JOIN artist b " +
                " ON a.artist_id = b.id ";


        String normalQuery = "SELECT b.name, count(*) AS orden " + queryString + "     GROUP BY artist_id  ORDER BY orden DESC  Limit 200";
        String countQuery = "Select count(*)" + queryString;
        return getArtistPlaysResultWrapper(connection, normalQuery, countQuery);

    }

    @Override
    public List<ScrobbledArtist> getAllUsersArtist(Connection connection, long discordId, Integer limit) {
        List<ScrobbledArtist> scrobbledArtists = new ArrayList<>();
        String queryString = " Select * from scrobbled_artist a join artist b on a.artist_id = b.id join user" +
                             " c on a.lastfm_id = c.lastfm_id where c.discord_id = ? order by a.playnumber desc";
        if (limit != null) {
            queryString += " limit " + limit;
        }
        try (
                PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            preparedStatement.setLong(1, discordId);

            ResultSet resultSet = preparedStatement.executeQuery();

            while (resultSet.next()) {
                String artistName = resultSet.getString("b.name");
                String mbid = resultSet.getString("b.mbid");
                String artistUrl = resultSet.getString("b.url");
                int plays = resultSet.getInt("a.playnumber");
                ScrobbledArtist e = new ScrobbledArtist(null, artistName, plays);
                e.setArtistMbid(mbid);
                e.setUrl(artistUrl);
                scrobbledArtists.add(e);
            }
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
        return scrobbledArtists;
    }

    @Override
    public List<LbEntry<Integer>> matchingArtistCount(Connection connection, long userId, long guildId, Long threshold) {

        String queryString = """
                        SELECT discord_id,lastfm_id,COUNT(*) AS orden
                        FROM
                                (SELECT artist_id,a.lastfm_id,b.discord_id FROM scrobbled_artist a
                                        JOIN user b
                                        ON a.lastfm_id = b.lastfm_id
                                        JOIN user_guild c
                                        ON c.discord_id = b.discord_id
                                        WHERE c.guild_id = ?
                                AND b.discord_id != ?) main


                        WHERE main.artist_id IN
                        (SELECT artist_id FROM scrobbled_artist a JOIN user b ON a.lastfm_id = b.lastfm_id WHERE
                        discord_id = ?
                	)
                        GROUP BY lastfm_id,discord_id
                        ORDER BY orden DESC;
                """;
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setLong(i++, guildId);
            preparedStatement.setLong(i++, userId);
            preparedStatement.setLong(i, userId);

            List<LbEntry<Integer>> returnedList = new ArrayList<>();

            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) { //&& (j < 10 && j < rows)) {
                String lastfmId = resultSet.getString("lastfm_id");
                long discordId = resultSet.getLong("discord_id");
                int crowns = resultSet.getInt("orden");

                returnedList.add(new ArtistLbEntry(lastfmId, discordId, crowns));


            }
            return returnedList;
        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException((e));
        }
    }

    @Override
    public List<VotingEntity> getAllArtistImages(Connection connection, long artistId) {
        List<VotingEntity> returnedList = new ArrayList<>();
        String queryString = " SELECT a.id,a.url,a.score,a.discord_id,a.added_date,b.name,b.id,(SELECT COUNT(*) FROM vote WHERE alt_id = a.id) AS totalvotes FROM alt_url a JOIN artist b ON a.artist_id = b.id  WHERE a.artist_id = ? ORDER BY a.score DESC , added_date ASC ";
        try (
                PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            preparedStatement.setLong(1, artistId);

            ResultSet resultSet = preparedStatement.executeQuery();

            while (resultSet.next()) {
                String artistName = resultSet.getString("b.name");
                String artistUrl = resultSet.getString("a.url");
                long owner = resultSet.getLong("a.discord_id");
                artistId = resultSet.getLong("b.id");
                long urlId = resultSet.getLong("a.id");

                int votes = resultSet.getInt("a.score");
                int totalVotes = resultSet.getInt("totalVotes");
                Timestamp date = resultSet.getTimestamp("a.added_date");
                returnedList.add(new VotingEntity(artistName, artistUrl, date.toLocalDateTime(), owner, artistId, votes, totalVotes, urlId));
            }
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
        return returnedList;
    }

    @Override
    public Boolean hasUserVotedImage(Connection connection, long urlId, long discordId) {
        String queryString = "SELECT ispositive FROM vote WHERE alt_id = ? AND discord_id = ? ";
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            preparedStatement.setLong(1, urlId);
            preparedStatement.setLong(2, discordId);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                return resultSet.getBoolean(1);
            }
            return null;

        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }


    @Override
    public List<String> getArtistAliases(Connection connection, long artistId) {
        String queryString = "SELECT alias FROM corrections WHERE artist_id = ? ";
        List<String> returnList = new ArrayList<>();
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setLong(i, artistId);
            ResultSet resultSet = preparedStatement.executeQuery();

            while (resultSet.next()) { //&& (j < 10 && j < rows)) {
                String name = resultSet.getString("alias");
                returnList.add(name);
            }
            return returnList;

        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException(e);
        }


    }

    @Override
    public long getArtistPlays(Connection connection, Long guildID, long artistId) {
        String queryString = "SELECT sum(playnumber) FROM scrobbled_artist a " +
                             "JOIN user b " +
                             "ON a.lastfm_id = b.lastfm_id ";
        if (guildID != null) {
            queryString += " JOIN user_guild c " +
                           " ON b.discord_id = c.discord_id ";
        }
        queryString += " WHERE  artist_id = ? ";
        if (guildID != null) {
            queryString += " and c.guild_id = ?";
        }
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            preparedStatement.setLong(1, artistId);
            if (guildID != null) {
                preparedStatement.setLong(2, guildID);
            }
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                return resultSet.getLong(1);
            }
            return 0;
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public long getAlbumPlays(Connection connection, Long guildID, long albumId) {
        String queryString = "SELECT sum(playnumber) FROM scrobbled_album a " +
                             "JOIN user b " +
                             "ON a.lastfm_id = b.lastfm_id ";
        if (guildID != null) {
            queryString += " JOIN user_guild c " +
                           " ON b.discord_id = c.discord_id ";
        }
        queryString += " WHERE  album_id = ? ";
        if (guildID != null) {
            queryString += " and c.guild_id = ?";
        }
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            preparedStatement.setLong(1, albumId);
            if (guildID != null) {
                preparedStatement.setLong(2, guildID);
            }
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                return resultSet.getLong(1);
            }
            return 0;
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public long getSongPlays(Connection connection, Long guildID, long trackId) {
        String queryString = "SELECT sum(playnumber) FROM scrobbled_track a " +
                             "JOIN user b " +
                             "ON a.lastfm_id = b.lastfm_id ";
        if (guildID != null) {
            queryString += " JOIN user_guild c " +
                           " ON b.discord_id = c.discord_id ";
        }
        queryString += " WHERE  track_id = ? ";
        if (guildID != null) {
            queryString += " and c.guild_id = ?";
        }
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            preparedStatement.setLong(1, trackId);
            if (guildID != null) {
                preparedStatement.setLong(2, guildID);
            }
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                return resultSet.getLong(1);
            }
            return 0;
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public long getArtistFrequencies(Connection connection, Long guildID, long artistId) {

        String queryBody =
                "Select count(*) from  scrobbled_artist where artist_id = ? \n";

        if (guildID != null) queryBody += """
                and lastfm_id in (
                (SELECT lastfm_id from user b\s
                 JOIN user_guild c\s
                 ON b.discord_id = c.discord_id
                 WHERE c.guild_id = ?))""";
        else queryBody += "";

        try (PreparedStatement preparedStatement2 = connection.prepareStatement(queryBody)) {
            preparedStatement2.setLong(1, artistId);
            if (guildID != null)
                preparedStatement2.setLong(2, guildID);

            ResultSet resultSet = preparedStatement2.executeQuery();
            if (!resultSet.next()) {
                return 0;
            }
            return resultSet.getLong(1);

        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public long getAlbumFrequencies(Connection connection, Long guildID, long albumId) {

        String queryBody =
                "Select count(*) from  scrobbled_album where album_id = ? \n";

        if (guildID != null) queryBody += """
                and lastfm_id in (
                (SELECT lastfm_id from user b\s
                 JOIN user_guild c\s
                 ON b.discord_id = c.discord_id
                 WHERE c.guild_id = ?))""";
        else queryBody += "";

        try (PreparedStatement preparedStatement2 = connection.prepareStatement(queryBody)) {
            preparedStatement2.setLong(1, albumId);
            if (guildID != null)
                preparedStatement2.setLong(2, guildID);

            ResultSet resultSet = preparedStatement2.executeQuery();
            if (!resultSet.next()) {
                return 0;
            }
            return resultSet.getLong(1);

        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public long getSongFrequencies(Connection connection, Long guildID, long trackId) {

        String queryBody =
                "Select count(*) from  scrobbled_track where track_id = ? \n";

        if (guildID != null) queryBody += """
                and lastfm_id in (
                (SELECT lastfm_id from user b\s
                 JOIN user_guild c\s
                 ON b.discord_id = c.discord_id
                 WHERE c.guild_id = ?))""";
        else queryBody += "";

        try (PreparedStatement preparedStatement2 = connection.prepareStatement(queryBody)) {
            preparedStatement2.setLong(1, trackId);
            if (guildID != null)
                preparedStatement2.setLong(2, guildID);

            ResultSet resultSet = preparedStatement2.executeQuery();
            if (!resultSet.next()) {
                return 0;
            }
            return resultSet.getLong(1);

        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public int getGuildCrownThreshold(Connection connection, long guildID) {
        String queryBody = "SELECT crown_threshold FROM guild WHERE guild_id = ? ";
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryBody)) {
            preparedStatement.setLong(1, guildID);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                return resultSet.getInt(1);
            }
            return 0;
        } catch (SQLException ex) {
            throw new ChuuServiceException(ex);
        }
    }

    @Override
    public boolean getGuildConfigEmbed(Connection connection, long guildID) {
        String queryBody = "SELECT additional_embed FROM guild WHERE guild_id = ? ";
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryBody)) {
            preparedStatement.setLong(1, guildID);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                return resultSet.getBoolean(1);
            }
            return false;
        } catch (SQLException ex) {
            throw new ChuuServiceException(ex);
        }
    }

    @Override
    public List<LbEntry<Integer>> getScrobblesLeaderboard(Connection connection, long guildId) {
        String queryBody = "SELECT b.lastfm_id,b.discord_id, sum(a.playnumber) AS ord " +
                           "FROM scrobbled_artist a JOIN user b ON a.lastfm_id = b.lastfm_id " +
                           "JOIN user_guild ug ON b.discord_id = ug.discord_id " +
                           "WHERE ug.guild_id = ? " +
                           "GROUP BY b.discord_id,b.lastfm_id " +
                           "ORDER BY ord DESC ";
        return getLbEntries(connection, guildId, queryBody, ScrobbleLbEntry::new, false, -1, Integer.class);
    }

    @Override
    public List<CrownableArtist> getCrownable(Connection connection, String lastfmId, Long guildId, boolean skipCrowns, boolean onlySecond, int crownDistance) {
        List<CrownableArtist> list = new ArrayList<>();
        @Language("MariaDB") String sql;
        if (guildId != null) {

            sql = """
                    with guild_user as (select lastfm_id
                                                                     from user_guild ug
                                                                              join user u on ug.discord_id = u.discord_id
                                                                     where ug.guild_id = ?),
                                                 
                                                      user_scrobbles as (SELECT artist_id,
                                                                                playnumber
                                                                         FROM scrobbled_artist s_a
                                                                         where s_a.lastfm_id = ?),
                                                      all_users as (select all_users.artist_id,
                                                                           all_users.playnumber,
                                                                           all_users.lastfm_id
                                                                    from scrobbled_artist all_users
                                                                    where lastfm_id in (select lastfm_id from guild_user)),
                                                      ranks as (select a.artist_id,
                                                                       count(*)                                                    as total,
                                                                       count(IF(us_2.playnumber <= a.playnumber = false, null, 0)) as ranks,
                                                                       max(a.playnumber)                                           as maxplays
                                                                from all_users a
                                                                         join user_scrobbles us_2 on a.artist_id = us_2.artist_id
                                                 
                                                                group by a.artist_id)
                                                 SELECT *
                                                 FROM (SELECT us.artist_id  AS artist,
                                                   us.playnumber AS plays,
                                                   r.*,
                                                   b.name as name
                                                       from user_scrobbles us
                                                                join ranks r on us.artist_id = r.artist_id
                                                                join artist b on us.artist_id = b.id
                                                                ) main
                                                                where 1 = 1 
                                                               """;
        } else {
            sql = """
                    with user_scrobbles as (SELECT artist_id,
                                                                     playnumber
                                                              FROM scrobbled_artist s_a
                                                              where s_a.lastfm_id = ?),
                                           ranks as (select a.artist_id,
                                                            count(*)                                                 as total,
                                                            count(IF(b.playnumber <= a.playnumber = false, null, 0)) as ranks,
                                                            max(a.playnumber)                                        as maxplays
                                                     from scrobbled_artist a
                                                              join user_scrobbles b on a.artist_id = b.artist_id
                                                     group by a.artist_id)
                                      SELECT *
                                      FROM (SELECT us.artist_id  AS artist,
                                                   us.playnumber AS plays,
                                                   r.*,
                                                   b.name as name
                                            from user_scrobbles us
                                                     join artist b on us.artist_id = b.id
                                                     join ranks r on us.artist_id = r.artist_id
                                            ) main
                                            where 1 = 1
                    """;

        }

        if (skipCrowns) {
            sql += " and ranks != 1 ";
        }
        if (onlySecond) {
            sql += " and ranks = 2 ";
        }
        if (crownDistance != Integer.MAX_VALUE) {
            sql += " and maxplays - plays < ? ";
        }
        sql += "ORDER BY plays DESC LIMIT 500";


        int i = 1;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (guildId != null) {
                ps.setLong(i++, guildId);
            }
            ps.setString(i++, lastfmId);
            if (crownDistance != Integer.MAX_VALUE) {
                ps.setInt(i, crownDistance);
            }


            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                String artist = rs.getString("name");

                int plays = rs.getInt("plays");
                int rank = rs.getInt("ranks");
                int total = rs.getInt("total");
                int maxPlays = rs.getInt("maxplays");

                CrownableArtist who = new CrownableArtist(artist, plays, maxPlays, rank, total);
                list.add(who);
            }

        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException(e);
        }
        return list;
    }

    @Override
    public Map<Long, Float> getRateLimited(Connection connection) {
        String queryString = "SELECT discord_id,queries_second  FROM rate_limited ";

        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {

            Map<Long, Float> returnList = new HashMap<>();
            /* Execute query. */
            ResultSet resultSet = preparedStatement.executeQuery();

            while (resultSet.next()) {

                long guildId = resultSet.getLong("discord_id");
                float queries_second = resultSet.getFloat("queries_second");

                returnList.put(guildId, queries_second);

            }
            return returnList;
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }

    }

    @Override
    public WrapperReturnNowPlaying getGlobalWhoKnows(Connection con, long artistId, int limit, boolean includeBottedUsers, long ownerId, boolean hidePrivate) {


        String queryString =
                """    
                        SELECT a2.name, a.lastfm_id, a.playNumber, a2.url, c.discord_id,c.privacy_mode
                        FROM  scrobbled_artist a
                        JOIN artist a2 ON a.artist_id = a2.id  JOIN `user` c on c.lastFm_Id = a.lastFM_ID  where
                        (? or not c.botted_account or c.discord_id = ? )
                        and (not ? or c.privacy_mode not in ('NORMAL','STRICT') or c.discord_id = ?)
                         and  a2.id = ? ORDER BY a.playNumber desc
                                """;

        queryString = limit == Integer.MAX_VALUE ? queryString : queryString + "limit " + limit;
        try (
                PreparedStatement preparedStatement = con.prepareStatement(queryString)) {

            int i = 1;
            preparedStatement.setBoolean(i++, includeBottedUsers);
            preparedStatement.setLong(i++, ownerId);
            preparedStatement.setBoolean(i++, hidePrivate);
            preparedStatement.setLong(i++, ownerId);
            preparedStatement.setLong(i, artistId);




            /* Execute query. */

            ResultSet resultSet = preparedStatement.executeQuery();
            String url = "";
            String artistName = "";
            List<ReturnNowPlaying> returnList = new ArrayList<>();



            /* Get results. */
            int j = 0;
            while (resultSet.next() && (j < limit)) {
                url = resultSet.getString("a2.url");
                artistName = resultSet.getString("a2.name");
                String lastfmId = resultSet.getString("a.lastFM_ID");

                int playNumber = resultSet.getInt("a.playNumber");
                long discordId = resultSet.getLong("c.discord_ID");
                PrivacyMode privacyMode = PrivacyMode.valueOf(resultSet.getString("c.privacy_mode"));

                returnList.add(new GlobalReturnNowPlaying(discordId, lastfmId, artistName, playNumber, privacyMode));
            }
            /* Return booking. */
            return new WrapperReturnNowPlaying(returnList, returnList.size(), url, artistName);

        } catch (
                SQLException e) {
            throw new ChuuServiceException(e);
        }
    }


    @Override
    public List<ScrobbledArtist> getRecommendations(Connection connection, long giverDiscordId,
                                                    long receiverDiscordId, boolean doPast, int limit) {
        String queryBody = """
                SELECT\s
                b.name,b.url,b.id,a.playnumber\s
                FROM scrobbled_artist a\s
                JOIN artist b ON a.artist_id = b.id\s
                JOIN user c ON a.lastfm_id = c.lastfm_id WHERE c.discord_id =  ?\s
                 AND  (?  OR (a.artist_id NOT IN (SELECT r.artist_id FROM past_recommendations r WHERE receiver_id =  ? ))) AND a.artist_id NOT IN (SELECT in_b.artist_id FROM scrobbled_artist in_b  JOIN user in_c ON in_b.lastfm_id = in_c.lastfm_id WHERE in_c.discord_id = ? ) ORDER BY a.playnumber DESC LIMIT ?""";

        List<ScrobbledArtist> scrobbledArtists = new ArrayList<>();
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryBody)) {
            int i = 1;
            preparedStatement.setLong(i++, giverDiscordId);
            preparedStatement.setBoolean(i++, doPast);
            preparedStatement.setLong(i++, receiverDiscordId);

            preparedStatement.setLong(i++, receiverDiscordId);
            preparedStatement.setInt(i, limit);

            ResultSet resultSet = preparedStatement.executeQuery();

            while (resultSet.next()) {
                String name = resultSet.getString("name");
                String url = resultSet.getString("url");
                long id = resultSet.getLong("id");
                int playnumber = resultSet.getInt("playnumber");

                ScrobbledArtist scrobbledArtist = new ScrobbledArtist(name, playnumber, url);
                scrobbledArtist.setArtistId(id);
                scrobbledArtists.add(scrobbledArtist);
            }
            return scrobbledArtists;
        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException(e);
        }
    }


    @Override
    public UniqueWrapper<ArtistPlays> getUniqueArtist(Connection connection, Long guildID, String lastfmId) {
        String queryString = """
                SELECT
                    name,
                    playnumber
                FROM
                    (
                        SELECT
                            b.playnumber,
                            b.artist_id
                        FROM
                            (
                                SELECT
                                    artist_id
                                FROM
                                    scrobbled_artist a
                                WHERE
                                      a.playnumber > 2
                                  AND lastfm_id = ?
                            ) temp
                                JOIN
                                scrobbled_artist b ON temp.artist_id = b.artist_id AND b.playnumber > 2
                                JOIN user c ON c.lastfm_id = b.lastfm_id
                                JOIN user_guild ug ON c.discord_id = ug.discord_id
                        WHERE guild_id = ?
                        GROUP BY
                            b.artist_id
                        HAVING
                            count(*) = 1
                    ) main
                        JOIN artist c ON main.artist_id = c.id
                ORDER BY
                    playnumber DESC;
                """;

        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setString(i++, lastfmId);
            preparedStatement.setLong(i, guildID);
            ResultSet resultSet = preparedStatement.executeQuery();

            List<ArtistPlays> returnList = new ArrayList<>();
            long discordId = 0;
            while (resultSet.next()) {
                String name = resultSet.getString("name");
                int countA = resultSet.getInt("playNumber");

                returnList.add(new ArtistPlays(name, countA));

            }
            return new UniqueWrapper<>(returnList.size(), discordId, lastfmId, returnList);


        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public UniqueWrapper<AlbumPlays> getUniqueAlbum(Connection connection, Long guildID, String lastfmId) {
        String queryString = """
                SELECT
                    name,
                    album_name,
                    playnumber
                FROM
                    (
                        SELECT
                            b.playnumber,
                            b.album_id
                        FROM
                            (
                                SELECT
                                    album_id
                                FROM
                                    scrobbled_album a
                                WHERE
                                      a.playnumber > 2
                                  AND lastfm_id = ?
                            ) temp
                                JOIN
                                scrobbled_album b ON temp.album_id = b.album_id AND b.playnumber > 2
                                JOIN user c
                                     ON b.lastfm_id = c.lastfm_id
                                JOIN user_guild d ON c.discord_id = d.discord_id
                        WHERE d.guild_id = ?
                        GROUP BY
                            b.album_id
                        HAVING
                            count(*) = 1
                    ) main
                        JOIN album b ON main.album_id = b.id
                        JOIN artist c ON b.artist_id = c.id
                ORDER BY
                    playnumber DESC
                """;

        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setString(i++, lastfmId);
            preparedStatement.setLong(i, guildID);
            ResultSet resultSet = preparedStatement.executeQuery();

            List<AlbumPlays> returnList = new ArrayList<>();
            long discordId = 0;
            while (resultSet.next()) {
                String name = resultSet.getString("name");
                String album = resultSet.getString("album_name");
                int countA = resultSet.getInt("playNumber");

                returnList.add(new AlbumPlays(name, countA, album));

            }
            return new UniqueWrapper<>(returnList.size(), discordId, lastfmId, returnList);


        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public UniqueWrapper<TrackPlays> getUniqueTracks(Connection connection, Long guildID, String lastfmId) {
        String queryString =
                """
                        SELECT
                            name,
                            track_name,
                            playnumber
                        FROM
                            (
                                SELECT
                                    b.playnumber,
                                    b.track_id
                                FROM
                                    (
                                        SELECT
                                            track_id
                                        FROM
                                            scrobbled_track a
                                        WHERE
                                              a.playnumber > 2
                                          AND lastfm_id = ?
                                    ) temp
                                        JOIN
                                        scrobbled_track b ON temp.track_id = b.track_id AND b.playnumber > 2
                                        JOIN user c
                                             ON b.lastfm_id = c.lastfm_id
                                        JOIN user_guild d ON c.discord_id = d.discord_id
                                WHERE d.guild_id = ?
                                GROUP BY
                                    b.track_id
                                HAVING
                                    count(*) = 1
                            ) main
                                JOIN track b ON main.track_id = b.id
                                JOIN artist c ON b.artist_id = c.id
                        ORDER BY
                            playnumber DESC;
                            """;


        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setString(i++, lastfmId);
            preparedStatement.setLong(i, guildID);
            ResultSet resultSet = preparedStatement.executeQuery();

            List<TrackPlays> returnList = new ArrayList<>();
            long discordId = 0;
            while (resultSet.next()) {
                String name = resultSet.getString("name");
                String track = resultSet.getString("track_name");
                int countA = resultSet.getInt("playNumber");

                returnList.add(new TrackPlays(name, countA, track));

            }
            return new UniqueWrapper<>(returnList.size(), discordId, lastfmId, returnList);


        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException(e);
        }
    }


    @Override
    public ResultWrapper<UserArtistComparison> similar(Connection connection, List<String> lastfMNames, int limit) {
        String userA = lastfMNames.get(0);
        String userB = lastfMNames.get(1);

        String queryString =
                "SELECT c.name  , a.playnumber,b.playnumber ," +
                "((a.playnumber * b.playnumber)/(ABS(a.playnumber-b.playnumber)+1))  *" +
                " (((a.playnumber + b.playnumber)) * 2.5) * " +
                " IF((a.playnumber > 10 * b.playnumber OR b.playnumber > 10 * a.playnumber) AND LEAST(a.playnumber,b.playnumber) < 400 ,0.01,2) " +
                "media ," +
                " c.url " +
                "FROM " +
                "(SELECT artist_id,playnumber " +
                "FROM scrobbled_artist " +
                "JOIN user b ON scrobbled_artist.lastfm_id = b.lastfm_id " +
                "WHERE b.lastfm_id = ? ) a " +
                "JOIN " +
                "(SELECT artist_id,playnumber " +
                "FROM scrobbled_artist " +
                " JOIN user b ON scrobbled_artist.lastfm_id = b.lastfm_id " +
                " WHERE b.lastfm_id = ? ) b " +
                "ON a.artist_id=b.artist_id " +
                "JOIN artist c " +
                "ON c.id=b.artist_id" +
                " ORDER BY media DESC";

        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {

            int i = 1;
            preparedStatement.setString(i++, userA);
            preparedStatement.setString(i, userB);


            /* Execute query. */
            ResultSet resultSet = preparedStatement.executeQuery();
            List<UserArtistComparison> returnList = new ArrayList<>();

            int counter = 0;
            while (resultSet.next()) {
                counter++;
                if (counter <= limit) {
                    String name = resultSet.getString("c.name");
                    int countA = resultSet.getInt("a.playNumber");
                    int countB = resultSet.getInt("b.playNumber");
                    String url = resultSet.getString("c.url");
                    returnList.add(new UserArtistComparison(countA, countB, name, userA, userB, url));
                }
            }

            return new ResultWrapper<>(counter, returnList);

        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }

    }

    @Override
    public WrapperReturnNowPlaying knows(Connection con, long artistId, long guildId, int limit) {


        String queryString =
                "SELECT a2.name, a.lastfm_id, a.playNumber, a2.url, c.discord_id " +
                "FROM  scrobbled_artist a  use index (artist_id)" +
                " JOIN artist a2 ON a.artist_id = a2.id  " +
                "JOIN `user` c on c.lastFm_Id = a.lastFM_ID " +
                "JOIN user_guild d on c.discord_ID = d.discord_Id " +
                "where d.guild_Id = ? " +
                "and  a2.id = ? " +
                "ORDER BY a.playNumber desc ";
        queryString = limit == Integer.MAX_VALUE ? queryString : queryString + "limit " + limit;
        try (PreparedStatement preparedStatement = con.prepareStatement(queryString)) {

            int i = 1;
            preparedStatement.setLong(i++, guildId);
            preparedStatement.setLong(i, artistId);



            /* Execute query. */

            ResultSet resultSet = preparedStatement.executeQuery();
            String url = "";
            String artistName = "";
            List<ReturnNowPlaying> returnList = new ArrayList<>();



            /* Get results. */
            int j = 0;
            while (resultSet.next() && (j < limit)) {
                url = resultSet.getString("a2.url");
                artistName = resultSet.getString("a2.name");
                String lastfmId = resultSet.getString("a.lastFM_ID");

                int playNumber = resultSet.getInt("a.playNumber");
                long discordId = resultSet.getLong("c.discord_ID");
                returnList.add(new ReturnNowPlaying(discordId, lastfmId, artistName, playNumber));
            }
            /* Return booking. */
            return new WrapperReturnNowPlaying(returnList, returnList.size(), url, artistName);

        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public UniqueWrapper<ArtistPlays> getCrowns(Connection connection, String lastfmId, long guildID,
                                                int crownThreshold) {
        List<ArtistPlays> returnList = new ArrayList<>();
        long discordId = 0;
        String queryString = """
                SELECT main.orden, b.name FROM (SELECT
                     a.artist_id,
                     a.orden
                 FROM
                     (SELECT
                          artist_id,
                          playnumber AS orden
                      FROM
                          scrobbled_artist a
                      WHERE
                          a.lastfm_id = ?
                          AND playnumber >= ?
                     ) a
                         JOIN (SELECT
                                   artist_id,
                                   max(playnumber) AS orden
                               FROM
                                   scrobbled_artist in_a
                                       JOIN
                                       user in_b
                                       ON in_a.lastfm_id = in_b.lastfm_id
                                       NATURAL JOIN
                                       user_guild in_c
                               WHERE
                                   guild_id = ?
                               GROUP BY artist_id
                     ) AS b
                              ON a.artist_id = b.artist_id AND a.orden = b.orden
                ) AS main JOIN artist b ON main.artist_id = b.id
                ORDER BY orden DESC
                """;

        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setString(i++, lastfmId);

            preparedStatement.setInt(i++, crownThreshold);
            preparedStatement.setLong(i, guildID);

            ResultSet resultSet = preparedStatement.executeQuery();


            while (resultSet.next()) {
                String artist = resultSet.getString(2);
                int plays = resultSet.getInt(1);
                returnList.add(new ArtistPlays(artist, plays));
            }
        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException(e);
        }
        return new UniqueWrapper<>(returnList.size(), discordId, lastfmId, returnList);
    }

    @Override
    public ResultWrapper<ScrobbledArtist> getGuildTop(Connection connection, Long guildID, int limit,
                                                      boolean doCount) {

        String innerSelect = """
                (select artist_id,playnumber
                from scrobbled_artist a
                """;
        if (guildID != null) {
            innerSelect += """
                    join user b on a.lastfm_id = b.lastfm_id
                    join user_guild c on b.discord_id = c.discord_id
                    where c.guild_id = ?""";
        }
        innerSelect += ") main ";

        String normalQuery = """
                SELECT (SELECT name FROM artist WHERE id = result.artist_id) AS name ,
                        (SELECT url FROM artist WHERE id = result.artist_id) AS url
                        ,orden AS orden
                FROM (SELECT sum(playnumber) AS orden,artist_id FROM ${main} GROUP BY artist_id ORDER BY  orden DESC LIMIT ? ) result
                """.replace("${main}", innerSelect);

        String countQuery = "SELECT count(*) AS orden FROM " + innerSelect;


        List<ScrobbledArtist> list = new ArrayList<>();
        int count = 0;
        int i = 1;
        try (PreparedStatement psQ = connection.prepareStatement(normalQuery)) {
            if (guildID != null)
                psQ.setLong(i++, guildID);

            psQ.setInt(i, limit);

            ResultSet rsQ = psQ.executeQuery();

            while (rsQ.next()) {
                String artist = rsQ.getString("name");
                String url = rsQ.getString("url");

                int plays = rsQ.getInt("orden");
                ScrobbledArtist who = new ScrobbledArtist(artist, plays, url);
                list.add(who);
            }
            if (doCount) {
                if (guildID == null) {
                    countQuery = "SELECT table_rows FROM  information_schema.tables WHERE tables.table_schema = 'lastfm' AND table_name = 'scrobbled_album'";
                }
                PreparedStatement psC = connection.prepareStatement(countQuery);
                i = 1;
                if (guildID != null)
                    psC.setLong(i, guildID);

                ResultSet rsC = psC.executeQuery();
                if (rsC.next()) {
                    count = rsC.getInt(1);
                }

            }
        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException(e);
        }
        return new ResultWrapper<>(count, list);
    }

    @Override
    public int userPlays(Connection con, long artistId, String whom) {
        String queryString = "SELECT a.playnumber " +
                             "FROM scrobbled_artist a JOIN user b ON a.lastfm_id=b.lastfm_id " +
                             "JOIN artist c ON a.artist_id = c.id " +
                             "WHERE a.lastfm_id = ? AND c.id = ?";
        try (PreparedStatement preparedStatement = con.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setString(i++, whom);
            preparedStatement.setLong(i, artistId);




            /* Execute query. */
            ResultSet resultSet = preparedStatement.executeQuery();
            if (!resultSet.next())
                return 0;
            return resultSet.getInt("a.playNumber");


        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public List<LbEntry<Integer>> crownsLeaderboard(Connection connection, long guildID, int threshold) {
        String queryString = """
                SELECT t2.lastfm_id, t3.discord_id, count(t2.lastfm_id) ord
                FROM (SELECT a.artist_id, max(a.playnumber) plays
                      FROM scrobbled_artist a
                               JOIN user b ON a.lastfm_id = b.lastfm_id
                               JOIN user_guild c ON b.discord_id = c.discord_id
                      WHERE c.guild_id = ?
                      GROUP BY a.artist_id) t
                         JOIN scrobbled_artist t2 ON t.plays = t2.playnumber AND t.artist_id = t2.artist_id
                         JOIN user t3 ON t2.lastfm_id = t3.lastfm_id
                         JOIN user_guild t4 ON t3.discord_id = t4.discord_id
                WHERE t4.guild_id = ? \s""";
        if (threshold > 1) {
            queryString += " and t2.playnumber >= ? ";
        }


        queryString += "  GROUP BY t2.lastfm_id,t3.discord_id " +
                       "  ORDER BY ord DESC";

        return getLbEntries(connection, guildID, queryString, CrownsLbEntry::new, true, threshold, Integer.class);


    }

    @Override
    public List<LbEntry<Integer>> uniqueLeaderboard(Connection connection, long guildId) {
        String queryString = """
                SELECT count(temp.lastfm_id) AS ord, temp.lastfm_id, temp.discord_id
                FROM (SELECT a.lastfm_id, b.discord_id
                      FROM scrobbled_artist a
                               JOIN user b ON a.lastfm_id = b.lastfm_id
                               JOIN user_guild c ON b.discord_id = c.discord_id
                      WHERE c.guild_id = ?
                        AND a.playnumber > 2
                      GROUP BY a.artist_id
                      HAVING COUNT(*) = 1) temp
                GROUP BY lastfm_id
                ORDER BY ord DESC""";

        return getLbEntries(connection, guildId, queryString, UniqueLbEntry::new, false, 0, Integer.class);
    }

    @Override
    public List<LbEntry<Integer>> uniqueAlbumLeaderboard(Connection connection, long guildId) {
        String queryString = """
                SELECT count(temp.lastfm_id) AS ord, temp.lastfm_id, temp.discord_id
                FROM (SELECT a.lastfm_id, b.discord_id
                      FROM scrobbled_album a
                               JOIN user b ON a.lastfm_id = b.lastfm_id
                               JOIN user_guild c ON b.discord_id = c.discord_id
                      WHERE c.guild_id = ?
                        AND a.playnumber > 2
                      GROUP BY a.album_id
                      HAVING COUNT(*) = 1) temp
                GROUP BY lastfm_id
                ORDER BY ord DESC""";

        return getLbEntries(connection, guildId, queryString, UniqueAlbumLbEntry::new, false, 0, Integer.class);
    }

    @Override
    public List<LbEntry<Integer>> uniqueSongLeaderboard(Connection connection, long guildId) {
        String queryString = """
                SELECT count(temp.lastfm_id) AS ord, temp.lastfm_id, temp.discord_id
                FROM (SELECT a.lastfm_id, b.discord_id
                      FROM scrobbled_track a
                               JOIN user b ON a.lastfm_id = b.lastfm_id
                               JOIN user_guild c ON b.discord_id = c.discord_id
                      WHERE c.guild_id = ?
                        AND a.playnumber > 2
                      GROUP BY a.track_id
                      HAVING COUNT(*) = 1) temp
                GROUP BY lastfm_id
                ORDER BY ord DESC""";

        return getLbEntries(connection, guildId, queryString, UniqueSongLbEntry::new, false, 0, Integer.class);
    }

    @Override
    public List<ScrobbledTrack> getUserTracksWithTag(Connection connection, long discordId, String genre, int limit) {
        String queryString = """
                SELECT a.id AS track_id, c.id,track_name,c.name,a.mbid AS artist_mbid,c.mbid,a.url,d.playnumber
                FROM track a
                JOIN artist c ON a.artist_id = c.id
                JOIN scrobbled_track d ON a.id = d.track_id
                JOIN user e ON d.lastfm_id = e.lastfm_id
                JOIN  track_tags b ON a.id = b.track_id
                WHERE tag = ? AND e.discord_id = ? ORDER BY playnumber DESC LIMIT ?
                """;
        List<ScrobbledTrack> returnInfoes = new ArrayList<>();
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {


            preparedStatement.setString(1, genre);
            preparedStatement.setLong(2, discordId);
            preparedStatement.setInt(3, limit);


            ResultSet resultSet = preparedStatement.executeQuery();

            while (resultSet.next()) {
                int plays = resultSet.getInt("playnumber");
                String url = resultSet.getString("url");
                String name = resultSet.getString("name");
                String albumName = resultSet.getString("track_name");
                String albumMbid = resultSet.getString("mbid");
                String artistMbid = resultSet.getString("artist_mbid");
                ScrobbledTrack st = new ScrobbledTrack(name, albumName, plays, false, 0, url, artistMbid, albumMbid);
                st.setArtistId(resultSet.getLong("id"));
                st.setTrackId(resultSet.getLong("track_id"));
                st.setCount(resultSet.getInt("playnumber"));

                returnInfoes.add(st);

            }
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
        return returnInfoes;
    }

    @Override
    public List<TrackInfo> getTracksWithTag(Connection connection, List<Long> tracks, long discordId, String tag) {
        String queryString = "SELECT a.id as track_id, c.id,track_name,c.name,a.mbid as artist_mbid,c.mbid,a.url,d.playnumber " +
                             "FROM track a " +
                             "join artist c on a.artist_id = c.id " +
                             "join scrobbled_track d on a.id = d.track_id " +
                             "join user e on d.lastfm_id = e.lastfm_id  " +
                             "join  track_tags b on a.id = b.track_id " +
                             "WHERE (a.id) in (%s) and tag = ? and e.discord_id = ? ";
        String sql = String.format(queryString, tracks.isEmpty() ? null : prepareSingle(tracks.size()));
        List<TrackInfo> returnInfoes = new ArrayList<>();
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {


            for (int i = 0; i < tracks.size(); i++) {
                preparedStatement.setLong(i + 1, tracks.get(i));
            }

            preparedStatement.setString(tracks.size() + 1, tag);
            preparedStatement.setLong(tracks.size() + 2, discordId);


            ResultSet resultSet = preparedStatement.executeQuery();

            while (resultSet.next()) {
                String name = resultSet.getString("name");
                String albumName = resultSet.getString("track_name");
                String albumMbid = resultSet.getString("mbid");
                String artistMbid = resultSet.getString("artist_mbid");
                TrackInfo e = new TrackInfo(name, null, albumName, albumMbid);
                returnInfoes.add(e);

            }
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
        return returnInfoes;

    }

    @Override
    public List<CommandUsage> getUserCommands(Connection connection, long discordId) {
        ArrayList<CommandUsage> returnList = new ArrayList<>();
        String queryString = "SELECT COUNT(*),command  FROM command_logs WHERE discord_id = ? GROUP BY command ORDER BY COUNT(*) DESC ";
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            preparedStatement.setLong(1, discordId);

            /* Execute query. */
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                long count = resultSet.getLong(1);
                String command = resultSet.getString(2);
                returnList.add(new CommandUsage(count, command));

            }
            return returnList;
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public List<UserCount> getServerCommandsLb(Connection connection, long guildId) {
        ArrayList<UserCount> returnList = new ArrayList<>();
        String queryString = "SELECT COUNT(*),a.discord_id,c.lastfm_id  FROM command_logs a JOIN user_guild b ON a.discord_id = b.discord_id JOIN user c ON b.discord_id = c.discord_id  WHERE b.guild_id = ? GROUP BY a.discord_id ORDER BY COUNT(*) DESC ";
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            preparedStatement.setLong(1, guildId);

            /* Execute query. */
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                long count = resultSet.getLong(1);
                long discordId = resultSet.getLong(2);
                String lastfm_id = resultSet.getString(3);
                returnList.add(new UserCount(count, discordId, lastfm_id));

            }
            return returnList;
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public AudioFeatures userFeatures(Connection connection, String lastfmId) {

        String queryString = "SELECT avg(acousticness),avg(danceability),avg(energy),avg(instrumentalness),avg(liveness),10 * log10(avg(pow(10,(loudness + 60) / 10))),avg(speechiness),avg(tempo),avg(valence),avg(time_signature)," +
                             "median(`key`) OVER (PARTITION BY lastfm_id),avg(duration)" +
                             "FROM audio_features a " +
                             "JOIN track t ON a.spotify_id = t.spotify_id " +
                             "JOIN scrobbled_track st ON t.id = st.track_id WHERE st.lastfm_id = ?";

        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            preparedStatement.setString(1, lastfmId);
            ResultSet resultSet = preparedStatement.executeQuery();
            AudioFeatures audioFeatures = null;
            int j = 0;
            if (resultSet.next()) {
                int i = 0;
                float acousticness = resultSet.getFloat(i + 1);
                float danceability = resultSet.getFloat(i + 2);
                float energy = resultSet.getFloat(i + 3);
                float instrumentalness = resultSet.getFloat(i + 4);
                float liveness = resultSet.getFloat(i + 5);
                float loudness = resultSet.getFloat(i + 6);
                float speechiness = resultSet.getFloat(i + 7);
                float tempo = resultSet.getFloat(i + 8);
                float valence = resultSet.getFloat(i + 9);
                int time_signature = resultSet.getInt(i + 10);
                int key = resultSet.getInt(i + 11);
                int duration = resultSet.getInt(i + 12);
                return new AudioFeatures(acousticness, null, danceability, duration * 1000, energy, null, instrumentalness, key, liveness, loudness, speechiness, tempo, time_signature, null, null, valence);
            }
            return null;
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }


    }

    @Override
    public List<ScrobbledArtist> getServerArtistsByMbid(Connection connection, long guildId) {
        List<ScrobbledArtist> scrobbledAlbums = new ArrayList<>();
        String s = """
                SELECT name,b.url,b.mbid,sum(a.playnumber)
                FROM scrobbled_artist a
                    JOIN user c ON a.lastfm_id = c.lastfm_id
                    JOIN user_guild ug ON c.discord_id = ug.discord_id
                    JOIN artist b ON a.artist_id = b.id
                WHERE ug.guild_id = ? AND b.mbid IS NOT NULL AND mbid <> ''
                 GROUP BY a.artist_id
                 ORDER BY a.playnumber""";
        try (PreparedStatement preparedStatement = connection.prepareStatement(s)) {
            preparedStatement.setLong(1, guildId);
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                String artist = resultSet.getString(1);
                String url = resultSet.getString(2);
                String mbid = resultSet.getString(3);
                int playnumber = resultSet.getInt(4);
                ScrobbledArtist scrobbledArtist = new ScrobbledArtist(artist, playnumber, url);
                scrobbledArtist.setArtistMbid(mbid);
                scrobbledArtist.setCount(playnumber);
                scrobbledAlbums.add(scrobbledArtist);
            }
            // TODO this might OOM
        } catch (
                SQLException throwables) {
            throw new ChuuServiceException(throwables);
        }
        return scrobbledAlbums;

    }

    @Override
    public List<LbEntry<Float>> audioLb(Connection con, AudioStats element, long guildId, Order order) {


        String operation = switch (element) {
            case LOUDNESS -> "10 * log10(avg(pow(10,(e.loudness + 60) / 10)))";
            case SONG_LENGTH -> "avg(d.duration)";
            default -> "avg(e." + element.getDbField() + ")";
        };
        String queryString = """
                (SELECT \s
                        a.lastfm_id , %s AS ord, c.discord_id
                    FROM         scrobbled_track a

                    JOIN user b ON a.lastfm_id = b.lastfm_id
                    JOIN user_guild c ON b.discord_id = c.discord_id
                    join track d on a.track_id = d.id
                    join audio_features e on d.spotify_id = e.spotify_id
                    WHERE         c.guild_id = ?
                    
                    GROUP BY a.lastfm_id
                    ORDER BY ord %s    )""".formatted(operation, order.toString());

        return getLbEntries(con, guildId, queryString, (s, aLong, aFloat) -> new AudioLbEntry(s, aLong, aFloat, element), false, 0, Float.class);
    }

    @Override
    public List<String> searchArtist(Connection connection, String inputTerm, int limit) {
        List<String> results = new ArrayList<>();
        @Language("MariaDB") String queryString =
                """
                        select distinct name
                        from ((SELECT name as name, 2 as ranking, play_ranking as numeric_ranking
                              from artist
                              where name like ?
                              limit ?)
                              
                              union
                              (select coalesce((select name from artist where name = ?), ?) as name, 1 as ranking, 0 as numeric_ranking)
                              order by ranking, numeric_ranking desc) main
                        """;
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            preparedStatement.setString(1, "" + inputTerm.replaceAll("%", "") + "%");
            preparedStatement.setInt(2, limit - 1);
            preparedStatement.setString(3, inputTerm);
            preparedStatement.setString(4, inputTerm);

            /* Execute query. */
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                results.add(resultSet.getString(1));
            }
            return results;
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }

    }

    @Override
    public List<String> searchAlbumsForArtist(Connection connection, long artistId, String inputTerm, int limit) {
        List<String> results = new ArrayList<>();
        @Language("MariaDB") String queryString = """
                select distinct name
                     from ((SELECT album_name as name, 2 as ranking
                           from album
                           where  album_name like ? and artist_id = ?
                           limit ?
                           )
                           union
                           (
                           select coalesce ((select album_name from album where album_name = ? and artist_id = ? ), ?) as name, 1 as ranking)
                           order by ranking asc
                          ) main
                """;
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            preparedStatement.setString(1, "" + inputTerm.replaceAll("%", "") + "%");
            preparedStatement.setLong(2, artistId);
            preparedStatement.setInt(3, limit - 1);
            preparedStatement.setString(4, inputTerm);
            preparedStatement.setLong(5, artistId);
            preparedStatement.setString(6, inputTerm);

            /* Execute query. */
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                results.add(resultSet.getString(1));
            }
            return results;
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public List<String> searchTracksForArtist(Connection connection, long artistId, String inputTerm, int limit) {
        List<String> results = new ArrayList<>();
        @Language("MariaDB") String queryString = """
                select distinct name
                     from ((SELECT track_name as name, 2 as ranking
                           from track
                           where  track_name like ? and artist_id = ?
                           limit ?
                           )
                           union
                           (select coalesce ((select track_name from track where track_name = ? and artist_id = ? ), ?) as name, 1 as ranking)
                           order by ranking
                          ) main
                """;
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            preparedStatement.setString(1, "" + inputTerm.replaceAll("%", "") + "%");
            preparedStatement.setInt(2, limit - 1);
            preparedStatement.setLong(3, artistId);
            preparedStatement.setString(4, inputTerm);
            preparedStatement.setLong(5, artistId);
            preparedStatement.setString(6, inputTerm);

            /* Execute query. */
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                results.add(resultSet.getString(1));
            }
            return results;
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public List<String> searchTracks(Connection connection, String inputTerm, int limit) {
        List<String> results = new ArrayList<>();
        String queryString = """
                select distinct name
                     from ((SELECT track_name as name, 2 as ranking
                     
                           from track
                           where  track_name like ?
                           limit ?)
                           union
                           (
                           select coalesce ((select track_name from track where track_name = ? limit 1), ?) as name, 1 as ranking)
                           order by ranking
                          ) main
                     
                """;
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            preparedStatement.setString(1, "" + inputTerm.replaceAll("%", "") + "%");
            preparedStatement.setInt(2, limit - 1);
            preparedStatement.setString(3, inputTerm);
            preparedStatement.setString(4, inputTerm);

            /* Execute query. */
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                results.add(resultSet.getString(1));
            }
            return results;
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public List<String> searchAlbums(Connection connection, String inputTerm, int limit) {
        List<String> results = new ArrayList<>();
        @Language("MariaDB") String queryString = """
                select distinct name
                     from ((SELECT album_name as name, 2 as ranking
                     
                           from album
                           where  album_name like ?
                           limit ?)
                           union
                           (
                           select coalesce ((select album_name from album where album_name = ? limit 1), ?) as name, 1 as ranking)
                           order by ranking
                          ) main
                     
                """;
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            preparedStatement.setString(1, "" + inputTerm.replaceAll("%", "") + "%");
            preparedStatement.setInt(2, limit - 1);
            preparedStatement.setString(3, inputTerm);
            preparedStatement.setString(4, inputTerm);

            /* Execute query. */
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                results.add(resultSet.getString(1));
            }
            return results;
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }


    @Override
    public Optional<Rank<PrivacyUserCount>> getGlobalPosition(Connection connection, long discordId) {
        String queryString = "SELECT * FROM (SELECT COUNT(*),a.discord_id,c.lastfm_id,c.privacy_mode  FROM command_logs a  JOIN user c ON a.discord_id = c.discord_id GROUP BY a.discord_id ORDER BY COUNT(*) DESC ) main WHERE main.discord_id = ?  ";
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            preparedStatement.setLong(1, discordId);

            /* Execute query. */
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                long count = resultSet.getLong(1);
                String lastfm_id = resultSet.getString(3);
                PrivacyMode privacyMode = PrivacyMode.valueOf(resultSet.getString(4));
                return Optional.of(new Rank<>(new PrivacyUserCount(count, discordId, lastfm_id, privacyMode), resultSet.getLong(4)));
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public List<PrivacyUserCount> getGlobalCommands(Connection connection) {
        ArrayList<PrivacyUserCount> returnList = new ArrayList<>();
        String queryString = "SELECT COUNT(*),a.discord_id,c.lastfm_id,c.privacy_mode  FROM command_logs a  JOIN user c ON a.discord_id = c.discord_id GROUP BY a.discord_id ORDER BY COUNT(*) DESC LIMIT 1000  ";
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            /* Execute query. */
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                long count = resultSet.getLong(1);
                long discordId = resultSet.getLong(2);
                String lastfm_id = resultSet.getString(3);
                PrivacyMode privacyMode = PrivacyMode.valueOf(resultSet.getString(4));
                returnList.add(new PrivacyUserCount(count, discordId, lastfm_id, privacyMode));
            }
            return returnList;
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public int userArtistCount(Connection con, String whom, int threshold) {
        String queryString = "SELECT COUNT(*) AS numb FROM scrobbled_artist WHERE scrobbled_artist.lastfm_id= ? AND playnumber >= ?";
        try (PreparedStatement preparedStatement = con.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setString(i++, whom);
            preparedStatement.setInt(i, threshold);


            /* Execute query. */
            ResultSet resultSet = preparedStatement.executeQuery();
            if (!resultSet.next())
                return 0;
            return resultSet.getInt("numb");


        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public List<LbEntry<Integer>> artistLeaderboard(Connection con, long guildID, int threshold) {
        @Language("MariaDB") String queryString = """
                (SELECT a.lastfm_id, count(*) AS ord, c.discord_id
                 FROM scrobbled_artist a
                          JOIN user b ON a.lastfm_id = b.lastfm_id
                          JOIN user_guild c ON b.discord_id = c.discord_id
                 WHERE c.guild_id = ?
                   and c.guild_id = ?\s""";
        if (threshold > 1) {
            queryString += " and a.playnumber >= ? ";

        }
        queryString += "GROUP BY a.lastfm_id, c.discord_id     ORDER BY ord DESC    )";

        return getLbEntries(con, guildID, queryString, ArtistLbEntry::new, true, threshold, Integer.class);
    }

    @Override
    public double obscurity(Connection connection, String lastfmId) {

        String queryString = """
                WITH popular AS
                (
                        SELECT   track_id,
                                 playnumber
                        FROM     scrobbled_track st
                        WHERE    st.lastfm_id = ?
                        ORDER BY playnumber DESC ), indexes AS
                (
                        SELECT   popularity,
                                 (ROW_NUMBER() OVER ( ORDER BY playnumber DESC)) AS i
                        FROM     popular a
                        JOIN     track b
                        ON       a.track_id = b.id
                        WHERE    popularity IS NOT NULL ), counted AS
                (
                      SELECT Count(*) AS tf
                      FROM   popular)
                SELECT   Sum( popularity * 2 * (1 - i/counted.tf))/counted.tf AS ord
                FROM     indexes
                JOIN     counted
                ORDER BY ord DESC""";
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            preparedStatement.setString(1, lastfmId);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                double result = resultSet.getDouble(1);
                return result == 0 ? 100 : result;
            }
            return 0;
        } catch (SQLException sqlException) {
            throw new ChuuServiceException(sqlException);
        }
    }

    @Override
    public Optional<ObscurityStats> serverObscurityStats(Connection connection, long guildId) {
        String queryString = """
                WITH averages AS (
                                  SELECT guild_id,avg(score)  AS score
                                  FROM obscurity o
                                  JOIN user u ON o.lastfm_id = u.lastfm_id
                              	JOIN user_guild ug ON u.discord_id = ug.discord_id
                                  GROUP BY guild_id
                                  ORDER BY score DESC
                              ),
                              indexes AS (
                                 SELECT guild_id,score,(rank() OVER (ORDER BY score ASC )) AS i\s
                                  FROM averages
                              ),
                              counted AS (
                                  SELECT count(*) AS tf FROM averages
                                  )
                                   SELECT  counted.tf  AS total_servers, guild_id,score,i
                                   FROM indexes JOIN counted
                              	 WHERE  guild_id = ?
                """;
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            preparedStatement.setLong(1, guildId);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                long count = resultSet.getLong(1);
                double average = resultSet.getDouble(3);
                long rank = resultSet.getLong(4);
                return Optional.of(new ObscurityStats(average, rank, count, guildId));
            }
            return Optional.empty();
        } catch (SQLException sqlException) {
            throw new ChuuServiceException(sqlException);
        }
    }

    @Override
    public List<LbEntry<Double>> obscurityLeaderboard(Connection connection, long guildId) {

        String queryString = """
                SELECT o.lastfm_id, score as ord, u.discord_id
                 FROM obscurity o
                 JOIN user u ON o.lastfm_id = u.lastfm_id
                 JOIN user_guild ug ON u.discord_id = ug.discord_id
                 WHERE ug.guild_id = ?
                 GROUP BY  u.lastfm_id ORDER BY ord asc
                """;
        return getLbEntries(connection, guildId, queryString, ObscurityEntry::new, false, 0, Double.class);
    }

    @Override
    public PresenceInfo getRandomArtistWithUrl(Connection connection) {

        String queryString =
                """
                        SELECT\s
                            b.name,
                            b.url,
                             discord_id,
                            (SELECT\s
                                    SUM(playnumber)
                                FROM
                                    scrobbled_artist
                                WHERE
                                    artist_id = a.artist_id) AS summa
                        FROM
                            scrobbled_artist a
                                JOIN
                            artist b ON a.artist_id = b.id
                                NATURAL JOIN
                            user c
                        WHERE
                            b.id IN (SELECT\s
                                    rando.id
                                FROM
                                    (SELECT\s
                                        a.id
                                    FROM
                                        artist a
                                        WHERE a.url IS NOT NULL
                                        AND a.url != ''
                                    ORDER BY RAND()
                                    LIMIT 15) rando)
                        ORDER BY summa DESC,RAND()
                        LIMIT 1;""";
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            ResultSet resultSet = preparedStatement.executeQuery();
            if (!resultSet.next())
                return null;

            String artistName = resultSet.getString("name");
            String url = resultSet.getString("url");

            long summa = resultSet.getLong("summa");
            long discordId = resultSet.getLong("discord_id");
            return new PresenceInfo(artistName, url, summa, discordId);

        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public StolenCrownWrapper artistsBehind(Connection connection, String ogUser, String queriedUser,
                                            int threshold) {
        List<StolenCrown> returnList = new ArrayList<>();
        long discordid = 0;
        long discordid2 = 0;
        String queryString = """
                SELECT\s
                    inn.name AS artist ,inn.orden AS ogplays , inn.discord_id AS ogid , inn2.discord_id queriedid,  inn2.orden AS queriedplays
                FROM
                    (SELECT\s
                        a.artist_id, a2.name, b.discord_id, playnumber AS orden
                    FROM
                        scrobbled_artist a
                    JOIN user b ON a.lastfm_id = b.lastfm_id
                 JOIN artist a2 ON a.artist_id = a2.id     WHERE
                        a.lastfm_id = ?) inn
                 join (SELECT\s
                        a.artist_id, a2.name, b.discord_id, playnumber AS orden
                    FROM
                        scrobbled_artist a
                    JOIN user b ON a.lastfm_id = b.lastfm_id
                 JOIN artist a2 ON a.artist_id = a2.id     WHERE
                        a.lastfm_id = ?) inn2   on inn2.artist_id = inn.artist_id and inn2.orden >= inn.orden\s""";


        if (threshold != 0) {
            queryString += " and inn.orden >= ? && inn2.orden >= ? ";
        }


        queryString += " ORDER BY inn.orden DESC , inn2.orden DESC\n";

        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setString(i++, ogUser);
            preparedStatement.setString(i++, queriedUser);

            if (threshold != 0) {
                preparedStatement.setInt(i++, threshold);
                preparedStatement.setInt(i, threshold);
            }
            ResultSet resultSet = preparedStatement.executeQuery();


            while (resultSet.next()) {
                discordid = resultSet.getLong("ogId");
                discordid2 = resultSet.getLong("queriedId");
                String artist = resultSet.getString("artist");
                int plays = resultSet.getInt("ogPlays");
                int plays2 = resultSet.getInt("queriedPlays");

                returnList.add(new StolenCrown(artist, plays, plays2));
            }
        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException(e);
        }
        //Ids will be 0 if returnlist is empty;
        return new StolenCrownWrapper(discordid, discordid2, returnList);
    }

    @Override
    public StolenCrownWrapper getCrownsStolenBy(Connection connection, String ogUser, String queriedUser,
                                                long guildId, int threshold) {
        List<StolenCrown> returnList = new ArrayList<>();
        long discordid = 0;
        long discordid2 = 0;
        String queryString = """
                SELECT\s
                    inn.name AS artist ,inn.orden AS ogplays , inn.discord_id AS ogid , inn2.discord_id queriedid,  inn2.orden AS queriedplays
                FROM
                    (SELECT\s
                        a.artist_id, a2.name, b.discord_id, playnumber AS orden
                    FROM
                        scrobbled_artist a
                    JOIN user b ON a.lastfm_id = b.lastfm_id
                 JOIN artist a2 ON a.artist_id = a2.id     WHERE
                        a.lastfm_id = ?) inn
                        JOIN
                    (SELECT\s
                        artist_id, b.discord_id, playnumber AS orden
                    FROM
                        scrobbled_artist a
                    JOIN user b ON a.lastfm_id = b.lastfm_id
                    WHERE
                        b.lastfm_id = ?) inn2 ON inn.artist_id = inn2.artist_id
                WHERE
                    (inn2.artist_id , inn2.orden) = (SELECT\s
                            in_a.artist_id, MAX(in_a.playnumber)
                        FROM
                            scrobbled_artist in_a
                                JOIN
                            user in_b ON in_a.lastfm_id = in_b.lastfm_id
                                NATURAL JOIN
                            user_guild in_c
                        WHERE
                            guild_id = ?
                                AND artist_id = inn2.artist_id)
                        AND (inn.artist_id , inn.orden) = (SELECT\s
                            in_a.artist_id, in_a.playnumber
                        FROM
                            scrobbled_artist in_a
                                JOIN
                            user in_b ON in_a.lastfm_id = in_b.lastfm_id
                                NATURAL JOIN
                            user_guild in_c
                        WHERE
                            guild_id = ?
                                AND artist_id = inn.artist_id
                        ORDER BY in_a.playnumber DESC
                        LIMIT 1 , 1)
                """;

        if (threshold != 0) {
            queryString += " and inn.orden >= ? && inn2.orden >= ? ";
        }


        queryString += " ORDER BY inn.orden DESC , inn2.orden DESC\n";

        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setString(i++, ogUser);
            preparedStatement.setString(i++, queriedUser);

            preparedStatement.setLong(i++, guildId);
            preparedStatement.setLong(i++, guildId);
            if (threshold != 0) {
                preparedStatement.setInt(i++, threshold);
                preparedStatement.setInt(i, threshold);
            }
            ResultSet resultSet = preparedStatement.executeQuery();


            while (resultSet.next()) {
                discordid = resultSet.getLong("ogId");
                discordid2 = resultSet.getLong("queriedId");
                String artist = resultSet.getString("artist");
                int plays = resultSet.getInt("ogPlays");
                int plays2 = resultSet.getInt("queriedPlays");

                returnList.add(new StolenCrown(artist, plays, plays2));
            }
        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException(e);
        }
        //Ids will be 0 if returnlist is empty;
        return new StolenCrownWrapper(discordid, discordid2, returnList);
    }

    @Override
    public UniqueWrapper<AlbumPlays> getUserAlbumCrowns(Connection connection, String lastfmId, int crownThreshold, long guildID) {
        long discordId = -1L;
        String queryString =
                """
                        WITH
                            mines AS (
                                SELECT
                                    album_id,
                                    playnumber
                                FROM
                                    scrobbled_album
                                WHERE
                                    lastfm_id = ?
                                    """ +
                (crownThreshold > 1 ? " and playnumber > ? " : "") +
                """
                            ),
                            globals AS (
                                SELECT
                                    a.album_id,
                                    max(a.playnumber) AS outf
                                FROM
                                    scrobbled_album a
                                        JOIN mines b ON a.album_id = b.album_id
                                        join user u ON a.lastfm_id = u.lastfm_id
                                        join user_guild ug ON u.discord_id = ug.discord_id
                                WHERE  guild_id = ?
                                GROUP BY a.album_id
                            )
                        SELECT
                            playnumber,
                            c.album_name,
                            b.name
                        FROM
                            mines
                                JOIN globals ON globals.album_id = mines.album_id AND mines.playnumber = globals.outf
                                JOIN album c ON globals.album_id = c.id
                                JOIN artist b ON c.artist_id = b.id
                        ORDER BY
                            playnumber DESC
                        """;

        List<AlbumPlays> returnList = new ArrayList<>();
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setString(i++, lastfmId);
            if (crownThreshold > 1) {
                preparedStatement.setInt(i++, crownThreshold);
            }
            preparedStatement.setLong(i, guildID);

            ResultSet resultSet = preparedStatement.executeQuery();


            while (resultSet.next()) {
                String artist = resultSet.getString("b.name");
                String album = resultSet.getString("c.album_name");

                int plays = resultSet.getInt("playnumber");
                returnList.add(new AlbumPlays(artist, plays, album));
            }
        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException(e);
        }
        return new UniqueWrapper<>(returnList.size(), discordId, lastfmId, returnList);
    }


    @Override
    public List<LbEntry<Integer>> albumCrownsLeaderboard(Connection con, long guildID, int threshold) {
        @Language("MariaDB") String queryString = """
                SELECT t2.lastfm_id, t3.discord_id, count(t2.lastfm_id) ord
                FROM (SELECT a.album_id, max(a.playnumber) plays
                      FROM scrobbled_album a
                               JOIN user b ON a.lastfm_id = b.lastfm_id
                               JOIN user_guild c ON b.discord_id = c.discord_id
                      WHERE c.guild_id = ?
                      GROUP BY a.album_id) t
                         JOIN scrobbled_album t2 ON t.plays = t2.playnumber AND t.album_id = t2.album_id
                         JOIN user t3 ON t2.lastfm_id = t3.lastfm_id
                         JOIN user_guild t4 ON t3.discord_id = t4.discord_id
                WHERE t4.guild_id = ? \s""";
        if (threshold > 1) {
            queryString += " and t2.playnumber > ? ";
        }


        queryString += "  GROUP BY t2.lastfm_id,t3.discord_id " +
                       "  ORDER BY ord DESC";


        return getLbEntries(con, guildID, queryString, AlbumCrownLbEntry::new, true, threshold, Integer.class);
    }

    @Override
    public ObscuritySummary getUserObscuritPoints(Connection connection, String lastfmId) {
        @Language("MariaDB") String queryString = """
                """;
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setString(i, lastfmId);
            ResultSet resultSet = preparedStatement.executeQuery();

            if (!resultSet.next()) {
                return null;

            }

            int totalPlays = resultSet.getInt("b");
            int otherPlaysOnMyArtists = resultSet.getInt("other_plays_on_my_artists");
            int uniqueCoefficient = resultSet.getInt("unique_coefficient");
            int total = resultSet.getInt("total");

            return new ObscuritySummary(totalPlays, otherPlaysOnMyArtists, uniqueCoefficient, total);


        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
        }
        return null;
    }

    @Override
    public int getRandomCount(Connection connection, Long userId) {
        String queryString = """
                SELECT\s
                  count(*) as counted FROM
                    randomlinks\s
                """;
        if (userId != null) {
            queryString += "WHERE discord_id = ?";

        }
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            if (userId != null) {
                preparedStatement.setLong(1, userId);
            }
            ResultSet resultSet = preparedStatement.executeQuery();
            if (!resultSet.next()) {
                return 0;
            }

            return resultSet.getInt("counted");

        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public List<GlobalCrown> getGlobalKnows(Connection connection, long artistID, boolean includeBottedUsers, long ownerId) {
        List<GlobalCrown> returnedList = new ArrayList<>();
        String queryString = """
                SELECT  playnumber AS ord, discord_id, l.lastfm_id, l.botted_account
                 FROM  scrobbled_artist ar
                  	 	 JOIN user l ON ar.lastfm_id = l.lastfm_id         WHERE  ar.artist_id = ?    AND   (? OR NOT l.botted_account OR l.discord_id = ? )            ORDER BY  playnumber DESC""";


        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setLong(i++, artistID);
            preparedStatement.setBoolean(i++, includeBottedUsers);
            preparedStatement.setLong(i, ownerId);


            ResultSet resultSet = preparedStatement.executeQuery();
            int j = 1;
            while (resultSet.next()) {

                String lastfmId = resultSet.getString("lastfm_id");
                long discordId = resultSet.getLong("discord_id");
                int crowns = resultSet.getInt("ord");
                boolean bootedAccount = resultSet.getBoolean("botted_account");

                returnedList.add(new GlobalCrown(lastfmId, discordId, crowns, j++, bootedAccount));
            }
            return returnedList;
        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException((e));
        }
    }

    @Override
    public List<GlobalCrown> getGlobalRankingAlbum(Connection connection, long albumId, boolean includeBottedUsers, long ownerId) {
        List<GlobalCrown> returnedList = new ArrayList<>();
        String queryString = """
                SELECT  playnumber AS ord, discord_id, l.lastfm_id, l.botted_account
                 FROM  scrobbled_album ar
                 	 JOIN user l ON ar.lastfm_id = l.lastfm_id         WHERE  ar.album_id = ?    AND   (? OR NOT l.botted_account OR l.discord_id = ? )            ORDER BY  playnumber DESC""";


        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setLong(i++, albumId);
            preparedStatement.setBoolean(i++, includeBottedUsers);
            preparedStatement.setLong(i, ownerId);
            ResultSet resultSet = preparedStatement.executeQuery();
            int j = 1;
            while (resultSet.next()) {

                String lastfmId = resultSet.getString("lastfm_id");
                long discordId = resultSet.getLong("discord_id");
                int crowns = resultSet.getInt("ord");
                boolean bootedAccount = resultSet.getBoolean("botted_account");

                returnedList.add(new GlobalCrown(lastfmId, discordId, crowns, j++, bootedAccount));
            }
            return returnedList;
        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException((e));
        }
    }

    @Override
    public List<GlobalCrown> getGlobalRankingSong(Connection connection, long trackId, boolean includeBottedUsers, long ownerId) {
        List<GlobalCrown> returnedList = new ArrayList<>();
        String queryString = """
                SELECT  playnumber AS ord, discord_id, l.lastfm_id, l.botted_account
                 FROM  scrobbled_track ar
                 	 JOIN user l ON ar.lastfm_id = l.lastfm_id         WHERE  ar.track_id = ?    AND   (? OR NOT l.botted_account OR l.discord_id = ? )            ORDER BY  playnumber DESC""";


        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setLong(i++, trackId);
            preparedStatement.setBoolean(i++, includeBottedUsers);
            preparedStatement.setLong(i, ownerId);
            ResultSet resultSet = preparedStatement.executeQuery();
            int j = 1;
            while (resultSet.next()) {

                String lastfmId = resultSet.getString("lastfm_id");
                long discordId = resultSet.getLong("discord_id");
                int crowns = resultSet.getInt("ord");
                boolean bootedAccount = resultSet.getBoolean("botted_account");

                returnedList.add(new GlobalCrown(lastfmId, discordId, crowns, j++, bootedAccount));
            }
            return returnedList;
        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException((e));
        }
    }

    //TriFunction is not the simplest approach but i felt like using it so :D
    @Nonnull
    private <T extends Number> List<LbEntry<T>> getLbEntries(Connection connection, long guildId, String
            queryString, TriFunction<String, Long, T, LbEntry<T>> fun, boolean needsReSet, int resetThreshold, Class<T> tClass) {
        List<LbEntry<T>> returnedList = new ArrayList<>();
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setLong(i, guildId);
            if (needsReSet) {
                preparedStatement.setLong(++i, guildId);
                if (resetThreshold > 1)
                    preparedStatement.setLong(++i, resetThreshold);
            }
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) { //&& (j < 10 && j < rows)) {
                String lastfmId = resultSet.getString("lastfm_id");
                long discordId = resultSet.getLong("discord_id");
                T order;
                if (tClass.equals(Integer.class)) {
                    order = tClass.cast(resultSet.getInt("ord"));
                } else if (tClass.equals(Float.class)) {
                    order = tClass.cast(resultSet.getFloat("ord"));
                } else {
                    order = tClass.cast(resultSet.getDouble("ord"));
                }


                returnedList.add(fun.apply(lastfmId, discordId, order));


            }
            return returnedList;
        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException((e));
        }
    }


    @Override
    public WrapperReturnNowPlaying whoKnowsAlbum(Connection con, long albumId, long guildId, int limit) {


        String queryString =
                "SELECT a2.album_name,a3.name, a.lastfm_id, a.playNumber, a2.url, c.discord_id " +
                "FROM  scrobbled_album a" +
                " JOIN album a2 ON a.album_id = a2.id  " +
                " join artist a3 on a2.artist_id = a3.id " +
                "JOIN `user` c on c.lastFm_Id = a.lastFM_ID " +
                "JOIN user_guild d on c.discord_ID = d.discord_Id " +
                "where d.guild_Id = ? " +
                "and  a2.id = ? " +
                "ORDER BY a.playNumber desc ";
        queryString = limit == Integer.MAX_VALUE ? queryString : queryString + "limit " + limit;
        try (PreparedStatement preparedStatement = con.prepareStatement(queryString)) {

            int i = 1;
            preparedStatement.setLong(i++, guildId);
            preparedStatement.setLong(i, albumId);



            /* Execute query. */

            ResultSet resultSet = preparedStatement.executeQuery();
            String url = "";
            String artistName = "";
            String albumName;
            List<ReturnNowPlaying> returnList = new ArrayList<>();



            /* Get results. */
            int j = 0;
            while (resultSet.next() && (j < limit)) {
                url = resultSet.getString("a2.url");
                artistName = resultSet.getString("a3.name");
                albumName = resultSet.getString("a2.album_name");

                String lastfmId = resultSet.getString("a.lastFM_ID");

                int playNumber = resultSet.getInt("a.playNumber");
                long discordId = resultSet.getLong("c.discord_ID");

                returnList.add(new ReturnNowPlaying(discordId, lastfmId, artistName + " - " + albumName, playNumber));
            }
            /* Return booking. */
            return new WrapperReturnNowPlaying(returnList, returnList.size(), url, artistName);

        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public WrapperReturnNowPlaying whoKnowsTrack(Connection con, long trackId, long guildId, int limit) {


        String queryString =
                "SELECT a2.track_name,a3.name, a.lastfm_id, a.playNumber, coalesce(a2.url,a3.url) as turl, c.discord_id " +
                "FROM  scrobbled_track a" +
                " JOIN track a2 ON a.track_id = a2.id  " +
                " join artist a3 on a2.artist_id = a3.id " +
                "JOIN `user` c on c.lastFm_Id = a.lastFM_ID " +
                "JOIN user_guild d on c.discord_ID = d.discord_Id " +
                "where d.guild_Id = ? " +
                "and  a2.id = ? " +
                "ORDER BY a.playNumber desc ";
        queryString = limit == Integer.MAX_VALUE ? queryString : queryString + "limit " + limit;
        try (PreparedStatement preparedStatement = con.prepareStatement(queryString)) {

            int i = 1;
            preparedStatement.setLong(i++, guildId);
            preparedStatement.setLong(i, trackId);
            /* Execute query. */

            ResultSet resultSet = preparedStatement.executeQuery();
            String url = "";
            String artistName = "";
            String trackName;
            List<ReturnNowPlaying> returnList = new ArrayList<>();



            /* Get results. */
            int j = 0;
            while (resultSet.next() && (j < limit)) {
                url = resultSet.getString("turl");
                artistName = resultSet.getString("a3.name");
                trackName = resultSet.getString("a2.track_name");

                String lastfmId = resultSet.getString("a.lastFM_ID");

                int playNumber = resultSet.getInt("a.playNumber");
                long discordId = resultSet.getLong("c.discord_ID");

                returnList.add(new ReturnNowPlaying(discordId, lastfmId, artistName + " - " + trackName, playNumber));
            }
            /* Return booking. */
            return new WrapperReturnNowPlaying(returnList, returnList.size(), url, artistName);

        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public WrapperReturnNowPlaying globalWhoKnowsAlbum(Connection con, long albumId, int limit, long ownerId, boolean includeBottedUsers, boolean hidePrivate) {


        String queryString =
                """
                        SELECT a2.album_name,a3.name, a.lastfm_id, a.playNumber, a2.url, c.discord_id,c.privacy_mode\s
                        FROM  scrobbled_album a\s
                        JOIN album a2 ON a.album_id = a2.id
                        join artist a3 on a2.artist_id = a3.id
                        JOIN `user` c on c.lastFm_Id = a.lastFM_ID
                        where   (? or not c.botted_account or c.discord_id = ? )
                        and (not ? or c.privacy_mode not in ('NORMAL','STRICT') or c.discord_id = ?) and
                          a2.id = ? ORDER BY a.playNumber desc\s""";
        queryString = limit == Integer.MAX_VALUE ? queryString : queryString + "limit " + limit;
        try (PreparedStatement preparedStatement = con.prepareStatement(queryString)) {

            int i = 1;
            preparedStatement.setBoolean(i++, includeBottedUsers);
            preparedStatement.setLong(i++, ownerId);
            preparedStatement.setBoolean(i++, hidePrivate);
            preparedStatement.setLong(i++, ownerId);

            preparedStatement.setLong(i, albumId);






            /* Execute query. */

            ResultSet resultSet = preparedStatement.executeQuery();
            String url = "";
            String artistName = "";
            String albumName = "";

            List<ReturnNowPlaying> returnList = new ArrayList<>();



            /* Get results. */
            int j = 0;
            while (resultSet.next() && (j < limit)) {
                url = resultSet.getString("a2.url");
                artistName = resultSet.getString("a3.name");
                albumName = resultSet.getString("a2.album_name");

                String lastfmId = resultSet.getString("a.lastFM_ID");

                int playNumber = resultSet.getInt("a.playNumber");
                long discordId = resultSet.getLong("c.discord_ID");
                PrivacyMode privacyMode = PrivacyMode.valueOf(resultSet.getString("c.privacy_mode"));

                returnList.add(new GlobalReturnNowPlayingAlbum(discordId, lastfmId, artistName, playNumber, privacyMode, albumName));
            }
            /* Return booking. */
            return new WrapperReturnNowPlaying(returnList, returnList.size(), url, artistName + " - " + albumName);

        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public UniqueWrapper<AlbumPlays> albumUniques(Connection connection, long guildID, String lastfmId) {
        String queryString =
                """
                        SELECT
                                            name,
                                            album_name,
                                            playnumber
                                        FROM
                                            (
                                                SELECT
                                                    b.playnumber,
                                                    b.album_id
                                                FROM
                                                    (
                                                        SELECT
                                                            album_id
                                                        FROM
                                                            scrobbled_album a
                                                        WHERE
                                                              a.playnumber > 2
                                                          AND lastfm_id = ?
                                                    ) temp
                                                        JOIN
                                                        scrobbled_album b ON temp.album_id = b.album_id AND b.playnumber > 2
                                                        JOIN user c ON b.lastfm_id = c.lastfm_id
                                                        JOIN user_guild ug ON c.discord_id = ug.discord_id
                                                WHERE guild_id = ?
                                                GROUP BY
                                                    b.album_id
                                                HAVING
                                                    count(*) = 1
                                            ) main
                                                JOIN album b ON main.album_id = b.id
                                                JOIN artist c ON b.artist_id = c.id
                                        ORDER BY
                                            playnumber DESC;
                        """;


        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setString(i++, lastfmId);
            preparedStatement.setLong(i, guildID);
            ResultSet resultSet = preparedStatement.executeQuery();

            List<AlbumPlays> returnList = new ArrayList<>();
            long discordId = 0;
            while (resultSet.next()) {
                String album = resultSet.getString("album_name");
                String artist = resultSet.getString("name");

                int countA = resultSet.getInt("playNumber");

                returnList.add(new AlbumPlays(artist, countA, album));

            }
            return new UniqueWrapper<>(returnList.size(), discordId, lastfmId, returnList);


        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public BotStats getBotStats(Connection connection) {
        String queryString = "SELECT (SELECT COUNT(*) FROM user) AS user_count," +
                             " (SELECT COUNT(*) FROM guild) guild_count," +
                             " (SELECT table_rows FROM  information_schema.tables WHERE tables.table_schema = 'lastfm' AND table_name = 'artist') artist_count," +
                             "(SELECT table_rows FROM  information_schema.tables WHERE tables.table_schema = 'lastfm' AND table_name = 'album') album_count," +
                             " (SELECT table_rows * 450 FROM  information_schema.tables WHERE tables.table_schema = 'lastfm' AND table_name = 'artist') scrobbled_count," +

                             "(SELECT table_rows FROM  information_schema.tables WHERE tables.table_schema = 'lastfm' AND table_name = 'album_rating' ) rym_count," +
                             " (SELECT AVG(rating) FROM album_rating ) rym_avg," +
                             " (SELECT COUNT(*) FROM past_recommendations) recommedation_count," +
                             " (SELECT COUNT(*) FROM corrections) correction_count, " +
                             "(SELECT COUNT(*) FROM randomlinks) random_count," +
                             " (SELECT table_rows FROM  information_schema.tables WHERE tables.table_schema = 'lastfm' AND table_name = 'alt_url') image_count, " +
                             "(SELECT COUNT(*) FROM vote) vote_count," +
                             "(SELECT COUNT(*) FROM user_guild) set_count," +
                             "(SELECT  value FROM metrics WHERE id = 5) AS api_count";

        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            ResultSet resultSet = preparedStatement.executeQuery();

            if (resultSet.next()) {
                long user_count = resultSet.getLong("user_count");
                long guild_count = resultSet.getLong("guild_count");
                long artist_count = resultSet.getLong("artist_count");
                long album_count = resultSet.getLong("album_count");
                long scrobbled_count = resultSet.getLong("scrobbled_count");
                long rym_count = resultSet.getLong("rym_count");
                double rym_avg = resultSet.getDouble("rym_avg");
                long recommendation_count = resultSet.getLong("recommedation_count");
                long correction_count = resultSet.getLong("correction_count");
                long random_count = resultSet.getLong("random_count");
                long image_count = resultSet.getLong("image_count");
                long vote_count = resultSet.getLong("vote_count");
                long set_count = resultSet.getLong("set_count");
                long api_count = resultSet.getLong("api_count");

                return new BotStats(user_count, guild_count, artist_count, album_count, scrobbled_count, rym_count, rym_avg, recommendation_count, correction_count, random_count, image_count, vote_count, set_count, api_count);

            }
            throw new ChuuServiceException();
        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException(e);
        }

    }

    @Override
    public long getUserAlbumCount(Connection con, long discordId) {
        String queryString = "SELECT COUNT(*) AS numb FROM scrobbled_album a JOIN user b ON a.lastfm_id = b.lastfm_id WHERE b.discord_id= ? ";
        try (PreparedStatement preparedStatement = con.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setLong(i, discordId);

            /* Execute query. */
            ResultSet resultSet = preparedStatement.executeQuery();
            if (!resultSet.next())
                return 0;
            return resultSet.getInt("numb");


        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }

    }

    @Override
    public List<StreakEntity> getUserStreaks(long discordId, Connection connection) {
        List<StreakEntity> returnList = new ArrayList<>();
        String queryString = "SELECT artist_combo,album_combo,track_combo,b.name,c.album_name,track_name,streak_start " +
                             "FROM top_combos a JOIN artist b ON a.artist_id = b.id LEFT JOIN album c ON a.album_id = c.id WHERE " +
                             "discord_id = ? ORDER BY  artist_combo DESC,album_combo DESC, track_combo DESC ";
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setLong(1, discordId);

            /* Execute query. */
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                int artistCombo = resultSet.getInt("artist_combo");
                int albumCombo = resultSet.getInt("album_combo");
                int trackCombo = resultSet.getInt("track_combo");
                String artistName = resultSet.getString("name");
                String trackName = resultSet.getString("track_name");

                String albumName = resultSet.getString("album_name");
                Instant timestamp = resultSet.getObject("streak_start", Timestamp.class).toInstant();


                StreakEntity streakEntity = new StreakEntity(artistName, artistCombo, albumName, albumCombo, trackName, trackCombo, timestamp, null);
                returnList.add(streakEntity);
            }

        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
        return returnList;

    }

    @Override
    public List<GlobalStreakEntities> getTopStreaks(Connection connection, @Nullable Long comboFilter, @Nullable Long guildId) {
        List<GlobalStreakEntities> returnList = new ArrayList<>();
        String queryString = "SELECT artist_combo,album_combo,track_combo,b.name,c.album_name,track_name,privacy_mode,a.discord_id,d.lastfm_id,streak_start " +
                             "FROM top_combos a join artist b on a.artist_id = b.id left join album c on a.album_id = c.id join user d on a.discord_id = d.discord_id    ";

        if (guildId != null) {
            queryString += " join user_guild e on d.discord_id = e.discord_id where e.guild_id = ? ";
        } else {
            queryString += " where 1=1";
        }

        if (comboFilter != null) {
            queryString += " and artist_combo > ? ";
        }

        queryString += " order by  artist_combo desc,album_combo desc, track_combo desc ";
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            if (guildId != null)
                preparedStatement.setLong(i++, guildId);
            if (comboFilter != null)
                preparedStatement.setLong(i, comboFilter);

            /* Execute query. */
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                int artistCombo = resultSet.getInt("artist_combo");
                int albumCombo = resultSet.getInt("album_combo");
                int trackCombo = resultSet.getInt("track_combo");
                String artistName = resultSet.getString("name");
                String trackName = resultSet.getString("track_name");

                String albumName = resultSet.getString("album_name");
                PrivacyMode privacyMode = PrivacyMode.valueOf(resultSet.getString("privacy_mode"));
                long discordId = resultSet.getLong("discord_id");
                String lastfm_id = resultSet.getString("lastfm_id");
                Instant timestamp = resultSet.getObject("streak_start", Timestamp.class).toInstant();


                GlobalStreakEntities streakEntity = new GlobalStreakEntities(artistName, artistCombo, albumName, albumCombo, trackName, trackCombo, timestamp, privacyMode, discordId, lastfm_id);
                returnList.add(streakEntity);
            }

        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
        return returnList;

    }

    @Override
    public String getReverseCorrection(Connection connection, String correction) {

        String queryString = "SELECT alias,MIN(a.id)  AS ird " +
                             "FROM corrections a JOIN artist b ON a.artist_id = b.id  JOIN scrobbled_artist c ON b.id = c.artist_id" +
                             " WHERE b.name = ? " +
                             "ORDER BY ird DESC " +
                             "LIMIT 1";

        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setString(i, correction);
            ResultSet resultSet = preparedStatement.executeQuery();

            if (resultSet.next()) {
                return resultSet.getString("alias");
            }
            return correction;

        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException(e);
        }

    }

    @Override
    public List<GlobalStreakEntities> getArtistTopStreaks(Connection connection, Long comboFilter, Long guildId, long artistId, Integer limit) {
        List<GlobalStreakEntities> returnList = new ArrayList<>();
        String queryString = "SELECT artist_combo,album_combo,track_combo,b.name,c.album_name,track_name,privacy_mode,a.discord_id,d.lastfm_id,streak_start" +
                             " FROM top_combos a join artist b on a.artist_id = b.id left join album c on a.album_id = c.id join user d on a.discord_id = d.discord_id    ";

        if (guildId != null) {
            queryString += " join user_guild e on d.discord_id = e.discord_id where e.guild_id = ? ";
        } else {
            queryString += " where 1=1";
        }

        if (comboFilter != null) {
            queryString += " and artist_combo > ? ";
        }
        queryString += " and a.artist_id = ? ";

        queryString += " order by  artist_combo desc,album_combo desc, track_combo desc ";
        if (limit != null) {
            queryString += " limit " + limit;
        }
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            if (guildId != null)
                preparedStatement.setLong(i++, guildId);
            if (comboFilter != null)
                preparedStatement.setLong(i++, comboFilter);
            preparedStatement.setLong(i, artistId);

            /* Execute query. */
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                int artistCombo = resultSet.getInt("artist_combo");
                int albumCombo = resultSet.getInt("album_combo");
                int trackCombo = resultSet.getInt("track_combo");
                String artistName = resultSet.getString("name");
                String trackName = resultSet.getString("track_name");

                String albumName = resultSet.getString("album_name");
                PrivacyMode privacyMode = PrivacyMode.valueOf(resultSet.getString("privacy_mode"));
                long discordId = resultSet.getLong("discord_id");
                String lastfm_id = resultSet.getString("lastfm_id");
                Instant timestamp = resultSet.getObject("streak_start", Timestamp.class).toInstant();


                GlobalStreakEntities streakEntity = new GlobalStreakEntities(artistName, artistCombo, albumName, albumCombo, trackName, trackCombo, timestamp, privacyMode, discordId, lastfm_id);
                returnList.add(streakEntity);
            }

        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
        return returnList;


    }

    @Override
    public List<StreakEntity> getUserArtistTopStreaks(Connection connection, long artistId, Integer limit, long discordId) {
        List<StreakEntity> returnList = new ArrayList<>();
        String queryString = """
                SELECT artist_combo,album_combo,track_combo,b.name,c.album_name,track_name,streak_start
                FROM top_combos a join artist b on a.artist_id = b.id
                left join album c on a.album_id = c.id
                where 1=1
                and a.artist_id = ? and a.discord_id = ?
                order by  artist_combo desc,album_combo desc, track_combo desc
                """;
        if (limit != null) {
            queryString += " limit " + limit;
        }
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setLong(i++, artistId);
            preparedStatement.setLong(i, discordId);

            /* Execute query. */
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                int artistCombo = resultSet.getInt("artist_combo");
                int albumCombo = resultSet.getInt("album_combo");
                int trackCombo = resultSet.getInt("track_combo");
                String artistName = resultSet.getString("name");
                String trackName = resultSet.getString("track_name");

                String albumName = resultSet.getString("album_name");
                Instant timestamp = resultSet.getObject("streak_start", Timestamp.class).toInstant();


                StreakEntity streakEntity = new StreakEntity(artistName, artistCombo, albumName, albumCombo, trackName, trackCombo, timestamp, null);
                returnList.add(streakEntity);
            }

        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
        return returnList;

    }

    @Override
    public List<ScoredAlbumRatings> getServerTopRandomUrls(Connection connection, long guildId) {

        List<ScoredAlbumRatings> returnList = new ArrayList<>();

        String s = """
                SELECT *  FROM (SELECT  COUNT(*) AS  coun,  AVG(rating) AS ave, a.url
                FROM random_links_ratings a
                JOIN user_guild d ON a.discord_id = d.discord_id
                WHERE guild_id = ? GROUP BY a.url) main ORDER BY ((0.5 * main.ave) + 10 * (1 - 0.5) * (1 - (EXP(-main.coun/5)))) DESC LIMIT 200""";
        try (PreparedStatement preparedStatement = connection.prepareStatement(s)) {
            preparedStatement.setLong(1, guildId);
            ResultSet resultSet = preparedStatement.executeQuery();
            getScoredAlbums(returnList, resultSet);
        } catch (
                SQLException throwables) {

            throw new ChuuServiceException(throwables);
        }
        return returnList;
    }

    @Override
    public List<ScoredAlbumRatings> getTopUrlsRatedByUser(Connection connection, long discordId) {

        List<ScoredAlbumRatings> returnList = new ArrayList<>();

        String s = """
                SELECT * FROM
                (SELECT  COUNT(*) AS  coun,  AVG(rating) AS ave, a.url
                FROM random_links_ratings a\s
                WHERE discord_id  = ?\s
                GROUP BY a.url) main
                ORDER BY ((0.5 * main.ave) + 10 * (1 - 0.5) * (1 - (EXP(-main.coun/5))))
                DESC LIMIT 200""";
        try (PreparedStatement preparedStatement = connection.prepareStatement(s)) {
            preparedStatement.setLong(1, discordId);
            ResultSet resultSet = preparedStatement.executeQuery();
            getScoredAlbums(returnList, resultSet);
        } catch (
                SQLException throwables) {

            throw new ChuuServiceException(throwables);
        }
        return returnList;
    }

    @Override
    public List<ScoredAlbumRatings> getGlobalTopRandomUrls(Connection connection) {

        List<ScoredAlbumRatings> returnList = new ArrayList<>();

        String s = """
                SELECT *  FROM
                (SELECT  COUNT(*) AS  coun,  AVG(rating) AS ave, a.url
                FROM random_links_ratings a
                GROUP BY a.url) main
                ORDER BY ((0.5 * main.ave) + 10 * (1 - 0.5) * (1 - (EXP(-main.coun/5))))
                DESC LIMIT 200""";
        try (PreparedStatement preparedStatement = connection.prepareStatement(s)) {
            ResultSet resultSet = preparedStatement.executeQuery();
            getScoredAlbums(returnList, resultSet);
        } catch (
                SQLException throwables) {

            throw new ChuuServiceException(throwables);
        }
        return returnList;
    }

    @Override
    public List<ScoredAlbumRatings> getUserTopRatedUrlsByEveryoneElse(Connection connection, long discordId) {
        List<ScoredAlbumRatings> returnList = new ArrayList<>();

        String s = "SELECT *  FROM (SELECT  COUNT(a.discord_id) AS  coun,  AVG(rating) AS ave, b.url " +
                   "FROM random_links_ratings a " +
                   "RIGHT JOIN randomlinks b ON a.url = b.url  " +
                   " WHERE b.discord_id = ? " +
                   "GROUP BY b.url) main " +
                   "ORDER BY ((0.5 * main.ave) + 10 * (1 - 0.5) * (1 - (EXP(-main.coun/5))))  DESC LIMIT 200";
        try (PreparedStatement preparedStatement = connection.prepareStatement(s)) {
            preparedStatement.setLong(1, discordId);
            ResultSet resultSet = preparedStatement.executeQuery();
            getScoredAlbums(returnList, resultSet);
        } catch (
                SQLException throwables) {

            throw new ChuuServiceException(throwables);
        }
        return returnList;
    }

    @Override
    public Set<String> getPrivateLastfmIds(Connection connection) {

        Set<String> returnList = new HashSet<>();

        String s = "SELECT lastfm_id FROM user WHERE private_lastfm";
        try (PreparedStatement preparedStatement = connection.prepareStatement(s)) {
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                returnList.add(resultSet.getString(1));
            }
        } catch (
                SQLException throwables) {

            throw new ChuuServiceException(throwables);
        }
        return returnList;
    }

    @Override
    public List<ScrobbledAlbum> getUserAlbums(Connection connection, String lastfmId, @Nullable Integer limit) {

        List<ScrobbledAlbum> scrobbledAlbums = new ArrayList<>();
        String s = "select b.album_name,c.name,b.url,b.mbid,a.playnumber  from scrobbled_album a join album b on a.album_id = b.id join artist c on a.artist_id = c.id  where a.lastfm_id = ? order by a.playnumber desc";
        if (limit != null) {
            s += " limit " + limit;
        }
        try (PreparedStatement preparedStatement = connection.prepareStatement(s)) {
            preparedStatement.setString(1, lastfmId);
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                String album = resultSet.getString(1);
                String artist = resultSet.getString(2);
                String url = resultSet.getString(3);
                String mbid = resultSet.getString(4);
                int playnumber = resultSet.getInt(5);

                ScrobbledAlbum scrobbledAlbum = new ScrobbledAlbum(album, artist, url, mbid);
                scrobbledAlbum.setCount(playnumber);
                scrobbledAlbums.add(scrobbledAlbum);
            }
        } catch (
                SQLException throwables) {

            throw new ChuuServiceException(throwables);
        }
        return scrobbledAlbums;
    }

    @Override
    public ResultWrapper<ScrobbledAlbum> getGuildTopAlbum(Connection connection, Long guildID, int limit, boolean doCount) {

        String innerSelect = """
                (select album_id,playnumber
                from scrobbled_album a
                """;
        if (guildID != null) {
            innerSelect += """
                    join user b on a.lastfm_id = b.lastfm_id
                    join user_guild c on b.discord_id = c.discord_id
                    where c.guild_id = ?""";
        }
        innerSelect += ") main ";

        String normalQuery = """
                SELECT (SELECT name FROM artist a JOIN album b ON b.artist_id = a.id WHERE b.id = result.album_id) AS name ,
                (SELECT url FROM  album b  WHERE b.id = result.album_id) AS url,
                (SELECT mbid FROM  album b  WHERE b.id = result.album_id) AS mbid,
                (SELECT album_name FROM  album b  WHERE b.id = result.album_id) AS album_name,
                orden AS orden
                FROM (SELECT sum(playnumber) AS orden,album_id FROM ${main} GROUP BY album_id ORDER BY  orden DESC LIMIT ? ) result
                """.replace("${main}", innerSelect);

        String countQuery = "SELECT count(*) AS orden FROM " + innerSelect;


        List<ScrobbledAlbum> list = new ArrayList<>();
        int count = 0;
        int i = 1;
        try (PreparedStatement preparedStatement1 = connection.prepareStatement(normalQuery)) {
            if (guildID != null)
                preparedStatement1.setLong(i++, guildID);

            preparedStatement1.setInt(i, limit);

            ResultSet resultSet1 = preparedStatement1.executeQuery();

            while (resultSet1.next()) {
                String artist = resultSet1.getString("name");
                String album = resultSet1.getString("album_name");
                String mbid = resultSet1.getString("mbid");
                String url = resultSet1.getString("url");

                int plays = resultSet1.getInt("orden");
                ScrobbledAlbum who = new ScrobbledAlbum(album, artist, url, mbid);
                who.setCount(plays);
                list.add(who);
            }
            if (doCount) {

                PreparedStatement preparedStatement = connection.prepareStatement(countQuery);
                i = 1;
                if (guildID != null)
                    preparedStatement.setLong(i, guildID);

                ResultSet resultSet = preparedStatement.executeQuery();
                if (resultSet.next()) {
                    count = resultSet.getInt(1);
                }


            }
        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException(e);
        }
        return new ResultWrapper<>(count, list);
    }


    @Override
    public ResultWrapper<ScrobbledAlbum> getCollectiveAOTY(Connection connection, Year year, Long guildID, int limit, boolean doCount) {

        String normalQUery = "SELECT d.name,e.album_name, sum(playnumber) AS orden ,e.url,e.mbid  ";

        String countQuery = "Select count(*) as orden ";


        String queryBody = "FROM  scrobbled_album a " +
                           " JOIN user b" +
                           " ON a.lastfm_id = b.lastfm_id" +
                           " JOIN artist d " +
                           " ON a.artist_id = d.id" +
                           " join album e on a.album_id = e.id";
        if (guildID != null) {
            queryBody += " JOIN  user_guild c" +
                         " ON b.discord_id=c.discord_id" +
                         " WHERE c.guild_id = ?";
        } else {
            queryBody += " where 1 = 1 ";
        }
        queryBody += " and e.release_year = ? ";

        List<ScrobbledAlbum> list = new ArrayList<>();
        int count = 0;
        int i = 1;
        try (PreparedStatement preparedStatement1 = connection.prepareStatement(normalQUery + queryBody + " GROUP BY album_id  ORDER BY orden DESC  LIMIT ?")) {
            if (guildID != null)
                preparedStatement1.setLong(i++, guildID);

            preparedStatement1.setInt(i++, year.getValue());
            preparedStatement1.setInt(i, limit);

            ResultSet resultSet1 = preparedStatement1.executeQuery();

            while (resultSet1.next()) {
                String artist = resultSet1.getString("d.name");
                String album = resultSet1.getString("e.album_name");
                String mbid = resultSet1.getString("e.mbid");


                String url = resultSet1.getString("e.url");

                int plays = resultSet1.getInt("orden");
                ScrobbledAlbum who = new ScrobbledAlbum(album, artist, url, mbid);
                who.setCount(plays);
                list.add(who);
            }
            if (doCount) {

                PreparedStatement preparedStatement = connection.prepareStatement(countQuery + queryBody);
                i = 1;
                if (guildID != null)
                    preparedStatement.setLong(i++, guildID);

                preparedStatement.setInt(i, year.getValue());

                ResultSet resultSet = preparedStatement.executeQuery();
                if (resultSet.next()) {
                    count = resultSet.getInt(1);
                }


            }
        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException(e);
        }
        return new ResultWrapper<>(count, list);
    }


    @Override
    public List<ScrobbledArtist> getTopTag(Connection connection, String genre, @Nullable Long guildId, int limit) {
        List<ScrobbledArtist> scrobbledArtists = new ArrayList<>();


        String queryString = " " +
                             "Select a.artist_id,sum(playNumber) plays,b.url,b.name" +
                             " from scrobbled_artist a " +
                             "join artist_tags e on a.artist_id = e.artist_id  " +
                             "join artist b on a.artist_id = b.id" +
                             " join user c on a.lastfm_id = c.lastfm_id ";

        if (guildId != null) {
            queryString += " join user_guild d on c.discord_id = d.discord_id " +
                           "where e.tag = ? and d.guild_id = ?";
        } else {
            queryString += "where e.tag = ? ";

        }
        queryString += " group by a.artist_id " +
                       "order by plays desc limit ?";
        try (
                PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setString(i++, genre);
            if (guildId != null) {
                preparedStatement.setLong(i++, guildId);
            }
            preparedStatement.setInt(i, limit);

            ResultSet resultSet = preparedStatement.executeQuery();

            while (resultSet.next()) {
                String artistName = resultSet.getString("b.name");
                String artistUrl = resultSet.getString("b.url");
                int plays = resultSet.getInt("plays");
                long artist_id = resultSet.getInt("a.artist_id");

                ScrobbledArtist value = new ScrobbledArtist(null, artistName, plays);
                value.setUrl(artistUrl);
                value.setArtistId(artist_id);
                scrobbledArtists.add(value);
            }
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
        return scrobbledArtists;
    }

    @Override
    public WrapperReturnNowPlaying whoKnowsTag(Connection connection, String genre, long guildId, int limit) {


        String queryString =
                "SELECT b.lastfm_id, sum(b.playnumber) plays, c.discord_id " +
                "FROM  artist_tags a " +
                "JOIN artist a2 ON a.artist_id = a2.id  " +
                "JOIN scrobbled_artist b on b.artist_id = a2.id " +
                "JOIN `user` c on c.lastfm_id = b.lastfm_id " +
                "JOIN user_guild d on c.discord_ID = d.discord_Id " +
                "where d.guild_Id = ? " +
                "and  a.tag = ? " +
                " group by b.lastfm_id,c.discord_id " +
                "ORDER BY plays desc ";
        queryString = limit == Integer.MAX_VALUE ? queryString : queryString + "limit " + limit;
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {

            int i = 1;
            preparedStatement.setLong(i++, guildId);
            preparedStatement.setString(i, genre);


            ResultSet resultSet = preparedStatement.executeQuery();
            List<ReturnNowPlaying> returnList = new ArrayList<>();


            int j = 0;
            while (resultSet.next() && (j < limit)) {
                String lastfmId = resultSet.getString("b.lastfm_id");
                int playNumber = resultSet.getInt("plays");
                long discordId = resultSet.getLong("c.discord_ID");
                returnList.add(new TagPlaying(discordId, lastfmId, genre, playNumber));
            }
            return new WrapperReturnNowPlaying(returnList, returnList.size(), null, genre);

        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public Set<String> getBannedTags(Connection connection) {
        Set<String> bannedTags = new HashSet<>();
        String queryString = "SELECT tag FROM banned_tags";
        try (
                PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            ResultSet resultSet = preparedStatement.executeQuery();

            while (resultSet.next()) {
                bannedTags.add(resultSet.getString(1).toLowerCase());
            }
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
        return bannedTags;
    }


    private String prepareSingle(int size) {
        return String.join(",", Collections.nCopies(size, "(?)"));
    }


    @Override
    public List<ScrobbledAlbum> getUserAlbumsWithTag(Connection connection, long discordId, String tag, int limit) {
        String queryString = "SELECT a.id AS album_id, c.id,album_name,c.name,a.mbid,c.mbid AS artist_mbid,a.url,d.playnumber  " +
                             "FROM album a " +
                             "JOIN artist c ON a.artist_id = c.id " +
                             "JOIN scrobbled_album d ON a.id = d.album_id " +
                             "JOIN user e ON d.lastfm_id = e.lastfm_id  " +
                             "JOIN  album_tags b ON a.id = b.album_id " +
                             "WHERE tag = ? AND e.discord_id = ? ORDER BY playnumber DESC LIMIT ? ";
        List<ScrobbledAlbum> returnInfoes = new ArrayList<>();
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {


            preparedStatement.setString(1, tag);
            preparedStatement.setLong(2, discordId);
            preparedStatement.setInt(3, limit);


            ResultSet resultSet = preparedStatement.executeQuery();

            while (resultSet.next()) {
                int plays = resultSet.getInt("playnumber");
                String url = resultSet.getString("url");
                String name = resultSet.getString("name");
                String albumName = resultSet.getString("album_name");
                String albumMbid = resultSet.getString("mbid");
                String artistMbid = resultSet.getString("artist_mbid");
                ScrobbledAlbum sb = new ScrobbledAlbum(albumName, name, url, albumMbid);
                sb.setArtistId(resultSet.getLong("id"));
                sb.setAlbumId(resultSet.getLong("album_id"));
                sb.setCount(resultSet.getInt("playnumber"));

                returnInfoes.add(sb);

            }
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
        return returnInfoes;
    }

    @Override
    public List<AlbumInfo> getAlbumsWithTag(Connection connection, List<Long> albums, long discordId, String tag) {
        String queryString = "SELECT album_name,c.name,a.mbid,c.mbid as artist_mbid " +
                             "FROM album a " +
                             "join artist c on a.artist_id = c.id " +
                             "join scrobbled_album d on a.id = d.album_id " +
                             "join user e on d.lastfm_id = e.lastfm_id  " +
                             "join  album_tags b on a.id = b.album_id " +
                             "WHERE a.id  IN (%s) and tag = ? and e.discord_id = ? ";
        String sql = String.format(queryString, albums.isEmpty() ? null : prepareSingle(albums.size()));
        List<AlbumInfo> returnInfoes = new ArrayList<>();
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {


            for (int i = 0; i < albums.size(); i++) {
                preparedStatement.setLong(i + 1, albums.get(i));
            }
            preparedStatement.setString(albums.size() + 1, tag);
            preparedStatement.setLong(albums.size() + 2, discordId);


            ResultSet resultSet = preparedStatement.executeQuery();

            while (resultSet.next()) {
                String name = resultSet.getString("name");
                String albumName = resultSet.getString("album_name");
                String albumMbid = resultSet.getString("mbid");
                String artistMbid = resultSet.getString("artist_mbid");
                AlbumInfo e = new AlbumInfo(albumMbid, albumName, name);
                returnInfoes.add(e);

            }
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
        return returnInfoes;
    }


    @Override
    public List<ScrobbledArtist> getUserArtistsWithMBID(Connection connection, String lastfmId) {
        List<ScrobbledArtist> scrobbledAlbums = new ArrayList<>();
        String s = "SELECT name,b.url,b.mbid,a.playnumber  FROM scrobbled_artist a JOIN artist b ON a.artist_id = b.id WHERE a.lastfm_id = ? AND b.mbid IS NOT NULL AND mbid <> '' ORDER BY a.playnumber DESC";
        try (PreparedStatement preparedStatement = connection.prepareStatement(s)) {
            preparedStatement.setString(1, lastfmId);
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                String artist = resultSet.getString(1);
                String url = resultSet.getString(2);
                String mbid = resultSet.getString(3);
                int playnumber = resultSet.getInt(4);
                ScrobbledArtist scrobbledArtist = new ScrobbledArtist(artist, playnumber, url);
                scrobbledArtist.setArtistMbid(mbid);
                scrobbledArtist.setCount(playnumber);
                scrobbledAlbums.add(scrobbledArtist);
            }
        } catch (
                SQLException throwables) {

            throw new ChuuServiceException(throwables);
        }
        return scrobbledAlbums;
    }

    @Override
    public List<ScrobbledArtist> getUserArtistWithTag(Connection connection, long discordId, String genre, int limit) {
        String queryString = "SELECT c.name,c.url,c.id,d.playnumber " +
                             "FROM artist c " +
                             "JOIN scrobbled_artist d ON c.id = d.artist_id " +
                             "JOIN user e ON d.lastfm_id = e.lastfm_id  " +
                             "JOIN  artist_tags b ON c.id = b.artist_id " +
                             "WHERE  tag = ? AND e.discord_id = ? ORDER BY playnumber DESC LIMIT ? ";
        List<ScrobbledArtist> returnInfoes = new ArrayList<>();
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {


            preparedStatement.setString(1, genre);
            preparedStatement.setLong(2, discordId);
            preparedStatement.setInt(3, limit);

            ResultSet resultSet = preparedStatement.executeQuery();

            while (resultSet.next()) {
                String name = resultSet.getString("name");
                long id = resultSet.getLong("id");
                int plays = resultSet.getInt("playnumber");
                String url = resultSet.getString("url");
                ScrobbledArtist scrobbledArtist = new ScrobbledArtist(name, plays, url);
                scrobbledArtist.setArtistId(id);
                returnInfoes.add(scrobbledArtist);

            }
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
        return returnInfoes;
    }

    @Override
    public List<ArtistInfo> getArtistWithTag(Connection connection, List<ArtistInfo> artists, long discordId, String genre) {
        String queryString = "SELECT c.name,c.url,c.mbid as artist_mbid " +
                             "FROM artist c " +
                             "join scrobbled_artist d on c.id = d.artist_id " +
                             "join user e on d.lastfm_id = e.lastfm_id  " +
                             "join  artist_tags b on c.id = b.artist_id " +
                             "WHERE (c.name)  IN (%s) and tag = ? and e.discord_id = ? ";
        String sql = String.format(queryString, artists.isEmpty() ? null : String.join(",", Collections.nCopies(artists.size(), "?")));
        List<ArtistInfo> returnInfoes = new ArrayList<>();
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {


            for (int i = 0; i < artists.size(); i++) {
                preparedStatement.setString(i + 1, artists.get(i).getArtist());
            }
            preparedStatement.setString(artists.size() + 1, genre);
            preparedStatement.setLong(artists.size() + 2, discordId);


            ResultSet resultSet = preparedStatement.executeQuery();

            while (resultSet.next()) {
                String name = resultSet.getString("name");
                String artistMbid = resultSet.getString("artist_mbid");
                String url = resultSet.getString("url");

                ArtistInfo e = new ArtistInfo(url, name, artistMbid);
                returnInfoes.add(e);

            }
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
        return returnInfoes;
    }

    @Override
    public Map<Genre, Integer> genreCountsByArtist(Connection connection, List<ArtistInfo> artistInfos) {

        String queryString = "SELECT tag,count(*) as coun FROM artist a join artist_tags b  on a.id = b.artist_id WHERE name IN (%s) group by b.tag";
        String sql = String.format(queryString, preparePlaceHolders(artistInfos.size()));


        try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            for (int i = 0; i < artistInfos.size(); i++) {
                preparedStatement.setString(i + 1, artistInfos.get(i).getArtist());
            }

            Map<Genre, Integer> returnList = new HashMap<>();
            /* Execute query. */
            ResultSet resultSet = preparedStatement.executeQuery();

            while (resultSet.next()) {

                String tag = resultSet.getString("tag");
                int count = resultSet.getInt("coun");

                returnList.put(new Genre(tag), count);

            }
            return returnList;
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public List<WrapperReturnNowPlaying> whoknowsSet(Connection connection, Set<String> artists, long guildId, int limit, @Nullable String user) {
        Map<String, String> urlMap = new HashMap<>();


        String queryString =
                "SELECT a2.name, a.lastfm_id, a.playNumber, a2.url, c.discord_id " +
                "FROM  scrobbled_artist a  use index (artist_id)" +
                " JOIN artist a2 ON a.artist_id = a2.id  " +
                "JOIN `user` c on c.lastFm_Id = a.lastFM_ID " +
                "JOIN user_guild d on c.discord_ID = d.discord_Id " +
                "where d.guild_Id = ? " +
                "and  (name) in  (%s) " +
                "ORDER BY a.playNumber desc ";
        String sql = String.format(queryString, preparePlaceHolders(artists.size()));

        try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {

            int i = 1;
            preparedStatement.setLong(i, guildId);

            for (String artist : artists) {
                preparedStatement.setString(++i, artist);
            }


            ResultSet resultSet = preparedStatement.executeQuery();
            String url;
            String artistName;
            List<ReturnNowPlaying> returnList = new ArrayList<>();



            /* Get results. */
            int j = 0;
            while (resultSet.next() && (j < limit)) {
                String lastfmId = resultSet.getString("a.lastFM_ID");
                if (user != null) {
                    if (!lastfmId.equals(user)) {
                        continue;
                    }
                }
                url = resultSet.getString("a2.url");
                artistName = resultSet.getString("a2.name");
                urlMap.put(artistName, url);
                int playNumber = resultSet.getInt("a.playNumber");
                long discordId = resultSet.getLong("c.discord_ID");

                returnList.add(new ReturnNowPlaying(discordId, lastfmId, artistName, playNumber));

            }

            return returnList
                    .stream().collect(
                            Collectors.collectingAndThen(
                                    Collectors.groupingBy(ReturnNowPlaying::getArtist,
                                            Collectors.collectingAndThen(
                                                    Collectors.toList(), list -> {
                                                        if (list.isEmpty()) {
                                                            return null;
                                                        }
                                                        ReturnNowPlaying a = list.get(0);
                                                        return new WrapperReturnNowPlaying(list, list.size(), urlMap.get(a.getArtist()), a.getArtist());
                                                    })),
                                    result -> result.values().stream().filter(Objects::nonNull)

                                            .sorted(Comparator.comparingLong(
                                                    (WrapperReturnNowPlaying t) -> t.getReturnNowPlayings().stream().mapToLong(ReturnNowPlaying::getPlayNumber).sum()).reversed())
                                            .limit(limit)
                                            .toList()));

        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }

    }

    @Override
    public WrapperReturnNowPlaying whoknowsTagsSet(Connection connection, Set<String> tags, long guildId, int limit, String user, SearchMode searchMode) {


        String queryString =
                """
                        SELECT b.lastfm_id, sum(b.playnumber) plays, c.discord_id\s
                        FROM  artist_tags a\s
                        JOIN artist a2 ON a.artist_id = a2.id \s
                        JOIN scrobbled_artist b on b.artist_id = a2.id\s
                        JOIN `user` c on c.lastfm_id = b.lastfm_id\s
                        JOIN user_guild d on c.discord_ID = d.discord_Id\s
                        where d.guild_Id = ?\s
                        and  (tag) in  (%s)\s
                         group by b.lastfm_id,c.discord_id\s
                        """;

        if (searchMode == SearchMode.EXCLUSIVE) {
            queryString += " having count(distinct tag) = ? ";

        }
        queryString += "ORDER BY plays desc ";
        String sql = String.format(queryString, preparePlaceHolders(tags.size()));

        try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {

            int i = 1;
            preparedStatement.setLong(i, guildId);

            for (String artist : tags) {
                preparedStatement.setString(++i, artist);
            }
            if (searchMode == SearchMode.EXCLUSIVE) {
                preparedStatement.setInt(++i, tags.size());
            }


            ResultSet resultSet = preparedStatement.executeQuery();
            String url = "";
            String artistName = "";
            List<ReturnNowPlaying> returnList = new ArrayList<>();


            int j = 0;
            while (resultSet.next() && (j < limit)) {
                String lastfmId = resultSet.getString("b.lastfm_id");
                int playNumber = resultSet.getInt("plays");
                long discordId = resultSet.getLong("c.discord_ID");
                returnList.add(new TagPlaying(discordId, lastfmId, tags.stream().findAny().orElse(null), playNumber));
            }
            return new WrapperReturnNowPlaying(returnList, returnList.size(), null, String.join(" | ", tags));


        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public List<ScrobbledArtist> getTopTagSet(Connection connection, Set<String> tags, Long guildId, int limit, SearchMode mode) {
        List<ScrobbledArtist> scrobbledArtists = new ArrayList<>();
        String queryString = """
                Select a.artist_id,sum(playNumber) plays,b.url,b.name
                from scrobbled_artist a
                join artist_tags e on a.artist_id = e.artist_id
                join artist b on a.artist_id = b.id
                join user c on a.lastfm_id = c.lastfm_id
                """;

        if (guildId != null) {
            queryString += " join user_guild d on c.discord_id = d.discord_id " +
                           "where e.tag in (%s) and d.guild_id = ?";
        } else {
            queryString += "where e.tag in (%s) ";

        }
        queryString += " group by a.artist_id ";
        if (mode == SearchMode.EXCLUSIVE) {
            queryString += " having count(distinct tag) = ? ";

        }
        queryString += "order by plays desc limit ?";
        String sql = String.format(queryString, preparePlaceHolders(tags.size()));

        try (
                PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            int i = 0;

            for (String tag : tags) {
                preparedStatement.setString(++i, tag);
            }
            if (guildId != null) {
                preparedStatement.setLong(++i, guildId);
            }
            if (mode == SearchMode.EXCLUSIVE) {
                preparedStatement.setInt(++i, tags.size());
            }

            preparedStatement.setInt(++i, limit);

            ResultSet resultSet = preparedStatement.executeQuery();

            while (resultSet.next()) {
                String artistName = resultSet.getString("b.name");
                String artistUrl = resultSet.getString("b.url");
                int plays = resultSet.getInt("plays");
                long artist_id = resultSet.getInt("a.artist_id");

                ScrobbledArtist value = new ScrobbledArtist(null, artistName, plays);
                value.setUrl(artistUrl);
                value.setArtistId(artist_id);
                scrobbledArtists.add(value);
            }
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
        return scrobbledArtists;
    }

    @Override
    public Set<Pair<String, String>> getArtistBannedTags(Connection connection) {
        Set<Pair<String, String>> bannedTags = new HashSet<>();
        String queryString = "SELECT tag,name FROM banned_artist_tags a JOIN artist b ON a.artist_id = b.id";
        try (
                PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            ResultSet resultSet = preparedStatement.executeQuery();

            while (resultSet.next()) {
                String tag = resultSet.getString(1);
                String artist = resultSet.getString(2);

                bannedTags.add(Pair.of(artist.toLowerCase(), tag.toLowerCase()));
            }
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
        return bannedTags;
    }

    @Override
    public Set<String> getArtistTag(Connection connection, long artistId) {
        String queryString = "SELECT tag FROM artist_tags WHERE artist_id = ? ";
        Set<String> returnList = new HashSet<>();
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setLong(i, artistId);
            ResultSet resultSet = preparedStatement.executeQuery();

            while (resultSet.next()) {
                String name = resultSet.getString("tag");
                returnList.add(name);
            }
            return returnList;

        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException(e);
        }

    }

    @Override
    public WrapperReturnNowPlaying globalWhoKnowsTrack(Connection connection, long trackId, int limit, long ownerId, boolean includeBotted, boolean hidePrivate) {


        String queryString =
                """
                        SELECT a2.track_name,a3.name, a.lastfm_id, a.playNumber, coalesce(a2.url,a4.url,a3.url) as url, c.discord_id,c.privacy_mode
                         FROM  scrobbled_track a
                         JOIN track a2 ON a.track_id = a2.id
                        join artist a3 on a2.artist_id = a3.id
                        left join album a4 on a2.album_id = a4.id
                        JOIN `user` c on c.lastFm_Id = a.lastFM_ID
                        where   (? or not c.botted_account or c.discord_id = ? )
                        and (not ? or c.privacy_mode not in ('NORMAL','STRICT') or c.discord_id = ?) and
                        a2.id = ? ORDER BY a.playNumber desc\s""";
        queryString = limit == Integer.MAX_VALUE ? queryString : queryString + "limit " + limit;
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {

            int i = 1;
            preparedStatement.setBoolean(i++, includeBotted);
            preparedStatement.setLong(i++, ownerId);
            preparedStatement.setBoolean(i++, hidePrivate);
            preparedStatement.setLong(i++, ownerId);

            preparedStatement.setLong(i, trackId);


            ResultSet resultSet = preparedStatement.executeQuery();
            String url = "";
            String artistName = "";
            String trackName = "";
            List<ReturnNowPlaying> returnList = new ArrayList<>();

            int j = 0;
            while (resultSet.next() && (j < limit)) {
                url = resultSet.getString("url");
                artistName = resultSet.getString("a3.name");
                trackName = resultSet.getString("a2.track_name");

                String lastfmId = resultSet.getString("a.lastFM_ID");

                int playNumber = resultSet.getInt("a.playNumber");
                long discordId = resultSet.getLong("c.discord_ID");
                PrivacyMode privacyMode = PrivacyMode.valueOf(resultSet.getString("c.privacy_mode"));

                returnList.add(new GlobalReturnNowPlayingSong(discordId, lastfmId, artistName, playNumber, privacyMode, trackName));
            }
            /* Return booking. */
            return new WrapperReturnNowPlaying(returnList, returnList.size(), url, artistName + " - " + trackName);

        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public UniqueWrapper<TrackPlays> getUserTrackCrowns(Connection connection, String lastfmId, int crownthreshold, long guildId) {
        long discordId = -1L;
        String queryString =
                """
                        WITH
                            mines AS (
                                SELECT
                                    track_id,
                                    playnumber
                                FROM
                                    scrobbled_track
                                WHERE
                                    lastfm_id = ?
                                    """ +
                (crownthreshold > 1 ? " and playnumber >= ? " : "") +
                """
                            ),
                            globals AS (
                                SELECT
                                    a.track_id,
                                    max(a.playnumber) AS outf
                                FROM
                                    scrobbled_track a
                                        JOIN mines b ON a.track_id = b.track_id
                                        join user u ON a.lastfm_id = u.lastfm_id
                                        join user_guild ug ON u.discord_id = ug.discord_id
                                where guild_id = ?
                                GROUP BY a.track_id
                            )
                        SELECT
                            playnumber,
                            c.track_name,
                            b.name
                        FROM
                            mines
                                JOIN globals ON globals.track_id = mines.track_id AND mines.playnumber = globals.outf
                                JOIN track c ON globals.track_id = c.id
                                JOIN artist b ON c.artist_id = b.id
                        ORDER BY
                            playnumber DESC
                        """;

        List<TrackPlays> returnList = new ArrayList<>();
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setString(i++, lastfmId);
            if (crownthreshold > 1) {
                preparedStatement.setInt(i++, crownthreshold);
            }
            preparedStatement.setLong(i, guildId);

            ResultSet resultSet = preparedStatement.executeQuery();


            while (resultSet.next()) {
                String artist = resultSet.getString("b.name");
                String track = resultSet.getString("c.track_name");


                int plays = resultSet.getInt("playnumber");
                returnList.add(new TrackPlays(artist, plays, track));
            }
        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException(e);
        }
        return new UniqueWrapper<>(returnList.size(), discordId, lastfmId, returnList);
    }

    @Override
    public List<LbEntry<Integer>> trackCrownsLeaderboard(Connection connection, long guildId, int threshold) {
        String queryString = """
                SELECT t2.lastfm_id, t3.discord_id, count(t2.lastfm_id) ord
                FROM (SELECT a.track_id, max(a.playnumber) plays
                      FROM scrobbled_track a
                               JOIN user b ON a.lastfm_id = b.lastfm_id
                               JOIN user_guild c ON b.discord_id = c.discord_id
                      WHERE c.guild_id = ?
                      GROUP BY a.track_id) t
                         JOIN scrobbled_track t2 ON t.plays = t2.playnumber AND t.track_id = t2.track_id
                         JOIN user t3 ON t2.lastfm_id = t3.lastfm_id
                         JOIN user_guild t4 ON t3.discord_id = t4.discord_id
                WHERE t4.guild_id = ? \s""";
        if (threshold > 1) {
            queryString += " and t2.playnumber > ? ";
        }


        queryString += "  GROUP BY t2.lastfm_id,t3.discord_id " +
                       "  ORDER BY ord DESC";


        return getLbEntries(connection, guildId, queryString, TrackCrownLbEntry::new, true, threshold, Integer.class);

    }

    @Override
    public ResultWrapper<UserArtistComparison> similarAlbumes(Connection connection, long artistId, List<String> lastFmNames, int limit) {
        String userA = lastFmNames.get(0);
        String userB = lastFmNames.get(1);

        String queryString =
                "SELECT c.album_name  , a.playnumber,b.playnumber ," +
                "((a.playnumber * b.playnumber)/(ABS(a.playnumber-b.playnumber)+1))  *" +
                " (((a.playnumber + b.playnumber)) * 2.5) * " +
                " IF((a.playnumber > 10 * b.playnumber OR b.playnumber > 10 * a.playnumber) AND LEAST(a.playnumber,b.playnumber) < 400 ,0.01,2) " +
                "media ," +
                " c.url " +
                "FROM " +
                "(SELECT album_id,playnumber " +
                "FROM scrobbled_album " +
                "JOIN user b ON scrobbled_album.lastfm_id = b.lastfm_id " +
                "WHERE b.lastfm_id = ? AND scrobbled_album.artist_id = ? ) a " +
                "JOIN " +
                "(SELECT album_id,playnumber " +
                "FROM scrobbled_album " +
                " JOIN user b ON scrobbled_album.lastfm_id = b.lastfm_id " +
                " WHERE b.lastfm_id = ? AND scrobbled_album.artist_id = ?) b " +
                "ON a.album_id=b.album_id " +
                "JOIN album c " +
                "ON c.id=b.album_id" +
                " ORDER BY media DESC";

        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {

            int i = 1;
            preparedStatement.setString(i++, userA);
            preparedStatement.setLong(i++, artistId);
            preparedStatement.setString(i++, userB);
            preparedStatement.setLong(i, artistId);


            /* Execute query. */
            ResultSet resultSet = preparedStatement.executeQuery();
            List<UserArtistComparison> returnList = new ArrayList<>();

            int counter = 0;
            while (resultSet.next()) {
                counter++;
                if (counter <= limit) {
                    String name = resultSet.getString("c.album_name");
                    int countA = resultSet.getInt("a.playNumber");
                    int countB = resultSet.getInt("b.playNumber");
                    String url = resultSet.getString("c.url");
                    returnList.add(new UserArtistComparison(countA, countB, name, userA, userB, url));
                }
            }

            return new ResultWrapper<>(counter, returnList);

        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public ResultWrapper<UserArtistComparison> similarTracks(Connection connection, long artistId, List<String> lastFmNames, int limit) {
        String userA = lastFmNames.get(0);
        String userB = lastFmNames.get(1);

        String queryString =
                "SELECT c.track_name  , a.playnumber,b.playnumber ," +
                "((a.playnumber * b.playnumber)/(ABS(a.playnumber-b.playnumber)+1))  *" +
                " (((a.playnumber + b.playnumber)) * 2.5) * " +
                " IF((a.playnumber > 10 * b.playnumber OR b.playnumber > 10 * a.playnumber) AND LEAST(a.playnumber,b.playnumber) < 400 ,0.01,2) " +
                "media ," +
                " COALESCE(c.url,d.url) AS url " +
                "FROM " +
                "(SELECT track_id,playnumber " +
                "FROM scrobbled_track " +
                "JOIN user b ON scrobbled_track.lastfm_id = b.lastfm_id " +
                "WHERE b.lastfm_id = ? AND scrobbled_track.artist_id = ? ) a " +
                "JOIN " +
                "(SELECT track_id,playnumber " +
                "FROM scrobbled_track " +
                " JOIN user b ON scrobbled_track.lastfm_id = b.lastfm_id " +
                " WHERE b.lastfm_id = ? AND scrobbled_track.artist_id = ?) b " +
                "ON a.track_id=b.track_id " +
                "JOIN track c " +
                "ON c.id=b.track_id " +
                " LEFT JOIN album d ON c.album_id =d.id " +
                " ORDER BY media DESC";

        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {

            int i = 1;
            preparedStatement.setString(i++, userA);
            preparedStatement.setLong(i++, artistId);
            preparedStatement.setString(i++, userB);
            preparedStatement.setLong(i, artistId);


            /* Execute query. */
            ResultSet resultSet = preparedStatement.executeQuery();
            List<UserArtistComparison> returnList = new ArrayList<>();

            int counter = 0;
            while (resultSet.next()) {
                counter++;
                if (counter <= limit) {
                    String name = resultSet.getString("c.track_name");
                    int countA = resultSet.getInt("a.playNumber");
                    int countB = resultSet.getInt("b.playNumber");
                    String url = resultSet.getString("url");
                    returnList.add(new UserArtistComparison(countA, countB, name, userA, userB, url));
                }
            }

            return new ResultWrapper<>(counter, returnList);

        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public ResultWrapper<UserArtistComparison> similarAlbumTracks(Connection connection, long albumId, List<String> lastFmNames, int limit) {
        String userA = lastFmNames.get(0);
        String userB = lastFmNames.get(1);

        String queryString =
                "SELECT c.track_name  , a.playnumber,b.playnumber ," +
                "((a.playnumber * b.playnumber)/(ABS(a.playnumber-b.playnumber)+1))  *" +
                " (((a.playnumber + b.playnumber)) * 2.5) * " +
                " IF((a.playnumber > 10 * b.playnumber OR b.playnumber > 10 * a.playnumber) AND LEAST(a.playnumber,b.playnumber) < 400 ,0.01,2) " +
                "media ," +
                " COALESCE(c.url,d.url) AS url " +
                "FROM " +
                "(SELECT track_id,playnumber " +
                "FROM scrobbled_track a JOIN track c ON a.track_id = c.id " +
                "JOIN user b ON a.lastfm_id = b.lastfm_id " +
                "WHERE b.lastfm_id = ? AND c.album_id = ? ) a " +
                "JOIN " +
                "(SELECT track_id,playnumber " +
                "FROM scrobbled_track a JOIN track c ON a.track_id = c.id " +
                " JOIN user b ON a.lastfm_id = b.lastfm_id " +
                " WHERE b.lastfm_id = ? AND c.album_id = ?) b " +
                "ON a.track_id=b.track_id " +
                "JOIN track c " +
                "ON c.id=b.track_id " +
                " LEFT JOIN album d ON c.album_id =d.id " +
                " ORDER BY media DESC";

        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {

            int i = 1;
            preparedStatement.setString(i++, userA);
            preparedStatement.setLong(i++, albumId);
            preparedStatement.setString(i++, userB);
            preparedStatement.setLong(i, albumId);


            /* Execute query. */
            ResultSet resultSet = preparedStatement.executeQuery();
            List<UserArtistComparison> returnList = new ArrayList<>();

            int counter = 0;
            while (resultSet.next()) {
                counter++;
                if (counter <= limit) {
                    String name = resultSet.getString("c.track_name");
                    int countA = resultSet.getInt("a.playNumber");
                    int countB = resultSet.getInt("b.playNumber");
                    String url = resultSet.getString("url");
                    returnList.add(new UserArtistComparison(countA, countB, name, userA, userB, url));
                }
            }

            return new ResultWrapper<>(counter, returnList);

        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public UniqueWrapper<ArtistPlays> getGlobalAlbumCrowns(Connection connection, String lastfmid, int threshold, boolean includeBottedUsers, long ownerId) {
        List<ArtistPlays> returnList = new ArrayList<>();
        long discordId = 0;

        String queryString =
                """
                        WITH
                                mines AS (
                                    SELECT
                                        album_id,
                                        playnumber
                                    FROM
                                        scrobbled_album
                                    WHERE
                                        lastfm_id = ?
                        """ +
                (threshold > 1 ? " and playnumber > ? " : "") +
                """
                                ),
                                globals AS (
                                    SELECT
                                        a.album_id,
                                        max(a.playnumber) AS outf
                                    FROM
                                        scrobbled_album a
                                        join user u ON a.lastfm_id = u.lastfm_id
                                            JOIN mines b ON a.album_id = b.album_id
                                            
                                    WHERE ? OR NOT u.botted_account
                                    GROUP BY a.album_id
                                )
                            SELECT
                                playnumber ,
                                album_name,
                                name
                            FROM
                                mines
                                    JOIN globals ON globals.album_id = mines.album_id AND mines.playnumber = globals.outf
                                    JOIN album b ON globals.album_id = b.id
                                    JOIN artist c ON b.artist_id = c.id
                            ORDER BY
                                mines.playnumber DESC
                        """;

        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setString(i++, lastfmid);
            if (threshold > 1) {
                preparedStatement.setInt(i++, threshold);
            }
            preparedStatement.setBoolean(i, includeBottedUsers);


            ResultSet resultSet = preparedStatement.executeQuery();


            while (resultSet.next()) {

                String artist = resultSet.getString("name");
                String albumName = resultSet.getString("album_name");
                int plays = resultSet.getInt("playnumber");
                returnList.add(new AlbumPlays(artist, plays, albumName));

            }
        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException(e);
        }
        return new UniqueWrapper<>(returnList.size(), discordId, lastfmid, returnList);
    }

    @Override
    public UniqueWrapper<ArtistPlays> getGlobalTrackCrowns(Connection connection, String lastfmid, int threshold, boolean includeBottedUsers, long ownerId) {
        List<ArtistPlays> returnList = new ArrayList<>();
        long discordId = 0;

        String queryString =
                """
                        WITH
                                mines AS (
                                    SELECT
                                        track_id,
                                        playnumber
                                    FROM
                                        scrobbled_track
                                    WHERE
                                        lastfm_id = ?
                        """ +
                (threshold > 1 ? " and playnumber > ? " : "") +
                """
                                ),
                                globals AS (
                                    SELECT
                                        a.track_id,
                                        max(a.playnumber) AS outf
                                    FROM
                                        scrobbled_track a
                                        join user u ON a.lastfm_id = u.lastfm_id
                                            JOIN mines b ON a.track_id = b.track_id
                                    WHERE ? OR NOT u.botted_account
                                    GROUP BY a.track_id
                                )
                            SELECT
                                mines.playnumber as playnumber,
                                b.track_name as track_name,
                                name
                            FROM
                                mines
                                    JOIN globals ON globals.track_id = mines.track_id AND mines.playnumber = globals.outf
                                    JOIN track b ON globals.track_id = b.id
                                    JOIN artist c ON b.artist_id = c.id
                            ORDER BY
                                mines.playnumber DESC
                        """;

        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setString(i++, lastfmid);
            if (threshold > 1) {
                preparedStatement.setInt(i++, threshold);
            }
            preparedStatement.setBoolean(i, includeBottedUsers);
            //preparedStatement.setLong(4, ownerId);


            ResultSet resultSet = preparedStatement.executeQuery();


            while (resultSet.next()) {

                String artist = resultSet.getString("name");
                String albumName = resultSet.getString("track_name");
                int plays = resultSet.getInt("playnumber");
                returnList.add(new TrackPlays(artist, plays, albumName));

            }
        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException(e);
        }
        return new UniqueWrapper<>(returnList.size(), discordId, lastfmid, returnList);


    }

    @Override
    public UniqueWrapper<TrackPlays> getArtistGlobalTrackCrowns(Connection connection, String lastfmid, long artistId, int threshold, boolean includeBottedUsers) {
        List<TrackPlays> returnList = new ArrayList<>();
        long discordId = 0;

        String queryString =
                """
                        WITH
                                mines AS (
                                    SELECT
                                        track_id,
                                        playnumber
                                    FROM
                                        scrobbled_track
                                    WHERE
                                    artist_id = ? and  lastfm_id = ?
                        """ +
                (threshold > 1 ? " and playnumber > ? " : "") +
                """
                                ),
                                globals AS (
                                    SELECT
                                        a.track_id,
                                        max(a.playnumber) AS outf
                                    FROM
                                        scrobbled_track a
                                        join user u ON a.lastfm_id = u.lastfm_id
                                            JOIN mines b ON a.track_id = b.track_id
                                    WHERE ? OR NOT u.botted_account
                                    GROUP BY a.track_id
                                )
                            SELECT
                                playnumber,
                                b.track_name as track_name
                            FROM
                                mines
                                    JOIN globals ON globals.track_id = mines.track_id AND mines.playnumber = globals.outf
                                    JOIN track b ON globals.track_id = b.id
                            ORDER BY
                                mines.playnumber DESC
                        """;

        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setLong(i++, artistId);
            preparedStatement.setString(i++, lastfmid);
            if (threshold > 1) {
                preparedStatement.setInt(i++, threshold);
            }
            preparedStatement.setBoolean(i, includeBottedUsers);
            ResultSet resultSet = preparedStatement.executeQuery();


            while (resultSet.next()) {

                String albumName = resultSet.getString("track_name");
                int plays = resultSet.getInt("playnumber");
                returnList.add(new TrackPlays(null, plays, albumName));

            }
        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException(e);
        }
        return new UniqueWrapper<>(returnList.size(), discordId, lastfmid, returnList);

    }

    @Override
    public UniqueWrapper<TrackPlays> getUserArtistTrackCrowns(Connection connection, String lastfmId, int crownthreshold, long guildId, long artistId) {
        long discordId = -1L;


        String queryString =
                """
                                        WITH
                                            mines AS (
                                                SELECT
                                                    track_id,
                                                    playnumber
                                                FROM
                                                    scrobbled_track
                                                WHERE artist_id = ? AND lastfm_id = ?
                        """
                + (crownthreshold > 1 ? " and playnumber > ? " : "") +
                """                
                            ),
                            globals AS (
                                SELECT
                                    a.track_id,
                                    max(a.playnumber) AS outf
                                FROM
                                    scrobbled_track a
                                        JOIN mines b ON a.track_id = b.track_id
                                        join user u ON a.lastfm_id = u.lastfm_id
                                        join user_guild ug ON u.discord_id = ug.discord_id
                                WHERE guild_id = ?
                                GROUP BY a.track_id
                            )
                        SELECT
                            playnumber,
                            c.track_name
                        FROM
                            mines
                                JOIN globals ON globals.track_id = mines.track_id AND mines.playnumber = globals.outf
                                JOIN track c ON globals.track_id = c.id
                        ORDER BY
                            playnumber DESC;
                        """;


        List<TrackPlays> returnList = new ArrayList<>();
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setLong(i++, artistId);
            preparedStatement.setString(i++, lastfmId);
            if (crownthreshold > 1) {
                preparedStatement.setInt(i++, crownthreshold);
            }
            preparedStatement.setLong(i, guildId);

            ResultSet resultSet = preparedStatement.executeQuery();


            while (resultSet.next()) {
                String album = resultSet.getString("track_name");
                int plays = resultSet.getInt("playnumber");
                returnList.add(new TrackPlays(null, plays, album));
            }
        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException(e);
        }
        return new UniqueWrapper<>(returnList.size(), discordId, lastfmId, returnList);
    }

    @Override
    public Optional<String> findArtistUrlAbove(Connection connection, long artistId, int upvotes) {
        Set<Pair<String, String>> bannedTags = new HashSet<>();
        String queryString = "SELECT url FROM alt_url a WHERE artist_id = ? AND score > ? ORDER BY RAND()";
        try (
                PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            preparedStatement.setLong(1, artistId);
            preparedStatement.setInt(2, upvotes);
            ResultSet resultSet = preparedStatement.executeQuery();

            if (resultSet.next()) {
                return Optional.of(resultSet.getString(1));
            }
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
        return Optional.empty();
    }

    @Override
    public Optional<Instant> getLastScrobbled(Connection connection, String lastfmId, long artistId, String song, boolean skipToday) {
        String queryString = "SELECT  timestamp  FROM user_billboard_data_scrobbles  WHERE artist_id = ? AND lastfm_id = ? AND track_name = ? AND (NOT ? || timestamp  <= NOW() - INTERVAL  1 DAY) ORDER BY timestamp  DESC LIMIT 1";
        try (
                PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            preparedStatement.setLong(1, artistId);
            preparedStatement.setString(2, lastfmId);
            preparedStatement.setString(3, song);
            preparedStatement.setBoolean(4, skipToday);
            ResultSet resultSet = preparedStatement.executeQuery();

            if (resultSet.next()) {
                return Optional.ofNullable(resultSet.getTimestamp(1).toInstant());
            }
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
        return Optional.empty();


    }

    @Override
    public Optional<Instant> getFirstScrobbled(Connection connection, String lastfmId, long artistId, String song) {
        String queryString = "SELECT  timestamp  FROM user_billboard_data_scrobbles  WHERE artist_id = ? AND lastfm_id = ? AND track_name = ?  ORDER BY timestamp  ASC LIMIT 1";
        try (
                PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            preparedStatement.setLong(1, artistId);
            preparedStatement.setString(2, lastfmId);
            preparedStatement.setString(3, song);
            ResultSet resultSet = preparedStatement.executeQuery();

            if (resultSet.next()) {
                return Optional.ofNullable(resultSet.getTimestamp(1).toInstant());
            }
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
        return Optional.empty();


    }

    @Override
    public Optional<Instant> getLastScrobbledArtist(Connection connection, String lastfmId, long artistId, boolean skipToday) {
        String queryString = "SELECT  timestamp  FROM user_billboard_data_scrobbles  WHERE artist_id = ? AND lastfm_id = ? AND (NOT ? ||  timestamp  <= (NOW() - INTERVAL  1 DAY))  ORDER BY timestamp  DESC LIMIT 1";
        try (
                PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            preparedStatement.setLong(1, artistId);
            preparedStatement.setString(2, lastfmId);
            preparedStatement.setBoolean(3, skipToday);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                return Optional.ofNullable(resultSet.getTimestamp(1).toInstant());
            }
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
        return Optional.empty();
    }

    @Override
    public int getCurrentCombo(Connection connection, String lastfm_id, long artistId) {

        String queryString = """
                SELECT COUNT(*)
                 FROM user_billboard_data_scrobbles a WHERE `timestamp` > (SELECT  MAX(`timestamp`) FROM user_billboard_data_scrobbles WHERE artist_id != ?  && lastfm_id = ?  )  AND lastfm_id = ?
                 """;


        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setLong(i++, artistId);
            preparedStatement.setString(i++, lastfm_id);
            preparedStatement.setString(i, lastfm_id);

            /* Execute query. */
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                return resultSet.getInt(1);
            }
            return 0;

        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }


    }

    @Override
    public Optional<Instant> getFirstScrobbledArtist(Connection connection, String lastfmId, long artistId) {
        String queryString = "SELECT  timestamp  FROM user_billboard_data_scrobbles  WHERE artist_id = ? AND lastfm_id = ?   ORDER BY timestamp  ASC LIMIT 1";
        try (
                PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            preparedStatement.setLong(1, artistId);
            preparedStatement.setString(2, lastfmId);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                return Optional.ofNullable(resultSet.getTimestamp(1).toInstant());
            }
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
        return Optional.empty();
    }

    @Override
    public List<UserListened> getServerFirstScrobbledArtist(Connection connection, long artistId, long guildId, Order order) {
        ArrayList<UserListened> listeneds = new ArrayList<>();
        String ordering = order == Order.ASC ? "min(timestamp)" : "max(timestamp)";
        String queryString = "Select  \n" +
                             "t.timestamp,a.discord_id,a.lastfm_id,a.timezone  \n" +
                             "from  user a join user_guild b on a.discord_id = b.discord_id \n" +
                             " join (select " + ordering + " as timestamp,lastfm_id from user_billboard_data_scrobbles where artist_id = ? group by lastfm_id ) t on a.lastfm_id = t.lastfm_id\n" +
                             "where guild_id = ? \n" +
                             "order by -timestamp " + order.getInverse().name();
        try (
                PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            preparedStatement.setLong(1, artistId);
            preparedStatement.setLong(2, guildId);
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {

                long discordId = resultSet.getLong(2);
                String lastfmId = resultSet.getString(3);
                TimeZone timezone = TimeZone.getTimeZone(Objects.requireNonNullElse(resultSet.getString(4), "GMT"));
                listeneds.add(new UserListened(discordId, lastfmId, timezone, Optional.ofNullable(resultSet.getTimestamp(1)).map(Timestamp::toInstant)));
            }
        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }
        return listeneds;
    }

    @Override
    public List<ScrobbledArtist> regexArtist(Connection connection, String regex, long userId) {
        List<ScrobbledArtist> scrobbledAlbums = new ArrayList<>();
        String s = "SELECT name,b.url,b.mbid,a.playnumber  FROM scrobbled_artist a JOIN artist b ON a.artist_id = b.id JOIN user c ON a.lastfm_id = c.lastfm_id    " +
                   "WHERE c.discord_id  = ? AND b.name REGEXP ? ORDER BY a.playnumber DESC";
        try (PreparedStatement preparedStatement = connection.prepareStatement(s)) {
            preparedStatement.setLong(1, userId);
            preparedStatement.setString(2, regex);
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                String artist = resultSet.getString(1);
                String url = resultSet.getString(2);
                String mbid = resultSet.getString(3);
                int playnumber = resultSet.getInt(4);
                ScrobbledArtist scrobbledArtist = new ScrobbledArtist(artist, playnumber, url);
                scrobbledArtist.setArtistMbid(mbid);
                scrobbledArtist.setCount(playnumber);
                scrobbledAlbums.add(scrobbledArtist);
            }
        } catch (
                SQLException throwables) {

            throw new ChuuServiceException(throwables);
        }
        return scrobbledAlbums;

    }

    @Override
    public List<ScrobbledAlbum> regexAlbum(Connection connection, String regex, long userId) {
        List<ScrobbledAlbum> scrobbledAlbums = new ArrayList<>();
        String s = "SELECT b.album_name,c.name,b.url,b.mbid,a.playnumber  " +
                   "FROM scrobbled_album a JOIN album b ON a.album_id = b.id JOIN artist c ON a.artist_id = c.id  JOIN user d ON a.lastfm_id = d.lastfm_id  " +
                   "WHERE d.discord_id = ? AND b.album_name REGEXP  ? ORDER BY a.playnumber DESC";
        try (PreparedStatement preparedStatement = connection.prepareStatement(s)) {
            preparedStatement.setLong(1, userId);
            preparedStatement.setString(2, regex);
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                String album = resultSet.getString(1);
                String artist = resultSet.getString(2);
                String url = resultSet.getString(3);
                String mbid = resultSet.getString(4);
                int playnumber = resultSet.getInt(5);

                ScrobbledAlbum scrobbledAlbum = new ScrobbledAlbum(album, artist, url, mbid);
                scrobbledAlbum.setCount(playnumber);
                scrobbledAlbums.add(scrobbledAlbum);
            }
        } catch (
                SQLException throwables) {

            throw new ChuuServiceException(throwables);
        }
        return scrobbledAlbums;
    }

    @Override
    public List<ScrobbledTrack> regexTrack(Connection connection, String regex, long userId) {
        List<ScrobbledTrack> scrobbledTracks = new ArrayList<>();

        String mySql = """
                SELECT b.id,
                       d.id,
                       c.id,
                       c.name,
                       d.album_name,
                       b.duration,
                       b.track_name,
                       COALESCE(b.url, d.url, c.url),
                       a.playnumber,
                       a.loved
                FROM scrobbled_track a
                         JOIN track b ON a.track_id = b.id
                         JOIN artist c ON b.artist_id = c.id
                         LEFT JOIN album d ON b.album_id = d.id
                         JOIN user e ON a.lastfm_id = e.lastfm_id
                WHERE e.discord_id = ?
                  AND b.track_name REGEXP  ?
                ORDER BY playnumber DESC""";
        try
                (PreparedStatement preparedStatement = connection.prepareStatement(mySql)) {
            preparedStatement.setLong(1, userId);
            preparedStatement.setString(2, regex);
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                long trackId = resultSet.getLong(1);
                long albumId = resultSet.getLong(2);
                long artistId = resultSet.getLong(3);
                String artsitName = resultSet.getString(4);
                String albumName = resultSet.getString(5);
                int duration = resultSet.getInt(6);
                String trackName = resultSet.getString(7);
                String url = resultSet.getString(8);
                int plays = resultSet.getInt(9);
                boolean loved = resultSet.getBoolean(10);

                ScrobbledTrack e = new ScrobbledTrack(artsitName, trackName, plays, loved, duration, url, null, null);
                e.setArtistId(artistId);
                e.setAlbumId(albumId);
                e.setTrackId(trackId);
                scrobbledTracks.add(e);
            }
        } catch (
                SQLException e) {
            throw new ChuuServiceException(e);
        }
        return scrobbledTracks;
    }

    @Override
    public ResultWrapper<ScrobbledAlbum> getCollectiveAOTD(Connection connection, Year year, int range, Long guildID, int limit, boolean doCount) {
        String normalQUery = "SELECT d.name,e.album_name, sum(playnumber) AS orden ,e.url,e.mbid  ";

        String countQuery = "Select count(*) as orden ";


        String queryBody = "FROM  scrobbled_album a " +
                           " JOIN user b" +
                           " ON a.lastfm_id = b.lastfm_id" +
                           " JOIN artist d " +
                           " ON a.artist_id = d.id" +
                           " join album e on a.album_id = e.id";
        if (guildID != null) {
            queryBody += " JOIN  user_guild c" +
                         " ON b.discord_id=c.discord_id" +
                         " WHERE c.guild_id = ?";
        } else {
            queryBody += " where 1 = 1 ";
        }

        queryBody += " and (e.release_year between ? and ?)";


        List<ScrobbledAlbum> list = new ArrayList<>();
        int count = 0;
        int i = 1;
        try (PreparedStatement preparedStatement1 = connection.prepareStatement(normalQUery + queryBody + " GROUP BY album_id  ORDER BY orden DESC  LIMIT ?")) {
            if (guildID != null)
                preparedStatement1.setLong(i++, guildID);

            preparedStatement1.setInt(i++, year.getValue());
            preparedStatement1.setInt(i++, year.getValue() + range);
            preparedStatement1.setInt(i, limit);

            ResultSet resultSet1 = preparedStatement1.executeQuery();

            while (resultSet1.next()) {
                String artist = resultSet1.getString("d.name");
                String album = resultSet1.getString("e.album_name");
                String mbid = resultSet1.getString("e.mbid");


                String url = resultSet1.getString("e.url");

                int plays = resultSet1.getInt("orden");
                ScrobbledAlbum who = new ScrobbledAlbum(album, artist, url, mbid);
                who.setCount(plays);
                list.add(who);
            }
            if (doCount) {

                PreparedStatement preparedStatement = connection.prepareStatement(countQuery + queryBody);
                i = 1;
                if (guildID != null)
                    preparedStatement.setLong(i++, guildID);

                preparedStatement.setInt(i++, year.getValue());
                preparedStatement.setInt(i, year.getValue() + range);

                ResultSet resultSet = preparedStatement.executeQuery();
                if (resultSet.next()) {
                    count = resultSet.getInt(1);
                }


            }
        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException(e);
        }
        return new ResultWrapper<>(count, list);

    }

    @Override
    public Map<Year, Integer> getUserYears(Connection connection, String lastfmId, boolean isDecade) {
        Map<Year, Integer> years = new HashMap<>();
        String field = isDecade ? "floor(release_year/10)*10" : "release_year";
        String mySql = "Select count(*),%s from scrobbled_album a join album b on a.album_id = b.id where a.lastfm_id = ?  and release_year is not null group by %s order by release_year asc ";

        try
                (PreparedStatement preparedStatement = connection.prepareStatement(mySql.formatted(field, field))) {
            preparedStatement.setString(1, lastfmId);
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                int count = resultSet.getInt(1);
                int year = resultSet.getInt(2);
                years.put(Year.of(year), count);
            }
        } catch (
                SQLException e) {
            throw new ChuuServiceException(e);
        }
        return years;
    }

    @Override
    public Map<Year, Integer> getCollectiveYears(Connection connection, @Nullable Long guildId, boolean isDecade) {
        Map<Year, Integer> years = new HashMap<>();
        String field = isDecade ? "floor(release_year/10)*10" : "release_year";
        @Language("MariaDB") String mySql = """
                Select count(*),%s
                from scrobbled_album a join album b on a.album_id = b.id
                """;
        if (guildId != null) {
            mySql += """  
                    join user c on a.lastfm_id = c.lastfm_id
                    join user_guild d on c.discord_id = d.discord_id
                    where guild_id = ?
                    """;
        } else {
            mySql += " where 1 =1 ";
        }

        mySql += "and release_year is not null group by %s  order by release_year asc";


        try
                (PreparedStatement preparedStatement = connection.prepareStatement(mySql.formatted(field, field))) {
            if (guildId != null) {
                preparedStatement.setLong(1, guildId);
            }
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                int count = resultSet.getInt(1);
                int year = resultSet.getInt(2);
                years.put(Year.of(year), count);
            }
        } catch (
                SQLException e) {
            throw new ChuuServiceException(e);
        }
        return years;

    }

    @Override
    public int userAlbumCount(Connection connection, String lastfmId, int threshold) {
        String queryString = "SELECT COUNT(*) AS numb FROM scrobbled_album WHERE scrobbled_album.lastfm_id= ? AND playnumber >= ?";
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setString(i++, lastfmId);
            preparedStatement.setInt(i, threshold);


            /* Execute query. */
            ResultSet resultSet = preparedStatement.executeQuery();
            if (!resultSet.next())
                return 0;
            return resultSet.getInt("numb");


        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }

    }

    @Override
    public int userTrackCount(Connection connection, String lastfmId, int threshold) {
        String queryString = "SELECT COUNT(*) AS numb FROM scrobbled_track WHERE scrobbled_track.lastfm_id= ? AND playnumber >= ?";
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setString(i++, lastfmId);
            preparedStatement.setInt(i, threshold);


            /* Execute query. */
            ResultSet resultSet = preparedStatement.executeQuery();
            if (!resultSet.next())
                return 0;
            return resultSet.getInt("numb");


        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }

    }

    @Override
    public List<LbEntry<Integer>> albumLeaderboard(Connection connection, long guildId, int threshold) {
        @Language("MariaDB") String queryString = """
                SELECT a.lastfm_id, count(*) AS ord, c.discord_id
                FROM scrobbled_album a
                         JOIN user b ON a.lastfm_id = b.lastfm_id
                         JOIN user_guild c ON b.discord_id = c.discord_id
                WHERE c.guild_id = ?
                  and c.guild_id = ?
                """;
        if (threshold > 1) {
            queryString += " and a.playnumber >= ? ";
        }
        queryString += """
                GROUP BY a.lastfm_id, c.discord_id
                ORDER BY ord DESC""";

        return getLbEntries(connection, guildId, queryString, AlbumLbEntry::new, true, threshold, Integer.class);
    }

    @Override
    public List<LbEntry<Integer>> trackLeaderboard(Connection connection, long guildId, int threshold) {
        String queryString = """
                SELECT a.lastfm_id, count(*) AS ord, c.discord_id
                 FROM scrobbled_track a
                          JOIN user b ON a.lastfm_id = b.lastfm_id
                          JOIN user_guild c ON b.discord_id = c.discord_id
                 WHERE c.guild_id = ?
                   and c.guild_id = ?\s""";
        if (threshold > 1) {
            queryString += " and a.playnumber >= ? ";
        }
        queryString += "GROUP BY a.lastfm_id, c.discord_id     ORDER BY ord DESC";

        return getLbEntries(connection, guildId, queryString, TrackLbEntry::new, true, threshold, Integer.class);
    }

    @Override
    public ListValuedMap<CoverItem, String> getBannedCovers(Connection connection) {
        ListValuedMap<CoverItem, String> resultMap = new ArrayListValuedHashMap<>();
        String queryString = "SELECT album_id,replacement_cover,b.album_name,c.name  FROM banned_cover a JOIN album b ON a.album_id = b.id JOIN artist c ON b.artist_id = c.id ";
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            /* Execute query. */
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                long aLong = resultSet.getLong(1);
                String string = resultSet.getString(2);
                String album = resultSet.getString(3);
                String artist = resultSet.getString(4);
                resultMap.put(new CoverItem(album, artist, aLong), string);

            }
            return resultMap;


        } catch (SQLException e) {
            throw new ChuuServiceException(e);
        }

    }

    @Override
    public UniqueWrapper<AlbumPlays> getGlobalAlbumUniques(Connection connection, String lastfmid) {
        String queryString = """
                SELECT
                    name,
                    album_name,
                    playnumber
                FROM
                    (
                        SELECT
                            b.playnumber,
                            b.album_id
                        FROM
                            (
                                SELECT
                                    album_id
                                FROM
                                    scrobbled_album a
                                WHERE
                                      a.playnumber > 2
                                  AND lastfm_id = ?
                            ) temp
                                JOIN
                                scrobbled_album b ON temp.album_id = b.album_id AND b.playnumber > 2
                        GROUP BY
                            b.album_id
                        HAVING
                            count(*) = 1
                    ) main
                        JOIN album b ON main.album_id = b.id
                        JOIN artist c ON b.artist_id = c.id
                ORDER BY
                    playnumber DESC;
                """;

        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setString(i, lastfmid);
            ResultSet resultSet = preparedStatement.executeQuery();


            List<AlbumPlays> returnList = new ArrayList<>();
            long discordId = 0;
            while (resultSet.next()) {
                String name = resultSet.getString("name");
                String albumname = resultSet.getString("album_name");
                int countA = resultSet.getInt("playNumber");

                returnList.add(new AlbumPlays(name, countA, albumname));

            }
            return new UniqueWrapper<>(returnList.size(), discordId, lastfmid, returnList);


        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException(e);
        }
    }

    @Override
    public UniqueWrapper<TrackPlays> getGlobalTrackUniques(Connection connection, String lastfmid) {
        String queryString = """
                SELECT
                    name,
                    track_name,
                    playnumber
                FROM
                    (
                        SELECT
                            b.playnumber,
                            b.track_id
                        FROM
                            (
                                SELECT
                                    track_id
                                FROM
                                    scrobbled_track a
                                WHERE
                                      a.playnumber > 2
                                  AND lastfm_id = ?
                            ) temp
                                JOIN scrobbled_track b ON temp.track_id = b.track_id AND b.playnumber > 2
                        GROUP BY
                            b.track_id
                        HAVING
                            count(*) = 1
                    ) main
                        JOIN track b ON main.track_id = b.id
                        JOIN artist c ON b.artist_id = c.id
                ORDER BY
                    playnumber DESC;
                """;

        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString)) {
            int i = 1;
            preparedStatement.setString(i, lastfmid);
            ResultSet resultSet = preparedStatement.executeQuery();


            List<TrackPlays> returnList = new ArrayList<>();
            long discordId = 0;
            while (resultSet.next()) {
                String name = resultSet.getString("name");
                String trackName = resultSet.getString("track_name");
                int countA = resultSet.getInt("playNumber");

                returnList.add(new TrackPlays(name, countA, trackName));

            }
            return new UniqueWrapper<>(returnList.size(), discordId, lastfmid, returnList);


        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            throw new ChuuServiceException(e);
        }

    }

    private void getScoredAlbums(List<ScoredAlbumRatings> returnList, ResultSet resultSet) throws SQLException {

        while (resultSet.next()) {
            long count = resultSet.getLong(1);
            double average = resultSet.getDouble(2);
            String url = resultSet.getString(3);
            returnList.add(new ScoredAlbumRatings(0, "", url, count, average, ""));

        }
    }


}





