package dev.gearrender;

import dev.gearrender.mixin.HandledScreenAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GearRenderClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("gearrender");
    private static boolean openNext;
    private static KeyBinding openKey, toggleKey;

    @Override
    public void onInitializeClient() {
        Config.load();
        Store.load();
        Manager.compile();

        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.gearrender.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, "category.gearrender"));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.gearrender.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, "category.gearrender"));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {
            for (String n : new String[]{"gearrender", "gr"}) {
                dispatcher.register(ClientCommandManager.literal(n).executes(ctx -> {
                    openNext = true;
                    return 1;
                }));
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (openNext) {
                openNext = false;
                mc.setScreen(new GearRenderScreen());
            }
            while (openKey.wasPressed()) if (mc.currentScreen == null) mc.setScreen(new GearRenderScreen());
            while (toggleKey.wasPressed()) {
                Config.d.autoTp = !Config.d.autoTp;
                Config.save();
                if (mc.player != null)
                    mc.inGameHud.setOverlayMessage(Text.literal("AutoTP: " + (Config.d.autoTp ? "ON" : "OFF")), false);
            }
            Manager.tick(mc);
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) Manager.onMessage(message);
        });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, ts) -> Manager.onMessage(message));

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            Manager.reset();
            Store.save();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> {
            Store.save();
            Config.save();
        });

        // Ctrl + A na przedmiocie w dowolnym GUI -> dodaj/usun z listy poszukiwanych
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof HandledScreen<?> hs) {
                ScreenKeyboardEvents.allowKeyPress(screen).register((s, key, scancode, mods) -> {
                    if (key == GLFW.GLFW_KEY_A && (mods & GLFW.GLFW_MOD_CONTROL) != 0) {
                        Slot slot = ((HandledScreenAccessor) hs).gearrender$getFocusedSlot();
                        if (slot != null && slot.hasStack()) {
                            toggleWatch(client, slot.getStack());
                            return false;
                        }
                    }
                    return true;
                });
            }
        });
    }

    private static void toggleWatch(MinecraftClient mc, ItemStack stack) {
        String id = Manager.id(stack);
        if (id.isEmpty()) return;
        boolean removed = Config.d.watch.remove(id);
        if (!removed) Config.d.watch.add(id);
        Config.save();
        if (mc.player != null)
            mc.player.sendMessage(Text.literal("[Gear Render] " + (removed ? "Usunięto z listy: " : "Dodano do listy: ")
                    + stack.getName().getString()), false);
    }
}
