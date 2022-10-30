package core.commands.charts;

import core.Chuu;
import core.apis.last.entities.chartentities.AlbumChart;
import core.apis.last.entities.chartentities.TrackDurationAlbumArtistChart;
import core.apis.last.entities.chartentities.UrlCapsule;
import core.commands.Context;
import core.commands.abstracts.ConcurrentCommand;
import core.commands.utils.ChuuEmbedBuilder;
import core.commands.utils.CommandCategory;
import core.commands.utils.CommandUtil;
import core.commands.utils.PieDoer;
import core.exceptions.LastFmException;
import core.imagerenderer.ChartQuality;
import core.imagerenderer.CollageMaker;
import core.imagerenderer.GraphicUtils;
import core.imagerenderer.util.pie.IPieableList;
import core.imagerenderer.util.pie.PieableListChart;
import core.otherlisteners.util.PaginatorBuilder;
import core.parsers.ChartableParser;
import core.parsers.DaoParser;
import core.parsers.params.ChartParameters;
import core.util.ServiceView;
import dao.entities.ChartMode;
import dao.entities.CountWrapper;
import dao.entities.DiscordUserDisplay;
import dao.entities.ScrobbledAlbum;
import net.dv8tion.jda.api.EmbedBuilder;
import org.knowm.xchart.PieChart;

import javax.annotation.Nonnull;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;

public abstract class ChartableCommand<T extends ChartParameters> extends ConcurrentCommand<T> {
    public final IPieableList<UrlCapsule, ChartParameters> pie;


    public ChartableCommand(ServiceView dao, boolean isLongRunningCommand) {
        super(dao, isLongRunningCommand);
        this.pie = getPie();
        ((DaoParser<?>) getParser()).setExpensiveSearch(true);
        order = 3;
    }

    public ChartableCommand(ServiceView dao) {
        this(dao, false);
    }


    @Override
    protected CommandCategory initCategory() {
        return CommandCategory.CHARTS;
    }

    public abstract ChartableParser<T> initParser();

    public abstract String getSlashName();

    @Override
    public final String slashName() {
        return getSlashName();
    }


    protected ChartMode getEffectiveMode(ChartParameters chartParameters) {
        if (chartParameters.isList()) {
            return ChartMode.LIST;
        } else if (chartParameters.isPie()) {
            return ChartMode.PIE;
        }
        return ChartMode.IMAGE;
    }

    @Override
    public void onCommand(Context e, @Nonnull T params) throws LastFmException {


        CountWrapper<BlockingQueue<UrlCapsule>> countWrapper = processQueue(params);
        BlockingQueue<UrlCapsule> urlCapsules = countWrapper.getResult();
        if (urlCapsules.isEmpty()) {
            this.noElementsMessage(params);
            return;
        }
        ChartMode chartMode = getEffectiveMode(params);
        switch (chartMode) {
            case IMAGE_INFO:
            case IMAGE:
            case IMAGE_ASIDE:
            case IMAGE_ASIDE_INFO:
                doImage(urlCapsules, params.getX(), params.getY(), params, countWrapper.getRows());
                return;
            default:
            case LIST:
                List<UrlCapsule> printList = new ArrayList<>(urlCapsules.size());
                urlCapsules.drainTo(printList);
                doList(printList, params, countWrapper.getRows());
                break;
            case PIE:
                printList = new ArrayList<>(urlCapsules.size());
                urlCapsules.drainTo(printList);
                PieChart pieChart = pie.doPie(params, printList);
                doPie(pieChart, params, countWrapper.getRows());
                break;
        }
    }


    public abstract CountWrapper<BlockingQueue<UrlCapsule>> processQueue(T params) throws
            LastFmException;

    void generateImage(BlockingQueue<UrlCapsule> queue, int x, int y, Context e, T params, int size) {
        int chartSize = queue.size();

        int minx = (int) Math.ceil((double) chartSize / x);
        if (minx == 1)
            x = chartSize;
        ChartQuality chartQuality = GraphicUtils.getQuality(chartSize, e);

        BufferedImage image = CollageMaker
                .generateCollageThreaded(x, minx, queue, chartQuality, params.isAside() || params.chartMode().equals(ChartMode.IMAGE_ASIDE) || params.chartMode().equals(ChartMode.IMAGE_ASIDE_INFO));

        boolean info = params.chartMode().equals(ChartMode.IMAGE_INFO) || params.chartMode().equals(ChartMode.IMAGE_ASIDE_INFO);
        sendImage(image, e, chartQuality, info ? configEmbed(new ChuuEmbedBuilder(e), params, size) : null);
    }


    public void doImage(BlockingQueue<UrlCapsule> queue, int x, int y, T parameters, int size) {
        Context e = parameters.getE();
        if (queue.size() < x * y) {
            x = Math.max((int) Math.ceil(Math.sqrt(queue.size())), 1);
            //noinspection SuspiciousNameCombination
            y = x;
        }

        UrlCapsule first = queue.peek();
        // Store some album covers
        if (first instanceof AlbumChart || first instanceof TrackDurationAlbumArtistChart) {
            queue.forEach(t -> t.setUrl(Chuu.getCoverService().getCover(t.getArtistName(), t.getAlbumName(), t.getUrl(), e)));
            if (CommandUtil.rand.nextFloat() >= 0.7f && first instanceof AlbumChart) {
                List<UrlCapsule> items = new ArrayList<>(queue);
                CommandUtil.runLog(() -> items.stream()
                        .filter(t -> t.getUrl() != null && !t.getUrl().isBlank())
                        .map(t -> (AlbumChart) t)
                        .forEach(z -> {
                            try {
                                ScrobbledAlbum scrobbledAlbum = CommandUtil.validateAlbum(db, z.getArtistName(), z.getAlbumName(), lastFM, null, null, false, false);
                                ;
                                db.updateAlbumImage(scrobbledAlbum.getAlbumId(), z.getUrl());
                            } catch (LastFmException ignored) {
                            }
                        }));
            }
        }

        generateImage(queue, x, y, e, parameters, size);

    }


    public void doList(List<UrlCapsule> urlCapsules, T params, int count) {

        DiscordUserDisplay userInfoConsideringGuildOrNot = CommandUtil.getUserInfoEscaped(params.getE(), params.getDiscordId());

        EmbedBuilder embedBuilder = configEmbed(new ChuuEmbedBuilder(params.getE())
                .setThumbnail(userInfoConsideringGuildOrNot.urlImage()), params, count);

        new PaginatorBuilder<>(params.getE(), embedBuilder, urlCapsules).build().queue();
    }

    public void doPie(PieChart pieChart, T chartParameters, int count) {
        DiscordUserDisplay userInfoNotStripped = CommandUtil.getUserInfoUnescaped(chartParameters.getE(), chartParameters.getDiscordId());
        String subtitle = configPieChart(pieChart, chartParameters, count, userInfoNotStripped.username());
        String urlImage = userInfoNotStripped.urlImage();


        sendImage(new PieDoer(subtitle, urlImage, pieChart).fill(), chartParameters.getE());
    }


    public abstract EmbedBuilder configEmbed(EmbedBuilder embedBuilder, T params, int count);

    public abstract String configPieChart(PieChart pieChart, T params, int count, String initTitle);

    public abstract void noElementsMessage(T parameters);

    public IPieableList<UrlCapsule, ChartParameters> getPie() {
        return new PieableListChart(this.parser);
    }


}
