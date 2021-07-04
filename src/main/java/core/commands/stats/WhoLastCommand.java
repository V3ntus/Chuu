package core.commands.stats;

import core.commands.Context;
import core.commands.abstracts.ConcurrentCommand;
import core.commands.utils.ChuuEmbedBuilder;
import core.commands.utils.CommandCategory;
import core.commands.utils.CommandUtil;
import core.commands.utils.PrivacyUtils;
import core.exceptions.LastFmException;
import core.otherlisteners.Reactionary;
import core.parsers.ArtistParser;
import core.parsers.Parser;
import core.parsers.params.ArtistParameters;
import core.services.validators.ArtistValidator;
import dao.ServiceView;
import dao.entities.Memoized;
import dao.entities.ScrobbledArtist;
import dao.entities.UserListened;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.utils.TimeFormat;

import javax.validation.constraints.NotNull;
import java.util.List;
import java.util.function.Function;

public class WhoLastCommand extends ConcurrentCommand<ArtistParameters> {

    public WhoLastCommand(ServiceView dao) {
        super(dao, true);
        respondInPrivate = false;
    }

    public static void handleUserListened(Context e, ArtistParameters params, List<UserListened> firsts, boolean isFirst) {
        Function<UserListened, String> toMemoize = (userListened) -> {
            String whem;
            if (userListened.moment().isEmpty()) {
                whem = "**Never**";
            } else {
                whem = "**" + CommandUtil.getDateTimestampt(userListened.moment().get(), TimeFormat.RELATIVE) + "**";
            }
            return ". [" + CommandUtil.getUserInfoConsideringGuildOrNot(e, userListened.discordId()).username() + "](" + PrivacyUtils.getLastFmUser(userListened.lastfmId()) + "): " + whem + "\n";
        };

        List<Memoized<UserListened, String>> strings = firsts.stream().map(t -> new Memoized<>(t, toMemoize)).toList();

        EmbedBuilder embedBuilder = new ChuuEmbedBuilder(e);
        StringBuilder builder = new StringBuilder();


        int counter = 1;

        for (var b : strings) {
            builder.append(counter++)
                    .append(b.toString());
            if (counter == 11)
                break;
        }
        String usable = CommandUtil.escapeMarkdown(e.getGuild().getName());
        embedBuilder.setTitle("Who listened " + (isFirst ? "first" : "last") + " to " + params.getScrobbledArtist().getArtist() + " in " + usable).
                setThumbnail(CommandUtil.noImageUrl(params.getScrobbledArtist().getUrl())).setDescription(builder)
                .setFooter(strings.size() + CommandUtil.singlePlural(strings.size(), " listener", " listeners"));
        e.sendMessage(embedBuilder.build())
                .queue(message1 ->
                        new Reactionary<>(strings, message1, embedBuilder));
    }

    @Override
    protected CommandCategory initCategory() {
        return CommandCategory.SERVER_LEADERBOARDS;
    }

    @Override
    public Parser<ArtistParameters> initParser() {
        return new ArtistParser(db, lastFM);
    }

    @Override
    public String getDescription() {
        return "Who listened last to an artist on a server";
    }

    @Override
    public List<String> getAliases() {
        return List.of("wholast", "wl", "wlast", "whol");
    }

    @Override
    public String getName() {
        return "Who listened last";
    }

    @Override
    protected void onCommand(Context e, @NotNull ArtistParameters params) throws LastFmException {

        ScrobbledArtist sA = new ArtistValidator(db, lastFM, e).validate(params.getArtist(), !params.isNoredirect());
        params.setScrobbledArtist(sA);

        List<UserListened> lasts = db.getServerLastScrobbledArtist(sA.getArtistId(), e.getGuild().getIdLong());
        if (lasts.isEmpty()) {
            sendMessageQueue(e, "Couldn't get the last time this server scrobbled **" + sA.getArtist() + "**");
            return;
        }

        handleUserListened(e, params, lasts, false);

    }

}
