package dev.localsync.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

final class GlassButton extends AbstractWidget {
    private final Runnable action;
    private final boolean accented;

    GlassButton(String label, int x, int y, int width, int height,
                boolean accented, Runnable action) {
        super(x, y, width, height, Component.literal(label));
        this.action = action;
        this.accented = accented;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX,
                                            int mouseY, float partialTick) {
        boolean highlighted = active && isHoveredOrFocused();
        boolean liquidGlass = active && ReGlassCompat.renderSurface(
            graphics, getX(), getY(), getWidth(), getHeight(),
            getHeight() * 0.5f, highlighted, accented);
        int fill;
        int border;
        if (!active) {
            fill = 0x55343A44;
            border = 0x18FFFFFF;
        } else if (accented) {
            fill = highlighted ? 0xE8FB7299 : 0xC8E75F89;
            border = highlighted ? 0xAAFFFFFF : 0x66FFFFFF;
        } else {
            fill = highlighted ? 0xC43B424E : 0x92303640;
            border = highlighted ? 0x72FFFFFF : 0x34FFFFFF;
        }
        if (!liquidGlass) {
            GlassUi.pill(graphics, getX(), getY(), getWidth(), getHeight(), fill, border);
        }
        int color = active ? GlassUi.TEXT : 0xFF747B86;
        var font = Minecraft.getInstance().font;
        String label = font.plainSubstrByWidth(getMessage().getString(),
            Math.max(1, getWidth() - 8));
        graphics.centeredText(font, label, getX() + getWidth() / 2,
            getY() + (getHeight() - 8) / 2, color);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (active && action != null) {
            action.run();
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (active && isFocused() && (event.key() == GLFW.GLFW_KEY_ENTER
                || event.key() == GLFW.GLFW_KEY_KP_ENTER
                || event.key() == GLFW.GLFW_KEY_SPACE)) {
            action.run();
            return true;
        }
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
