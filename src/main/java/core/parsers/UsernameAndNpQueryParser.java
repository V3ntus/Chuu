package core.parsers;

import core.apis.last.ConcurrentLastFM;
import core.exceptions.LastFmException;
import core.parsers.explanation.QuerySearchExplanation;
import core.parsers.explanation.StrictUserExplanation;
import core.parsers.explanation.util.Explanation;
import core.parsers.params.ExtraParameters;
import core.parsers.params.WordParameter;
import core.services.NPService;
import dao.ChuuService;
import dao.entities.LastFMData;
import dao.entities.NowPlayingArtist;
import dao.exceptions.InstanceNotFoundException;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import java.util.List;
import java.util.function.Function;

public class UsernameAndNpQueryParser extends DaoParser<ExtraParameters<WordParameter, User>> {

    private final ConcurrentLastFM lastFM;
    private final Function<NowPlayingArtist, String> mapper;

    public UsernameAndNpQueryParser(ChuuService dao, ConcurrentLastFM lastFM) {
        this(dao, lastFM, (np) -> np.artistName() + " " + np.songName());
    }

    public UsernameAndNpQueryParser(ChuuService dao, ConcurrentLastFM lastFM, Function<NowPlayingArtist, String> mapper) {
        super(dao);
        this.lastFM = lastFM;
        this.mapper = mapper;
    }

    @Override
    protected ExtraParameters<WordParameter, User> parseLogic(MessageReceivedEvent e, String[] words) throws InstanceNotFoundException, LastFmException {
        ParserAux parserAux = new ParserAux(words);
        User oneUser = parserAux.getOneUser(e, dao);
        words = parserAux.getMessage();

        if (words.length == 0) {
            NowPlayingArtist np;
            LastFMData lastFMData = dao.findLastFMData(oneUser.getIdLong());
            np = new NPService(lastFM, lastFMData).getNowPlaying();
            return new ExtraParameters<>(e, new WordParameter(e, mapper.apply(np)), oneUser);
        } else {
            return new ExtraParameters<>(e, new WordParameter(e, String.join(" ", words)), oneUser);
        }
    }

    @Override
    public List<Explanation> getUsages() {
        return List.of(new QuerySearchExplanation(), new StrictUserExplanation());
    }


}
