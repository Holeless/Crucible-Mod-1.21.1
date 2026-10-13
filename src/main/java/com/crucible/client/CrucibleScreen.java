package com.crucible.client;

import com.crucible.Crucible;
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

        // Replace the difficulty button with our own, same size and position.
        difficultyButton.visible = false;
        button = new CrucibleButton(difficultyButton.getX(), difficultyButton.getY(),
                difficultyButton.getWidth(), difficultyButton.getHeight());
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
        button.visible = onGameTab;
        if (!onGameTab) return;
        button.setX(difficultyButton.getX());
        button.setY(difficultyButton.getY());
        button.render(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), event.getPartialTick());
    }

    private static class CrucibleButton extends Button {
        CrucibleButton(int x, int y, int w, int h) {
            super(x, y, w, h, Component.literal(NAMES[level - 1]),
                    b -> {
                        level = level % 3 + 1;
                        b.setMessage(Component.literal(NAMES[level - 1]));
                    }, DEFAULT_NARRATION);
        }

        @Override
        public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.renderWidget(g, mouseX, mouseY, partialTick);
            int size = 8;
            int x = getX() + getWidth() - 5 - level * (size + 1);
            int y = getY() + (getHeight() - size) / 2;
            for (int i = 0; i < level; i++) {
                g.blit(SKULL, x + i * (size + 1), y, size, size, 0f, 0f, 16, 16, 16, 16);
            }
        }
    }
}
