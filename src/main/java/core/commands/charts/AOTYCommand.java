package core.commands.charts;

import core.apis.last.entities.chartentities.*;
import core.commands.Context;
import core.commands.utils.CommandUtil;
import core.exceptions.LastFmException;
import core.parsers.ChartSmartYearParser;
import core.parsers.ChartableParser;
import core.parsers.params.ChartYearParameters;
import core.util.ServiceView;
import dao.entities.*;
import dao.musicbrainz.MusicBrainzService;
import dao.musicbrainz.MusicBrainzServiceSingleton;
import net.dv8tion.jda.api.EmbedBuilder;
import org.json.JSONObject;
import org.knowm.xchart.PieChart;

import java.time.Year;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AOTYCommand extends ChartableCommand<ChartYearParameters> {
    private final MusicBrainzService mb;
    private final int searchSpace = 1500;


    public AOTYCommand(ServiceView dao) {
        super(dao);
        mb = MusicBrainzServiceSingleton.getInstance();
    }

    @Override
    public String getSlashName() {
        return "aoty";
    }

    @Override
    public ChartableParser<ChartYearParameters> initParser() {
        return new ChartSmartYearParser(db);
    }


    @Override
    public String getDescription() {
        return "Gets your top albums of the year queried";
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("aoty", "albumoftheyear");
    }

    @Override
    public String getName() {
        return "Albums Of The Year!";
    }

    @Override
    public CountWrapper<BlockingQueue<UrlCapsule>> processQueue(ChartYearParameters param) throws LastFmException {
        BlockingQueue<UrlCapsule> queue = new LinkedBlockingQueue<>();

        boolean isByTime = param.isByTime();
        List<AlbumInfo> nonEmptyMbid;
        List<AlbumInfo> emptyMbid;
        Set<AlbumInfo> albumsMbizMatchingYear = new HashSet<>();

        Year year = param.getYear();
        if (!isByTime && param.getTimeFrameEnum().isAllTime()) {
            List<ScrobbledAlbum> userAlbumByMbid = db.getUserAlbumsOfYear(param.getUser().getName(), year);
            albumsMbizMatchingYear.addAll(userAlbumByMbid.stream().map(x -> new AlbumInfo(x.getAlbumMbid(), x.getAlbum(), x.getArtist())).toList());

            AtomicInteger atomicInteger = new AtomicInteger(0);
            List<ScrobbledAlbum> userAlbumsWithNoYear = db.getUserAlbumsWithNoYear(param.getUser().getName(), 2000);
            BlockingQueue<UrlCapsule> finalQueue = queue;
            userAlbumByMbid.forEach(x -> finalQueue.add(new AlbumChart(x.getUrl(), atomicInteger.getAndIncrement(), x.getAlbum(), x.getArtist(), x.getAlbumMbid(), x.getCount(), param.isWriteTitles(), param.isWritePlays(), param.isAside())));
            userAlbumsWithNoYear.forEach(x -> finalQueue.add(new AlbumChart(x.getUrl(), atomicInteger.getAndIncrement(), x.getAlbum(), x.getArtist(), x.getAlbumMbid(), x.getCount(), param.isWriteTitles(), param.isWritePlays(), param.isAside())));
            queue = finalQueue.stream().sorted(Comparator.comparingInt(UrlCapsule::getPlays).reversed()).collect(Collectors.toCollection(LinkedBlockingDeque::new));

            Map<Boolean, List<AlbumInfo>> results = userAlbumsWithNoYear.stream()
                    .map(x -> new AlbumInfo(x.getAlbumMbid(), x.getAlbum(), x.getArtist()))
                    .collect(Collectors.partitioningBy(albumInfo -> albumInfo.getMbid() == null || albumInfo.getMbid().isEmpty()));
            nonEmptyMbid = results.get(false);
            emptyMbid = results.get(true);
        } else {
            BiFunction<JSONObject, Integer, UrlCapsule> parser;
            if (param.getTimeFrameEnum().getTimeFrameEnum().equals(TimeFrameEnum.DAY)) {
                if (isByTime)
                    parser = TrackDurationAlbumArtistChart.getDailyArtistAlbumDurationParser(param, lastFM.getTrackDurations(param.getUser(), TimeFrameEnum.WEEK));
                else {
                    parser = AlbumChart.getDailyAlbumParser(param);
                }
            } else {
                if (isByTime)
                    parser = TrackDurationAlbumArtistChart.getTimedParser(param);
                else {
                    parser = ChartUtil.getParser(param.getTimeFrameEnum(), TopEntity.ALBUM, param, lastFM, param.getUser());
                }
            }


            lastFM.getChart(param.getUser(), param.getTimeFrameEnum(), this.searchSpace / 100, 100, TopEntity.ALBUM, parser, queue);
            //List of obtained elements


            List<AlbumInfo> albumInfos = db.albumsOfYear(queue.stream()
                            .map(capsule ->
                                    new AlbumInfo(capsule.getMbid(), capsule.getAlbumName(), capsule.getArtistName())).toList()
                    , param.getYear());
            albumsMbizMatchingYear.addAll(albumInfos);


            Map<Boolean, List<AlbumInfo>> results =
                    queue.stream()

                            .map(capsule ->
                                    new AlbumInfo(capsule.getMbid(), capsule.getAlbumName(), capsule.getArtistName()))
                            .filter(x -> !albumsMbizMatchingYear.contains(x))
                            .collect(Collectors.partitioningBy(albumInfo -> !albumsMbizMatchingYear.contains(albumInfo)));
            albumsMbizMatchingYear.addAll(results.get(false));
            List<AlbumInfo> notMatching = results.get(true);
            Map<Boolean, List<AlbumInfo>> byMbid = notMatching.stream().collect(Collectors.partitioningBy(albumInfo -> albumInfo.getMbid().isEmpty()));
            nonEmptyMbid = byMbid.get(false);
            emptyMbid = results.get(true);
        }
        if (isByTime) {
            return handleTimedChart(param, nonEmptyMbid, emptyMbid, queue);
        }
        List<AlbumInfo> foundByYear = mb.listOfYearReleases(nonEmptyMbid, year);
        CommandUtil.runLog(() -> db.insertAlbumsOfYear(foundByYear, year));

        albumsMbizMatchingYear.addAll(foundByYear);
        List<AlbumInfo> mbFoundBYName = mb.findArtistByRelease(emptyMbid, year);
        CommandUtil.runLog(() -> db.insertAlbumsOfYear(mbFoundBYName, year));
        ;
        //CompletableFuture.supplyAsync( x -> dao.insertAlbum(x))
        emptyMbid.removeAll(mbFoundBYName);

        int discogsMetrics = 0;
        //Keep the order of the original queue so the final chart is ordered by plays
        AtomicInteger counter2 = new AtomicInteger(0);
        queue.removeIf(urlCapsule -> {
            for (AlbumInfo albumInfo : albumsMbizMatchingYear) {
                if ((albumInfo.getMbid() != null && !albumInfo.getMbid().isEmpty() && albumInfo.getMbid().equals(urlCapsule.getMbid())) || urlCapsule
                                                                                                                                                   .getAlbumName().equalsIgnoreCase(albumInfo.getName()) && urlCapsule.getArtistName()
                                                                                                                                                   .equalsIgnoreCase(albumInfo.getArtist())) {
                    urlCapsule.setPos(counter2.getAndIncrement());
                    return false;
                }
            }
            return true;
        });
        db.updateMetrics(discogsMetrics, mbFoundBYName.size(), albumsMbizMatchingYear
                .size(), ((long) param.getX()) * param.getX());
        return new CountWrapper<>(albumsMbizMatchingYear.size(), queue);
    }


    @Override
    public EmbedBuilder configEmbed(EmbedBuilder embedBuilder, ChartYearParameters params, int count) {
        Year year = params.getYear();
        String s = " in their top " + searchSpace + " albums";
        if (year.getValue() != Year.now().getValue()) {
            s = " in their library";
        }
        return params.initEmbed("s top albums from " + year, embedBuilder, " has " + count + " albums from " + year + s, params.getUser().getName());
    }

    @Override
    public String configPieChart(PieChart pieChart, ChartYearParameters params, int count, String initTitle) {
        Year year = params.getYear();
        String time = params.getTimeFrameEnum().getDisplayString();
        String s;
        if (year.getValue() != Year.now().getValue()) {
            s = "in their library";
        } else {
            s = "in their top " + searchSpace + " albums";
        }
        pieChart.setTitle(String.format("%ss top albums from %s%s", initTitle, year, time));
        return String.format("%s has %d albums from %s %s%s (showing top %d)", initTitle, count, year, s, time, params.getX() * params.getY());
    }

    @Override
    public void noElementsMessage(ChartYearParameters parameters) {
        Context e = parameters.getE();
        DiscordUserDisplay ingo = CommandUtil.getUserInfoEscaped(e, parameters.getDiscordId());
        String s;
        if (parameters.getYear().getValue() != Year.now().getValue()) {
            s = "in their library";
        } else {
            s = "in their top " + searchSpace + " albums";
        }
        sendMessageQueue(e, String.format("Couldn't find any %s album %s%s!", parameters.getYear().toString(), s, parameters.getTimeFrameEnum().getDisplayString()));
    }

    @Override
    public void doImage(BlockingQueue<UrlCapsule> queue, int x, int y, ChartYearParameters parameters, int size) {
        if (!parameters.isCareAboutSized()) {
            int imageSize = Math.max((int) Math.ceil(Math.sqrt(queue.size())), 1);
            super.doImage(queue, imageSize, imageSize, parameters, size);
        } else {
            BlockingQueue<UrlCapsule> tempQueuenew = new LinkedBlockingDeque<>();
            queue.drainTo(tempQueuenew, x * y);
            super.doImage(tempQueuenew, x, y, parameters, size);
        }
    }


    private CountWrapper<BlockingQueue<UrlCapsule>> handleTimedChart(ChartYearParameters parameters, List<AlbumInfo> nonEmptyMbid, List<AlbumInfo> emptyMbid, BlockingQueue<UrlCapsule> queue) {
        Year year = parameters.getYear();

        List<AlbumInfo> albumsMbizMatchingYear;

        List<CountWrapper<AlbumInfo>> mbFoundByMbid = mb.listOfYearReleasesWithAverage(nonEmptyMbid, year);
        List<CountWrapper<AlbumInfo>> mbFoundBYName = mb.findArtistByReleaseWithAverage(emptyMbid, year);

        albumsMbizMatchingYear = Stream.concat(mbFoundByMbid.stream(), mbFoundBYName.stream()).map(CountWrapper::getResult).toList();

        emptyMbid.removeAll(mbFoundBYName.stream().map(CountWrapper::getResult).toList());
        mbFoundByMbid.addAll(mbFoundBYName);

        List<UrlCapsule> b = new ArrayList<>();
        queue.drainTo(b);

        b.removeIf(urlCapsule -> {
            for (CountWrapper<AlbumInfo> t : mbFoundByMbid) {
                AlbumInfo albumInfo = t.getResult();

                if ((!albumInfo.getMbid().isEmpty() && albumInfo.getMbid().equals(urlCapsule.getMbid())) || urlCapsule.getAlbumName().equalsIgnoreCase(albumInfo.getName()) && urlCapsule.getArtistName()
                        .equalsIgnoreCase(albumInfo.getArtist())) {
                    TrackDurationAlbumArtistChart urlCapsule1 = (TrackDurationAlbumArtistChart) urlCapsule;
                    urlCapsule1.setSeconds((t.getRows() / 1000) * urlCapsule.getPlays());
                    return false;
                }
            }
            return true;
        });
        AtomicInteger ranker = new AtomicInteger(0);

        BlockingQueue<UrlCapsule> sortedQ = b.stream().sorted(Comparator.comparing(x -> (
                        ((TrackDurationAlbumArtistChart) x).getSeconds()
                )).reversed()).peek(x -> x.setPos(ranker.getAndIncrement()))
                .collect(Collectors.toCollection(LinkedBlockingDeque::new));
        return new CountWrapper<>(albumsMbizMatchingYear.size(), sortedQ);
    }


}
