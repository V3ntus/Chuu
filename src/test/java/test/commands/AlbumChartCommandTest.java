package test.commands;

import org.junit.jupiter.api.Test;
import test.commands.parsers.NullReturnParsersTest;
import test.commands.parsers.TestAssertion;
import test.commands.utils.CommandTest;
import test.commands.utils.ImageUtils;
import test.commands.utils.ImageUtils2;
import test.runner.AssertionRunner;

import java.util.List;

public class AlbumChartCommandTest extends CommandTest {

    @Override
    public String giveCommandName() {
        return "!chart";
    }

    @Test
    @Override
    public void nullParserReturned() {
        NullReturnParsersTest.chartParser(COMMAND_ALIAS);
    }


    @Test
    public void ChartNormalTest() {

        AssertionRunner.fromMessage(COMMAND_ALIAS + " a 1x1 ")
                .assertion(List.of(
                        TestAssertion.typing(),
                        TestAssertion.image(
                                (e) -> ImageUtils2.testImage(e, 300, 300, ".png"),
                                "Then I should receive a 300x300 png image"
                        )));

    }

    @Test
    public void ChartBigTest() {

        AssertionRunner.fromMessage(COMMAND_ALIAS + " a 20x11")
                .assertion(
                        List.of(
                                TestAssertion.typing(),
                                TestAssertion.image(
                                        (e) -> ImageUtils2.testImage(e, 1650, 3000, ".jpg"),
                                        "Then I should receive a 900x1500 jpg image"
                                )));
    }

    @Test
    public void ChartOptionalsTest() {
        ImageUtils.testImage(COMMAND_ALIAS + " a 1x1 --notitles --plays", 300, 300, ".png");
    }

    @Test
    public void ChartBigWithWarningTest() {
        ImageUtils
                .testImageWithPreWarning(COMMAND_ALIAS + " a 101x1", "Going to take a while", true, 300, 300 * 101, ".png", ".jpg");
    }


}
