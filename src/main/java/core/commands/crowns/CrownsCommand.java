package core.commands.crowns;

import core.commands.Context;
import core.commands.abstracts.ConcurrentCommand;
import core.commands.utils.ChuuEmbedBuilder;
import core.commands.utils.CommandCategory;
import core.commands.utils.CommandUtil;
import core.otherlisteners.Reactionary;
import core.parsers.OnlyUsernameParser;
import core.parsers.Parser;
import core.parsers.params.ChuuDataParams;
import core.parsers.params.NumberParameters;
import dao.ServiceView;
import dao.entities.ArtistPlays;
import dao.entities.DiscordUserDisplay;
import dao.entities.UniqueWrapper;
import net.dv8tion.jda.api.EmbedBuilder;

import javax.validation.constraints.NotNull;
import java.util.Collections;
import java.util.List;

import static core.parsers.NumberParser.generateThresholdParser;

public class CrownsCommand extends ConcurrentCommand<NumberParameters<ChuuDataParams>> {

    public CrownsCommand(ServiceView dao) {
        super(dao, true);
        this.respondInPrivate = false;
    }

    @Override
    protected CommandCategory initCategory() {
        return CommandCategory.CROWNS;
    }

    @Override
    public Parser<NumberParameters<ChuuDataParams>> initParser() {
        return generateThresholdParser(new OnlyUsernameParser(db));

    }

    @Override
    public String slashName() {
        return "artists";
    }

    public String getTitle() {
        return "";
    }


    public UniqueWrapper<ArtistPlays> getList(NumberParameters<ChuuDataParams> params) {
        Long threshold = params.getExtraParam();
        long idLong = params.getE().getGuild().getIdLong();

        if (threshold == null) {
            threshold = (long) db.getGuildCrownThreshold(idLong);
        }
        return db.getCrowns(params.getInnerParams().getLastFMData().getName(), idLong, Math.toIntExact(threshold));
    }

    @Override
    public String getDescription() {
        return ("List of artist you are the top listener within a server");
    }

    @Override
    public List<String> getAliases() {
        return Collections.singletonList("crowns");
    }

    @Override
    protected void onCommand(Context e, @NotNull NumberParameters<ChuuDataParams> params) {


        ChuuDataParams innerParams = params.getInnerParams();
        UniqueWrapper<ArtistPlays> uniqueDataUniqueWrapper = getList(params);
        DiscordUserDisplay userInformation = CommandUtil.getUserInfoConsideringGuildOrNot(e, innerParams.getLastFMData().getDiscordId());
        String userName = userInformation.username();
        String userUrl = userInformation.urlImage();
        List<ArtistPlays> resultWrapper = uniqueDataUniqueWrapper.getUniqueData();

        int rows = resultWrapper.size();
        if (rows == 0) {
            sendMessageQueue(e, userName + " doesn't have any " + getTitle() + "crown :'(");
            return;
        }

        StringBuilder a = new StringBuilder();
        for (int i = 0; i < 10 && i < rows; i++) {
            ArtistPlays g = resultWrapper.get(i);
            a.append(i + 1).append(g.toString());
        }


        long discordId = params.getInnerParams().getLastFMData().getDiscordId();
        EmbedBuilder embedBuilder = new ChuuEmbedBuilder(e)
                .setDescription(a)
                .setTitle(String.format("%s's %scrowns", userName, getTitle()), CommandUtil.getLastFmUser(uniqueDataUniqueWrapper.getLastFmId())).setFooter(String.format("%s has %d %scrowns!!%n", CommandUtil.unescapedUser(userName, discordId, e), resultWrapper.size(), getTitle()), null)
                .setThumbnail(userUrl);
        e.sendMessage(embedBuilder.build()).queue(message1 ->

                new Reactionary<>(resultWrapper, message1, embedBuilder));
    }

    @Override
    public String getName() {
        return "Crowns";
    }


}



