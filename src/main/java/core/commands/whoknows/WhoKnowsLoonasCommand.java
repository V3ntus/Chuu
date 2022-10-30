package core.commands.whoknows;

import core.commands.Context;
import core.commands.utils.ChuuEmbedBuilder;
import core.commands.utils.CommandUtil;
import core.imagerenderer.ChartQuality;
import core.imagerenderer.CollageGenerator;
import core.imagerenderer.ExetricWKMaker;
import core.imagerenderer.WhoKnowsMaker;
import core.otherlisteners.util.PaginatorBuilder;
import core.parsers.LOOONAParser;
import core.parsers.Parser;
import core.parsers.params.LOONAParameters;
import core.util.ServiceView;
import dao.entities.*;
import net.dv8tion.jda.api.EmbedBuilder;
import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.HashSetValuedHashMap;
import org.apache.commons.lang3.tuple.Pair;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class WhoKnowsLoonasCommand extends WhoKnowsBaseCommand<LOONAParameters> {

    private static MultiValuedMap<LOONA, String> loonas;
    private static Map<String, LOONA> reverseLookUp;

    static {
        refreshLOONAS();
    }

    public WhoKnowsLoonasCommand(ServiceView dao) {
        super(dao);
        parser.removeOptional("pie");
        parser.removeOptional("list");
    }

    public static void refreshLOONAS() {

        try (InputStream resourceAsStream = WhoKnowsLoonasCommand.class.getResourceAsStream("/loonas.json")) {
            if (resourceAsStream == null) {
                throw new IllegalStateException("Could not init class.");
            }
            try (InputStreamReader in = new InputStreamReader(resourceAsStream, StandardCharsets.UTF_8)) {
                MultiValuedMap<LOONA, String> temp = new HashSetValuedHashMap<>();
                JSONArray jsonArray = new JSONArray(new JSONTokener(in));
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject jsonObject = jsonArray.getJSONObject(i);
                    String name = jsonObject.getString("name");
                    StreamSupport.stream(jsonObject.getJSONArray("group").spliterator(), false).
                            map(JSONObject.class::cast).map(x -> x.getString("type")).map(LOONA::get).forEach(l -> temp.put(l, name));
                }
                loonas = temp;
                Map<String, LOONA> temp2 = new HashMap<>();
                temp.asMap().forEach((key, value) -> value.forEach(y -> temp2.put(y, key)));
                reverseLookUp = temp2;
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Could not init class.", exception);
        }
    }

    private static <T> BiConsumer<String, Map.Entry<T, List<ReturnNowPlaying>>> setArtistAsrepresentative() {
        return (representative, x) -> x.getValue().stream().peek(l -> l.setArtist(representative)).forEach(l -> l.setDiscordName(representative));

    }

    private static <T> BiConsumer<String, Map.Entry<T, List<ReturnNowPlaying>>> setUserAsRepresentative() {
        return (representative, x) -> x.getValue().stream().peek(l -> l.setLastFMId(representative)).forEach(l -> l.setDiscordName(representative));

    }

    public static Map<String, ReturnNowPlaying> groupByUser(List<WrapperReturnNowPlaying> whoKnowsArtistSet) {
        return whoKnowsArtistSet.stream().flatMap(x -> x.getReturnNowPlayings().stream()).collect(Collectors.groupingBy(ReturnNowPlaying::getLastFMId,
                Collectors.collectingAndThen(Collectors.toList(),
                        (List<ReturnNowPlaying> t) ->
                                t.stream().reduce((z, x) -> {
                                    z.setPlayNumber(z.getPlayNumber() + x.getPlayNumber());
                                    return z;
                                }).orElse(null))));
    }

    @Override
    public String slashName() {
        return "loona";
    }

    @Override
    public void onCommand(Context e, @Nonnull LOONAParameters params) {


        @Nullable String nullableOwner = params.getSubject() == LOONAParameters.Subject.ME ? params.getLastFMData().getName() : null;
        LOONAParameters.Display display = params.getDisplay();
        LOONAParameters.SubCommand subCommand = params.getSubCommand();
        int limit = display == LOONAParameters.Display.COLLAGE ? 40 : Integer.MAX_VALUE;

        Set<String> artists = switch (subCommand) {
            case GENERAL -> new HashSet<>(loonas.values());
            case SPECIFIC -> {
                assert params.getTargetedLOONA() != null;
                yield new HashSet<>(loonas.get(params.getTargetedLOONA()));
            }
            case GROUPED -> {
                assert params.getTargetedType() != null;
                yield loonas.asMap().entrySet().stream().filter(x -> x.getKey().getType() == params.getTargetedType()).flatMap(x -> x.getValue().stream()).collect(Collectors.toSet());
            }
        };
        LOONAParameters.Mode mode = params.getMode();
        List<WrapperReturnNowPlaying> whoKnowsArtistSet = db.getWhoKnowsArtistSet(artists, e.getGuild().getIdLong(), limit, nullableOwner);
        Map<Long, String> mapper = new HashMap<>();
        if (whoKnowsArtistSet.isEmpty()) {
            sendMessageQueue(e, "Didnt find any artist :(");
            return;
        }
        whoKnowsArtistSet.stream().flatMap(x -> x.getReturnNowPlayings().stream()).forEach(x -> {
            String s = mapper.get(x.getDiscordId());
            if (s == null) {
                s = CommandUtil.getUserInfoUnescaped(e, x.getDiscordId()).username();
                mapper.put(x.getDiscordId(), s);
            }
            x.setDiscordName(s);
        });

        switch (display) {
            case COLLAGE:
                int xSize = 5;
                int y = 5;
                switch (mode) {
                    case GROUPED -> {
                        whoKnowsArtistSet = List.of(handleRepresentatives(params, whoKnowsArtistSet));
                        switch (subCommand) {
                            case GENERAL -> {
                                xSize = 1;
                                y = 1;
                            }
                            case SPECIFIC -> {
                                xSize = 2;
                                y = 2;
                            }
                            case GROUPED -> {
                                switch (params.getTargetedType()) {
                                    case GROUP, MISC -> {
                                        xSize = 1;
                                        y = 1;
                                    }
                                    case SUBUNIT -> {
                                        xSize = 3;
                                        y = 1;
                                    }
                                    case MEMBER -> {
                                        xSize = 4;
                                        y = 3;
                                    }
                                }
                            }
                        }
                    }
                    case ALL -> {
                        if (subCommand == LOONAParameters.SubCommand.GROUPED) {
                            Map<LOONA, List<ReturnNowPlaying>> groupedByType = whoKnowsArtistSet
                                    .stream()
                                    .flatMap(x -> x.getReturnNowPlayings().stream())
                                    .collect(Collectors.groupingBy(x -> reverseLookUp.get(x.getArtist()), () -> new EnumMap<>(LOONA.class), Collectors.toList()));
                            whoKnowsArtistSet = groupedByType.entrySet().stream()

                                    .sorted(Comparator.comparingLong((Map.Entry<LOONA, List<ReturnNowPlaying>> t) -> t.getValue().stream().mapToLong(ReturnNowPlaying::getPlayNumber).sum()).reversed())
                                    .map(x ->
                                    {
                                        String representative = LOONA.getRepresentative(x.getKey());
                                        String artistUrl = db.getArtistUrl(representative);
                                        WrapperReturnNowPlaying wrapperReturnNowPlaying = new WrapperReturnNowPlaying(x.getValue(), x.getValue().size(), artistUrl, representative);
                                        Map<String, ReturnNowPlaying> userToPlays = groupByUser(List.of(wrapperReturnNowPlaying));
                                        return new WrapperReturnNowPlaying(userToPlays.values().stream().sorted(Comparator.comparingLong(ReturnNowPlaying::getPlayNumber).reversed()).toList(), userToPlays.size(), artistUrl, representative);
                                    })
                                    .toList();
                            switch (params.getTargetedType()) {
                                case GROUP, MISC -> {
                                    xSize = 1;
                                    y = 1;
                                }
                                case SUBUNIT -> {
                                    xSize = 3;
                                    y = 1;
                                }
                                case MEMBER -> {
                                    xSize = 4;
                                    y = 3;
                                }
                            }

                        } else if (subCommand == LOONAParameters.SubCommand.GENERAL) {
                            Map<LOONA.Type, List<ReturnNowPlaying>> groupedByType = whoKnowsArtistSet.stream().flatMap(x -> x.getReturnNowPlayings().stream())
                                    .collect(Collectors.groupingBy(x -> reverseLookUp.get(x.getArtist()).getType(), () -> new EnumMap<>(LOONA.Type.class), Collectors.toList()));
                            whoKnowsArtistSet = groupedByType.entrySet().stream()

                                    .sorted(Comparator.comparingLong((Map.Entry<LOONA.Type, List<ReturnNowPlaying>> t) -> t.getValue().stream().mapToLong(ReturnNowPlaying::getPlayNumber).sum()).reversed())
                                    .map(x ->
                                    {
                                        String representative = LOONA.getRepresentative(x.getKey());
                                        String artistUrl = db.getArtistUrl(representative);
                                        WrapperReturnNowPlaying wrapperReturnNowPlaying = new WrapperReturnNowPlaying(x.getValue(), x.getValue().size(), artistUrl, representative);
                                        Map<String, ReturnNowPlaying> userToPlays = groupByUser(List.of(wrapperReturnNowPlaying));
                                        return new WrapperReturnNowPlaying(userToPlays.values().stream().sorted(Comparator.comparingLong(ReturnNowPlaying::getPlayNumber).reversed()).toList(), userToPlays.size(), artistUrl, representative);
                                    })
                                    .toList();
                            xSize = 2;
                            y = 2;
                        }
                    }
                    case UNGROUPED -> {
                    }
                }


                int size = whoKnowsArtistSet.size();

                AtomicInteger atomicInteger = new AtomicInteger(0);

                if (size < xSize * y) {

                    xSize = Math.max((int) Math.ceil(Math.sqrt(size)), 1);
                    if (xSize * (xSize - 1) > size) {
                        y = xSize - 1;
                    } else {
                        //noinspection SuspiciousNameCombination
                        y = xSize;
                    }
                }

                BlockingQueue<Pair<BufferedImage, Integer>> imageIndex = whoKnowsArtistSet.stream().
                        map(x -> Pair.of(doImage(params, x), atomicInteger.getAndIncrement()))
                        .collect(Collectors.toCollection(LinkedBlockingQueue::new));
                BufferedImage bufferedImage = CollageGenerator.generateCollageThreaded(xSize, y, imageIndex, ChartQuality.PNG_BIG);
                sendImage(bufferedImage, e, ChartQuality.PNG_BIG);

                break;
            case SUM:
                if (mode == LOONAParameters.Mode.GROUPED && subCommand == LOONAParameters.SubCommand.GROUPED) {
                    Map<LOONA, List<ReturnNowPlaying>> groupedByType = whoKnowsArtistSet.stream()
                            .flatMap(x -> x.getReturnNowPlayings().stream())
                            .collect(Collectors.groupingBy(x -> reverseLookUp.get(x.getArtist()),
                                    () -> new EnumMap<>(LOONA.class),
                                    Collectors.toList()));
                    whoKnowsArtistSet = group(groupedByType, setUserAsRepresentative(), LOONA::getRepresentative);

                } else if (mode == LOONAParameters.Mode.GROUPED && subCommand == LOONAParameters.SubCommand.GENERAL) {
                    Map<LOONA.Type, List<ReturnNowPlaying>> groupedByType = whoKnowsArtistSet.stream().flatMap(x -> x.getReturnNowPlayings().stream()).collect(Collectors.groupingBy(x -> reverseLookUp.get(x.getArtist()).getType(),
                            () -> new EnumMap<>(LOONA.Type.class),
                            Collectors.toList()));
                    whoKnowsArtistSet = group(groupedByType, setUserAsRepresentative(), LOONA::getRepresentative);

                }
                Map<String, ReturnNowPlaying> userToPlays = groupByUser(whoKnowsArtistSet);
                WrapperReturnNowPlaying wrapperReturnNowPlaying = handleRepresentatives(params, userToPlays);
                wrapperReturnNowPlaying.setIndexes();
                super.doImage(params, wrapperReturnNowPlaying);
                break;
            case COUNT:
                if (mode == LOONAParameters.Mode.GROUPED) {
                    whoKnowsArtistSet = switch (subCommand) {
                        case GENERAL -> {
                            Map<LOONA.Type, List<ReturnNowPlaying>> groupedByType = whoKnowsArtistSet.stream().flatMap(x -> x.getReturnNowPlayings().stream()).collect(Collectors.groupingBy(x -> reverseLookUp.get(x.getArtist()).getType(),
                                    () -> new EnumMap<>(LOONA.Type.class),
                                    Collectors.toList()));
                            yield group(groupedByType, setArtistAsrepresentative(), LOONA::getRepresentative);
                        }
                        case GROUPED, SPECIFIC -> {
                            Map<LOONA, List<ReturnNowPlaying>> groupedByLoonas = whoKnowsArtistSet.stream().flatMap(x -> x.getReturnNowPlayings().stream())
                                    .collect(Collectors.groupingBy(x -> reverseLookUp.get(x.getArtist()),
                                            () -> new EnumMap<>(LOONA.class),
                                            Collectors.toList()));
                            yield group(groupedByLoonas, setArtistAsrepresentative(), LOONA::getRepresentative);
                        }
                    };
                }


                userToPlays = whoKnowsArtistSet.stream().flatMap(x -> x.getReturnNowPlayings().stream()).peek(x -> x.setPlayNumber(1)).collect(
                        Collectors.groupingBy(ReturnNowPlaying::getArtist,
                                Collectors.collectingAndThen(Collectors.toList(),
                                        (List<ReturnNowPlaying> t) ->
                                                t.stream().reduce((z, x) -> {
                                                    z.setPlayNumber(z.getPlayNumber() + x.getPlayNumber());
                                                    return z;
                                                }).orElse(null))));
                wrapperReturnNowPlaying = handleRepresentatives(params, userToPlays);
                wrapperReturnNowPlaying.setIndexes();
                doList(params, wrapperReturnNowPlaying);
                break;
        }

    }

    private WrapperReturnNowPlaying handleRepresentatives(LOONAParameters parse, List<WrapperReturnNowPlaying> whoKnowsArtistSet) {
        return handleRepresentatives(parse, groupByUser(whoKnowsArtistSet));
    }

    public <T> List<WrapperReturnNowPlaying> group(Map<T, List<ReturnNowPlaying>> whoKnowsArtistSet, BiConsumer<String, Map.Entry<T, List<ReturnNowPlaying>>> consumer, Function<T, String> mapper) {
        return whoKnowsArtistSet.entrySet().stream()
                .map(x ->
                {
                    String representative = mapper.apply(x.getKey());
                    String artistUrl = db.getArtistUrl(representative);
                    consumer.accept(representative, x);
                    return new WrapperReturnNowPlaying(x.getValue(), x.getValue().size(), artistUrl, representative);
                }).toList();
    }

    private WrapperReturnNowPlaying handleRepresentatives(LOONAParameters
                                                                  parse, Map<String, ReturnNowPlaying> userToPlays) {
        String representativeArtist;
        String represenentativeUrl;
        switch (parse.getSubCommand()) {
            case GENERAL -> {
                representativeArtist = "LOONAVERSE";
                represenentativeUrl = db.getArtistUrl(representativeArtist);
            }
            case SPECIFIC -> {
                representativeArtist = LOONA.getRepresentative(parse.getTargetedLOONA());
                represenentativeUrl = db.getArtistUrl(representativeArtist);
            }
            case GROUPED -> {
                representativeArtist = LOONA.getRepresentative(parse.getTargetedType());
                represenentativeUrl = db.getArtistUrl(representativeArtist);
            }
            default -> throw new IllegalStateException("Unexpected value: " + parse.getSubCommand());
        }
        return new WrapperReturnNowPlaying(userToPlays.values().stream().sorted(Comparator.comparingLong(ReturnNowPlaying::getPlayNumber).reversed()).toList(), 0, represenentativeUrl, representativeArtist);
    }

    @Override
    BufferedImage doImage(LOONAParameters ap, WrapperReturnNowPlaying wrapperReturnNowPlaying) {
        Context e = ap.getE();
        BufferedImage logo = null;
        ImageTitle title;
        if (e.isFromGuild()) {
            logo = CommandUtil.getLogo(db, e);
            title = new ImageTitle(e.getGuild().getName(), e.getGuild().getIconUrl());
        } else {
            title = new ImageTitle(e.getJDA().getSelfUser().getName(), e.getJDA().getSelfUser().getAvatarUrl());
        }
        handleWkMode(ap, wrapperReturnNowPlaying, WhoKnowsDisplayMode.IMAGE);
        LastFMData data = obtainLastFmData(ap);
        BufferedImage image;
        if (data.getWkModes().contains(WKMode.BETA)) {
            image = ExetricWKMaker.generateWhoKnows(wrapperReturnNowPlaying, title.title(), title.logo(), logo);
        } else {
            image = WhoKnowsMaker.generateWhoKnows(wrapperReturnNowPlaying, title.title(), logo);
        }

        return image;

    }

    @Override
    LastFMData obtainLastFmData(LOONAParameters ap) {
        return ap.getLastFMData();
    }

    @Override
    public Optional<Rank<ReturnNowPlaying>> fetchNotInList(LOONAParameters ap, WrapperReturnNowPlaying wr) {
        return Optional.empty();
    }

    void doList(LOONAParameters ap, WrapperReturnNowPlaying wrapperReturnNowPlaying) {

        Context e = ap.getE();

        String usable;
        if (e.isFromGuild()) {
            usable = CommandUtil.escapeMarkdown(e.getGuild().getName());
        } else {
            usable = e.getJDA().getSelfUser().getName();
        }

        EmbedBuilder embedBuilder = new ChuuEmbedBuilder(ap.getE())
                .setTitle(getTitle(ap, usable)).
                setThumbnail(CommandUtil.noImageUrl(wrapperReturnNowPlaying.getUrl()));

        new PaginatorBuilder<>(e, embedBuilder, wrapperReturnNowPlaying.getReturnNowPlayings()).mapper(ReturnNowPlaying::toDisplay).unnumered().build().queue();

    }


    @Override
    public Parser<LOONAParameters> initParser() {
        return new LOOONAParser(db);
    }


    @Override
    public String getDescription() {
        return "LOONA";
    }

    @Override
    public List<String> getAliases() {
        return List.of("LOOΠΔ", "wkl", "whoknowsloona", "loona", "loopidelta", "looπδ");
    }

    @Override
    public String getName() {
        return "LOONA";
    }


    @Override
    WhoKnowsDisplayMode getWhoknowsMode(LOONAParameters params) {
        return WhoKnowsDisplayMode.IMAGE;
    }

    @Override
    WrapperReturnNowPlaying generateWrapper(LOONAParameters params, WhoKnowsDisplayMode whoKnowsDisplayMode) {
        return null;
    }

    @Override
    public String getTitle(LOONAParameters params, String baseTitle) {
        return null;
    }

}
