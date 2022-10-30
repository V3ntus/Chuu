package core.commands.abstracts;

import core.Chuu;
import core.apis.last.ConcurrentLastFM;
import core.apis.last.LastFMFactory;
import core.apis.last.TokenExceptionHandler;
import core.commands.Context;
import core.commands.ContextMessageReceived;
import core.commands.ContextSlashReceived;
import core.commands.ContextUserCommandReceived;
import core.commands.utils.ChuuEmbedBuilder;
import core.commands.utils.CommandCategory;
import core.commands.utils.CommandUtil;
import core.exceptions.*;
import core.imagerenderer.ChartQuality;
import core.parsers.Parser;
import core.parsers.params.CommandParameters;
import core.services.HeavyCommandRateLimiter;
import core.util.ServiceView;
import dao.ChuuService;
import dao.exceptions.InstanceNotFoundException;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.GenericEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.UserContextInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.hooks.EventListener;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.RestAction;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

public abstract class MyCommand<T extends CommandParameters> implements EventListener {
    public final ConcurrentLastFM lastFM;
    protected final ChuuService db;
    protected final Parser<T> parser;
    private final CommandCategory category;
    private final boolean isLongRunningCommand;
    public boolean respondInPrivate = true;
    public boolean ephemeral = false;
    public boolean canAnswerFast = false;
    public int order = Integer.MAX_VALUE;

    protected MyCommand(ServiceView serviceview, boolean isLongRunningCommand) {
        this.isLongRunningCommand = isLongRunningCommand;
        lastFM = LastFMFactory.getNewInstance();
        this.db = serviceview.getView(isLongRunningCommand, this);
        this.parser = initParser();
        this.category = initCategory();
    }

    protected MyCommand(ServiceView serviceview) {
        this(serviceview, false);
    }

    protected static void logCommand(ChuuService service, Context e, MyCommand<?> command, long exectTime, boolean success, boolean isNormalCommand) {
        CommandUtil.runLog(
                () -> service.logCommand(e.getAuthor().getIdLong(), e.isFromGuild() ? e.getGuild().getIdLong() : null, command.getName(), exectTime, Instant.now(), success, isNormalCommand));
    }

    protected EnumSet<Permission> initRequiredPerms() {
        return EnumSet.noneOf(Permission.class);
    }

    protected abstract CommandCategory initCategory();

    public abstract Parser<T> initParser();

    public final Parser<T> getParser() {
        return parser;
    }

    public abstract String getDescription();

    public String getUsageInstructions() {
        return parser.getUsage(getAliases().get(0));
    }

    public abstract List<String> getAliases();

    public String slashName() {
        return getAliases().get(0);
    }

    @Override
    public void onEvent(@org.jetbrains.annotations.NotNull GenericEvent event) {
        onMessageReceived(((MessageReceivedEvent) event));
    }

    public void onSlashCommandReceived(@Nonnull SlashCommandInteractionEvent event) {
        if (!event.isFromGuild() && !respondInPrivate) {
            event.reply("This command only works in a server").queue();
            return;
        }
        if (!canAnswerFast) {
            event.deferReply(ephemeral).queue();
            if (ephemeral) {
                event.getHook().setEphemeral(true);
            }
        }
        ContextSlashReceived ctx = new ContextSlashReceived(event);
        doCommand(ctx);

    }

    public void onUserCommandReceived(UserContextInteractionEvent event) {
        if (!event.isFromGuild() && !respondInPrivate) {
            event.reply("This command only works in a server").queue();
            return;
        }
        if (!canAnswerFast) {
            event.deferReply(ephemeral).queue();
            if (ephemeral) {
                event.getHook().setEphemeral(true);
            }
        }
        ContextUserCommandReceived ctx = new ContextUserCommandReceived(event);
        doCommand(ctx);
    }

    /**
     * @param e Because we are using the {@link core.commands.CustomInterfacedEventManager CustomInterfacedEventManager} we know that this is the only OnMessageReceived event handled so we can skip the cheks
     */
    public void onMessageReceived(MessageReceivedEvent e) {
        ContextMessageReceived ctx = new ContextMessageReceived(e);
        if (Chuu.doTyping) {
            e.getChannel().sendTyping().queue(unused -> {
            }, throwable -> {
            });
        }
        if (!e.isFromGuild() && !respondInPrivate) {
            sendMessageQueue(ctx, "This command only works in a server");
            return;
        }
        doCommand(ctx);
    }

    private void doCommand(Context ctx) {
        if (isLongRunningCommand) {
            HeavyCommandRateLimiter.RateLimited rateLimited = HeavyCommandRateLimiter.checkRateLimit(ctx);
            switch (rateLimited) {
                case SERVER -> {
                    sendMessageQueue(ctx, "This command takes a while to execute so it cannot be executed in this server more than 15 times per 10 minutes.\n" + "Usable again in: " + rateLimited.remainingTime(ctx));
                    return;
                }
                case GLOBAL -> {
                    sendMessageQueue(ctx, "This command takes a while to execute so now is on a global cooldown.\n" + "Usable again in: " + rateLimited.remainingTime(ctx));
                    return;
                }
            }
        }
        measureTime(ctx);
    }

    public final CommandCategory getCategory() {
        return category;
    }

    public abstract String getName();

    protected void measureTime(Context e) {
        try {
            long startTime = System.nanoTime();
            boolean sucess = handleCommand(e);
            long timeElapsed = System.nanoTime() - startTime;
            logCommand(db, e, this, timeElapsed, sucess, e instanceof ContextMessageReceived);
        } catch (Exception ex) {
            Chuu.getLogger().warn(ex.getMessage(), ex);
        }
    }


    protected boolean handleCommand(Context e) {
        boolean success = false;
        try {
            T params = parser.parse(e);
            if (params != null) {
                onCommand(e, params);
            }
            success = true;
        } catch (LastFMNoPlaysException ex) {
            String username = ex.getUsername();
            if (e.isFromGuild()) {
                long idLong = e.getGuild().getIdLong();
                try {
                    long discordIdFromLastfm = db.getDiscordIdFromLastfm(ex.getUsername(), idLong);
                    username = getUserString(e, discordIdFromLastfm, username);
                } catch (InstanceNotFoundException ignored) {
                    // We left the inital value
                }
            } else {
                username = CommandUtil.escapeMarkdown(e.getAuthor().getName());
            }

            String init = "hasn't played anything" + ex.getTimeFrameEnum().toLowerCase();

            parser.sendError(username + " " + init, e);
        } catch (LastFmEntityNotFoundException ex) {
            parser.sendError(ex.toMessage(), e);
        } catch (UnknownLastFmException ex) {
            parser.sendError(new TokenExceptionHandler(ex, db).handle(), e);
            Chuu.getLogger().warn(ex.getMessage(), ex);
            Chuu.getLogger().warn(String.valueOf(ex.getCode()));
        } catch (InstanceNotFoundException ex) {

            String instanceNotFoundTemplate = InstanceNotFoundException.getInstanceNotFoundTemplate();

            String s = instanceNotFoundTemplate.replaceFirst("user_to_be_used_yep_yep", Matcher.quoteReplacement(getUserString(e, ex.getDiscordId(), ex.getLastFMName())));
            s = s.replaceFirst("prefix_to_be_used_yep_yep", Matcher.quoteReplacement(String.valueOf(CommandUtil.getMessagePrefix(e))));

            MessageEmbed build = new ChuuEmbedBuilder(e).setDescription(s).build();
            if (e instanceof ContextMessageReceived mes) {
                mes.e().getChannel().sendMessageEmbeds(build).reference(mes.e().getMessage()).queue();

            } else {
                e.sendMessage(build).queue();
            }

        } catch (LastFMConnectionException ex) {
            parser.sendError("Last.fm is not working well or the bot might be overloaded :(", e);
        } catch (Exception ex) {
            if (ex instanceof LastFMServiceException && ex.getMessage().equals("500")) {
                parser.sendError("Last.fm is not working well atm :(", e);
                return false;
            }
            if (ex instanceof InsufficientPermissionException ipe) {
                if (ipe.getPermission() != Permission.MESSAGE_SEND) {
                    parser.sendError("Couldn't execute the command because im missing the permission: **" + ipe.getPermission().getName() + "**", e);
                }
                return false;
            }
            parser.sendError("Internal Chuu Error", e);

            Chuu.getLogger().warn("Internal Chuu error happened handling command {} | {} | in {}", getName(), e.toLog(), e.isFromGuild() ? e.getGuild().getName() : "in dms", ex);
        }
        if (e.isFromGuild()) deleteMessage(e, e.getGuild().getIdLong());
        return success;
    }

    private void deleteMessage(Context e, long guildId) {
        if (e instanceof ContextMessageReceived mes && Chuu.getMessageDeletionService().isMarked(guildId) && e.getGuild().getSelfMember().hasPermission(Permission.MESSAGE_MANAGE)) {
            mes.e().getMessage().delete().queueAfter(5, TimeUnit.SECONDS, (vo) -> {
            }, (throwable) -> {
                if (throwable instanceof ErrorResponseException) {
                    ErrorResponse errorResponse = ((ErrorResponseException) throwable).getErrorResponse();
                    if (errorResponse.equals(ErrorResponse.MISSING_PERMISSIONS)) {
                        Chuu.getMessageDeletionService().removeServerToDelete(guildId);
                        sendMessageQueue(e, "Can't delete messages anymore so from now one won't delete any more message");
                    }
                }
            });
        }
    }

    public abstract void onCommand(Context e, @Nonnull T params) throws LastFmException, InstanceNotFoundException;

    protected final String getUserString(Context e, long discordId) {
        return getUserString(e, discordId, "Unknown");
    }

    protected String getUserString(Context e, long discordId, String replacement) {
        try {
            return CommandUtil.getUserInfoEscaped(e, discordId).username();
        } catch (Exception ex) {
            return replacement != null ? replacement : "Unknown";
        }

    }

    protected void sendMessageQueue(Context e, String message) {
        e.sendMessageQueue(message);
    }

    @CheckReturnValue
    protected RestAction<Message> sendMessage(Context e, String message) {
        return e.sendMessage(message);
    }

    protected final void sendImage(BufferedImage image, Context e) {
        e.sendImage(image);
    }

    protected final void sendImage(BufferedImage image, Context e, ChartQuality chartQuality) {
        e.sendImage(image, chartQuality);
    }

    protected final void sendImage(BufferedImage image, Context e, ChartQuality chartQuality, EmbedBuilder embedBuilder) {
        e.sendImage(image, chartQuality, embedBuilder);
    }

}
