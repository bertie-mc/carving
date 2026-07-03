package com.berlord.carving.client;

import com.berlord.carving.Carving;
import com.berlord.carving.CarvingMaterial;
import com.berlord.carving.net.OpenCarvingPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = Carving.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class CarvingClient {

    private CarvingClient() {
    }

    /** Called from OpenCarvingPayload.handle (client thread). */
    public static void openScreen(OpenCarvingPayload payload) {
        CarvingMaterial material = CarvingMaterial.byIndex(payload.material());
        InteractionHand hand = payload.mainHand() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        Minecraft.getInstance().setScreen(new CarvingScreen(material, payload.armor(), hand));
    }

    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        // Re-read shape JSONs on resource reload (F3+T) so silhouette edits take effect live.
        event.registerReloadListener(
                (ResourceManagerReloadListener) resourceManager -> ShapeLibrary.clear());
    }

    @SubscribeEvent
    public static void onRegisterScreens(RegisterMenuScreensEvent event) {
        event.register(Carving.CARVING_STATION_MENU.get(), CarvingStationScreen::new);
    }
}
