package core.commands;

import com.google.common.util.concurrent.RateLimiter;
import core.Chuu;
import core.commands.abstracts.MyCommand;
import core.music.listeners.VoiceListener;
import core.otherlisteners.*;
import core.parsers.params.CommandParameters;
import core.services.ChuuRunnable;
import core.util.ChuuVirtualPool;
import core.util.StringUtils;
import net.dv8tion.jda.api.entities.Channel;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageType;
import net.dv8tion.jda.api.events.GenericEvent;
import net.dv8tion.jda.api.events.ReadyEvent;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceJoinEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceLeaveEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceMoveEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.UserContextInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.SelectMenuInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.hooks.EventListener;
import net.dv8tion.jda.api.hooks.IEventManager;
import net.dv8tion.jda.api.interactions.commands.CommandInteraction;
import net.dv8tion.jda.internal.JDAImpl;

import javax.annotation.Nonnull;
import java.nio.CharBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@SuppressWarnings("UnstableApiUsage")
public class CustomInterfacedEventManager implements IEventManager {

    private static final ExecutorService reactionExecutor = ChuuVirtualPool.of("Reaction-handle-");
    private static final ExecutorService autocompleteExecutor = ChuuVirtualPool.of("AutoComplete-handle-");
    private final Set<EventListener> otherListeners = ConcurrentHashMap.newKeySet();
    private final Map<String, MyCommand<? extends CommandParameters>> commandListeners = new HashMap<>();
    private final Map<Long, ChannelConstantListener> channelConstantListeners = new HashMap<>();
    private final Set<ConstantListener> constantListeners = new HashSet<>();
    private final Map<Long, Map<ReactionListener, ScheduledFuture<?>>> reactionaries = new ConcurrentHashMap<>();
    private final Map<String, MyCommand<? extends CommandParameters>> slashVariants = new HashMap<>();
    public boolean isReady;
    private VoiceListener voiceListener;
    private AutoCompleteListener autoCompleteListener;
    private JoinLeaveListener joinLeaveListener;

    public CustomInterfacedEventManager(int a) {
    }

    private void handleReaction(@Nonnull GenericEvent event) {
        assert event instanceof MessageReactionAddEvent || event instanceof ButtonInteractionEvent || event instanceof SelectMenuInteractionEvent;
        long channelId = switch (event) {
            case MessageReactionAddEvent e3 -> e3.getChannel().getIdLong();
            case ButtonInteractionEvent e3 -> Optional.of(e3.getChannel()).map(Channel::getIdLong).orElse(0L);
            case SelectMenuInteractionEvent e3 -> e3.getChannel().getIdLong();
            default -> throw new IllegalStateException("Unexpected value: " + event);
        };
        ChannelConstantListener c = channelConstantListeners.get(channelId);
        if (c != null) {
            c.onEvent(event);
            return;
        }
        Map<ReactionListener, ScheduledFuture<?>> channelReactionaries = reactionaries.getOrDefault(channelId, Collections.emptyMap());
        for (ReactionListener listener : channelReactionaries.keySet()) {
            listener.onEvent(event);
        }

        for (ConstantListener constantListener : constantListeners) {
            constantListener.onEvent(event);
        }


    }


    @Override
    public void register(@Nonnull Object listener) {
        if (!(listener instanceof EventListener))
            throw new IllegalArgumentException("Listener must implement EventListener");
        if (listener instanceof VoiceListener voiceListener) {
            this.voiceListener = voiceListener;
        }
        if (listener instanceof AutoCompleteListener acl) {
            this.autoCompleteListener = acl;
        }
        if (listener instanceof JoinLeaveListener jl) {
            this.joinLeaveListener = jl;
        }
        if ((listener instanceof MyCommand<?> myCommand)) {
            List<String> aliases = myCommand.getAliases();
            for (String alias : aliases) {
                commandListeners.put(alias, myCommand);
            }

        }
        if (listener instanceof ReactionListener reactionListener) {
            ScheduledFuture<?> schedule = Chuu.getScheduledService().addSchedule((() -> {
                Map<ReactionListener, ScheduledFuture<?>> prev = reactionaries.get(reactionListener.channelId);
                if (prev != null) {
                    prev.remove(reactionListener);
                }
                reactionListener.dispose();
            }), reactionListener.getActiveSeconds(), TimeUnit.SECONDS);
            Map<ReactionListener, ScheduledFuture<?>> map = new WeakHashMap<>();
            map.put(reactionListener, schedule);
            reactionaries.merge(reactionListener.channelId, map, (m1, m2) -> {
                m1.putAll(m2);
                return m1;
            });
        } else if (listener instanceof ConstantListener cl) {
            if (listener instanceof ChannelConstantListener ccl) {
                channelConstantListeners.put(ccl.getChannelId(), ccl);
            } else {
                constantListeners.add(cl);
            }
        }
        if (listener instanceof AwaitReady) {
            otherListeners.add((EventListener) listener);
        }

    }

    @Override
    public void unregister(@Nonnull Object listener) {
        switch (listener) {
            case MyCommand<?> myCommand -> {
                List<String> aliases = myCommand.getAliases();
                for (String alias : aliases) {
                    commandListeners.remove(alias);
                }
            }
            case ReactionListener reactionListener -> {
                Map<ReactionListener, ScheduledFuture<?>> prev = this.reactionaries.get(reactionListener.channelId);
                if (prev != null) {
                    ScheduledFuture<?> scheduledFuture = prev.remove(reactionListener);
                    if (prev.isEmpty()) {
                        this.reactionaries.remove(reactionListener.channelId);
                    }
                    if (scheduledFuture != null) {
                        scheduledFuture.cancel(true);
                        reactionListener.dispose();
                    }
                }

            }
            case AwaitReady a -> otherListeners.remove(a);
            case ChannelConstantListener ccl -> channelConstantListeners.remove(ccl.getChannelId(), ccl);
            case ConstantListener cl -> constantListeners.remove(cl);
            default -> {
            }
        }
    }

    /**
     * @param event We are taking advantage from only using three types of events in
     *              the whole bot , so we avoid less checking, for the momnent those
     *              are MessageReceivedEvent, GuildMemberLeaveEvent and
     *              MessageReactionAddEvent If you are using any other , pls modify
     *              the code or use the default one
     */
    @Override
    public void handle(@Nonnull GenericEvent event) {
        try {
            switch (event) {
                case CommandAutoCompleteInteractionEvent cacie ->
                        autocompleteExecutor.submit((ChuuRunnable) () -> autoCompleteListener.onEvent(event));
                case MessageReceivedEvent mes ->
                        handleMessageReceived(mes); // Delegate running in pool if its a valid message
                case UserContextInteractionEvent ucie -> handleUserCommand(ucie);
                case SlashCommandInteractionEvent sce -> handleSlashCommand(sce);
                case ReadyEvent re -> {
                    for (EventListener listener : otherListeners)
                        listener.onEvent(re);
                }
                case MessageReactionAddEvent react ->
                        reactionExecutor.submit((ChuuRunnable) () -> this.handleReaction(react));
                case ButtonInteractionEvent button -> this.handleReaction(button);
                case SelectMenuInteractionEvent selected ->
                        reactionExecutor.submit((ChuuRunnable) () -> this.handleReaction(selected));

                // TODO cant group then on one
                case GuildVoiceJoinEvent gvje -> this.voiceListener.onEvent(gvje);
                case GuildVoiceLeaveEvent gvle -> this.voiceListener.onEvent(gvle);
                case GuildVoiceMoveEvent gvme -> this.voiceListener.onEvent(gvme);

                case GuildMemberRemoveEvent gmre -> this.joinLeaveListener.onEvent(gmre);
                case GuildMemberJoinEvent gmje -> this.joinLeaveListener.onEvent(gmje);
                case GuildJoinEvent gje -> this.joinLeaveListener.onEvent(gje);
                default -> {
                }

            }
        } catch (Throwable throwable) {
            JDAImpl.LOG.error("One of the EventListeners had an uncaught exception", throwable);
        }
    }

    private void handleSlashCommand(SlashCommandInteractionEvent sce) {
        if (!isReady) {
            return;
        }
        MyCommand<? extends CommandParameters> myCommand = parseCommand(sce);
        if (myCommand == null) {
            Chuu.getLogger().warn("Not found command {} ", sce.getCommandPath());
            return;
        }
        ContextSlashReceived ctx = new ContextSlashReceived(sce);
        if (!Chuu.getMessageDisablingService().isMessageAllowed(myCommand, ctx)) {
            if (Chuu.getMessageDisablingService().doResponse(ctx))
                sce.reply("This command is disabled in this channel.").queue();
            else {
                sce.reply("This command is disabled in this channel.").setEphemeral(true).queue();
            }
            return;
        }
        myCommand.onSlashCommandReceived(sce);
    }

    public MyCommand<? extends CommandParameters> parseCommand(CommandInteraction sce) {
        MyCommand<? extends CommandParameters> myCommand;
        if (sce.getSubcommandName() == null) {
            myCommand = commandListeners.get(sce.getName().toLowerCase(Locale.ROOT));
        } else {
            myCommand = slashVariants.get(sce.getCommandPath());
            if (myCommand == null) {
                myCommand = commandListeners.get(sce.getSubcommandName());
            }
        }
        return myCommand;
    }

    public MyCommand<? extends CommandParameters> parseCommand(CommandAutoCompleteInteractionEvent sce) {
        MyCommand<? extends CommandParameters> myCommand;
        if (sce.getSubcommandName() == null) {
            myCommand = commandListeners.get(sce.getName().toLowerCase(Locale.ROOT));
        } else {
            myCommand = slashVariants.get(sce.getCommandPath());
            if (myCommand == null) {
                myCommand = commandListeners.get(sce.getSubcommandName());
            }
        }
        return myCommand;
    }


    private void handleUserCommand(UserContextInteractionEvent ucie) {
        if (!isReady) {
            return;
        }

        MyCommand<? extends CommandParameters> myCommand = parseCommand(ucie);

        myCommand.onUserCommandReceived(ucie);
    }

    private void handleMessageReceived(MessageReceivedEvent mes) {
        if (mes.getAuthor().isBot()) {
            return;
        }
        ContextMessageReceived ctx = new ContextMessageReceived(mes);
        Character correspondingPrefix = Chuu.prefixService.getCorrespondingPrefix(ctx);
        String contentRaw = mes.getMessage().getContentRaw();
        int length = contentRaw.length();
        if ((length <= 1)) {
            return;
        }
        if (mes.isFromGuild() && contentRaw.charAt(0) != correspondingPrefix) {
            if (mes.getMessage().getMentions().isMentioned(mes.getJDA().getSelfUser(),
                    Message.MentionType.USER)
                && mes.getMessage().getType() != MessageType.INLINE_REPLY) {
                if (mes.getMessage().getContentRaw().contains("prefix")) {
                    mes.getChannel().sendMessage("My prefix is: `" + correspondingPrefix + "`").queue();
                }
            }
            return;
        }
        Map<Long, RateLimiter> ratelimited = Chuu.getRatelimited();
        RateLimiter rateLimiter = ratelimited.get(mes.getAuthor().getIdLong());
        if (rateLimiter != null) {
            if (!rateLimiter.tryAcquire()) {
                mes.getChannel().sendMessage("You have been rate limited, try again later.").queue();
                return;
            }
        }
        String substring = StringUtils.WORD_SPLITTER.split(CharBuffer.wrap(contentRaw, 1, length))[0];
        MyCommand<?> myCommand = commandListeners.get(substring.toLowerCase());
        if (myCommand != null) {
            if (!Chuu.getMessageDisablingService().isMessageAllowed(myCommand, ctx)) {
                if (Chuu.getMessageDisablingService().doResponse(ctx))
                    mes.getChannel().sendMessage("This command is disabled in this channel.").queue();
                return;
            }
            myCommand.onMessageReceived(mes);
        }
    }

    @Nonnull
    @Override
    public List<Object> getRegisteredListeners() {
        return Stream.<Object>concat(
                commandListeners.values().stream().distinct(),
                Stream.concat(
                        reactionaries.keySet().stream(),
                        otherListeners.stream()
                )).toList();
    }

    public void refreshReactionay(ReactionListener reactionListener, long seconds) {
        Map<ReactionListener, ScheduledFuture<?>> channelReactionaires = this.reactionaries.get(reactionListener.channelId);
        if (channelReactionaires != null) {
            ScheduledFuture<?> scheduledFuture = channelReactionaires.get(reactionListener);
            if (scheduledFuture != null && scheduledFuture.cancel(false)) {
                channelReactionaires.put(reactionListener, Chuu.getScheduledService().addSchedule((() -> {
                    channelReactionaires.remove(reactionListener);
                    if (channelReactionaires.isEmpty()) {
                        this.reactionaries.remove(reactionListener.channelId);
                    }
                    otherListeners.remove(reactionListener);
                    reactionListener.dispose();
                }), seconds, TimeUnit.SECONDS));
            }
        }
    }

    public void addSlashVariants(Map<String, MyCommand<?>> slashVariants) {
        this.slashVariants.putAll(slashVariants);
    }

}


