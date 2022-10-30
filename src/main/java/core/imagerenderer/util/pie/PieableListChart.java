package core.imagerenderer.util.pie;

import core.apis.last.entities.chartentities.UrlCapsule;
import core.imagerenderer.ChartLine;
import core.imagerenderer.util.bubble.StringFrequency;
import core.parsers.Parser;
import core.parsers.params.ChartParameters;
import org.knowm.xchart.PieChart;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class PieableListChart extends OptionalPie implements IPieableList<UrlCapsule, ChartParameters> {


    public PieableListChart(Parser<?> parser) {
        super(parser);
    }


    @Override
    public PieChart fillPie(PieChart chart, ChartParameters params, List<UrlCapsule> data) {
        int total = data.stream().mapToInt(UrlCapsule::getChartValue).sum();
        int breakpoint = (int) (0.75 * total);
        AtomicInteger counter = new AtomicInteger(0);
        AtomicInteger acceptedCount = new AtomicInteger(0);
        IPieableList.fillListedSeries(chart,
                x -> x.getLines().stream().map(ChartLine::getLine).collect(Collectors.joining(" - ")),
                UrlCapsule::getChartValue,
                data);

        return chart;
    }

    @Override
    public List<StringFrequency> obtainFrequencies(List<UrlCapsule> data, ChartParameters params) {
        return data.stream().map(t -> new StringFrequency(t.getLines().stream().map(ChartLine::getLine).collect(Collectors.joining(" - ")), t.getChartValue())).toList();

    }
}

