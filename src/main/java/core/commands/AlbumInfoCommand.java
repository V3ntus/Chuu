package core.commands;

import core.exceptions.InstanceNotFoundException;
import core.exceptions.LastFmException;
import core.parsers.ArtistAlbumParser;
import dao.ChuuService;
import dao.entities.FullAlbumEntityExtended;
import dao.entities.LastFMData;
import dao.entities.ScrobbledArtist;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.MessageBuilder;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import java.util.List;
import java.util.stream.Collectors;

public class AlbumInfoCommand extends AlbumPlaysCommand {

    public AlbumInfoCommand(ChuuService dao) {
        super(dao);
        this.parser = new ArtistAlbumParser(dao, lastFM);
    }

    @Override
    public String getDescription() {
        return "Information about an album";
    }

    @Override
    public List<String> getAliases() {
        return List.of("albuminfo", "abi");
    }

    @Override
    public String getName() {
        return "Album Info";
    }

    @Override
    void doSomethingWithAlbumArtist(ScrobbledArtist artist, String album, MessageReceivedEvent e, long who) throws InstanceNotFoundException, LastFmException {
        LastFMData lastFMData = getService().findLastFMData(who);
        FullAlbumEntityExtended albumSummary = lastFM.getAlbumSummary(lastFMData.getName(), artist.getArtist(), album);
        String username = getUserStringConsideringGuildOrNot(e, who, lastFMData.getName());

        EmbedBuilder embedBuilder = new EmbedBuilder();
        String tagsField = albumSummary.getTagList().isEmpty()
                ? ""
                : albumSummary.getTagList().stream()
                .map(tag -> "[" + tag + "](" + CommandUtil.getLastFmTagUrl(tag) + ")")
                .collect(Collectors.joining(" - "));
        StringBuilder trackList = new StringBuilder();

        albumSummary.getTrackList().forEach(x ->
                trackList.append(x.getPosition()).append(". ")
                        .append(x.getName())
                        .append(". ").append(
                        String
                                .format("%02d:%02d", x.getDuration() / 60, x.getDuration() % 60))
                        .append("\n"));
        MessageBuilder messageBuilder = new MessageBuilder();
        embedBuilder.setTitle(albumSummary.getAlbum(), CommandUtil.getLastFmArtistAlbumUrl(albumSummary.getArtist(), albumSummary.getAlbum()))
                .addField("Artist:", "[" + albumSummary.getArtist() + "](" + CommandUtil.getLastFmArtistUrl(albumSummary.getArtist()) + ")", false)
                .addField(username + "'s plays:", String.valueOf(albumSummary.getTotalPlayNumber()), true)
                .addField("Listeners:", String.valueOf(albumSummary.getListeners()), true)
                .addField("Scrobbles:", String.valueOf(albumSummary.getTotalscrobbles()), true)
                .addField("Tags:", tagsField, false);
        if (!albumSummary.getTrackList().isEmpty()) {
            embedBuilder.addField("Track List:", trackList.toString(), false)
                    .addField("Total Duration:",
                            (String.format("%02d:%02d minutes", albumSummary.getTotalDuration() / 60, albumSummary.getTotalDuration() % 60))
                            , true);
        }
        embedBuilder.setImage(albumSummary.getAlbumUrl().isBlank() ? null : albumSummary.getAlbumUrl())
                .setColor(CommandUtil.randomColor())
                .setThumbnail(artist.getUrl());
        messageBuilder.setEmbed(embedBuilder.build()).sendTo(e.getChannel()).queue();

    }
}
