package de.raindancer.core.land;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;

import java.time.Duration;

/** The enter and leave titles of a claim, including their fade timings. */
public final class ClaimTitles {

    private StyledText enterTitle = StyledText.empty();
    private StyledText enterSubtitle = StyledText.empty();
    private StyledText leaveTitle = StyledText.empty();
    private StyledText leaveSubtitle = StyledText.empty();

    private int fadeInTicks = 10;
    private int stayTicks = 40;
    private int fadeOutTicks = 10;

    public StyledText enterTitle() {
        return enterTitle;
    }

    public StyledText enterSubtitle() {
        return enterSubtitle;
    }

    public StyledText leaveTitle() {
        return leaveTitle;
    }

    public StyledText leaveSubtitle() {
        return leaveSubtitle;
    }

    public void enterTitle(StyledText text) {
        this.enterTitle = text == null ? StyledText.empty() : text;
    }

    public void enterSubtitle(StyledText text) {
        this.enterSubtitle = text == null ? StyledText.empty() : text;
    }

    public void leaveTitle(StyledText text) {
        this.leaveTitle = text == null ? StyledText.empty() : text;
    }

    public void leaveSubtitle(StyledText text) {
        this.leaveSubtitle = text == null ? StyledText.empty() : text;
    }

    public int fadeInTicks() {
        return fadeInTicks;
    }

    public int stayTicks() {
        return stayTicks;
    }

    public int fadeOutTicks() {
        return fadeOutTicks;
    }

    public void fadeInTicks(int ticks) {
        this.fadeInTicks = clampTicks(ticks);
    }

    public void stayTicks(int ticks) {
        this.stayTicks = clampTicks(ticks);
    }

    public void fadeOutTicks(int ticks) {
        this.fadeOutTicks = clampTicks(ticks);
    }

    private static int clampTicks(int ticks) {
        return Math.max(0, Math.min(200, ticks));
    }

    public boolean hasEnterTitle() {
        return !enterTitle.isBlank() || !enterSubtitle.isBlank();
    }

    public boolean hasLeaveTitle() {
        return !leaveTitle.isBlank() || !leaveSubtitle.isBlank();
    }

    public Title buildEnter() {
        return build(enterTitle.render(), enterSubtitle.render());
    }

    public Title buildLeave() {
        return build(leaveTitle.render(), leaveSubtitle.render());
    }

    private Title build(Component title, Component subtitle) {
        Title.Times times = Title.Times.times(
                Duration.ofMillis(fadeInTicks * 50L),
                Duration.ofMillis(stayTicks * 50L),
                Duration.ofMillis(fadeOutTicks * 50L));
        return Title.title(title, subtitle, times);
    }
}
