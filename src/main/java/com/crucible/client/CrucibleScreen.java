package com.crucible.client;

import com.crucible.Crucible;
import com.crucible.CrucibleManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.ArrayList;
import java.util.List;

@EventBusSubscriber(modid = Crucible.MOD_ID, value = Dist.CLIENT)
public class CrucibleScreen {
    private static final ResourceLocation SKULL =
            ResourceLocation.fromNamespaceAndPath(Crucible.MOD_ID, "textures/gui/skull.png");
    private static final String[] NAMES = {"Crucible I", "Crucible II", "Crucible III"};

    private static final int SKULL_SIZE = 16;   // native texture size, 1 texel = 1 GUI pixel
    private static final int BOX_H = 56;
    private static final int BUTTON_H = 20;
    private static final int GAP = 6;
    private static final int LEFT_MARGIN = 8;
    private static final int RIGHT_MARGIN = 8;
    private static final int PANEL_TOP = 32;    // just below the tab bar
    private static final int FOOTER_H = 33;     // vanilla footer height
    private static final int LINE_H = 9;

    // ---------------------------------------------------------------------
    // Effect text. Add Crucible II / III by filling in the other slots.
    // Each section: { title, { {name, description}, ... } }
    // ---------------------------------------------------------------------
    private record Section(String title, String[][] effects) {}

    private static final Section[] CRUCIBLE_1 = {
            new Section("World:", new String[][]{
                    {"Brittle", "Armor and tools lose durability faster."}
            }),
            new Section("Entities:", new String[][]{
                    {"Unity", "Hostile mobs never get aggravated at each other."},
                    {"Swarm", "Hostile mobs have a higher spawn rate; Spawners spawn faster."},
                    {"Kiting", "Skeletons and Witches will backpedal away from you while attacking, "
                            + "they also hide behind structures and other mobs when attacking and between attacks."},
                    {"Extrication", "Hostile mobs can break boats, minecarts, and doors."},
                    {"Snare", "Spiders try to trap you with webbing."},
                    {"Nocturne", "Phantoms always spawn at night regardless of sleep and in caves like bats."},
                    {"Relentless", "Mob follow range doubled; mobs keep tracking your last known position after losing sight."},
                    {"Vigilance", "Mob detection range doubled."},
                    {"Marksman", "Skeletons have a higher chance to spawn with a randomly enchanted bow."},
                    {"Vicious", "Passive mobs are neutral and attack as a group if provoked."}
            }),
            new Section("Players:", new String[][]{
                    {"Famine", "Hunger drains faster."},
                    {"Insomnia", "Cannot sleep."}
            })
    };

    private static final Section[][] EFFECTS = {CRUCIBLE_1, null, null};

    /** Selected crucible, 1-3. Mirrored into CrucibleManager.pendingLevel for the server side. */
    public static int level = 1;

    private static CrucibleButton button;
    private static CycleButton<?> difficultyButton;

    // layout, recomputed every frame
    private static int selX, selY, selW;
    private static int effX, effY, effW, effH;
    private static int scroll = 0;
    private static int maxScroll = 0;

    @SubscribeEvent
    public static void onInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof CreateWorldScreen screen)) return;

        button = null;
        CycleButton<?> gameMode = null;
        CycleButton<?> commands = null;
        difficultyButton = null;
        for (GuiEventListener child : screen.children()) {
            if (child instanceof CycleButton<?> cb) {
                if (cb.getValue() instanceof WorldCreationUiState.SelectedGameMode) gameMode = cb;
                else if (cb.getValue() instanceof Difficulty) difficultyButton = cb;
                else if (cb.getValue() instanceof Boolean) commands = cb;
            }
        }
        if (gameMode == null || difficultyButton == null) return;

        // Hardcore-only and no cheats, so none of these three need to be visible or clickable.
        for (CycleButton<?> cb : new CycleButton<?>[]{gameMode, difficultyButton, commands}) {
            if (cb != null) {
                cb.visible = false;
                cb.active = false;
            }
        }

        scroll = 0;
        CrucibleManager.pendingLevel = level;
        button = new CrucibleButton();
        event.addListener(button);

        WorldCreationUiState state = screen.getUiState();
        final CycleButton<?> lockedMode = gameMode;
        state.addListener(s -> lockedMode.active = false);
        state.setGameMode(WorldCreationUiState.SelectedGameMode.HARDCORE);
        state.setAllowCommands(false);
        lockedMode.active = false;
    }

    /** Layout pass. Runs every frame and is idempotent, so it survives tab switches and resizes. */
    @SubscribeEvent
    public static void onRenderPre(ScreenEvent.Render.Pre event) {
        if (!(event.getScreen() instanceof CreateWorldScreen screen) || button == null) return;

        // 1) Push the current tab's vanilla widgets to the left edge.
        int minX = Integer.MAX_VALUE;
        List<AbstractWidget> tabWidgets = new ArrayList<>();
        for (GuiEventListener c : screen.children()) {
            if (c instanceof AbstractWidget w && w != button && w.getY() < screen.height - FOOTER_H) {
                tabWidgets.add(w);
                minX = Math.min(minX, w.getX());
            }
        }
        if (minX != Integer.MAX_VALUE) {
            int dx = LEFT_MARGIN - minX;
            if (dx != 0) for (AbstractWidget w : tabWidgets) w.setX(w.getX() + dx);
        }

        // 2) Left column width = widest vanilla widget on this tab.
        int leftRight = LEFT_MARGIN + 210;
        EditBox nameBox = null;
        for (AbstractWidget w : tabWidgets) {
            leftRight = Math.max(leftRight, w.getX() + w.getWidth());
            if (w instanceof EditBox eb && nameBox == null) nameBox = eb;
        }

        // 3) Effects panel fills everything to the right of the left column, on every tab.
        effX = leftRight + 12;
        effW = Math.max(60, screen.width - RIGHT_MARGIN - effX);
        effY = PANEL_TOP;
        effH = Math.max(40, screen.height - FOOTER_H - 8 - effY);

        // 4) Selector sits directly under the world name field (Game tab only).
        boolean onGameTab = screen.children().contains(difficultyButton);
        button.visible = onGameTab;
        if (!onGameTab) return;

        if (nameBox != null) {
            selX = nameBox.getX();
            selW = nameBox.getWidth();
            selY = nameBox.getY() + nameBox.getHeight() + GAP + 2;
        } else {
            selX = LEFT_MARGIN;
            selW = leftRight - LEFT_MARGIN;
            selY = PANEL_TOP;
        }
        button.setX(selX);
        button.setY(selY + BOX_H + GAP);
        button.setWidth(selW);
    }

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof CreateWorldScreen) || button == null) return;

        GuiGraphics g = event.getGuiGraphics();

        if (button.visible) {
            drawSelector(g, selX, selY, selW, BOX_H);
            button.render(g, event.getMouseX(), event.getMouseY(), event.getPartialTick());
        }

        drawEffects(g, effX, effY, effW, effH);
    }

    @SubscribeEvent
    public static void onScroll(ScreenEvent.MouseScrolled.Pre event) {
        if (!(event.getScreen() instanceof CreateWorldScreen) || button == null) return;
        double mx = event.getMouseX(), my = event.getMouseY();
        if (mx >= effX && mx < effX + effW && my >= effY && my < effY + effH) {
            scroll = Mth.clamp(scroll - (int) (event.getScrollDeltaY() * 12), 0, maxScroll);
            event.setCanceled(true);
        }
    }

    /** Vanilla text-field look: grey outline, black fill. */
    private static void drawFrame(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xFFA0A0A0);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF000000);
    }

    private static void drawSelector(GuiGraphics g, int x, int y, int w, int h) {
        drawFrame(g, x, y, w, h);

        int cx = x + w / 2;
        g.drawCenteredString(Minecraft.getInstance().font, Component.literal(NAMES[level - 1]),
                cx, y + 8, 0xFFFFFFFF);

        // Skulls drawn 1:1 so their pixels match the GUI's pixel size.
        int spacing = 2;
        int total = level * SKULL_SIZE + (level - 1) * spacing;
        int sx = cx - total / 2;
        int sy = y + h - SKULL_SIZE - 8;
        for (int i = 0; i < level; i++) {
            g.blit(SKULL, sx + i * (SKULL_SIZE + spacing), sy,
                    0f, 0f, SKULL_SIZE, SKULL_SIZE, SKULL_SIZE, SKULL_SIZE);
        }
    }

    private record Line(FormattedCharSequence text, int y) {}

    private static void drawEffects(GuiGraphics g, int x, int y, int w, int h) {
        Font font = Minecraft.getInstance().font;
        drawFrame(g, x, y, w, h);

        int pad = 5;
        int wrapW = w - pad * 2 - 4; // leave room for the scrollbar

        List<Line> lines = new ArrayList<>();
        int cy = 0;
        Section[] sections = EFFECTS[level - 1];
        if (sections == null) {
            for (FormattedCharSequence s : font.split(
                    Component.literal("No effects defined yet.").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC), wrapW)) {
                lines.add(new Line(s, cy));
                cy += LINE_H;
            }
        } else {
            for (Section sec : sections) {
                for (FormattedCharSequence s : font.split(
                        Component.literal(sec.title()).withStyle(ChatFormatting.GOLD), wrapW)) {
                    lines.add(new Line(s, cy));
                    cy += LINE_H;
                }
                cy += 2;
                for (String[] e : sec.effects()) {
                    Component c = Component.literal(e[0] + ": ").withStyle(ChatFormatting.YELLOW)
                            .append(Component.literal(e[1]).withStyle(ChatFormatting.GRAY));
                    for (FormattedCharSequence s : font.split(c, wrapW)) {
                        lines.add(new Line(s, cy));
                        cy += LINE_H;
                    }
                    cy += 3;
                }
                cy += 4;
            }
        }

        int innerH = h - pad * 2;
        maxScroll = Math.max(0, cy - innerH);
        scroll = Mth.clamp(scroll, 0, maxScroll);

        g.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
        for (Line l : lines) {
            int ly = y + pad + l.y() - scroll;
            if (ly + LINE_H < y || ly > y + h) continue;
            g.drawString(font, l.text(), x + pad, ly, 0xFFFFFFFF);
        }
        g.disableScissor();

        if (maxScroll > 0) {
            int bx = x + w - 5;
            int trackTop = y + 2, trackH = h - 4;
            g.fill(bx, trackTop, bx + 3, trackTop + trackH, 0xFF404040);
            int thumbH = Math.max(12, trackH * innerH / cy);
            int thumbY = trackTop + (trackH - thumbH) * scroll / maxScroll;
            g.fill(bx, thumbY, bx + 3, thumbY + thumbH, 0xFFA0A0A0);
        }
    }

    private static class CrucibleButton extends Button {
        CrucibleButton() {
            super(0, 0, 100, BUTTON_H, Component.literal("Change Crucible"),
                    b -> {
                        level = level % 3 + 1;
                        scroll = 0;
                        CrucibleManager.pendingLevel = level;
                    }, DEFAULT_NARRATION);
        }
    }
}
