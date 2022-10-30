package core.imagerenderer;

import core.imagerenderer.util.fitter.StringFitter;
import core.imagerenderer.util.fitter.StringFitterBuilder;
import dao.entities.BillboardEntity;
import org.beryx.awt.color.ColorFactory;
import org.imgscalr.Scalr;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class HotMaker {
    private final static int X_MAX = 900;
    private final static int BOX_SIZE = 125;
    private static final StringFitter subTitleFitter = new StringFitterBuilder(18f, X_MAX - BOX_SIZE * 2).setMinSize(18).build();
    private static final StringFitter titleFitter = new StringFitterBuilder(22f, X_MAX).setMinSize(22).build();

    private final static Color fontColor = ColorFactory.valueOf("#344072");
    private final static Color secondaryColor = ColorFactory.valueOf("#8086a0");
    private final static Color newColor = ColorFactory.valueOf("#ffaa36");


    private final static String metric1 = "Last Week";
    private final static String metric2 = "Peak";
    private final static String metric3 = "Streak";


    private final static Font normalFont = GraphicUtils.chooseFont(metric1);
    private static final StringFitter serverFitter = new StringFitterBuilder(14, 450).setBaseFont(normalFont.deriveFont(14f).deriveFont(Font.BOLD)).setMinSize(14).build();
    private static BufferedImage upboats;
    private static BufferedImage downvote;

    static {
        try (InputStream down = BandRendered.class.getResourceAsStream("/images/downvote.png");
             InputStream upvote = BandRendered.class.getResourceAsStream("/images/upvote.png")) {
            if (down == null) {
                downvote = null;
            } else {
                downvote = Scalr.resize(ImageIO.read(down), Scalr.Method.QUALITY, 15, Scalr.OP_ANTIALIAS);

            }
            if (upvote == null) {
                upboats = null;
            } else {
                upboats = Scalr.resize(ImageIO.read(upvote), Scalr.Method.QUALITY, 15, Scalr.OP_ANTIALIAS);
            }
        } catch (IOException e) {
            downvote = null;
            upboats = null;
        }
    }

    private HotMaker() {
    }


    public static BufferedImage doHotMaker(String title, String subtitle, List<BillboardEntity> hots, boolean doListeners, int itemCount, @Nullable BufferedImage logo) {
        int Y_MAX = 77 + itemCount * BOX_SIZE;
        BufferedImage canvas = new BufferedImage(X_MAX, Y_MAX, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        GraphicUtils.setQuality(g);
        g.setColor(Color.WHITE);

        g.fillRect(0, 0, X_MAX, Y_MAX);
        g.setColor(fontColor);
        g.setFont(normalFont.deriveFont(14f).deriveFont(Font.BOLD));
        String metric4 = doListeners ? "Listeners" : "Scrobbles";
        String str = metric1 + "    " + metric2 + "     " + metric3 + "    " + metric4;
        int width = g.getFontMetrics().stringWidth(str);
        int height = (int) g.getFontMetrics().getStringBounds(str, g).getHeight();

        int startMetrics = X_MAX - width - BOX_SIZE - 50;
        int widthMetrics1 = g.getFontMetrics().stringWidth(metric1);
        int startMetric2 = startMetrics + g.getFontMetrics().stringWidth(metric1 + "    ");
        int widthMetrics2 = g.getFontMetrics().stringWidth(metric2);
        int startMetric3 = startMetric2 + g.getFontMetrics().stringWidth(metric2 + "    ");
        int widthMetrics3 = g.getFontMetrics().stringWidth(metric3);
        int startMetrics4 = startMetric3 + g.getFontMetrics().stringWidth(metric3 + "    ");
        int widthMetrics4 = g.getFontMetrics().stringWidth(metric4);

        g.drawString(str, startMetrics, 60 - height);
        int yCounter = 60 + height;
        int x = (int) (X_MAX * .15);
        if (logo != null) {
            logo = Scalr.resize(logo, Scalr.Method.QUALITY, Scalr.Mode.FIT_EXACT, 50, Scalr.OP_ANTIALIAS);
            x = (int) (((X_MAX * .15) - logo.getWidth()) / 2);
            g.drawImage(logo, x, (yCounter - 50) / 2, null);
            x += logo.getWidth();
        }
        StringFitter.FontMetadata titleMetadata = serverFitter.getFontMetadata(g, title);

        g.drawString(titleMetadata.atrribute().getIterator(), (x + 15), yCounter - 50);
        g.setFont(g.getFont().deriveFont(12f));
        g.drawString(subtitle, (x + 15), yCounter - 25);

        for (int i = 0, hotsSize = hots.size(); i < hotsSize && (i < itemCount); i++) {
            BillboardEntity hot = hots.get(i);
            g.setColor(secondaryColor);
            g.drawRect(0, yCounter, X_MAX - 2, BOX_SIZE);
            int innerYCounter = yCounter;
            g.setFont(g.getFont().deriveFont(42f).deriveFont(Font.BOLD));
            Rectangle2D stringBounds = g.getFontMetrics().getStringBounds(String.valueOf(hot.getPosition()), g);
            int rankX = (int) (((X_MAX * .15) - stringBounds.getWidth()) / 2);
            int rankY = innerYCounter + (int) ((BOX_SIZE - stringBounds.getHeight() + 40) / 2);
            int FIRST_COLUMN_MIDDLE = (int) (rankX + (stringBounds.getWidth() / 2));
            g.setColor(fontColor);
            g.drawString(String.valueOf(hot.getPosition()), rankX, rankY);
            int textStart = (int) (rankY - stringBounds.getHeight());
            innerYCounter = (int) (rankY + stringBounds.getHeight() * 0.5);
            int previousWeek = hot.getPreviousWeek();
            if (previousWeek == 0) {
                String numberSubtitle = hot.getPeak() < hot.getPosition() ? "Re-Enter" : "New";
                g.setColor(newColor);
                g.setFont(g.getFont().deriveFont(14f).deriveFont(Font.BOLD));
                stringBounds = g.getFontMetrics().getStringBounds(numberSubtitle, g);
                g.drawString(numberSubtitle, (int) (FIRST_COLUMN_MIDDLE - stringBounds.getWidth() / 2), innerYCounter + 10);
            } else {
                if (previousWeek > hot.getPosition()) {
                    g.drawImage(upboats, FIRST_COLUMN_MIDDLE - upboats.getWidth() / 2, innerYCounter - 2, null);
                } else if (previousWeek < hot.getPosition()) {
                    g.drawImage(downvote, FIRST_COLUMN_MIDDLE - upboats.getWidth() / 2, innerYCounter - 2, null);
                } else {
                    g.setColor(secondaryColor);
                    g.setFont(g.getFont().deriveFont(14f).deriveFont(Font.BOLD));
                    stringBounds = g.getFontMetrics().getStringBounds("-", g);
                    g.drawString("-", (int) (FIRST_COLUMN_MIDDLE - stringBounds.getWidth() / 2), innerYCounter + 10);
                }
            }

            int xCounter = (int) (X_MAX * 0.15);
            StringFitter.FontMetadata fontMetadata = titleFitter
                    .getFontMetadata(g, hot.getName());

            g.setColor(fontColor);
            int trackHeight = (int) fontMetadata.bounds().getHeight();
            innerYCounter = (int) (textStart + trackHeight * 1.5);

            g.drawString(fontMetadata.atrribute().getIterator(), xCounter, innerYCounter);
            innerYCounter += trackHeight;

            g.setColor(secondaryColor);
            if (hot.getArtist() != null) {
                StringFitter.FontMetadata artistMetadata = subTitleFitter
                        .getFontMetadata(g, hot.getArtist());

                g.drawString(artistMetadata.atrribute().getIterator(), xCounter, innerYCounter);
            }

            g.setFont(g.getFont().deriveFont(Font.PLAIN, 14));
            doMetric(g, widthMetrics1, startMetrics, innerYCounter, previousWeek, String.valueOf(previousWeek));

            int peak = hot.getPeak();
            doMetric(g, widthMetrics2, startMetric2, innerYCounter, peak, String.valueOf(peak));

            int streak = hot.getStreak();
            doMetric(g, widthMetrics3, startMetric3, innerYCounter, streak, String.valueOf(streak));


            long metrics4 = hot.getListeners();
            doMetric(g, widthMetrics4, startMetrics4, innerYCounter, metrics4, String.valueOf(metrics4));


            BufferedImage imageFromUrl = GraphicUtils.getImageFromUrl(hot.getUrl(), GraphicUtils.noArtistImage);

            imageFromUrl = GraphicUtils.resizeOrCrop(imageFromUrl, BOX_SIZE);
            g.drawLine(X_MAX - imageFromUrl.getWidth() - 2, yCounter, X_MAX - imageFromUrl.getWidth() - 2, yCounter + BOX_SIZE);

            g.drawImage(imageFromUrl, X_MAX - imageFromUrl.getWidth() - 1, yCounter + 1, null);
            g.drawLine(X_MAX - 1, yCounter, X_MAX - 1, yCounter + BOX_SIZE);


            if (hot.getArtist() != null) {
                innerYCounter += g.getFontMetrics(fontMetadata.maxFont()).getStringBounds(hot.getArtist(), g).getHeight();
            } else {
                innerYCounter += g.getFontMetrics(fontMetadata.maxFont()).getStringBounds(hot.getName(), g).getHeight();
            }
            String variation;
            if (previousWeek == 0) {
                variation = "-";
            } else {
                int diff = previousWeek - hot.getPosition();
                if (diff == 0) {
                    variation = "-";
                } else {
                    variation = String.valueOf(Math.abs(diff));
                    variation = diff > 0 ? "+" + variation : "-" + variation;
                }
            }


            Font font2 = GraphicUtils.chooseFont(variation);
            g.setFont(font2.deriveFont(14f));
            g.drawString(variation, xCounter, innerYCounter);

            yCounter += BOX_SIZE;
        }
        return canvas;

    }

    private static void doMetric(Graphics2D g, int widthMetrics4, int startMetrics4, int innerYCounter,
                                 long metrics4, String s) {
        String metric4Metrics;
        int size;
        if (metrics4 == 0) {
            metric4Metrics = "-";
        } else {
            metric4Metrics = s;
        }
        size = g.getFontMetrics().stringWidth(metric4Metrics);
        int metric4Start = startMetrics4 + ((widthMetrics4 - size) / 2);
        g.drawString(metric4Metrics, metric4Start, innerYCounter);
    }
}
