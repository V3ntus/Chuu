package main.Commands;

import DAO.DaoImplementation;
import DAO.Entities.UrlCapsule;
import main.Exceptions.LastFmEntityNotFoundException;
import main.Exceptions.LastFmException;
import main.Exceptions.ParseException;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.MessageBuilder;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class TopCommand extends ChartCommand {
	public TopCommand(DaoImplementation dao) {
		super(dao);
	}


	@Override
	public List<String> getAliases() {
		return Collections.singletonList("!top");
	}

	@Override
	public String getDescription() {
		return ("Your all time top albums!");
	}

	@Override
	public String getName() {
		return "Top Albums Chart";
	}

	@Override
	public List<String> getUsageInstructions() {
		return Collections.singletonList
				("!top *username\n\tIf username is not specified defaults to authors account \n\n");

	}

	@Override
	public String[] parse(MessageReceivedEvent e) throws ParseException {
		String[] message = getSubMessage(e.getMessage());
		boolean flag = true;
		String[] message1 = Arrays.stream(message).filter(s -> !s.equals("--artist")).toArray(String[]::new);
		if (message1.length != message.length) {
			message = message1;
			flag = false;
		}
		return new String[]{getLastFmUsername1input(message, e.getAuthor().getIdLong(), e), Boolean.toString(flag)};
	}

	@Override
	public void errorMessage(MessageReceivedEvent e, int code, String cause) {
		String base = " An Error Happened while processing " + e.getAuthor().getName() + "'s request:\n";
		if (code == 0) {
			userNotOnDB(e, code);
			return;
		} else if (code == 3) {
			sendMessage(e, base + cause + " is not a real lastFM username");
			return;
		}
		sendMessage(e, base + "Internal Server Error, Try again later");

	}

	@Override
	public void threadableCode(MessageReceivedEvent e) {
		String[] message;
		MessageBuilder mes = new MessageBuilder();
		EmbedBuilder embed = new EmbedBuilder();
		try {
			message = parse(e);
		} catch (ParseException e1) {
			errorMessage(e, 0, e1.getMessage());
			return;
		}
		boolean isAlbum = Boolean.parseBoolean(message[1]);
		embed.setImage("attachment://cat.png") // we specify this in sendFile as "cat.png"
				.setDescription(e.getAuthor().getName() + " 's most listened " + (isAlbum ? "albums" : "artist"));
		mes.setEmbed(embed.build());
		try {
			BlockingQueue<UrlCapsule> queue = new ArrayBlockingQueue<>(25);
			lastFM.getUserList(message[0], "overall", 5, 5, isAlbum, queue);
			generateImage(queue, 5, 5, e);
		} catch (LastFmEntityNotFoundException e1) {
			errorMessage(e, 3, e1.getMessage());
		} catch (LastFmException ex) {
			errorMessage(e, 1, ex.getMessage());


		}
	}
}
