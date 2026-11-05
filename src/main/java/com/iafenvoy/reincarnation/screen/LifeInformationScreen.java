package com.iafenvoy.reincarnation.screen;

import com.iafenvoy.reincarnation.Reincarnation;
import com.iafenvoy.reincarnation.api.LifeNumbers;
import com.iafenvoy.reincarnation.data.information.LifeViewOpenScreenData;
import com.iafenvoy.reincarnation.data.information.expression.LifeExpression;
import com.iafenvoy.reincarnation.network.payload.LifeViewRefreshC2SPayload;
import com.iafenvoy.reincarnation.network.payload.LifeViewRefreshS2CPayload;
import com.iafenvoy.saturn.data.information.InformationGroup;
import com.iafenvoy.saturn.data.information.InformationText;
import com.iafenvoy.saturn.data.information.model.InformationEntry;
import com.iafenvoy.saturn.data.information.model.InformationValue;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.layout.Position;
import com.sighs.apricityui.layout.Size;
import com.sighs.apricityui.screen.ApricityScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

import static com.iafenvoy.reincarnation.screen.InformationElements.*;

public final class LifeInformationScreen extends ApricityScreen {
    private static final String PAGE = "reincarnation/information/information.html";
    private static final String UNGROUPED = "ungrouped";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT);
    private static final int HISTORY_HEIGHT = 30, ROW_HEIGHT = 15, GROUP_HEIGHT = 20;
    // Match the Minecraft font sizes in information.css.
    private static final int INSET = 3, TEXT_HEIGHT = 8, TITLE_HEIGHT = 10;
    private static final int CURRENT_REFRESH_INTERVAL = 20;
    private static final int REFRESH_TIMEOUT = 100;
    private final Set<String> expanded = new HashSet<>();
    private final Map<String, InformationAnimation> groupAnimations = new HashMap<>();
    private LifeViewOpenScreenData data;
    private @Nullable Document bound;
    private long boundGeneration = -1, lastFrame;
    private Element panel, title, subtitle, selected, previous, next, pageNumber, expand, collapse, close;
    private Pane history, details;
    private int layoutWidth = -1, layoutHeight = -1, panelWidth, detailWidth, selectedWidth, subtitleWidth;
    private int waitingTicks;
    private int refreshTicks = CURRENT_REFRESH_INTERVAL;
    private int refreshPendingTicks;
    private @Nullable UUID refreshRequest;
    private boolean dirty = true, revealSelection = true;

    private LifeInformationScreen(LifeViewOpenScreenData data) {
        super(PAGE);
        this.data = data;
        this.expanded.add("reincarnation:life");
        this.expanded.add("saturn:basic");
    }

    public static Screen open(LifeViewOpenScreenData data) {
        if (Minecraft.getInstance().screen instanceof LifeInformationScreen screen && screen.data.account().equals(data.account())) {
            boolean changedLife = screen.data.generation() != data.generation();
            boolean changedPage = screen.data.page() != data.page();
            screen.data = data;
            screen.waitingTicks = 0;
            screen.refreshPendingTicks = 0;
            screen.refreshRequest = null;
            screen.refreshTicks = CURRENT_REFRESH_INTERVAL;
            if (screen.details != null && changedLife) screen.details.scroll.snapTo(0);
            if (changedPage) screen.revealSelection = true;
            screen.dirty = true;
            return screen;
        }
        return new LifeInformationScreen(data);
    }

    public static void refresh(LifeViewRefreshS2CPayload payload) {
        if (!(Minecraft.getInstance().screen instanceof LifeInformationScreen screen)
                || !payload.requestId().equals(screen.refreshRequest) || screen.waitingTicks > 0
                || !payload.account().equals(screen.data.account()) || payload.generation() != screen.data.generation())
            return;
        screen.refreshRequest = null;
        screen.refreshPendingTicks = 0;
        LifeViewOpenScreenData old = screen.data;
        List<LifeViewOpenScreenData.Life> lives = old.lives().stream().map(life -> life.generation() == old.generation()
                ? payload.life().orElse(life) : life).toList();
        screen.data = new LifeViewOpenScreenData(old.account(), old.name(), old.generation(), lives, old.page(), old.pages(),
                payload.current(), payload.document().orElse(old.document()));
        screen.dirty = true;
    }

    @Override
    protected void init() {
        prepare(PAGE);
        super.init();
        this.bound = null;
        this.bind();
    }

    private boolean bind() {
        Document document = this.getLinkedDocument();
        if (document == null) return false;
        if (this.bound == document && this.boundGeneration == document.getRefreshGeneration())
            return this.history != null;
        this.bound = document;
        this.boundGeneration = document.getRefreshGeneration();
        InformationAnimation historyScroll = this.history == null ? new InformationAnimation(0, 0.14) : this.history.scroll;
        InformationAnimation detailsScroll = this.details == null ? new InformationAnimation(0, 0.14) : this.details.scroll;
        this.history = null;
        this.details = null;
        try {
            this.panel = require(document, "panel");
            this.title = require(document, "title");
            this.subtitle = require(document, "subtitle");
            this.selected = require(document, "selected");
            this.previous = require(document, "previous");
            this.next = require(document, "next");
            this.pageNumber = require(document, "page_number");
            this.expand = require(document, "expand");
            this.collapse = require(document, "collapse");
            this.close = require(document, "close");
            this.history = new Pane(require(document, "history"), true, historyScroll);
            this.details = new Pane(require(document, "details"), false, detailsScroll);
            this.previous.addEventListener("click", _ -> this.request(this.data.generation(), this.data.page() - 1));
            this.next.addEventListener("click", _ -> this.request(this.data.generation(), this.data.page() + 1));
            this.expand.addEventListener("click", _ -> {
                this.details.holdGroup(null);
                this.data.document().groups().forEach(group -> this.expanded.add(group.unwrapKey().orElseThrow().identifier().toString()));
                this.expanded.add(UNGROUPED);
                this.dirty = true;
            });
            this.collapse.addEventListener("click", _ -> {
                this.details.holdGroup(null);
                this.expanded.clear();
                this.dirty = true;
            });
            this.close.addEventListener("click", _ -> this.onClose());
            this.layoutWidth = -1;
            this.lastFrame = 0;
            this.dirty = true;
            return true;
        } catch (IllegalStateException failure) {
            Reincarnation.LOGGER.error("Cannot bind the life information page", failure);
            this.history = null;
            this.details = null;
            return false;
        }
    }

    private void request(long generation, int page) {
        if (this.waitingTicks > 0 || page < 1 || page > this.data.pages() || this.minecraft.getConnection() == null)
            return;
        this.waitingTicks = 100;
        this.refreshRequest = null;
        this.refreshPendingTicks = 0;
        this.minecraft.getConnection().sendCommand("reincarnation view " + this.data.account() + " " + LifeNumbers.display(generation) + " " + page);
        this.dirty = true;
    }

    private void requestCurrentRefresh() {
        if (!this.data.current() || this.waitingTicks > 0 || this.refreshPendingTicks > 0 || this.minecraft.getConnection() == null)
            return;
        this.refreshPendingTicks = REFRESH_TIMEOUT;
        this.refreshRequest = UUID.randomUUID();
        ClientPacketDistributor.sendToServer(new LifeViewRefreshC2SPayload(this.refreshRequest, this.data.account(), this.data.generation()));
    }

    @Override
    public void tick() {
        super.tick();
        if (this.waitingTicks > 0 && --this.waitingTicks == 0) this.dirty = true;
        if (this.refreshPendingTicks > 0 && --this.refreshPendingTicks == 0) this.refreshRequest = null;
        if (--this.refreshTicks <= 0) {
            this.refreshTicks = CURRENT_REFRESH_INTERVAL;
            this.requestCurrentRefresh();
        }
    }

    private void layout() {
        Size viewport = this.bound.getViewportSize();
        int width = Math.max(1, (int) viewport.width());
        int height = Math.max(1, (int) viewport.height());
        if (width != this.layoutWidth || height != this.layoutHeight) {
            this.layoutWidth = width;
            this.layoutHeight = height;
            this.panelWidth = Math.min(650, Math.max(296, width - 24));
            int panelHeight = Math.min(360, Math.max(180, height - 24));
            int left = (width - this.panelWidth) / 2, top = (height - panelHeight) / 2;
            int historyWidth = Math.clamp(this.panelWidth / 4, 86, 154);
            int right = historyWidth + 24;
            this.detailWidth = this.panelWidth - right - 12;
            bounds(this.panel, left, top, this.panelWidth, panelHeight);
            int expandWidth = this.textWidth(Component.translatable("screen.reincarnation.information.expand")) + 14;
            int collapseWidth = this.textWidth(Component.translatable("screen.reincarnation.information.collapse")) + 14;
            int closeX = this.panelWidth - 32;
            int collapseX = closeX - 6 - collapseWidth;
            int expandX = collapseX - 4 - expandWidth;
            bounds(this.close, closeX, 10, 20, 18);
            bounds(this.expand, expandX, 10, expandWidth, 18);
            bounds(this.collapse, collapseX, 10, collapseWidth, 18);
            int textWidth = Math.max(0, expandX - 22);
            int titleWidth = (this.font.width(Component.translatable("screen.reincarnation.information.title")) * TITLE_HEIGHT + 8) / 9;
            // Keep the player identity visible when the header is too narrow for both captions.
            boolean showTitle = textWidth >= titleWidth + 72;
            visible(this.title, showTitle);
            bounds(this.title, 12, 10, titleWidth, 18);
            int subtitleX = showTitle ? 12 + titleWidth + 10 : 12;
            this.subtitleWidth = Math.max(0, expandX - 8 - subtitleX);
            bounds(this.subtitle, subtitleX, 10, this.subtitleWidth, 18);
            bounds(require(this.bound, "history_caption"), 16, 32, historyWidth - 8, 12);
            this.selectedWidth = this.detailWidth - 8;
            bounds(this.selected, right + 4, 32, this.selectedWidth, 12);
            int listTop = 50;
            int pagerTop = panelHeight - 30;
            this.history.geometry(12, listTop, historyWidth, pagerTop - 4 - listTop);
            this.details.geometry(right, listTop, this.detailWidth, panelHeight - 12 - listTop);
            bounds(this.previous, 12, pagerTop, 22, 18);
            bounds(this.next, 12 + historyWidth - 22, pagerTop, 22, 18);
            bounds(this.pageNumber, 38, pagerTop, Math.max(1, historyWidth - 52), 18);
            this.dirty = true;
        }
        if (!this.dirty) return;
        this.dirty = false;
        text(this.title, Component.translatable("screen.reincarnation.information.title"));
        text(this.subtitle, this.abbreviate(Component.translatable("screen.reincarnation.information.player", this.data.name(), this.data.account().toString()), this.subtitleWidth));
        Component selection = this.waitingTicks > 0 ? Component.translatable("screen.reincarnation.information.loading")
                : Component.translatable("screen.reincarnation.information.life", LifeNumbers.display(this.data.generation()));
        text(this.selected, this.abbreviate(selection, this.selectedWidth));
        text(this.pageNumber, Component.literal(this.data.page() + " / " + this.data.pages()));
        disabled(this.previous, this.waitingTicks > 0 || this.data.page() == 1);
        disabled(this.next, this.waitingTicks > 0 || this.data.page() == this.data.pages());
        List<DisplayRow> lives = new ArrayList<>();
        for (LifeViewOpenScreenData.Life life : this.data.lives()) {
            Component number = Component.translatable("screen.reincarnation.information.life", LifeNumbers.display(life.generation()));
            Component second = life.endedAt() == 0 ? Component.translatable("command.reincarnation.state.current") : life.reason();
            lives.add(new DisplayRow("", number, second, "text-muted", false, life.generation() == this.data.generation(), 0,
                    () -> {
                        if (life.generation() != this.data.generation())
                            this.request(life.generation(), this.data.page());
                    }));
        }
        this.history.replace(lives);
        if (this.revealSelection) {
            this.revealSelection = false;
            this.history.scroll.snapTo(0);
            for (int index = 0; index < lives.size(); index++)
                if (lives.get(index).selected) {
                    this.history.scroll.snapTo(Math.max(0, INSET + index * HISTORY_HEIGHT - (this.history.height - HISTORY_HEIGHT) / 2.0));
                    break;
                }
        }
        this.details.replace(this.informationRows());
    }

    private List<DisplayRow> informationRows() {
        List<DisplayRow> rows = new ArrayList<>();
        for (Holder<InformationGroup> group : this.data.document().groups()) {
            String key = group.unwrapKey().orElseThrow().identifier().toString();
            this.group(rows, key, group.value().title(), this.data.document().rows(group));
        }
        List<InformationEntry> ungrouped = this.data.document().rows().stream().filter(entry -> entry.groups().isEmpty()).sorted(InformationEntry.ORDER).toList();
        if (!ungrouped.isEmpty())
            this.group(rows, UNGROUPED, Component.translatable("info.saturn.group.ungrouped"), ungrouped);
        if (rows.isEmpty())
            rows.add(new DisplayRow("", Component.empty(), Component.translatable("info.saturn.empty"), "text-muted", false, false, 0, () -> {
            }));
        return rows;
    }

    private void group(List<DisplayRow> rows, String key, Component title, List<InformationEntry> entries) {
        InformationAnimation animation = this.groupAnimations.computeIfAbsent(key, _ -> new InformationAnimation(this.expanded.contains(key) ? 1 : 0, 0.20));
        animation.moveTo(this.expanded.contains(key) ? 1 : 0);
        Component header = Component.translatable("screen.reincarnation.information.group", title, entries.size());
        rows.add(new DisplayRow(key, header, Component.empty(), "", true, false, 0, () -> {
            this.details.holdGroup(key);
            if (!this.expanded.remove(key)) this.expanded.add(key);
            this.dirty = true;
        }));
        int maximumLabelWidth = Math.max(36, Math.min(132, (this.detailWidth - 36) * 2 / 5));
        int labelWidth = Math.clamp(entries.stream().mapToInt(entry -> this.textWidth(entry.label())).max().orElse(40), 36, maximumLabelWidth);
        int valueWidth = Math.max(20, this.detailWidth - labelWidth - 36);
        for (InformationEntry entry : entries) {
            List<FormattedText> labels = this.font.getSplitter().splitLines(entry.label(), labelWidth * 9 / TEXT_HEIGHT, Style.EMPTY);
            List<FormattedText> values = this.informationLines(entry).stream()
                    .flatMap(value -> this.font.getSplitter().splitLines(value, valueWidth * 9 / TEXT_HEIGHT, Style.EMPTY).stream()).toList();
            String valueClass = switch (entry.tone()) {
                case NORMAL -> "";
                case POSITIVE -> "value-positive";
                case WARNING -> "value-warning";
            };
            for (int line = 0; line < Math.max(1, Math.max(labels.size(), values.size())); line++)
                rows.add(new DisplayRow(key, line < labels.size() ? labels.get(line) : FormattedText.EMPTY,
                        line < values.size() ? values.get(line) : FormattedText.EMPTY, valueClass, false, false, labelWidth, () -> {
                }));
        }
    }

    private List<Component> informationLines(InformationEntry entry) {
        if (entry.row().value().value() instanceof LifeExpression(LifeExpression.Value value)
                && entry.value() instanceof InformationValue.Text text
                && switch (value) {
            case STARTED, ENDED, OBSERVED -> true;
            default -> false;
        }) {
            Component original = text.text();
            try {
                String formatted = TIME.format(Instant.parse(original.getString()).atZone(ZoneId.systemDefault()));
                return List.of(Component.literal(formatted).setStyle(original.getStyle()));
            } catch (DateTimeParseException ignored) {
                // A datapack may replace the evaluated text; keep unrecognized content intact.
            }
        }
        return InformationText.lines(entry.value());
    }

    private int textWidth(FormattedText text) {
        return (this.font.width(text) * TEXT_HEIGHT + 8) / 9;
    }

    private FormattedText abbreviate(FormattedText text, int width) {
        if (width <= 0) return FormattedText.EMPTY;
        if (this.textWidth(text) <= width) return text;
        return FormattedText.composite(this.font.substrByWidth(text, Math.max(0, width * 9 / TEXT_HEIGHT - this.font.width("..."))), FormattedText.of("..."));
    }

    @Override
    public void extractBackground(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        this.extractTransparentBackground(graphics);
    }

    @Override
    public void extractRenderState(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (this.bind()) {
            this.layout();
            long now = System.nanoTime();
            double seconds = this.lastFrame == 0 ? 0 : (now - this.lastFrame) / 1_000_000_000.0;
            this.lastFrame = now;
            boolean groupsMoving = false;
            for (InformationAnimation animation : this.groupAnimations.values())
                groupsMoving |= animation.advance(seconds);
            this.history.frame(seconds, false);
            this.details.frame(seconds, groupsMoving);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (super.mouseScrolled(x, y, horizontal, vertical)) return true;
        if (!this.bind()) return false;
        for (Pane pane : List.of(this.history, this.details)) {
            if (!pane.contains(x, y)) continue;
            pane.anchor = null;
            pane.scroll.moveTo(Math.clamp(pane.scroll.target - vertical * (pane.historyPane ? HISTORY_HEIGHT : ROW_HEIGHT) * 2.5, 0, pane.maximum()));
            pane.changed = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseClicked(@NonNull MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && this.bind())
            for (Pane pane : List.of(this.history, this.details))
                if (pane.drag(event.x(), event.y(), true) || pane.click(event.x(), event.y())) return true;
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(@NonNull MouseButtonEvent event, double dx, double dy) {
        if (event.button() == 0 && this.bind())
            for (Pane pane : List.of(this.history, this.details))
                if (pane.drag(event.x(), event.y(), false)) return true;
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(@NonNull MouseButtonEvent event) {
        if (this.history != null) this.history.dragging = false;
        if (this.details != null) this.details.dragging = false;
        return super.mouseReleased(event);
    }

    @Override
    public void removed() {
        this.refreshRequest = null;
        this.refreshPendingTicks = 0;
        this.bound = null;
        this.history = null;
        this.details = null;
        super.removed();
    }

    private record DisplayRow(String group, FormattedText label, FormattedText value, String valueClass,
                              boolean heading, boolean selected, int labelWidth, Runnable click) {
    }

    private record PlacedRow(DisplayRow row, double top, int height, double clipTop, double clipBottom,
                             double openness) {
    }

    private final class Pane {
        final Element root, track, thumb;
        final boolean historyPane;
        final InformationAnimation scroll;
        final List<Cell> cells = new ArrayList<>();
        final List<PlacedRow> placed = new ArrayList<>();
        List<DisplayRow> rows = List.of();
        int width, height;
        double contentHeight, grab, anchorTop;
        boolean dragging, changed = true;
        @Nullable String anchor;

        Pane(Element root, boolean historyPane, InformationAnimation scroll) {
            this.root = root;
            this.historyPane = historyPane;
            this.scroll = scroll;
            root.setTextContent("");
            this.track = child(root, "scroll-track scrollbar-2-track");
            this.thumb = child(this.track, "scroll-thumb scrollbar-2-thumb");
        }

        void geometry(int x, int y, int width, int height) {
            this.width = width;
            this.height = height;
            bounds(this.root, x, y, width, height);
            this.changed = true;
        }

        void replace(List<DisplayRow> rows) {
            this.rows = rows;
            this.changed = true;
        }

        double maximum() {
            return Math.max(0, this.contentHeight - this.height);
        }

        void holdGroup(@Nullable String key) {
            this.scroll.snapTo(this.scroll.value);
            if (key == null) {
                for (PlacedRow slot : this.placed) {
                    double min = Math.min(slot.top + slot.height, slot.clipBottom);
                    if (min > Math.max(slot.top, slot.clipTop) && min > this.scroll.value + INSET) {
                        key = slot.row.group;
                        break;
                    }
                }
            }
            this.anchor = key;
            for (PlacedRow slot : this.placed)
                if (slot.row.heading && slot.row.group.equals(key)) {
                    this.anchorTop = slot.top;
                    return;
                }
            this.anchor = null;
        }

        void frame(double seconds, boolean groupsMoving) {
            boolean scrolling = this.scroll.advance(seconds);
            if (!this.changed && !groupsMoving && !scrolling) return;
            this.changed = false;
            this.placed.clear();
            double top = INSET;
            for (int index = 0; index < this.rows.size(); ) {
                DisplayRow row = this.rows.get(index++);
                if (!row.heading) {
                    int rowHeight = this.historyPane ? HISTORY_HEIGHT : ROW_HEIGHT;
                    this.placed.add(new PlacedRow(row, top, rowHeight, top, top + rowHeight, 1));
                    top += rowHeight;
                    continue;
                }
                double openness = LifeInformationScreen.this.groupAnimations.get(row.group).value;
                this.placed.add(new PlacedRow(row, top, GROUP_HEIGHT, top, top + GROUP_HEIGHT, openness));
                top += GROUP_HEIGHT;
                int start = index;
                while (index < this.rows.size() && !this.rows.get(index).heading) index++;
                double end = top + (index - start) * ROW_HEIGHT * openness;
                for (int child = start; child < index; child++)
                    this.placed.add(new PlacedRow(this.rows.get(child), top + (child - start) * ROW_HEIGHT, ROW_HEIGHT, top, end, openness));
                top = end + INSET;
            }
            this.contentHeight = top + INSET;
            if (this.anchor != null) {
                for (PlacedRow slot : this.placed)
                    if (slot.row.heading && slot.row.group.equals(this.anchor)) {
                        this.scroll.shift(slot.top - this.anchorTop);
                        this.anchorTop = slot.top;
                        break;
                    }
                if (!groupsMoving) this.anchor = null;
            }
            this.scroll.clamp(this.maximum());
            int shown = 0;
            for (PlacedRow slot : this.placed) {
                double clipTop = Math.max(INSET, slot.clipTop - this.scroll.value);
                double clipBottom = Math.min(this.height - INSET, slot.clipBottom - this.scroll.value);
                double rowTop = slot.top - this.scroll.value;
                int visibleTop = (int) Math.ceil(Math.max(rowTop, clipTop));
                int visibleBottom = (int) Math.floor(Math.min(rowTop + slot.height - 2, clipBottom));
                if (visibleBottom <= visibleTop) continue;
                if (shown == this.cells.size()) this.cells.add(new Cell(this.root));
                Cell cell = this.cells.get(shown++);
                DisplayRow row = slot.row;
                visible(cell.root, true);
                bounds(cell.root, INSET, visibleTop, this.width - 16, visibleBottom - visibleTop);
                token(cell.root, "group", row.heading);
                token(cell.root, "history", this.historyPane);
                token(cell.root, "active", row.selected);
                token(cell.root, "list-group-2-item", row.heading || this.historyPane);
                int fullWidth = this.width - 28;
                int labelX = row.heading ? 18 : 6;
                int labelWidth = row.heading ? fullWidth - 12 : this.historyPane ? fullWidth : row.labelWidth;
                double labelTop = rowTop + (this.historyPane ? 5 : (slot.height - 2 - TEXT_HEIGHT) / 2.0);
                double opacity = row.heading ? 1 : Math.min(1, slot.openness * 1.5);
                this.placeText(cell.label, labelX, labelTop, labelWidth, visibleTop, clipTop, clipBottom, opacity);
                this.placeText(cell.value, this.historyPane ? 6 : row.labelWidth + 16,
                        this.historyPane ? rowTop + 17 : labelTop, this.historyPane ? fullWidth : this.width - row.labelWidth - 36,
                        visibleTop, clipTop, clipBottom, opacity);
                if (row.heading) visible(cell.value, false);
                this.placeText(cell.marker, 5, labelTop, 8, visibleTop, clipTop, clipBottom, 1);
                if (!row.heading) visible(cell.marker, false);
                style(cell.marker, "transform", "rotate(" + Math.round(slot.openness * 90) + "deg)");
                if (cell.row != row) {
                    cell.value.setAttribute("class", "row-text row-value " + row.valueClass);
                    text(cell.label, row.heading || this.historyPane ? LifeInformationScreen.this.abbreviate(row.label, labelWidth) : row.label);
                    text(cell.value, this.historyPane ? LifeInformationScreen.this.abbreviate(row.value, fullWidth) : row.value);
                    cell.row = row;
                }
            }
            for (int index = shown; index < this.cells.size(); index++) visible(this.cells.get(index).root, false);
            visible(this.track, this.maximum() > 0);
            bounds(this.track, this.width - 7, INSET, 4, this.height - INSET * 2);
            int thumbHeight = this.thumbHeight();
            int thumbY = this.maximum() == 0 ? 0 : (int) Math.round(this.thumbTravel() * this.scroll.value / this.maximum());
            bounds(this.thumb, 0, thumbY, 4, thumbHeight);
        }

        private void placeText(Element element, int x, double top, int width, int rootTop, double clipTop, double clipBottom, double opacity) {
            // Bound each visible row and text run ourselves; AUI overflow masks can darken the list.
            bounds(element, x, (int) Math.round(top) - rootTop, Math.max(0, width), TEXT_HEIGHT);
            visible(element, top >= clipTop && top + TEXT_HEIGHT <= clipBottom);
            double edge = Math.clamp((Math.min(top - clipTop, clipBottom - top - TEXT_HEIGHT) + 1) / 4, 0, 1);
            style(element, "opacity", Double.toString(opacity * edge));
        }

        private int thumbHeight() {
            int trackHeight = this.height - INSET * 2;
            return (int) Math.clamp(trackHeight * this.height / Math.max(1, this.contentHeight), Math.min(18, trackHeight), trackHeight);
        }

        private int thumbTravel() {
            return this.height - INSET * 2 - this.thumbHeight();
        }

        Position local(double x, double y) {
            Position pointer = LifeInformationScreen.this.bound.screenToDocumentPosition(new Position(x, y));
            Position origin = Position.of(this.root);
            return new Position(pointer.x - origin.x, pointer.y - origin.y);
        }

        boolean contains(double x, double y) {
            Position pointer = this.local(x, y);
            return pointer.x >= 0 && pointer.x < this.width && pointer.y >= 0 && pointer.y < this.height;
        }

        boolean click(double x, double y) {
            if (!this.contains(x, y)) return false;
            Position pointer = this.local(x, y);
            if (pointer.x < INSET || pointer.x >= this.width - 12 || pointer.y < INSET || pointer.y >= this.height - INSET)
                return true;
            double contentY = pointer.y + this.scroll.value;
            for (PlacedRow slot : this.placed)
                if (contentY >= Math.max(slot.top, slot.clipTop) && contentY < Math.min(slot.top + slot.height - 2, slot.clipBottom)
                        && (slot.row.heading || this.historyPane)) {
                    slot.row.click.run();
                    break;
                }
            return true;
        }

        boolean drag(double x, double y, boolean begin) {
            Position pointer = this.local(x, y);
            int thumbHeight = this.thumbHeight();
            if (begin) {
                if (this.maximum() == 0 || pointer.x < this.width - 11 || pointer.x >= this.width || pointer.y < INSET || pointer.y >= this.height - INSET)
                    return false;
                double thumbY = INSET + this.thumbTravel() * this.scroll.value / this.maximum();
                this.grab = pointer.y >= thumbY && pointer.y < thumbY + thumbHeight ? pointer.y - thumbY : thumbHeight / 2.0;
                this.dragging = true;
                this.anchor = null;
            }
            if (!this.dragging) return false;
            this.scroll.snapTo(Math.clamp((pointer.y - INSET - this.grab) / Math.max(1, this.thumbTravel()) * this.maximum(), 0, this.maximum()));
            this.changed = true;
            return true;
        }
    }

    private static final class Cell {
        final Element root, label, value, marker;
        @Nullable DisplayRow row;

        Cell(Element parent) {
            this.root = child(parent, "information-row");
            this.label = child(this.root, "row-text row-label");
            this.value = child(this.root, "row-text row-value");
            this.marker = child(this.root, "row-marker");
            text(this.marker, Component.literal(">"));
        }
    }
}
