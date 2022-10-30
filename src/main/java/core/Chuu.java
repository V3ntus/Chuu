package core;

import com.google.common.util.concurrent.RateLimiter;
import com.sedmelluq.discord.lavaplayer.jdaudp.NativeAudioSendFactory;
import core.apis.discogs.DiscogsSingleton;
import core.apis.last.LastFMFactory;
import core.apis.spotify.SpotifySingleton;
import core.commands.CustomInterfacedEventManager;
import core.commands.abstracts.MyCommand;
import core.commands.config.HelpCommand;
import core.commands.config.PrefixCommand;
import core.commands.discovery.FeaturedCommand;
import core.commands.moderation.AdministrativeCommand;
import core.commands.moderation.EvalCommand;
import core.commands.moderation.TagWithYearCommand;
import core.interactions.InteractionBuilder;
import core.music.ExtendedAudioPlayerManager;
import core.music.PlayerRegistry;
import core.music.listeners.VoiceListener;
import core.music.scrobble.ScrobbleEventManager;
import core.music.scrobble.StatusProcessor;
import core.music.utils.ScrabbleProcessor;
import core.otherlisteners.*;
import core.services.*;
import core.services.validators.AlbumFinder;
import core.util.ServiceView;
import core.util.botlists.BotListPoster;
import dao.ChuuDatasource;
import dao.ChuuService;
import dao.LongExecutorChuuDatasource;
import dao.UpdateDatasource;
import dao.entities.Callback;
import dao.entities.Metrics;
import dao.entities.UsersWrapper;
import dao.exceptions.ChuuServiceException;
import gnu.trove.set.TLongSet;
import gnu.trove.set.hash.TLongHashSet;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.EventListener;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;
import net.dv8tion.jda.api.requests.restaction.MessageAction;
import net.dv8tion.jda.api.sharding.DefaultShardManagerBuilder;
import net.dv8tion.jda.api.sharding.ShardManager;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.login.LoginException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@SuppressWarnings("UnstableApiUsage")
public class Chuu {

    public static final Character DEFAULT_PREFIX = '!';
    public static final String DEFAULT_LASTFM_ID = "chuubot";
    private static final LongAdder lastFMMetric = new LongAdder();
    private static final LongAdder cacheMetric = new LongAdder();
    private static final Set<String> privateLastFms = new HashSet<>();
    private static final TLongSet knownIds = new TLongHashSet();


    public static PlayerRegistry playerRegistry;
    public static ExtendedAudioPlayerManager playerManager;
    public static String chuuSess;
    public static String ipv6Block;
    public static long channelId;
    public static long channel2Id;
    public static PrefixService prefixService;
    public static CustomInterfacedEventManager customManager;
    public static boolean doTyping = true;
    private static ShardManager shardManager;
    private static Logger logger;
    private static Map<Long, RateLimiter> ratelimited;
    private static ServiceView db;
    private static CoverService coverService;
    private static MessageDeletionService messageDeletionService;
    private static MessageDisablingService messageDisablingService = new MessageDisablingService();
    private static ScrobbleEventManager scrobbleEventManager;
    private static ScrabbleProcessor scrabbleProcessor;
    private static ScheduledService scheduledService;

    public static String getLastFmId(String lastfmId) {
        if (privateLastFms.contains(lastfmId)) {
            return DEFAULT_LASTFM_ID;
        }
        return lastfmId;
    }

    public static void incrementMetric() {
        lastFMMetric.increment();
    }

    private static Collection<GatewayIntent> getIntents() {
        EnumSet<GatewayIntent> gatewayIntents = GatewayIntent.fromEvents(GuildMemberRemoveEvent.class, MessageReceivedEvent.class);
        gatewayIntents.add(GatewayIntent.GUILD_VOICE_STATES);
        gatewayIntents.add(GatewayIntent.GUILD_EMOJIS_AND_STICKERS);
        return gatewayIntents;
    }

    public static Properties initFields() {
        logger = LoggerFactory.getLogger(Chuu.class);
        Properties properties = readToken();
        String channel = properties.getProperty("MODERATION_CHANNEL_ID");
        String channel2 = properties.getProperty("MODERATION_CHANNEL_2_ID");
        if (channel != null) {
            channelId = Long.parseLong(channel);
        }
        if (channel2 != null) {
            channel2Id = Long.parseLong(channel2);
        }
        chuuSess = properties.getProperty("LASTFM_BOT_SESSION_KEY");
        ipv6Block = properties.getProperty("IPV6_BLOCK");
        db = new ServiceView(new ChuuService(new ChuuDatasource()), new ChuuService(new LongExecutorChuuDatasource()), new ChuuService(new UpdateDatasource()));
        ChuuService service = db.normalService();
        prefixService = new PrefixService(service);
        ColorService.init(service);
        coverService = new CoverService(service);
        DiscogsSingleton.init(properties.getProperty("DC_SC"), properties.getProperty("DC_KY"));
        SpotifySingleton.init(properties.getProperty("client_ID"), properties.getProperty("client_Secret"));
        scrobbleEventManager = new ScrobbleEventManager(new StatusProcessor(service));
        scrabbleProcessor = new ScrabbleProcessor(new AlbumFinder(service, LastFMFactory.getNewInstance()));
        playerManager = new ExtendedAudioPlayerManager(scrobbleEventManager, scrabbleProcessor);
        playerRegistry = new PlayerRegistry(playerManager);
        scheduledService = new ScheduledService(Executors.newScheduledThreadPool(6), db.normalService());
        ratelimited = service.getRateLimited().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, y -> RateLimiter.create(y.getValue())));
        MessageAction.setDefaultMentions(EnumSet.noneOf(Message.MentionType.class));
        MessageAction.setDefaultMentionRepliedUser(false);
        initPrivateLastfms(db.normalService());
        messageDeletionService = new MessageDeletionService(db.normalService().getServersWithDeletableMessages());
        return properties;

    }

    public static void setupBot(boolean doDbCleanUp, boolean installGlobalCommands, boolean startRightAway, boolean notMain) {
        Properties properties = initFields();
        ChuuService service = db.normalService();
        List<Long> ids = db.longService().getAllALL().stream().map(UsersWrapper::getDiscordID).toList();
        knownIds.addAll(ids);
        if (!notMain) {
            // Only on main instance
            scheduledService.setScheduled();
        }
        // Logs every fime minutes the api calls
        scheduledService.addSchedule(() -> {
            long l = lastFMMetric.longValue();
            lastFMMetric.reset();
            service.updateMetric(Metrics.LASTFM_PETITIONS, l);
            logger.info("Made {} lastfm requests in the last 5 minutes", l);

            long c = cacheMetric.longValue();
            cacheMetric.reset();
            logger.info("Made {} db requests in the last 5 minutes", c);
        }, 2, 5, TimeUnit.MINUTES);


        MessageAction.setDefaultMentions(EnumSet.noneOf(Message.MentionType.class));
        MessageAction.setDefaultMentionRepliedUser(false);

        customManager = new CustomInterfacedEventManager(0);
        EvalCommand evalCommand = new EvalCommand(db);
        AtomicInteger counter = new AtomicInteger(0);

        DefaultShardManagerBuilder builder = DefaultShardManagerBuilder.
                create(getIntents())
                .setChunkingFilter(ChunkingFilter.NONE)
                .enableCache(CacheFlag.EMOJI, CacheFlag.VOICE_STATE)
                .disableCache(CacheFlag.ACTIVITY, CacheFlag.CLIENT_STATUS, CacheFlag.ONLINE_STATUS, CacheFlag.MEMBER_OVERRIDES, CacheFlag.STICKER, CacheFlag.ROLE_TAGS)
                .setAudioSendFactory(new NativeAudioSendFactory()).setBulkDeleteSplittingEnabled(false)
                .setMemberCachePolicy(member -> {
                    try {
                        return knownIds.contains(member.getIdLong());
                    } catch (Exception e) {
                        getLogger().info("Timeout on member caching | Member {} | Guild {} ", member.getUser().getAsTag(), member.getGuild().getName(), e);
                        return false;
                    }
                })
                .setLargeThreshold(50)
                .setToken(properties.getProperty("DISCORD_TOKEN"))
                .setAutoReconnect(true)
                .setEventManagerProvider(a -> customManager)
                .addEventListeners(evalCommand)
                .setShardsTotal(-1)
                .addEventListeners(new AwaitReady(counter, (ShardManager shard) -> {

                    JDA firstShard = shard.getShardById(0);
                    assert firstShard != null;
                    if (!startRightAway) {
                        shutDownPreviousInstance(() -> {
                            addAll(db, shardManager::addEventListener);
                            customManager.isReady = true;
                            doTyping = true;
                            messageDisablingService = new MessageDisablingService(firstShard, service);
                            InteractionBuilder.setGlobalCommands(firstShard);
                        });
                    }
                    scheduledService.addSchedule(() -> new BotListPoster().doPost(), 5, 120, TimeUnit.MINUTES);

                    updatePresence("Chuu");
                    if (installGlobalCommands) {
                        InteractionBuilder.setGlobalCommands(firstShard).queue();
                    }
                }));

        try {
            if (startRightAway) {
                addAll(db, builder::addEventListeners);
                customManager.isReady = true;
                doTyping = true;

            }

            shardManager = builder.build();
            if (startRightAway) {
                shardManager.getShards().stream().findFirst().ifPresent(z -> {
                    messageDisablingService = new MessageDisablingService(z, service);
                    CommandListUpdateAction ignored = InteractionBuilder.setGlobalCommands(z);
                });
            }
        } catch (LoginException e) {
            Chuu.getLogger().warn(e.getMessage(), e);
            throw new ChuuServiceException(e);
        }
    }

    public static void shutDownPreviousInstance(Callback callback) {
        var a = ProcessHandle.current();

        Optional<String[]> arguments = a.info().arguments();
        if (arguments.isEmpty()) {
            getLogger().warn("Args of current pid are empty???");
            callback.execute();
            return;
        }
        String[] a_args = Arrays.stream(arguments.get()).filter(z -> !z.endsWith(".jar")).toArray(String[]::new);
        var c = ProcessHandle.allProcesses().filter(z -> z.pid() != a.pid()).filter(z -> {
            boolean present = z.info().arguments().isPresent();
            if (!present) {
                return false;
            }
            String[] strings = Arrays.stream(z.info().arguments().get()).filter(t -> !t.endsWith(".jar")).toArray(String[]::new);
            return Arrays.equals(strings, a_args);
        }).findFirst();

        c.ifPresentOrElse(processHandle -> {
            getLogger().warn("Destroyed process with pid {} ", processHandle.pid());
            processHandle.destroy();
            callback.execute();
        }, () -> {
            getLogger().warn("Didn't destroy any process!!!");
            callback.execute();
        });


    }

    public static void addAll(ServiceView db, Consumer<EventListener> consumer) {
        HelpCommand help = new HelpCommand(db);

        AdministrativeCommand commandAdministrator = new AdministrativeCommand(db);
        PrefixCommand prefixCommand = new PrefixCommand(db);
        TagWithYearCommand tagWithYearCommand = new TagWithYearCommand(db);
        FeaturedCommand featuredCommand = new FeaturedCommand(db, scheduledService);
        consumer.accept(help);

        consumer.accept(help.registerCommand(commandAdministrator));
        consumer.accept(help.registerCommand(prefixCommand));
        consumer.accept(help.registerCommand(featuredCommand));
        consumer.accept(help.registerCommand(tagWithYearCommand));
        consumer.accept(help.registerCommand(featuredCommand));
        MyCommand<?>[] myCommands = scanListeners(help);
        Arrays.stream(myCommands).forEach(consumer);
        consumer.accept(new VoiceListener());
        consumer.accept(new JoinLeaveListener(db.normalService(), LastFMFactory.getNewInstance()));
        consumer.accept(new AutoCompleteListener());
        consumer.accept(new AlbumYearApproval(channelId, db.normalService()));
        consumer.accept(new FriendRequester(db.normalService()));
    }

    private static MyCommand<?>[] scanListeners(HelpCommand help) {
        try (ScanResult result = new ClassGraph().acceptPackages("core.commands").scan()) {
            return result.getAllClasses().stream()
                    .filter(x -> x.isStandardClass() && !x.isAbstract())
                    .filter(x -> !x.getSimpleName().equals(HelpCommand.class.getSimpleName())
                                 && !x.getSimpleName().equals(AdministrativeCommand.class.getSimpleName())
                                 && !x.getSimpleName().equals(PrefixCommand.class.getSimpleName())
                                 && !x.getSimpleName().equals(TagWithYearCommand.class.getSimpleName())
                                 && !x.getSimpleName().equals(EvalCommand.class.getSimpleName())
                                 && !x.getSimpleName().equals(FeaturedCommand.class.getSimpleName()))
                    .filter(x -> x.extendsSuperclass("core.commands.abstracts.MyCommand")).map(x -> {
                        try {
                            return (MyCommand<?>) x.loadClass().getConstructor(ServiceView.class).newInstance(db);
                        } catch (InstantiationException | IllegalAccessException | InvocationTargetException |
                                 NoSuchMethodException e) {
                            throw new ChuuServiceException(e);
                        }

                    }).peek(help::registerCommand).toArray(MyCommand<?>[]::new);
        } catch (Exception ex) {
            logger.error("There was an error while registering the commands!", ex);
            logger.error(ex.getMessage(), ex);
            throw new ChuuServiceException(ex);
        }
    }

    private static void initPrivateLastfms(ChuuService dao) {
        privateLastFms.addAll(dao.getPrivateLastfmIds());
    }

    public static void changePrivacyLastfm(String lastfmId, boolean mode) {
        if (mode) {
            privateLastFms.add(lastfmId);
        } else {
            privateLastFms.remove(lastfmId);
        }
    }

    public static Logger getLogger() {
        return logger;
    }

    public static Properties readToken() {

        Properties properties = new Properties();
        try (InputStream in = Chuu.class.getResourceAsStream("/all.properties")) {
            properties.load(in);
            return properties;
        } catch (IOException e) {
            throw new ChuuServiceException(e);
        }
    }

    public static void updatePresence(String artist) {
        Chuu.shardManager.getShards().forEach(x -> x.getPresence().setActivity(Activity.playing(artist + " | !help for help")));
    }

    public static Map<Long, RateLimiter> getRatelimited() {
        return ratelimited;
    }

    public static ShardManager getShardManager() {
        return shardManager;
    }

    public static ChuuService getDb() {
        return db.normalService();
    }


    public static MessageDeletionService getMessageDeletionService() {
        return messageDeletionService;
    }

    public static MessageDisablingService getMessageDisablingService() {
        return messageDisablingService;
    }

    public static String getRandomSong(String name) {

        return null;
    }

    public static ScrobbleEventManager getScrobbleEventManager() {
        return scrobbleEventManager;
    }

    public static ScrabbleProcessor getScrobbleProcesser() {
        return scrabbleProcessor;
    }

    public static CoverService getCoverService() {
        return coverService;
    }

    public static ScheduledService getScheduledService() {
        return scheduledService;
    }

    public static boolean isLoaded() {
        return shardManager.getShardsRunning() == shardManager.getShardsTotal();
    }

    public static void main(String[] args) {
        System.out.println("Stating");
        if (System.getProperty("file.encoding").equals("UTF-8")) {
            setupBot(Arrays.stream(args).anyMatch(x -> x.equalsIgnoreCase("stop-asking")),
                    Arrays.stream(args).noneMatch(x -> x.equalsIgnoreCase("no-global")),
                    Arrays.stream(args).anyMatch(x -> x.equalsIgnoreCase("start-away")),
                    Arrays.stream(args).anyMatch(x -> x.equalsIgnoreCase("not-main")));
        } else {
            throw new ChuuServiceException("Set up utf-8 pls");
        }
    }

    public static void refreshCache(long id) {
        knownIds.add(id);
    }

}
