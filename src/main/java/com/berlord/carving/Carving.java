package com.berlord.carving;

import com.berlord.carving.block.CarvingStationBlock;
import com.berlord.carving.block.CarvingStationBlockEntity;
import com.berlord.carving.compat.SlagCompat;
import com.berlord.carving.item.SlateItem;
import com.berlord.carving.menu.CarvingStationMenu;
import com.berlord.carving.net.CarveResultPayload;
import com.berlord.carving.net.OpenCarvingPayload;
import com.berlord.carving.net.StationCarveResultPayload;
import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Carving - shape tool heads & armor from material slates instead of crafting.
 *
 * <p>The carve produces the FINAL item directly: a {@code slag:dynamic_part} when Slag is present
 * (and the material has a Slag id), otherwise the full vanilla tool/armor (or always vanilla for
 * leather). No Berlord's intermediate item exists. TIER 1 materials are carved in-hand, TIER 2 at the
 * carving station's water-jet.
 */
@Mod(Carving.MODID)
public class Carving {
    public static final String MODID = "berlords_carving";

    /** Temporary diagnostic logger (durability-penalty investigation). */
    public static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("berlords_carving");

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, MODID);
    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, MODID);

    /** Tier-1 carving error count, ridden onto the result: -25% current durability per flaw. */
    public static final Supplier<DataComponentType<Integer>> FLAWS = COMPONENTS.register("flaws",
            () -> DataComponentType.<Integer>builder()
                    .persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT).build());
    /** Tier-2 (water-jet) penalty steps, ridden onto the result: -20% current durability per step. */
    public static final Supplier<DataComponentType<Integer>> PENALTY = COMPONENTS.register("penalty",
            () -> DataComponentType.<Integer>builder()
                    .persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT).build());
    /** Marker: this assembled Slag tool has already taken its one-time carving penalty. */
    public static final Supplier<DataComponentType<Unit>> PENALIZED = COMPONENTS.register("penalized",
            () -> DataComponentType.<Unit>builder()
                    .persistent(Codec.unit(Unit.INSTANCE)).networkSynchronized(StreamCodec.unit(Unit.INSTANCE)).build());

    public static final DeferredBlock<CarvingStationBlock> CARVING_STATION =
            BLOCKS.registerBlock("carving_station", CarvingStationBlock::new,
                    BlockBehaviour.Properties.of().mapColor(MapColor.DIAMOND).strength(3.5F)
                            .sound(SoundType.METAL).requiresCorrectToolForDrops().noOcclusion());
    public static final DeferredItem<BlockItem> CARVING_STATION_ITEM =
            ITEMS.registerSimpleBlockItem("carving_station", CARVING_STATION);

    /** Block of Flint (9 flint); the flint carving-canvas background, now a real block. */
    public static final DeferredBlock<Block> FLINT_BLOCK = BLOCKS.registerSimpleBlock("flint_block",
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).strength(1.5F, 6.0F).sound(SoundType.STONE));
    public static final DeferredItem<BlockItem> FLINT_BLOCK_ITEM =
            ITEMS.registerSimpleBlockItem("flint_block", FLINT_BLOCK);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CarvingStationBlockEntity>>
            CARVING_STATION_BE = BLOCK_ENTITIES.register("carving_station",
            () -> BlockEntityType.Builder.of(CarvingStationBlockEntity::new, CARVING_STATION.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<CarvingStationMenu>> CARVING_STATION_MENU =
            MENUS.register("carving_station", () -> IMenuTypeExtension.create(CarvingStationMenu::new));

    /** Small slates (tools) — every material except leather. */
    public static final Map<CarvingMaterial, DeferredItem<Item>> SMALL_SLATES = new EnumMap<>(CarvingMaterial.class);
    /** Big slates (armor) — every material. */
    public static final Map<CarvingMaterial, DeferredItem<Item>> BIG_SLATES = new EnumMap<>(CarvingMaterial.class);

    /** Register only the slates that can actually do something: any material with Slag present, or one
     *  with a vanilla equivalent. So with Slag ABSENT, the Slag-only materials (emerald, copper, ...)
     *  get NO slate item at all -- they don't even appear in the creative search. Done in the constructor
     *  (not a static block) so {@code ModList} is ready. */
    private static void registerSlates() {
        boolean slag = ModList.get().isLoaded("slag");
        for (CarvingMaterial m : CarvingMaterial.values()) {
            if (m.hasTools && (slag || m.vanillaTool != null)) {
                SMALL_SLATES.put(m, ITEMS.registerItem(m.id + "_slate",
                        p -> new SlateItem(p, m, false), new Item.Properties().stacksTo(16)));
            }
            if (slag || m.vanillaArmor != null) {
                BIG_SLATES.put(m, ITEMS.registerItem(m.id + "_big_slate",
                        p -> new SlateItem(p, m, true), new Item.Properties().stacksTo(16)));
            }
        }
    }

    public Carving(IEventBus modEventBus) {
        registerSlates(); // before ITEMS.register so the items are queued for the registry event
        ITEMS.register(modEventBus);
        BLOCKS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        MENUS.register(modEventBus);
        COMPONENTS.register(modEventBus);
        modEventBus.addListener(this::registerPayloads);
        modEventBus.addListener(this::buildCreativeTabs);
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");
        r.playToClient(OpenCarvingPayload.TYPE, OpenCarvingPayload.STREAM_CODEC, OpenCarvingPayload::handle);
        r.playToServer(CarveResultPayload.TYPE, CarveResultPayload.STREAM_CODEC, CarveResultPayload::handle);
        r.playToServer(StationCarveResultPayload.TYPE, StationCarveResultPayload.STREAM_CODEC,
                StationCarveResultPayload::handle);
    }

    private void buildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() != CreativeModeTabs.TOOLS_AND_UTILITIES) {
            return;
        }
        event.accept(CARVING_STATION_ITEM);
        event.accept(FLINT_BLOCK_ITEM);
        // only registered slates exist (Slag-only materials are skipped when Slag is absent)
        for (CarvingMaterial m : CarvingMaterial.values()) {
            if (SMALL_SLATES.containsKey(m)) {
                event.accept(SMALL_SLATES.get(m));
            }
            if (BIG_SLATES.containsKey(m)) {
                event.accept(BIG_SLATES.get(m));
            }
        }
    }

    /** Server-side: after Slag assembles a tool, apply the carved part's one-time durability penalty. */
    @EventBusSubscriber(modid = MODID)
    public static final class GameEvents {
        @SubscribeEvent
        public static void onPlayerTick(PlayerTickEvent.Post event) {
            if (event.getEntity() instanceof ServerPlayer player
                    && player.tickCount % 10 == 0
                    && ModList.get().isLoaded("slag")) {
                SlagCompat.scanAndPenalize(player);
            }
        }
    }

    // ---- the carved result (the heart of the direct-output model) ----------

    /** True when the carve of this material should yield a Slag part (vs a vanilla item / leather). */
    public static boolean usesSlag(CarvingMaterial m) {
        return m.slagId != null && ModList.get().isLoaded("slag");
    }

    /**
     * Build the carved result for (material, armor, kind): a slag:dynamic_part when {@link #usesSlag},
     * else the full vanilla tool/armor. {@code flaws} (tier 1) / {@code penalty} (tier 2) ride on it —
     * as a component for the slag part (SlagCompat applies it later) or as direct damage for vanilla.
     * Returns EMPTY if there is no valid result (e.g. a Slag-only material with Slag absent).
     */
    public static ItemStack resultStack(CarvingMaterial m, boolean armor, int kindIndex, int flaws, int penalty) {
        if (usesSlag(m)) {
            String part = armor ? ArmorKind.byIndex(kindIndex).id : ToolKind.byIndex(kindIndex).slagPart;
            ItemStack s;
            try {
                s = SlagCompat.buildDynamicPart(m.slagId, part);
            } catch (Throwable t) {
                s = ItemStack.EMPTY;
            }
            if (!s.isEmpty()) {
                if (flaws > 0) {
                    s.set(FLAWS.get(), flaws);
                }
                if (penalty > 0) {
                    s.set(PENALTY.get(), penalty);
                }
            }
            return s;
        }
        String prefix = armor ? m.vanillaArmor : m.vanillaTool;
        if (prefix == null) {
            return ItemStack.EMPTY;
        }
        String suffix = armor ? ArmorKind.byIndex(kindIndex).id : ToolKind.byIndex(kindIndex).id;
        Item it = BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(prefix + "_" + suffix));
        if (it == Items.AIR) {
            return ItemStack.EMPTY;
        }
        ItemStack s = new ItemStack(it);
        float frac = penalty > 0 ? penalty * 0.30F : flaws * 0.25F;
        if (frac > 0 && s.isDamageableItem()) {
            int max = s.getMaxDamage();
            s.setDamageValue(Math.max(0, Math.min(max - 1, Math.round(max * frac))));
        }
        return s;
    }

    /** The result with no penalty — used for tab icons / previews. */
    public static ItemStack iconStack(CarvingMaterial m, boolean armor, int kindIndex) {
        return resultStack(m, armor, kindIndex, 0, 0);
    }

    /** The carving-shape file key. Always the slag-part (tool-HEAD / armor-part) silhouette — berlord
     *  wants tool heads, not full tools; the OUTPUT still differs (slag part with Slag, else vanilla item). */
    public static String shapeKey(CarvingMaterial m, boolean armor, int kindIndex) {
        return "slag/" + (armor ? ArmorKind.byIndex(kindIndex).id : ToolKind.byIndex(kindIndex).slagPart);
    }
}
