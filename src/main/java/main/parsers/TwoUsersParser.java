package main.parsers;

import dao.DaoImplementation;
import main.exceptions.InstanceNotFoundException;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

public class TwoUsersParser extends DaoParser {
	public TwoUsersParser(DaoImplementation dao) {
		super(dao);
	}

	public String[] parseLogic(MessageReceivedEvent e, String[] subMessage) throws InstanceNotFoundException {
		String[] message = getSubMessage(e.getMessage());
		if (message.length == 0) {
			sendError(getErrorMessage(5), e);
			return null;
		}

		String[] userList = {"", ""};
		if (message.length == 1) {
			userList[1] = message[0];
			userList[0] = dao.findLastFMData(e.getAuthor().getIdLong()).getName();


		} else {
			userList[0] = message[0];
			userList[1] = message[1];
		}

		java.util.List<String> lastFmNames;
		// Si userList contains @ -> user
		try {
			java.util.List<User> list = e.getMessage().getMentionedUsers();
			lastFmNames = Arrays.stream(userList)
					.map(s -> lambda(s, list))
					.collect(Collectors.toList());
			lastFmNames.forEach(System.out::println);
		} catch (RuntimeException ex) {
			throw new InstanceNotFoundException(Long.parseLong(ex.getMessage()));
		}
		return new String[]{lastFmNames.get(0), lastFmNames.get(1)};
	}

	private String lambda(String s, java.util.List<User> list) {
		if (s.startsWith("<@")) {
			User result = this.findUsername(s, list);
			if (result != null) {
				try {
					return dao.findLastFMData(result.getIdLong()).getName();
				} catch (InstanceNotFoundException e) {
					throw new RuntimeException(String.valueOf(e.getDiscordId()));
				}
			}
		}
		return s;
	}

	private User findUsername(String name, java.util.List<User> userList) {
		Optional<User> match = userList.stream().
				filter(user -> {
					String nameNoDigits = name.replaceAll("\\D+", "");

					long a = Long.parseLong(nameNoDigits);
					return (user.getIdLong() == a);
				})
				.findFirst();
		return match.orElse(null);
	}

	@Override
	public String getUsageLogic(String commandName) {
		return "**" + commandName + " *userName* *userName***\n" +
				"\tIf user2 is missing it gets replaced by Author user\n";

	}

	@Override
	public void setUpErrorMessages() {
		super.setUpErrorMessages();
		errorMessages.put(5, "Need at least one username");
		errorMessages.put(-1, "Mentioned user is not registered");


	}
}
