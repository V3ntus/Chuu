package core.commands.leaderboards;

import core.commands.Context;
import core.commands.abstracts.LeaderboardCommand;
import core.commands.moderation.ImportCommand;
import core.commands.utils.ChuuEmbedBuilder;
import core.commands.utils.CommandCategory;
import core.commands.utils.CommandUtil;
import core.parsers.OnlyUsernameParser;
import core.parsers.Parser;
import core.parsers.params.ChuuDataParams;
import dao.ServiceView;
import dao.entities.LbEntry;
import dao.entities.ObscurityEntry;
import dao.entities.ObscurityStats;
import dao.entities.UsersWrapper;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class ObscurityLeaderboardCommand extends LeaderboardCommand<ChuuDataParams, Double> {


    public ObscurityLeaderboardCommand(ServiceView dao) {
        super(dao, true);
    }


    @Override
    protected CommandCategory initCategory() {
        return CommandCategory.SERVER_LEADERBOARDS;
    }

    @Override
    public Parser<ChuuDataParams> initParser() {
        return new OnlyUsernameParser(db);
    }


    @Override
    public String getDescription() {
        return "Gets how \\*obscure\\* your scrobbled artist are in relation with all the rest of the users of the server";
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("obscuritylb", "ob", "obs");
    }


    @Override
    public String getName() {
        return "Obscurity";
    }

    @Override
    protected void setFooter(EmbedBuilder embedBuilder, List<LbEntry<Double>> list, ChuuDataParams params) {
        Optional<ObscurityStats> serverObscurityStats = db.getServerObscurityStats(params.getE().getGuild().getIdLong());
        if (serverObscurityStats.isPresent()) {
            ObscurityStats obscurityStats = serverObscurityStats.get();
            Context e = params.getE();
            embedBuilder.setFooter("%s is ranked %d%s out of %d servers with an average of a %s%%"
                    .formatted(e.getGuild().getName(), obscurityStats.rank(), CommandUtil.getRank(obscurityStats.rank()),
                            obscurityStats.total(),
                            ObscurityEntry.average.format(obscurityStats.averageScore())
                    ), null);
        } else {
            super.setFooter(embedBuilder, list, params);
        }
    }

    @Override
    public List<LbEntry<Double>> getList(ChuuDataParams params) {
        Context e = params.getE();
        long guildId = params.getE().getGuild().getIdLong();
        List<UsersWrapper> unprocessed = db.getAllObscurifyPending(guildId);
        int size = unprocessed.size();
        CompletableFuture<Message> submit = null;
        boolean didUser = false;
        if (!unprocessed.isEmpty()) {
            if (size > 15) {
                submit = e.sendMessage(
                        getEmbedBuilder(e, 0, size).build()
                ).submit();
            }
            var now = Instant.now();
            for (int i = 0; i < unprocessed.size(); i++) {
                String lastFMName = unprocessed.get(i).getLastFMName();
                if (lastFMName.equals(params.getLastFMData().getName())) {
                    didUser = true;
                }
                db.obtainObscurity(lastFMName);
                if (size > 15) {
                    if (Instant.now().isAfter(now.plusMillis(500))) {
                        int finalI = i;

                        submit.thenCompose(z -> z.editMessage(getEmbedBuilder(e, finalI, size).build()).submit());
                        now = now.plusMillis(500);
                    }
                }
            }

        }
        if (!didUser)
            db.obtainObscurity(params.getLastFMData().getName());
        if (size > 15)
            return submit.thenCompose(z -> z.delete().submit()).thenApply(z -> db.getObscurityRankings(e.getGuild().getIdLong())).join();
        return db.getObscurityRankings(e.getGuild().getIdLong());
    }

    @Override
    public String getEntryName(ChuuDataParams params) {
        return "obscurity";
    }

    @org.jetbrains.annotations.NotNull
    private EmbedBuilder getEmbedBuilder(Context e, int current, int size) {
        return new ChuuEmbedBuilder(e)
                .setTitle("Need to process %s users".formatted(size))
                .setThumbnail(ImportCommand.spinner)
                .setDescription("%d/%d done".formatted(current, size));
    }
}
