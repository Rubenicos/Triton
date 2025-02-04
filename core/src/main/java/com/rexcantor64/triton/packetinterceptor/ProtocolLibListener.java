package com.rexcantor64.triton.packetinterceptor;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerOptions;
import com.comphenix.protocol.events.ListeningWhitelist;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;
import com.comphenix.protocol.injector.GamePhase;
import com.comphenix.protocol.reflect.MethodUtils;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.BukkitConverters;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.comphenix.protocol.wrappers.WrappedWatchableObject;
import com.comphenix.protocol.wrappers.nbt.NbtBase;
import com.comphenix.protocol.wrappers.nbt.NbtCompound;
import com.comphenix.protocol.wrappers.nbt.NbtFactory;
import com.comphenix.protocol.wrappers.nbt.NbtList;
import com.rexcantor64.triton.SpigotMLP;
import com.rexcantor64.triton.Triton;
import com.rexcantor64.triton.api.wrappers.EntityType;
import com.rexcantor64.triton.config.MainConfig;
import com.rexcantor64.triton.language.item.SignLocation;
import com.rexcantor64.triton.language.parser.AdvancedComponent;
import com.rexcantor64.triton.packetinterceptor.protocollib.AdvancementsPacketHandler;
import com.rexcantor64.triton.packetinterceptor.protocollib.SignPacketHandler;
import com.rexcantor64.triton.player.LanguagePlayer;
import com.rexcantor64.triton.player.SpigotLanguagePlayer;
import com.rexcantor64.triton.utils.ComponentUtils;
import com.rexcantor64.triton.utils.EntityTypeUtils;
import com.rexcantor64.triton.utils.NMSUtils;
import com.rexcantor64.triton.wrappers.AdventureComponentWrapper;
import lombok.SneakyThrows;
import lombok.val;
import lombok.var;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.TranslatableComponent;
import net.md_5.bungee.chat.ComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SuppressWarnings({"deprecation"})
public class ProtocolLibListener implements PacketListener, PacketInterceptor {
    private final Class<?> CONTAINER_PLAYER_CLASS;
    private final Class<?> MERCHANT_RECIPE_LIST_CLASS;
    private final Class<?> CRAFT_MERCHANT_RECIPE_LIST_CLASS;
    private final Class<?> BOSSBAR_UPDATE_TITLE_ACTION_CLASS;
    private final Class<BaseComponent[]> BASE_COMPONENT_ARRAY_CLASS = BaseComponent[].class;
    private StructureModifier<Object> SCOREBOARD_TEAM_METADATA_MODIFIER = null;
    private final Class<?> ADVENTURE_COMPONENT_CLASS;
    private final Field PLAYER_ACTIVE_CONTAINER_FIELD;
    private final String MERCHANT_RECIPE_SPECIAL_PRICE_FIELD;
    private final String MERCHANT_RECIPE_DEMAND_FIELD;

    private final SignPacketHandler signPacketHandler = new SignPacketHandler();
    private final AdvancementsPacketHandler advancementsPacketHandler;

    private final SpigotMLP main;
    private final Map<PacketType, BiConsumer<PacketEvent, SpigotLanguagePlayer>> packetHandlers = new HashMap<>();

    public ProtocolLibListener(SpigotMLP main) {
        this.main = main;
        if (main.getMcVersion() >= 17)
            MERCHANT_RECIPE_LIST_CLASS = NMSUtils.getClass("net.minecraft.world.item.trading.MerchantRecipeList");
        else if (main.getMcVersion() >= 14)
            MERCHANT_RECIPE_LIST_CLASS = NMSUtils.getNMSClass("MerchantRecipeList");
        else
            MERCHANT_RECIPE_LIST_CLASS = null;
        CRAFT_MERCHANT_RECIPE_LIST_CLASS = main.getMcVersion() >= 14 ? NMSUtils
                .getCraftbukkitClass("inventory.CraftMerchantRecipe") : null;
        CONTAINER_PLAYER_CLASS = main.getMcVersion() >= 17 ?
                NMSUtils.getClass("net.minecraft.world.inventory.ContainerPlayer") :
                NMSUtils.getNMSClass("ContainerPlayer");
        BOSSBAR_UPDATE_TITLE_ACTION_CLASS = main.getMcVersion() >= 17 ? NMSUtils.getClass("net.minecraft.network.protocol.game.PacketPlayOutBoss$e") : null;

        ADVENTURE_COMPONENT_CLASS = NMSUtils.getClassOrNull("net.kyori.adventure.text.Component");

        MERCHANT_RECIPE_SPECIAL_PRICE_FIELD = getMCVersion() >= 17 ? "g" : "specialPrice";
        MERCHANT_RECIPE_DEMAND_FIELD = getMCVersion() >= 17 ? "h" : "demand";

        val containerClass = MinecraftReflection.getMinecraftClass("world.inventory.Container", "Container");
        PLAYER_ACTIVE_CONTAINER_FIELD = Arrays.stream(MinecraftReflection.getEntityHumanClass().getDeclaredFields())
                .filter(field -> field.getType() == containerClass && !field.getName().equals("defaultContainer")).findAny().orElse(null);

        this.advancementsPacketHandler = getMCVersion() >= 12 ? new AdvancementsPacketHandler() : null;

        setupPacketHandlers();
    }

    @Override
    public Plugin getPlugin() {
        return main.getLoader();
    }

    private void setupPacketHandlers() {
        packetHandlers.put(PacketType.Play.Server.CHAT, this::handleChat);
        if (main.getMcVersion() >= 17) {
            // Title packet split on 1.17
            packetHandlers.put(PacketType.Play.Server.SET_TITLE_TEXT, this::handleTitle);
            packetHandlers.put(PacketType.Play.Server.SET_SUBTITLE_TEXT, this::handleTitle);

            // New actionbar packet
            packetHandlers.put(PacketType.Play.Server.SET_ACTION_BAR_TEXT, this::handleActionbar);
        } else {
            packetHandlers.put(PacketType.Play.Server.TITLE, this::handleTitle);
        }

        packetHandlers.put(PacketType.Play.Server.PLAYER_LIST_HEADER_FOOTER, this::handlePlayerListHeaderFooter);
        packetHandlers.put(PacketType.Play.Server.OPEN_WINDOW, this::handleOpenWindow);
        packetHandlers.put(PacketType.Play.Server.ENTITY_METADATA, this::handleEntityMetadata);
        packetHandlers.put(PacketType.Play.Server.SPAWN_ENTITY, this::handleSpawnEntity);
        packetHandlers.put(PacketType.Play.Server.SPAWN_ENTITY_LIVING, this::handleSpawnEntityLiving);
        packetHandlers.put(PacketType.Play.Server.NAMED_ENTITY_SPAWN, this::handleNamedEntitySpawn);
        packetHandlers.put(PacketType.Play.Server.ENTITY_DESTROY, this::handleEntityDestroy);
        packetHandlers.put(PacketType.Play.Server.PLAYER_INFO, this::handlePlayerInfo);
        packetHandlers.put(PacketType.Play.Server.KICK_DISCONNECT, this::handleKickDisconnect);
        if (main.getMcVersion() >= 13) { // Scoreboard rewrite on 1.13
            packetHandlers.put(PacketType.Play.Server.SCOREBOARD_TEAM, this::handleScoreboardTeam);
            packetHandlers.put(PacketType.Play.Server.SCOREBOARD_OBJECTIVE, this::handleScoreboardObjective);
        }
        signPacketHandler.registerPacketTypes(packetHandlers);
        if (advancementsPacketHandler != null) {
            advancementsPacketHandler.registerPacketTypes(packetHandlers);
        }
        packetHandlers.put(PacketType.Play.Server.WINDOW_ITEMS, this::handleWindowItems);
        packetHandlers.put(PacketType.Play.Server.SET_SLOT, this::handleSetSlot);
        if (getMCVersion() >= 9) packetHandlers.put(PacketType.Play.Server.BOSS, this::handleBoss);
        if (getMCVersion() >= 14)
            packetHandlers.put(PacketType.Play.Server.OPEN_WINDOW_MERCHANT, this::handleMerchantItems);
    }

    /* PACKET HANDLERS */

    private void handleChat(PacketEvent packet, SpigotLanguagePlayer languagePlayer) {
        boolean ab = isActionbar(packet.getPacket());

        // Don't bother parsing anything else if it's disabled on config
        if ((ab && !main.getConfig().isActionbars()) || (!ab && !main.getConfig().isChat())) return;

        val baseComponentModifier = packet.getPacket().getSpecificModifier(BASE_COMPONENT_ARRAY_CLASS);
        BaseComponent[] result = null;

        // Hot fix for 1.16 Paper builds 472+ (and 1.17+)
        StructureModifier<?> adventureModifier =
                ADVENTURE_COMPONENT_CLASS == null ? null : packet.getPacket().getSpecificModifier(ADVENTURE_COMPONENT_CLASS);

        if (adventureModifier != null && adventureModifier.readSafely(0) != null) {
            Object adventureComponent = adventureModifier.readSafely(0);
            result = AdventureComponentWrapper.toMd5Component(adventureComponent);
            adventureModifier.writeSafely(0, null);
        } else if (baseComponentModifier.readSafely(0) != null) {
            result = baseComponentModifier.readSafely(0);
        } else {
            val msg = packet.getPacket().getChatComponents().readSafely(0);
            if (msg != null) result = ComponentSerializer.parse(msg.getJson());
        }

        // Something went wrong while getting data from the packet, or the packet is empty...?
        if (result == null) return;

        // Translate the message
        result = main.getLanguageParser().parseComponent(
                languagePlayer,
                ab ? main.getConf().getActionbarSyntax() : main.getConf().getChatSyntax(),
                result);

        // Handle disabled line
        if (result == null) {
            packet.setCancelled(true);
            return;
        }

        // Flatten action bar's json
        baseComponentModifier.writeSafely(0, ab && getMCVersion() < 16 ? ComponentUtils.mergeComponents(result) : result);
    }

    private void handleActionbar(PacketEvent packet, SpigotLanguagePlayer languagePlayer) {
        if (!main.getConf().isActionbars()) return;

        val baseComponentModifier = packet.getPacket().getSpecificModifier(BASE_COMPONENT_ARRAY_CLASS);
        BaseComponent[] result = null;

        // Hot fix for Paper builds 472+
        StructureModifier<?> adventureModifier =
                ADVENTURE_COMPONENT_CLASS == null ? null : packet.getPacket().getSpecificModifier(ADVENTURE_COMPONENT_CLASS);

        if (adventureModifier != null && adventureModifier.readSafely(0) != null) {
            Object adventureComponent = adventureModifier.readSafely(0);
            result = AdventureComponentWrapper.toMd5Component(adventureComponent);
            adventureModifier.writeSafely(0, null);
        } else if (baseComponentModifier.readSafely(0) != null) {
            result = baseComponentModifier.readSafely(0);
            baseComponentModifier.writeSafely(0, null);
        } else {
            val msg = packet.getPacket().getChatComponents().readSafely(0);
            if (msg != null) result = ComponentSerializer.parse(msg.getJson());
        }

        // Something went wrong while getting data from the packet, or the packet is empty...?
        if (result == null) return;

        // Translate the message
        result = main.getLanguageParser().parseComponent(
                languagePlayer,
                main.getConf().getActionbarSyntax(),
                result);

        // Handle disabled line
        if (result == null) {
            packet.setCancelled(true);
            return;
        }

        // Flatten action bar's json
        packet.getPacket().getChatComponents().writeSafely(0, WrappedChatComponent.fromJson(ComponentSerializer.toString(result)));
    }

    private void handleTitle(PacketEvent packet, SpigotLanguagePlayer languagePlayer) {
        if (!main.getConf().isTitles()) return;

        WrappedChatComponent msg = packet.getPacket().getChatComponents().readSafely(0);
        if (msg == null) return;
        BaseComponent[] result = main.getLanguageParser().parseComponent(languagePlayer,
                main.getConf().getTitleSyntax(), ComponentSerializer.parse(msg.getJson()));
        if (result == null) {
            packet.setCancelled(true);
            return;
        }
        msg.setJson(ComponentSerializer.toString(result));
        packet.getPacket().getChatComponents().writeSafely(0, msg);
    }

    private void handlePlayerListHeaderFooter(PacketEvent packet, SpigotLanguagePlayer languagePlayer) {
        if (!main.getConf().isTab()) return;
        StructureModifier<?> adventureModifier =
                ADVENTURE_COMPONENT_CLASS == null ? null : packet.getPacket().getSpecificModifier(ADVENTURE_COMPONENT_CLASS);

        WrappedChatComponent header = packet.getPacket().getChatComponents().readSafely(0);
        String headerJson = null;
        WrappedChatComponent footer = packet.getPacket().getChatComponents().readSafely(1);
        String footerJson = null;

        if (adventureModifier != null && adventureModifier.readSafely(0) != null
                && adventureModifier.readSafely(1) != null) {
            // Paper 1.18 builds now have Adventure Component fields for header and footer, handle conversion
            // In future versions we might implement an Adventure parser
            Object adventureHeader = adventureModifier.readSafely(0);
            Object adventureFooter = adventureModifier.readSafely(1);

            headerJson = AdventureComponentWrapper.toJson(adventureHeader);
            footerJson = AdventureComponentWrapper.toJson(adventureFooter);

            adventureModifier.writeSafely(0, null);
            adventureModifier.writeSafely(1, null);
        }
        if (headerJson == null) {
            if (header == null || footer == null) {
                Triton.get().getLogger().logWarning("Could not translate player list header footer because content is null.");
                return;
            }
            headerJson = header.getJson();
            footerJson = footer.getJson();
        }

        BaseComponent[] resultHeader = main.getLanguageParser().parseComponent(languagePlayer,
                main.getConf().getTabSyntax(), ComponentSerializer.parse(headerJson));
        if (resultHeader == null)
            resultHeader = new BaseComponent[]{new TextComponent("")};
        else if (resultHeader.length == 1 && resultHeader[0] instanceof TextComponent) {
            // This is needed because the Notchian client does not render the header/footer
            // if the content of the header top level component is an empty string.
            val textComp = (TextComponent) resultHeader[0];
            if (textComp.getText().length() == 0 && !headerJson.equals("{\"text\":\"\"}"))
                textComp.setText("§0§1§2§r");
        }
        header = WrappedChatComponent.fromJson(ComponentSerializer.toString(resultHeader));
        packet.getPacket().getChatComponents().writeSafely(0, header);

        BaseComponent[] resultFooter = main.getLanguageParser().parseComponent(languagePlayer,
                main.getConf().getTabSyntax(), ComponentSerializer.parse(footerJson));
        if (resultFooter == null)
            resultFooter = new BaseComponent[]{new TextComponent("")};
        footer = WrappedChatComponent.fromJson(ComponentSerializer.toString(resultFooter));
        packet.getPacket().getChatComponents().writeSafely(1, footer);
        languagePlayer.setLastTabHeader(headerJson);
        languagePlayer.setLastTabFooter(footerJson);
    }

    private void handleOpenWindow(PacketEvent packet, SpigotLanguagePlayer languagePlayer) {
        if (!main.getConf().isGuis()) return;

        WrappedChatComponent msg = packet.getPacket().getChatComponents().readSafely(0);
        BaseComponent[] result = main.getLanguageParser()
                .parseComponent(languagePlayer, main.getConf().getGuiSyntax(), ComponentSerializer
                        .parse(msg.getJson()));
        if (result == null)
            result = new BaseComponent[]{new TextComponent("")};
        msg.setJson(ComponentSerializer.toString(ComponentUtils.mergeComponents(result)));
        packet.getPacket().getChatComponents().writeSafely(0, msg);
    }

    private void handleNamedEntitySpawn(PacketEvent packet, SpigotLanguagePlayer languagePlayer) {
        if ((!main.getConf().isHologramsAll() && !main.getConf().getHolograms()
                .contains(EntityType.PLAYER)))
            return;
        // TODO For now, it is only possible to translate NPCs that are saved server side
        Triton.asSpigot().callSync(() -> packet.getPacket().getEntityModifier(packet).readSafely(0))
                .ifPresent(entity -> addPlayer(packet.getPlayer().getWorld(), entity.getEntityId(), entity, languagePlayer));
    }

    private void handleSpawnEntityLiving(PacketEvent packet, SpigotLanguagePlayer languagePlayer) {
        int entityId = packet.getPacket().getIntegers().readSafely(0);
        int type = packet.getPacket().getIntegers().readSafely(1);
        EntityType et = EntityTypeUtils.getEntityTypeById(type);
        if ((!main.getConf().isHologramsAll() && !main.getConf().getHolograms()
                .contains(et)))
            return;
        if (et == EntityType.PLAYER)
            return;
        addEntity(packet.getPlayer().getWorld(), entityId, null, languagePlayer);
        if (getMCVersion() >= 15) return; // DataWatcher is not sent on 1.15 anymore in this packet
        // Clone the data watcher, so we don't edit the display name permanently
        WrappedDataWatcher dataWatcher = new WrappedDataWatcher(new ArrayList<>(packet.getPacket()
                .getDataWatcherModifier()
                .readSafely(0).asMap().values()));
        WrappedWatchableObject watchableObject = dataWatcher.getWatchableObject(2);
        if (watchableObject == null) return;
        boolean forceHideCustomName = false;
        if (getMCVersion() >= 13) {
            Optional optional = (Optional) watchableObject.getValue();
            if (optional.isPresent()) {
                String displayName = WrappedChatComponent.fromHandle(optional.get()).getJson();
                addEntity(packet.getPlayer().getWorld(), entityId, displayName, languagePlayer);
                BaseComponent[] result = main.getLanguageParser().parseComponent(languagePlayer, main.getConf()
                        .getHologramSyntax(), ComponentSerializer
                        .parse(displayName));
                if (result != null)
                    dataWatcher.setObject(2, new WrappedWatchableObject(watchableObject.getWatcherObject(),
                            Optional.of(WrappedChatComponent.fromJson(ComponentSerializer
                                            .toString(result))
                                    .getHandle())));
                else {
                    dataWatcher.setObject(2, new WrappedWatchableObject(watchableObject.getWatcherObject(),
                            Optional.empty()));
                    forceHideCustomName = true;
                }
            }
        } else if (getMCVersion() >= 9) {
            addEntity(packet.getPlayer().getWorld(), entityId, (String) watchableObject.getValue(), languagePlayer);
            String result = translate((String) watchableObject.getValue(), languagePlayer, main.getConf()
                    .getHologramSyntax());
            if (result != null)
                dataWatcher.setObject(2, new WrappedWatchableObject(watchableObject.getWatcherObject(), result));
            else {
                dataWatcher.setObject(2, new WrappedWatchableObject(watchableObject.getWatcherObject(),
                        ""));
                forceHideCustomName = true;
            }
        } else {
            addEntity(packet.getPlayer().getWorld(), entityId, (String) watchableObject.getValue(), languagePlayer);
            String result = translate((String) watchableObject.getValue(), languagePlayer, main.getConf()
                    .getHologramSyntax());
            if (result != null)
                dataWatcher.setObject(2, new WrappedWatchableObject(watchableObject.getIndex(), result));
            else {
                dataWatcher.setObject(2, new WrappedWatchableObject(watchableObject.getIndex(),
                        ""));
                forceHideCustomName = true;
            }
        }
        if (forceHideCustomName) {
            WrappedWatchableObject isCustomName = dataWatcher.getWatchableObject(3);
            if (isCustomName != null)
                if (getMCVersion() >= 9)
                    dataWatcher.setObject(3, new WrappedWatchableObject(isCustomName.getWatcherObject(), false));
                else
                    dataWatcher.setObject(3, new WrappedWatchableObject(isCustomName.getIndex(), (byte) 0));
        }
        packet.getPacket().getDataWatcherModifier().writeSafely(0, dataWatcher);
    }

    private void handleSpawnEntity(PacketEvent packet, SpigotLanguagePlayer languagePlayer) {
        int entityId = packet.getPacket().getIntegers().readSafely(0);
        EntityType et;
        if (getMCVersion() >= 14)
            et = EntityType.fromBukkit(packet.getPacket().getEntityTypeModifier().readSafely(0));
        else if (getMCVersion() >= 9)
            et = EntityTypeUtils.getEntityTypeByObjectId(packet.getPacket().getIntegers().readSafely(6));
        else
            et = EntityTypeUtils.getEntityTypeByObjectId(packet.getPacket().getIntegers().readSafely(9));
        if ((!main.getConf().isHologramsAll() && !main.getConf().getHolograms()
                .contains(et)))
            return;
        if (et == EntityType.PLAYER)
            return;
        addEntity(packet.getPlayer().getWorld(), entityId, null, languagePlayer);
    }

    private void handleEntityMetadata(PacketEvent packet, SpigotLanguagePlayer languagePlayer) {
        int entityId = packet.getPacket().getIntegers().readSafely(0);

        val worldEntitiesMap = languagePlayer.getEntitiesMap().get(packet.getPlayer().getWorld());
        if (worldEntitiesMap == null || !worldEntitiesMap.containsKey(entityId))
            return;

        List<WrappedWatchableObject> dw = packet.getPacket().getWatchableCollectionModifier().readSafely(0);
        if (dw == null) {
            // The DataWatcher.Item List is Nullable
            // Since it's null, it doesn't have any text to translate anyway, so just ignore it
            return;
        }

        List<WrappedWatchableObject> dwn = new ArrayList<>();
        boolean forceHideCustomName = false;
        for (WrappedWatchableObject obj : dw) {
            // Index 2 is "Custom Name" of type "OptChat"
            // https://wiki.vg/Entity_metadata#Entity_Metadata_Format
            if (obj.getIndex() == 2) {
                if (getMCVersion() < 9) {
                    addEntity(packet.getPlayer().getWorld(), entityId, (String) obj.getValue(), languagePlayer);
                    String result = translate((String) obj.getValue(), languagePlayer, main.getConf()
                            .getHologramSyntax());
                    if (result != null)
                        dwn.add(new WrappedWatchableObject(obj.getIndex(), result));
                    else {
                        dwn.add(new WrappedWatchableObject(obj.getIndex(), ""));
                        forceHideCustomName = true;
                    }
                } else if (getMCVersion() < 13) {
                    addEntity(packet.getPlayer().getWorld(), entityId, (String) obj.getValue(), languagePlayer);
                    String result = translate((String) obj.getValue(), languagePlayer, main.getConf()
                            .getHologramSyntax());
                    if (result != null)
                        dwn.add(new WrappedWatchableObject(obj.getWatcherObject(), result));
                    else {
                        dwn.add(new WrappedWatchableObject(obj.getWatcherObject(), ""));
                        forceHideCustomName = true;
                    }
                } else {
                    Optional optional = (Optional) obj.getValue();
                    if (optional.isPresent()) {
                        addEntity(packet.getPlayer().getWorld(), entityId, WrappedChatComponent
                                .fromHandle(optional.get())
                                .getJson(), languagePlayer);
                        BaseComponent[] result = main.getLanguageParser().parseComponent(languagePlayer, main.getConf()
                                .getHologramSyntax(), ComponentSerializer
                                .parse(WrappedChatComponent.fromHandle(optional.get()).getJson()));
                        if (result != null)
                            dwn.add(new WrappedWatchableObject(obj.getWatcherObject(),
                                    Optional.of(WrappedChatComponent.fromJson(ComponentSerializer
                                                    .toString(result))
                                            .getHandle())));
                        else {
                            dwn.add(new WrappedWatchableObject(obj.getWatcherObject(), Optional.empty()));
                            forceHideCustomName = true;
                        }
                    } else dwn.add(obj);
                }
            } else if (obj.getIndex() == 3) {
                // Index 3 is "Is custom name visible" of type "Boolean"
                // https://wiki.vg/Entity_metadata#Entity_Metadata_Format
                if (forceHideCustomName) {
                    if (getMCVersion() >= 9)
                        dwn.add(new WrappedWatchableObject(obj.getWatcherObject(), false));
                    else
                        dwn.add(new WrappedWatchableObject(obj.getIndex(), (byte) 0));
                } else {
                    dwn.add(obj);
                }
            } else {
                dwn.add(obj);
            }
        }
        packet.getPacket().getWatchableCollectionModifier().writeSafely(0, dwn);
    }

    private void handleEntityDestroy(PacketEvent packet, SpigotLanguagePlayer languagePlayer) {
        Stream<Integer> ids;
        if (packet.getPacket().getIntegers().size() > 0)
            ids = Stream.of(packet.getPacket().getIntegers().readSafely(0));
        else if (packet.getPacket().getIntegerArrays().size() > 0)
            ids = Arrays.stream(packet.getPacket().getIntegerArrays().readSafely(0)).boxed();
        else
            ids = ((List<Integer>) packet.getPacket().getModifier().readSafely(0)).stream();
        removeEntities(
                ids,
                languagePlayer.getEntitiesMap().get(packet.getPlayer().getWorld()),
                languagePlayer.getPlayersMap().get(packet.getPlayer().getWorld())
        );
    }

    private void handlePlayerInfo(PacketEvent packet, SpigotLanguagePlayer languagePlayer) {
        if (!main.getConf().isHologramsAll() && !main.getConf().getHolograms().contains(EntityType.PLAYER)) return;

        EnumWrappers.PlayerInfoAction infoAction = packet.getPacket().getPlayerInfoAction().readSafely(0);
        List<PlayerInfoData> dataList = packet.getPacket().getPlayerInfoDataLists().readSafely(0);
        if (infoAction == EnumWrappers.PlayerInfoAction.REMOVE_PLAYER) {
            for (PlayerInfoData data : dataList)
                languagePlayer.getShownPlayers().remove(data.getProfile().getUUID());
            return;
        }

        if (infoAction != EnumWrappers.PlayerInfoAction.ADD_PLAYER && infoAction != EnumWrappers.PlayerInfoAction.UPDATE_DISPLAY_NAME)
            return;

        List<PlayerInfoData> dataListNew = new ArrayList<>();
        for (PlayerInfoData data : dataList) {
            WrappedGameProfile oldGP = data.getProfile();
            languagePlayer.getShownPlayers().add(oldGP.getUUID());
            WrappedGameProfile newGP = oldGP.withName(translate(languagePlayer, oldGP.getName(), 16,
                    main.getConf().getHologramSyntax()));
            newGP.getProperties().putAll(oldGP.getProperties());
            WrappedChatComponent msg = data.getDisplayName();
            if (msg != null) {
                BaseComponent[] result = main.getLanguageParser().parseComponent(languagePlayer,
                        main.getConf().getHologramSyntax(), ComponentSerializer.parse(msg.getJson()));
                if (result == null)
                    msg = null;
                else
                    msg.setJson(ComponentSerializer.toString(result));
            }
            dataListNew.add(new PlayerInfoData(newGP, data.getLatency(), data.getGameMode(), msg));
        }
        packet.getPacket().getPlayerInfoDataLists().writeSafely(0, dataListNew);
    }

    private void handleKickDisconnect(PacketEvent packet, SpigotLanguagePlayer languagePlayer) {
        if (!main.getConf().isKick()) return;

        WrappedChatComponent msg = packet.getPacket().getChatComponents().readSafely(0);
        BaseComponent[] result = main.getLanguageParser().parseComponent(languagePlayer,
                main.getConf().getKickSyntax(), ComponentSerializer.parse(msg.getJson()));
        if (result == null)
            result = new BaseComponent[]{new TextComponent("")};
        msg.setJson(ComponentSerializer.toString(result));
        packet.getPacket().getChatComponents().writeSafely(0, msg);
    }

    private void handleWindowItems(PacketEvent packet, SpigotLanguagePlayer languagePlayer) {
        if (!main.getConf().isItems()) return;

        if (!main.getConf().isInventoryItems() && isPlayerInventoryOpen(packet.getPlayer()))
            return;

        List<ItemStack> items = getMCVersion() <= 10 ?
                Arrays.asList(packet.getPacket().getItemArrayModifier().readSafely(0)) :
                packet.getPacket().getItemListModifier().readSafely(0);
        for (ItemStack item : items)
            translateItemStack(item, languagePlayer, true);
        if (getMCVersion() <= 10)
            packet.getPacket().getItemArrayModifier().writeSafely(0, items.toArray(new ItemStack[0]));
        else
            packet.getPacket().getItemListModifier().writeSafely(0, items);
    }

    private void handleSetSlot(PacketEvent packet, SpigotLanguagePlayer languagePlayer) {
        if (!main.getConf().isItems()) return;

        if (!main.getConf().isInventoryItems() && isPlayerInventoryOpen(packet.getPlayer()))
            return;

        ItemStack item = packet.getPacket().getItemModifier().readSafely(0);
        translateItemStack(item, languagePlayer, true);
        packet.getPacket().getItemModifier().writeSafely(0, item);
    }

    @SneakyThrows
    private void handleBoss(PacketEvent packet, SpigotLanguagePlayer languagePlayer) {
        if (!main.getConf().isBossbars()) return;

        val uuid = packet.getPacket().getUUIDs().readSafely(0);
        WrappedChatComponent bossbar;
        Object actionObj = null;

        if (getMCVersion() >= 17) {
            actionObj = packet.getPacket().getModifier().readSafely(1);
            val method = actionObj.getClass().getMethod("a");
            method.setAccessible(true);
            val actionEnum = ((Enum<?>) method.invoke(actionObj)).ordinal();
            if (actionEnum == 1) {
                languagePlayer.removeBossbar(uuid);
                return;
            }
            if (actionEnum != 0 && actionEnum != 3) return;

            bossbar = WrappedChatComponent.fromHandle(NMSUtils.getDeclaredField(actionObj, "a"));
        } else {
            Action action = packet.getPacket().getEnumModifier(Action.class, 1).readSafely(0);
            if (action == Action.REMOVE) {
                languagePlayer.removeBossbar(uuid);
                return;
            }
            if (action != Action.ADD && action != Action.UPDATE_NAME) return;

            bossbar = packet.getPacket().getChatComponents().readSafely(0);
        }


        try {
            languagePlayer.setBossbar(uuid, bossbar.getJson());
            BaseComponent[] result = main.getLanguageParser().parseComponent(languagePlayer,
                    main.getConf().getBossbarSyntax(), ComponentSerializer.parse(bossbar.getJson()));
            if (result == null)
                result = new BaseComponent[]{new TranslatableComponent("")};
            bossbar.setJson(ComponentSerializer.toString(result));
            if (getMCVersion() >= 17) {
                NMSUtils.setDeclaredField(actionObj, "a", bossbar.getHandle());
            } else {
                packet.getPacket().getChatComponents().writeSafely(0, bossbar);
            }
        } catch (RuntimeException e) {
            // Catch 1.16 Hover 'contents' not being parsed correctly
            // Has been fixed in newer versions of Spigot 1.16
            Triton.get().getLogger()
                    .logError(1, "Could not parse a bossbar, so it was ignored. Bossbar: %1", bossbar.getJson());
        }
    }

    @SuppressWarnings({"unchecked"})
    private void handleMerchantItems(PacketEvent packet, SpigotLanguagePlayer languagePlayer) {
        if (!main.getConf().isItems()) return;

        try {
            ArrayList<?> recipes = (ArrayList<?>) packet.getPacket()
                    .getSpecificModifier(MERCHANT_RECIPE_LIST_CLASS).readSafely(0);
            ArrayList<Object> newRecipes = (ArrayList<Object>) MERCHANT_RECIPE_LIST_CLASS.newInstance();
            for (val recipeObject : recipes) {
                val recipe = (MerchantRecipe) NMSUtils.getMethod(recipeObject, "asBukkit");
                val originalSpecialPrice = NMSUtils.getDeclaredField(recipeObject, MERCHANT_RECIPE_SPECIAL_PRICE_FIELD);
                val originalDemand = NMSUtils.getDeclaredField(recipeObject, MERCHANT_RECIPE_DEMAND_FIELD);

                val newRecipe = new MerchantRecipe(translateItemStack(recipe.getResult()
                        .clone(), languagePlayer, false), recipe.getUses(), recipe.getMaxUses(), recipe
                        .hasExperienceReward(), recipe.getVillagerExperience(), recipe.getPriceMultiplier());

                for (val ingredient : recipe.getIngredients()) {
                    newRecipe.addIngredient(translateItemStack(ingredient.clone(), languagePlayer, false));
                }
                Object newCraftRecipe = MethodUtils
                        .invokeExactStaticMethod(CRAFT_MERCHANT_RECIPE_LIST_CLASS, "fromBukkit", newRecipe);
                Object newNMSRecipe = MethodUtils.invokeExactMethod(newCraftRecipe, "toMinecraft", null);
                NMSUtils.setDeclaredField(newNMSRecipe, MERCHANT_RECIPE_SPECIAL_PRICE_FIELD, originalSpecialPrice);
                NMSUtils.setDeclaredField(newNMSRecipe, MERCHANT_RECIPE_DEMAND_FIELD, originalDemand);
                newRecipes.add(newNMSRecipe);
            }
            packet.getPacket().getModifier().writeSafely(1, newRecipes);
        } catch (IllegalAccessException | InstantiationException | NoSuchMethodException | InvocationTargetException e) {
            e.printStackTrace();
        }
    }

    private void handleScoreboardTeam(PacketEvent packet, SpigotLanguagePlayer languagePlayer) {
        if (!main.getConf().isScoreboards()) return;

        val teamName = packet.getPacket().getStrings().readSafely(0);
        val mode = packet.getPacket().getIntegers().readSafely(0);

        if (mode == 1) {
            languagePlayer.removeScoreboardTeam(teamName);
            return;
        }

        if (mode != 0 && mode != 2) return; // Other modes don't change text

        // Pack name tag visibility, collision rule, team color and friendly flags into list
        val modifiers = packet.getPacket().getModifier();
        List<Object> options;
        WrappedChatComponent displayName, prefix, suffix;
        StructureModifier<WrappedChatComponent> chatComponents;

        if (getMCVersion() >= 17) {
            Optional<?> meta = (Optional<?>) modifiers.readSafely(3);
            if (!meta.isPresent()) return;

            val obj = meta.get();

            if (SCOREBOARD_TEAM_METADATA_MODIFIER == null)
                SCOREBOARD_TEAM_METADATA_MODIFIER = new StructureModifier<>(obj.getClass());
            val structure = SCOREBOARD_TEAM_METADATA_MODIFIER.withTarget(obj);

            options = Stream.of(3, 4, 5, 6).map(structure::readSafely).collect(Collectors.toList());

            chatComponents = structure.withType(MinecraftReflection.getIChatBaseComponentClass(), BukkitConverters.getWrappedChatComponentConverter());
            displayName = chatComponents.read(0);
            prefix = chatComponents.read(1);
            suffix = chatComponents.read(2);
        } else {
            options = Stream.of(4, 5, 6, 9).map(modifiers::readSafely).collect(Collectors.toList());

            chatComponents = packet.getPacket().getChatComponents();
            displayName = chatComponents.readSafely(0);
            prefix = chatComponents.readSafely(1);
            suffix = chatComponents.readSafely(2);
        }

        languagePlayer.setScoreboardTeam(teamName, displayName.getJson(), prefix.getJson(), suffix.getJson(), options);

        int i = 0;
        for (WrappedChatComponent component : Arrays.asList(displayName, prefix, suffix)) {
            BaseComponent[] result = main.getLanguageParser()
                    .parseComponent(languagePlayer, main.getConf().getScoreboardSyntax(), ComponentSerializer
                            .parse(component.getJson()));
            if (result == null) result = new BaseComponent[]{new TextComponent("")};
            component.setJson(ComponentSerializer.toString(result));
            chatComponents.writeSafely(i++, component);
        }
    }

    private void handleScoreboardObjective(PacketEvent packet, SpigotLanguagePlayer languagePlayer) {
        if (!main.getConf().isScoreboards()) return;

        val objectiveName = packet.getPacket().getStrings().readSafely(0);
        val mode = packet.getPacket().getIntegers().readSafely(0);

        if (mode == 1) {
            languagePlayer.removeScoreboardObjective(objectiveName);
            return;
        }
        // There are only 3 modes, so no need to check for more modes

        val healthDisplay = packet.getPacket().getModifier().readSafely(2);
        val displayName = packet.getPacket().getChatComponents().readSafely(0);

        languagePlayer.setScoreboardObjective(objectiveName, displayName.getJson(), healthDisplay);

        BaseComponent[] result = main.getLanguageParser()
                .parseComponent(languagePlayer, main.getConf().getScoreboardSyntax(), ComponentSerializer
                        .parse(displayName.getJson()));
        if (result == null) result = new BaseComponent[]{new TextComponent("")};
        displayName.setJson(ComponentSerializer.toString(result));
        packet.getPacket().getChatComponents().writeSafely(0, displayName);
    }

    /* PROTOCOL LIB */

    @Override
    public void onPacketSending(PacketEvent packet) {
        if (!packet.isServerPacket()) return;
        SpigotLanguagePlayer languagePlayer;
        try {
            languagePlayer =
                    (SpigotLanguagePlayer) Triton.get().getPlayerManager().get(packet.getPlayer().getUniqueId());
        } catch (Exception e) {
            Triton.get().getLogger()
                    .logWarning(1, "Failed to translate packet because UUID of the player is unknown (because " +
                            "the player hasn't joined yet).");
            if (Triton.get().getConfig().getLogLevel() >= 1)
                e.printStackTrace();
            return;
        }
        if (languagePlayer == null) {
            Triton.get().getLogger().logWarning(1, "Language Player is null on packet sending");
            return;
        }

        val handler = packetHandlers.get(packet.getPacketType());
        if (handler != null) {
            handler.accept(packet, languagePlayer);
        }
    }

    @Override
    public void onPacketReceiving(PacketEvent packet) {
        if (packet.isServerPacket()) return;
        SpigotLanguagePlayer languagePlayer;
        try {
            languagePlayer =
                    (SpigotLanguagePlayer) Triton.get().getPlayerManager().get(packet.getPlayer().getUniqueId());
        } catch (Exception ignore) {
            Triton.get().getLogger()
                    .logWarning(1, "Failed to get SpigotLanguagePlayer because UUID of the player is unknown " +
                            "(because the player hasn't joined yet).");
            return;
        }
        if (languagePlayer == null) {
            Triton.get().getLogger().logWarning(1, "Language Player is null on packet receiving");
            return;
        }
        if (packet.getPacketType() == PacketType.Play.Client.SETTINGS) {
            if (languagePlayer.isWaitingForClientLocale())
                Bukkit.getScheduler().runTask(Triton.asSpigot().getLoader(), () -> languagePlayer
                        .setLang(Triton.get().getLanguageManager()
                                .getLanguageByLocale(packet.getPacket().getStrings().readSafely(0), true)));
        }
    }

    @Override
    public ListeningWhitelist getSendingWhitelist() {
        return ListeningWhitelist.newBuilder()
                .gamePhase(GamePhase.PLAYING)
                .types(packetHandlers.keySet())
                .mergeOptions(ListenerOptions.ASYNC)
                .highest()
                .build();
    }

    @Override
    public ListeningWhitelist getReceivingWhitelist() {
        return ListeningWhitelist.newBuilder()
                .gamePhase(GamePhase.PLAYING)
                .types(PacketType.Play.Client.SETTINGS)
                .mergeOptions(ListenerOptions.ASYNC)
                .highest()
                .build();
    }

    /* REFRESH */

    @Override
    public void refreshSigns(SpigotLanguagePlayer player) {
        signPacketHandler.refreshSignsForPlayer(player);
    }

    @Override
    public void refreshEntities(SpigotLanguagePlayer player) {
        if (!player.toBukkit().isPresent()) return;
        val bukkitPlayer = player.toBukkit().get();
        if (player.getEntitiesMap().containsKey(bukkitPlayer.getWorld()))
            for (Map.Entry<Integer, Optional<String>> entry : player.getEntitiesMap().get(bukkitPlayer.getWorld())
                    .entrySet()) {
                if (!entry.getValue().isPresent()) continue;
                val displayName = entry.getValue().get();
                PacketContainer packet =
                        ProtocolLibrary.getProtocolManager().createPacket(PacketType.Play.Server.ENTITY_METADATA);
                packet.getIntegers().writeSafely(0, entry.getKey());
                boolean forceHideCustomName = false;
                Object value;
                if (getMCVersion() >= 13) {
                    BaseComponent[] result = main.getLanguageParser().parseComponent(player, main.getConf()
                            .getHologramSyntax(), ComponentSerializer.parse(displayName));
                    if (result != null)
                        value = Optional.of(WrappedChatComponent.fromJson(ComponentSerializer
                                        .toString(result))
                                .getHandle());
                    else {
                        value = Optional.empty();
                        forceHideCustomName = true;
                    }
                } else {
                    value = main.getLanguageParser().replaceLanguages(main.getLanguageManager()
                                    .matchPattern(displayName, player), player,
                            main.getConf().getHologramSyntax());
                    if (value == null) {
                        value = "";
                        forceHideCustomName = true;
                    }
                }
                List<WrappedWatchableObject> watchableObjects = new ArrayList<>();
                WrappedWatchableObject watchableObject;
                if (getMCVersion() >= 9)
                    watchableObject = new WrappedWatchableObject(new WrappedDataWatcher.WrappedDataWatcherObject(2,
                            getMCVersion() >= 13 ? WrappedDataWatcher.Registry
                                    .getChatComponentSerializer(true) : WrappedDataWatcher.Registry
                                    .get(String.class)), value);
                else
                    watchableObject = new WrappedWatchableObject(2, value);
                watchableObjects.add(watchableObject);
                if (forceHideCustomName) {
                    WrappedWatchableObject customNameVisibility;
                    if (getMCVersion() >= 9)
                        customNameVisibility =
                                new WrappedWatchableObject(new WrappedDataWatcher.WrappedDataWatcherObject(3,
                                        WrappedDataWatcher.Registry
                                                .get(Boolean.class)), false);
                    else
                        customNameVisibility = new WrappedWatchableObject(3, (byte) 0);
                    watchableObjects.add(customNameVisibility);
                }
                packet.getWatchableCollectionModifier().writeSafely(0, watchableObjects);
                try {
                    ProtocolLibrary.getProtocolManager().sendServerPacket(bukkitPlayer, packet, false);
                } catch (InvocationTargetException e) {
                    main.getLogger().logError("Failed to send entity update packet: %1", e.getMessage());
                    e.printStackTrace();
                }
            }

        if (player.getPlayersMap().containsKey(bukkitPlayer.getWorld()))
            playerLoop:
                    for (Map.Entry<Integer, Entity> entry : player.getPlayersMap().get(bukkitPlayer.getWorld())
                            .entrySet()) {
                        val p = (Player) entry.getValue();
                        for (val op : Bukkit.getOnlinePlayers())
                            if (op.getUniqueId().equals(p.getUniqueId())) continue playerLoop;

                        val dataList = new ArrayList<PlayerInfoData>();
                        dataList.add(new PlayerInfoData(WrappedGameProfile.fromPlayer(p), 50,
                                EnumWrappers.NativeGameMode.fromBukkit(p.getGameMode()),
                                WrappedChatComponent.fromText(p.getPlayerListName())));

                        val packetRemove =
                                ProtocolLibrary.getProtocolManager().createPacket(PacketType.Play.Server.PLAYER_INFO);
                        packetRemove.getPlayerInfoAction().writeSafely(0, EnumWrappers.PlayerInfoAction.REMOVE_PLAYER);
                        packetRemove.getPlayerInfoDataLists().writeSafely(0, dataList);

                        val packetAdd =
                                ProtocolLibrary.getProtocolManager().createPacket(PacketType.Play.Server.PLAYER_INFO);
                        packetAdd.getPlayerInfoAction().writeSafely(0, EnumWrappers.PlayerInfoAction.ADD_PLAYER);
                        packetAdd.getPlayerInfoDataLists().writeSafely(0, dataList);

                        val packetDestroy =
                                ProtocolLibrary.getProtocolManager()
                                        .createPacket(PacketType.Play.Server.ENTITY_DESTROY);
                        packetDestroy.getIntegerArrays().writeSafely(0, new int[]{p.getEntityId()});

                        val packetSpawn =
                                ProtocolLibrary.getProtocolManager()
                                        .createPacket(PacketType.Play.Server.NAMED_ENTITY_SPAWN);
                        packetSpawn.getIntegers().writeSafely(0, p.getEntityId());
                        packetSpawn.getUUIDs().writeSafely(0, p.getUniqueId());
                        // Location in 1.8 is integer only
                        if (getMCVersion() < 9)
                            packetSpawn.getIntegers()
                                    .writeSafely(1, (int) Math.floor(p.getLocation().getX() * 32.00D))
                                    .writeSafely(2, (int) Math.floor(p.getLocation().getY() * 32.00D))
                                    .writeSafely(3, (int) Math.floor(p.getLocation().getZ() * 32.00D));
                        else
                            packetSpawn.getDoubles().writeSafely(0, p.getLocation().getX()).writeSafely(1,
                                    p.getLocation().getY()).writeSafely(2, p.getLocation().getZ());
                        packetSpawn.getBytes().writeSafely(0, (byte) (int) (p.getLocation().getYaw() * 256.0F / 360.0F))
                                .writeSafely(1, (byte) (int) (p.getLocation().getPitch() * 256.0F / 360.0F));
                        packetSpawn.getDataWatcherModifier().writeSafely(0, WrappedDataWatcher.getEntityWatcher(p));

                        val packetLook = ProtocolLibrary.getProtocolManager()
                                .createPacket(PacketType.Play.Server.ENTITY_HEAD_ROTATION);
                        packetLook.getIntegers().writeSafely(0, p.getEntityId());
                        packetLook.getBytes().writeSafely(0, (byte) (int) (p.getLocation().getYaw() * 256.0F / 360.0F));

                        try {
                            val isHiddenEntity = !player.getShownPlayers().contains(p.getUniqueId());
                            ProtocolLibrary.getProtocolManager()
                                    .sendServerPacket(bukkitPlayer, packetRemove, true);
                            ProtocolLibrary.getProtocolManager()
                                    .sendServerPacket(bukkitPlayer, packetDestroy, true);
                            ProtocolLibrary.getProtocolManager().sendServerPacket(bukkitPlayer, packetAdd, true);
                            ProtocolLibrary.getProtocolManager()
                                    .sendServerPacket(bukkitPlayer, packetSpawn, true);
                            ProtocolLibrary.getProtocolManager().sendServerPacket(bukkitPlayer, packetLook, true);
                            if (isHiddenEntity) {
                                Bukkit.getScheduler().runTaskLater(main.getLoader(), () -> {
                                    try {
                                        ProtocolLibrary
                                                .getProtocolManager()
                                                .sendServerPacket(bukkitPlayer, packetRemove, true);
                                    } catch (InvocationTargetException e) {
                                        main.getLogger().logError("Failed to send player entity update packet: %1", e
                                                .getMessage());
                                        e.printStackTrace();
                                    }
                                }, 4L);
                            }
                        } catch (InvocationTargetException e) {
                            main.getLogger().logError("Failed to send player entity update packet: %1", e.getMessage());
                            e.printStackTrace();
                        }
                    }

    }

    @Override
    public void refreshTabHeaderFooter(SpigotLanguagePlayer player, String header, String footer) {
        player.toBukkit().ifPresent(bukkitPlayer -> {
            PacketContainer packet =
                    ProtocolLibrary.getProtocolManager().createPacket(PacketType.Play.Server.PLAYER_LIST_HEADER_FOOTER);
            packet.getChatComponents().writeSafely(0, WrappedChatComponent.fromJson(header));
            packet.getChatComponents().writeSafely(1, WrappedChatComponent.fromJson(footer));
            try {
                ProtocolLibrary.getProtocolManager().sendServerPacket(bukkitPlayer, packet, true);
            } catch (InvocationTargetException e) {
                main.getLogger().logError("Failed to send tab update packet: %1", e.getMessage());
                e.printStackTrace();
            }
        });
    }

    @Override
    @SneakyThrows
    public void refreshBossbar(SpigotLanguagePlayer player, UUID uuid, String json) {
        if (getMCVersion() <= 8) return;
        val bukkitPlayerOpt = player.toBukkit();
        if (!bukkitPlayerOpt.isPresent()) return;
        val bukkitPlayer = bukkitPlayerOpt.get();

        PacketContainer packet = ProtocolLibrary.getProtocolManager().createPacket(PacketType.Play.Server.BOSS);
        packet.getUUIDs().writeSafely(0, uuid);
        if (getMCVersion() >= 17) {
            val msg = WrappedChatComponent.fromJson(json);
            val constructor = BOSSBAR_UPDATE_TITLE_ACTION_CLASS.getDeclaredConstructor(msg.getHandleType());
            constructor.setAccessible(true);
            val action = constructor.newInstance(msg.getHandle());
            packet.getModifier().writeSafely(1, action);
        } else {
            packet.getEnumModifier(Action.class, 1).writeSafely(0, Action.UPDATE_NAME);
            packet.getChatComponents().writeSafely(0, WrappedChatComponent.fromJson(json));
        }
        try {
            ProtocolLibrary.getProtocolManager().sendServerPacket(bukkitPlayer, packet, true);
        } catch (InvocationTargetException e) {
            main.getLogger().logError("Failed to send bossbar update packet: %1", e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void refreshScoreboard(SpigotLanguagePlayer player) {
        val bukkitPlayerOpt = player.toBukkit();
        if (!bukkitPlayerOpt.isPresent()) return;
        val bukkitPlayer = bukkitPlayerOpt.get();

        player.getObjectivesMap().forEach((key, value) -> {
            val packet = ProtocolLibrary.getProtocolManager()
                    .createPacket(PacketType.Play.Server.SCOREBOARD_OBJECTIVE);
            packet.getIntegers().writeSafely(0, 2); // Update display name mode
            packet.getStrings().writeSafely(0, key);
            packet.getChatComponents().writeSafely(0, WrappedChatComponent.fromJson(value.getChatJson()));
            packet.getModifier().writeSafely(2, value.getType());
            try {
                ProtocolLibrary.getProtocolManager().sendServerPacket(bukkitPlayer, packet, true);
            } catch (InvocationTargetException e) {
                main.getLogger().logError("Failed to send scoreboard objective update packet: %1", e.getMessage());
                e.printStackTrace();
            }
        });

        player.getTeamsMap().forEach((key, value) -> {
            val packet = ProtocolLibrary.getProtocolManager()
                    .createPacket(PacketType.Play.Server.SCOREBOARD_TEAM);
            packet.getIntegers().writeSafely(0, 2); // Update team info mode
            packet.getStrings().writeSafely(0, key);
            if (getMCVersion() >= 17) {
                Optional<?> meta = (Optional<?>) packet.getModifier().readSafely(3);
                if (!meta.isPresent()) {
                    Triton.get().getLogger().logError("Triton was not able to refresh a scoreboard team, probably due to changes in ProtocolLib!");
                    return;
                }

                val obj = meta.get();

                if (SCOREBOARD_TEAM_METADATA_MODIFIER == null)
                    SCOREBOARD_TEAM_METADATA_MODIFIER = new StructureModifier<>(obj.getClass());
                val structure = SCOREBOARD_TEAM_METADATA_MODIFIER.withTarget(obj);

                int j = 0;
                for (int i : Arrays.asList(3, 4, 5, 6))
                    structure.writeSafely(i, value.getOptionData().get(j++));

                val chatComponents = structure.withType(
                        MinecraftReflection.getIChatBaseComponentClass(), BukkitConverters.getWrappedChatComponentConverter());
                chatComponents.writeSafely(0, WrappedChatComponent.fromJson(value.getDisplayJson()));
                chatComponents.writeSafely(1, WrappedChatComponent.fromJson(value.getPrefixJson()));
                chatComponents.writeSafely(2, WrappedChatComponent.fromJson(value.getSuffixJson()));
            } else {
                packet.getChatComponents().writeSafely(0, WrappedChatComponent.fromJson(value.getDisplayJson()));
                packet.getChatComponents().writeSafely(1, WrappedChatComponent.fromJson(value.getPrefixJson()));
                packet.getChatComponents().writeSafely(2, WrappedChatComponent.fromJson(value.getSuffixJson()));
                int j = 0;
                for (int i : Arrays.asList(4, 5, 6, 9))
                    packet.getModifier().writeSafely(i, value.getOptionData().get(j++));
            }

            try {
                ProtocolLibrary.getProtocolManager().sendServerPacket(bukkitPlayer, packet, true);
            } catch (InvocationTargetException e) {
                main.getLogger().logError("Failed to send scoreboard team update packet: %1", e.getMessage());
                e.printStackTrace();
            }
        });
    }

    @Override
    public void refreshAdvancements(SpigotLanguagePlayer languagePlayer) {
        if (this.advancementsPacketHandler == null) return;
        
        this.advancementsPacketHandler.refreshAdvancements(languagePlayer);
    }

    @Override
    public void resetSign(Player p, SignLocation location) {
        World world = Bukkit.getWorld(location.getWorld());
        if (world == null) return;
        Block block = world.getBlockAt(location.getX(), location.getY(), location.getZ());
        BlockState state = block.getState();
        if (!(state instanceof Sign))
            return;
        String[] lines = ((Sign) state).getLines();
        if (existsSignUpdatePacket()) {
            PacketContainer container =
                    ProtocolLibrary.getProtocolManager().createPacket(PacketType.Play.Server.UPDATE_SIGN, true);
            container.getBlockPositionModifier().writeSafely(0, new BlockPosition(location.getX(), location.getY(),
                    location.getZ()));
            container.getChatComponentArrays().writeSafely(0,
                    new WrappedChatComponent[]{WrappedChatComponent.fromText(lines[0]),
                            WrappedChatComponent.fromText(lines[1]), WrappedChatComponent.fromText(lines[2]),
                            WrappedChatComponent.fromText(lines[3])});
            try {
                ProtocolLibrary.getProtocolManager().sendServerPacket(p, container, false);
            } catch (Exception e) {
                main.getLogger().logError("Failed refresh sign: %1", e.getMessage());
                e.printStackTrace();
            }
        } else {
            PacketContainer container =
                    ProtocolLibrary.getProtocolManager().createPacket(PacketType.Play.Server.TILE_ENTITY_DATA, true);
            container.getBlockPositionModifier().writeSafely(0, new BlockPosition(location.getX(), location.getY(),
                    location.getZ()));
            container.getIntegers().writeSafely(0, 9); // Action (9): Update sign text
            NbtCompound nbt = NbtFactory.asCompound(container.getNbtModifier().readSafely(0));
            for (int i = 0; i < 4; i++)
                nbt.put("Text" + (i + 1), ComponentSerializer.toString(TextComponent.fromLegacyText(lines[i])));
            nbt.put("name", "null").put("x", block.getX()).put("y", block.getY()).put("z", block.getZ()).put("id",
                    getMCVersion() <= 10 ? "Sign" : "minecraft:sign");
            try {
                ProtocolLibrary.getProtocolManager().sendServerPacket(p, container, false);
            } catch (Exception e) {
                main.getLogger().logError("Failed refresh sign: %1", e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /* UTILITIES */

    private void addEntity(World world, int id, String displayName, SpigotLanguagePlayer lp) {
        if (!lp.getEntitiesMap().containsKey(world))
            lp.getEntitiesMap().put(world, new ConcurrentHashMap<>());
        lp.getEntitiesMap().get(world).put(id, Optional.ofNullable(displayName));
    }

    @SafeVarargs
    private final void removeEntities(Stream<Integer> ids, Map<Integer, ?>... maps) {
        ids.forEach(id -> Arrays.stream(maps).filter(Objects::nonNull).forEach(map -> map.keySet().remove(id)));
    }

    private void addPlayer(World world, int id, Entity player, SpigotLanguagePlayer lp) {
        if (!lp.getPlayersMap().containsKey(world))
            lp.getPlayersMap().put(world, new ConcurrentHashMap<>());
        lp.getPlayersMap().get(world).put(id, player);
    }

    private boolean isActionbar(PacketContainer container) {
        if (getMCVersion() >= 12)
            return container.getChatTypes().readSafely(0) == EnumWrappers.ChatType.GAME_INFO;
        else
            return container.getBytes().readSafely(0) == 2;
    }

    private short getMCVersion() {
        return main.getMcVersion();
    }

    private short getMCVersionR() {
        return main.getMinorMcVersion();
    }

    private boolean existsSignUpdatePacket() {
        return getMCVersion() == 8 || (getMCVersion() == 9 && getMCVersionR() == 1);
    }

    private String translate(LanguagePlayer lp, String s, int max, MainConfig.FeatureSyntax syntax) {
        String r = translate(s, lp, syntax);
        if (r != null && r.length() > max) return r.substring(0, max);
        return r;
    }

    private String translate(String s, LanguagePlayer lp, MainConfig.FeatureSyntax syntax) {
        if (s == null) return null;
        return main.getLanguageParser().replaceLanguages(main.getLanguageManager().matchPattern(s, lp), lp, syntax);
    }

    /**
     * Translates an item stack in one of two ways:
     * - if the item has a CraftBukkit handler, the item is translated through its NBT tag;
     * - otherwise, Bukkit's ItemMeta API is used instead.
     * <p>
     * Special attention is given to Shulker Boxes (the names of the items inside them are also translated for preview purposes)
     * and to Written Books (their text is also translated).
     *
     * @param item           The item to translate. Might be mutated
     * @param languagePlayer The language player to translate for
     * @param translateBooks Whether it should translate written books
     * @return The translated item stack, which may or may not be the same as the given parameter
     */
    private ItemStack translateItemStack(ItemStack item, LanguagePlayer languagePlayer, boolean translateBooks) {
        if (item == null || item.getType() == Material.AIR) return item;
        NbtCompound compound = null;
        try {
            val nbtTagOptional = NbtFactory.fromItemOptional(item);
            if (!nbtTagOptional.isPresent()) return item;
            compound = NbtFactory.asCompound(nbtTagOptional.get());
        } catch (IllegalArgumentException ignore) {
            // This means the item is just an ItemStack and not a CraftItemStack
            // However we can still translate stuff using the Bukkit ItemMeta API instead of NBT tags
        }
        // Translate the contents of shulker boxes
        if (compound != null && compound.containsKey("BlockEntityTag")) {
            NbtCompound blockEntityTag = compound.getCompoundOrDefault("BlockEntityTag");
            if (blockEntityTag.containsKey("Items")) {
                NbtBase<?> itemsBase = blockEntityTag.getValue("Items");
                if (itemsBase instanceof NbtList<?>) {
                    NbtList<?> items = (NbtList<?>) itemsBase;
                    Collection<? extends NbtBase<?>> itemsCollection = items.asCollection();
                    for (NbtBase<?> base : itemsCollection) {
                        NbtCompound invItem = NbtFactory.asCompound(base);
                        if (!invItem.containsKey("tag")) continue;
                        NbtCompound tag = invItem.getCompoundOrDefault("tag");
                        translateNbtItem(tag, languagePlayer, false);
                    }
                }
            }
        }
        // If the compound is null, the item is not a CraftItemStack, therefore it doesn't have NBT data
        if (compound != null) {
            // try to translate name and lore
            translateNbtItem(compound, languagePlayer, true);
            // translate the content of written books
            if (translateBooks && item.getType() == Material.WRITTEN_BOOK && main.getConf().isBooks()) {
                if (compound.containsKey("pages")) {
                    NbtList<String> pages = compound.getList("pages");
                    Collection<NbtBase<String>> pagesCollection = pages.asCollection();
                    List<String> newPagesCollection = new ArrayList<>();
                    for (NbtBase<String> page : pagesCollection) {
                        if (page.getValue().startsWith("\"")) {
                            String result = translate(page.getValue()
                                    .substring(1, page.getValue().length() - 1), languagePlayer, main.getConf()
                                    .getItemsSyntax());
                            if (result != null)
                                newPagesCollection.add(
                                        ComponentSerializer.toString(
                                                TextComponent.fromLegacyText(result)));
                        } else {
                            BaseComponent[] result = main.getLanguageParser()
                                    .parseComponent(languagePlayer, main.getConf().getItemsSyntax(), ComponentSerializer
                                            .parse(page.getValue()));
                            if (result != null)
                                newPagesCollection.add(ComponentSerializer.toString(result));
                        }
                    }
                    compound.put("pages", NbtFactory.ofList("pages", newPagesCollection));
                }
            }
        }
        // If the item is not a craft item, use the Bukkit API
        if (compound == null && item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta.hasDisplayName())
                meta.setDisplayName(translate(meta.getDisplayName(),
                        languagePlayer, main.getConf().getItemsSyntax()));
            if (meta.hasLore()) {
                List<String> newLore = new ArrayList<>();
                for (String lore : meta.getLore()) {
                    String result = translate(lore, languagePlayer,
                            main.getConf().getItemsSyntax());
                    if (result != null)
                        newLore.addAll(Arrays.asList(result.split("\n")));
                }
                meta.setLore(newLore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Translates an item's name (and optionally lore) by their NBT tag, mutating the given compound
     *
     * @param compound       The NBT tag of the item
     * @param languagePlayer The language player to translate for
     * @param translateLore  Whether to attempt to translate the lore of the item
     */
    private void translateNbtItem(NbtCompound compound, LanguagePlayer languagePlayer, boolean translateLore) {
        if (!compound.containsKey("display")) return;
        NbtCompound display = compound.getCompoundOrDefault("display");
        if (display.containsKey("Name")) {
            String name = display.getStringOrDefault("Name");
            if (getMCVersion() >= 13) {
                BaseComponent[] result = main.getLanguageParser()
                        .parseComponent(languagePlayer, main.getConf().getItemsSyntax(), ComponentSerializer
                                .parse(name));
                if (result == null)
                    display.remove("Name");
                else
                    display.put("Name", ComponentSerializer.toString(ComponentUtils.ensureNotItalic(Arrays.stream(result))));
            } else {
                String result = translate(name, languagePlayer, main.getConf().getItemsSyntax());
                if (result == null)
                    display.remove("Name");
                else
                    display.put("Name", result);
            }
        }
        if (translateLore && display.containsKey("Lore")) {
            NbtList<String> loreNbt = display.getListOrDefault("Lore");

            List<String> newLore = new ArrayList<>();
            for (String lore : loreNbt) {
                if (getMCVersion() >= 13) {
                    BaseComponent[] result = main.getLanguageParser()
                            .parseComponent(languagePlayer, main.getConf().getItemsSyntax(), ComponentSerializer
                                    .parse(lore));
                    if (result != null) {
                        List<List<BaseComponent>> splitLoreLines = ComponentUtils.splitByNewLine(Arrays.asList(result));
                        newLore.addAll(splitLoreLines.stream()
                                .map(comps -> ComponentUtils.ensureNotItalic(comps.stream()))
                                .map(ComponentSerializer::toString)
                                .collect(Collectors.toList()));
                    }
                } else {
                    String result = translate(lore, languagePlayer,
                            main.getConf().getItemsSyntax());
                    if (result != null)
                        newLore.addAll(Arrays.asList(result.split("\n")));
                }
            }
            display.put(NbtFactory.ofList("Lore", newLore));
        }
    }

    private boolean isPlayerInventoryOpen(Player player) {
        val nmsHandle = NMSUtils.getHandle(player);

        try {
            return Objects.requireNonNull(PLAYER_ACTIVE_CONTAINER_FIELD).get(nmsHandle).getClass() == CONTAINER_PLAYER_CLASS;
        } catch (IllegalAccessException | NullPointerException e) {
            return false;
        }
    }

    /**
     * BossBar packet Action wrapper
     */
    public enum Action {
        ADD, REMOVE, UPDATE_PCT, UPDATE_NAME, UPDATE_STYLE, UPDATE_PROPERTIES
    }

}
