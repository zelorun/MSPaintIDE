package com.uddernetworks.mspaint.imagestreams;

import com.uddernetworks.mspaint.logging.FormattedAppender;
import com.uddernetworks.mspaint.main.StartupLogic;
import com.uddernetworks.newocr.utils.ConversionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ImageOutputStream extends OutputStream {

    private static Logger LOGGER = LoggerFactory.getLogger(ImageOutputStream.class);

    private StringBuilder string = new StringBuilder();
    private StartupLogic startupLogic;
    private File location;
    private Graphics2D graphics;
    private int width;
    private final boolean log;
    private int minHeight;
    private Color color;
    private Color background;
    private StringBuilder buffer = new StringBuilder();

    public ImageOutputStream(StartupLogic startupLogic, File location, int width, boolean log) {
        this.startupLogic = startupLogic;
        this.location = location;

        this.width = width;
        this.log = log;
        this.minHeight = 200;

        this.color = Color.BLACK;
        this.background = Color.WHITE;
    }

    @Override
    public void write(int b) {
        var c = (char) b;
        string.append(c);
        if (!log) return;
        if (c == '\n') {
            FormattedAppender.appendText(buffer.append('\n').toString());
            buffer.setLength(0);
        } else {
            buffer.append(c);
        }
    }

    public void saveImage() {
        var fontSize = 24; // In pts
        var fontSizePx = ConversionUtils.pointToPixel(fontSize);
        BufferedImage image = new BufferedImage(width, minHeight, BufferedImage.TYPE_INT_ARGB);
        this.graphics = image.createGraphics();

        String message = string.toString();

        graphics.setRenderingHints(new RenderingHints(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON));

        Font font = new Font(this.startupLogic.getFontName(), Font.PLAIN, fontSize);
        graphics.setFont(font);

        List<String> linesList = new ArrayList<>();

        String[] lines = message.split("\n");
        for (String line1 : lines) {
            String[] innerLines = breakStringUp(line1).split("\n");
            linesList.addAll(Arrays.asList(innerLines));
        }

        int newHeight = (linesList.size() + 1) * fontSizePx;

        var height = Math.max(newHeight, minHeight);
        image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        this.graphics = image.createGraphics();

        graphics.setRenderingHints(new RenderingHints(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON));

        graphics.setFont(font);
        graphics.setPaint(this.background);
        graphics.fillRect(0, 0, this.width, height);
        graphics.setPaint(this.color);

        for (int i = 0; i < linesList.size(); i++) {
            graphics.drawString(linesList.get(i), 10, fontSizePx + (i * fontSizePx));
        }

        try {
            ImageIO.write(image, "png", location);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void changeColor(Color color) {
        this.color = color;
    }

    public void changeBackground(Color background) {
        this.background = background;
    }

    private String breakStringUp(String message) {
        FontMetrics fontMetrics = graphics.getFontMetrics();

        String[] words = message.split(" ");

        StringBuilder ret = new StringBuilder();

        int currentWidth = 0;
        for (String word : words) {
            int currWordWidth = fontMetrics.stringWidth(word + " ");

            if (currentWidth + currWordWidth + 20 > width) {
                ret.append("\n");
                currentWidth = 0;

                if (currWordWidth + 20 > width) {
                    ret.append(breakWordUp(word));
                } else {
                    ret.append(word).append(" ");
                }
            } else {
                currentWidth += currWordWidth;
                ret.append(word).append(" ");
            }
        }

        return ret.toString();
    }

    private String breakWordUp(String word) {
        FontMetrics fontMetrics = graphics.getFontMetrics();

        char[] chars = word.toCharArray();

        StringBuilder ret = new StringBuilder();

        int currentWidth = 0;
        for (char cha : chars) {
            int currWordWidth = fontMetrics.stringWidth(Character.toString(cha));

            if (currentWidth + currWordWidth + 20 > width) {
                ret.append("\n");
                currentWidth = 0;
                ret.append(cha);
            } else {
                currentWidth += currWordWidth;
                ret.append(cha);
            }
        }

        return ret.toString();
    }
}