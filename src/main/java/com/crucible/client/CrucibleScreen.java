package com.crucible.client;

import com.crucible.Crucible;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

@EventBusSubscriber(modid = Crucible.MOD_ID, value = Dist.CLIENT)
public class CrucibleScreen {
    private static final ResourceLocation SKULL =
            ResourceLocation.fromNamespaceAndPath(Crucible.MOD_ID, "textures/gui/skull.png");
    private static final String[] NAMES = {"Crucible I", "Crucible II", "Crucible III"};

    private static final int SKULL_SIZE = 16;   // native texture size, 1 texel = 1 GUI pixel
    private static final int PANEL_MAX_W = 120;
    private static final int PANEL_MIN_W = 80;
    private static final int BOX_H = 56;
    private static final int GAP = 6;

    /** Selected crucible, 1-3. Read this wherever you need it later. */
    public static int level = 1;

    private static CrucibleButton button;
    private static CycleButton<?> difficultyButton;

    @SubscribeEvent
    public static void onInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof CreateWorldScreen screen)) return;

        CycleButton<?> gameMode = null;
        difficultyButton = null;
        for (GuiEventListener child : screen.children()) {
            if (child instanceof CycleButton<?> cb) {
                if (cb.getValue() instanceof WorldCreationUiState.SelectedGameMode) gameMode = cb;
                else if (cb.getValue() instanceof Difficulty) difficultyButton = cb;
            }
        }
        if (gameMode == null || difficultyButton == null) return;

        // Hide the vanilla difficulty button; ours lives on the right.
        difficultyButton.visible = false;
        button = new CrucibleButton();
        event.addListener(button);

        // Hardcore only, locked.
        WorldCreationUiState state = screen.getUiState();
        final CycleButton<?> lockedMode = gameMode;
        state.addListener(s -> lockedMode.active = false);
        state.setGameMode(WorldCreationUiState.SelectedGameMode.HARDCORE);
        lockedMode.active = false;
    }

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof CreateWorldScreen screen) || button == null) return;

        // Only show on the tab that has the difficulty button.
        boolean onGameTab = screen.children().contains(difficultyButton);

        // Space to the right of the 310px-wide centered content.
        int contentRight = screen.width / 2 + 155;
        int panelX = contentRight + 10;
        int panelW = Math.min(PANEL_MAX_W, screen.width - panelX - 10);
        boolean fits = panelW >= PANEL_MIN_W;

        button.visible = onGameTab && fits;
        if (!button.visible) return;

        int boxY = difficultyButton.getY() - 4;
        button.setX(panelX);
        button.setY(boxY + BOX_H + GAP);
        button.setWidth(panelW);

        GuiGraphics g = event.getGuiGraphics();
        drawBox(g, screen, panelX, boxY, panelW, BOX_H);
        button.render(g, event.getMouseX(), event.getMouseY(), event.getPartialTick());
    }

    /** Vanilla text-field look: grey outline, black fill. */
    private static void drawBox(GuiGraphics g, CreateWorldScreen screen, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xFFA0A0A0);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF000000);

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

    private static class CrucibleButton extends Button {
        CrucibleButton() {
            super(0, 0, PANEL_MAX_W, 20, Component.literal("Change Crucible"),
                    b -> level = level % 3 + 1, DEFAULT_NARRATION);
        }
    }
}
