package org.cloudburstmc.proxypass.network.bedrock.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.log4j.Log4j2;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.nbt.util.stream.LittleEndianDataOutputStream;
import org.cloudburstmc.protocol.bedrock.BedrockSession;
import org.cloudburstmc.protocol.bedrock.data.SubChunkData;
import org.cloudburstmc.protocol.bedrock.data.SubChunkRequestResult;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.cloudburstmc.proxypass.ProxyPass;
import org.cloudburstmc.proxypass.network.bedrock.util.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;


@Log4j2
@RequiredArgsConstructor
public class DownstreamPacketHandler implements BedrockPacketHandler {
    private final BedrockSession session;
    private final ProxyPlayerSession player;
    private final ProxyPass proxy;

    private int dimension;

    private Map<Long, byte[]> blockEntityMap = new HashMap<>();

    @Override
    public PacketSignal handle(AvailableEntityIdentifiersPacket packet) {
        proxy.saveNBT("entity_identifiers", packet.getIdentifiers());
        return PacketSignal.UNHANDLED;
    }

    // Handles biome definitions when client-side chunk generation is enabled
    @Override
    public PacketSignal handle(CompressedBiomeDefinitionListPacket packet) {
        proxy.saveNBT("biome_definitions_compressed", packet.getDefinitions());
        return PacketSignal.UNHANDLED;
    }

    // Handles biome definitions when client-side chunk generation is disabled
    @Override
    public PacketSignal handle(BiomeDefinitionListPacket packet) {
        proxy.saveNBT("biome_definitions", packet.getDefinitions());
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(StartGamePacket packet) {
        List<DataEntry> itemData = new ArrayList<>();
        LinkedHashMap<String, Integer> legacyItems = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> legacyBlocks = new LinkedHashMap<>();

        for (ItemDefinition entry : packet.getItemDefinitions()) {
            if (entry.getRuntimeId() > 255) {
                legacyItems.putIfAbsent(entry.getIdentifier(), entry.getRuntimeId());
            } else {
                String id = entry.getIdentifier();
                if (id.contains(":item.")) {
                    id = id.replace(":item.", ":");
                }
                if (entry.getRuntimeId() > 0) {
                    legacyBlocks.putIfAbsent(id, entry.getRuntimeId());
                } else {
                    legacyBlocks.putIfAbsent(id, 255 - entry.getRuntimeId());
                }
            }

            dimension = packet.getDimensionId();

            itemData.add(new DataEntry(entry.getIdentifier(), entry.getRuntimeId()));
            ProxyPass.legacyIdMap.put(entry.getRuntimeId(), entry.getIdentifier());
        }

        SimpleDefinitionRegistry<ItemDefinition> itemDefinitions = SimpleDefinitionRegistry.<ItemDefinition>builder()
                .addAll(packet.getItemDefinitions())
                .add(new SimpleItemDefinition("minecraft:empty", 0, false))
                .build();

        this.session.getPeer().getCodecHelper().setItemDefinitions(itemDefinitions);
        player.getUpstream().getPeer().getCodecHelper().setItemDefinitions(itemDefinitions);

        this.session.getPeer().getCodecHelper().setBlockDefinitions(this.proxy.getBlockDefinitions());
        player.getUpstream().getPeer().getCodecHelper().setBlockDefinitions(this.proxy.getBlockDefinitions());

        itemData.sort(Comparator.comparing(o -> o.name));

        proxy.saveJson("legacy_block_ids.json", sortMap(legacyBlocks));
        proxy.saveJson("legacy_item_ids.json", sortMap(legacyItems));
        proxy.saveJson("runtime_item_states.json", itemData);

        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(CraftingDataPacket packet) {
        RecipeUtils.writeRecipes(packet, this.proxy);
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(DisconnectPacket packet) {
        this.session.disconnect();
        // Let the client see the reason too.
        return PacketSignal.UNHANDLED;
    }

    private void dumpCreativeItems(ItemData[] contents) {
        List<CreativeItemEntry> entries = new ArrayList<>();
        for (ItemData data : contents) {
            ItemDefinition entry = data.getDefinition();
            String id = entry.getIdentifier();
            Integer damage = data.getDamage() == 0 ? null : (int) data.getDamage();

            String blockTag = null;
            Integer blockRuntimeId = null;
            if (data.getBlockDefinition() instanceof NbtBlockDefinitionRegistry.NbtBlockDefinition definition) {
                blockTag = encodeNbtToString(definition.tag());
            } else if (data.getBlockDefinition() != null) {
                blockRuntimeId = data.getBlockDefinition().getRuntimeId();
            }

            NbtMap tag = data.getTag();
            String tagData = null;
            if (tag != null) {
                tagData = encodeNbtToString(tag);
            }
            entries.add(new CreativeItemEntry(id, damage, blockRuntimeId, blockTag, tagData));
        }

        CreativeItems items = new CreativeItems(entries);

        proxy.saveJson("creative_items.json", items);
    }

    @Override
    public PacketSignal handle(CreativeContentPacket packet) {
        try {
            dumpCreativeItems(packet.getContents());
        } catch (Exception e) {
            log.error("Failed to dump creative contents", e);
        }
        return PacketSignal.UNHANDLED;
    }

    // Handles creative items for versions prior to 1.16
    @Override
    public PacketSignal handle(InventoryContentPacket packet) {
        if (packet.getContainerId() == ContainerId.CREATIVE) {
            dumpCreativeItems(packet.getContents().toArray(new ItemData[0]));
        }
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(LevelChunkPacket packet) {
        long chunkIndex = chunkIndex(packet.getChunkX(), packet.getChunkZ());

        int subChunksCount = packet.isRequestSubChunks() ?
                (this.dimension == 0 ? 24 : 16) :
                packet.getSubChunksLength();

        ByteBuf buffer = packet.getData();
        if (packet.isRequestSubChunks()) {
            PaletteHolder[] biomes = new PaletteHolder[subChunksCount];
            for (int i = 0; i < biomes.length; i++) {
                PaletteHolder palette = this.readPalettedBiomes(buffer);
                if (palette == null && i == 0) {
                    throw new IllegalStateException("First biome palette can not point to previous!");
                }

                if (palette == null) {
                    palette = biomes[i - 1].copy();
                }
                biomes[i] = palette;
            }

            short borderBlocksSize = buffer.readUnsignedByte();
            buffer.skipBytes(borderBlocksSize); // 1 byte per borderBlock

            byte[] blockEntities = new byte[buffer.readableBytes()];
            buffer.readBytes(blockEntities);
            this.blockEntityMap.put(chunkIndex, blockEntities);
        } else {
            log.info("LevelChunkPacket not requesting subchunks!");
            SubChunkHolder[] subChunks = new SubChunkHolder[subChunksCount];
            for (int y = 0, i = 0; y < 15 && i < subChunksCount ;y++, i++) {
                int subChunkVersion = buffer.readUnsignedByte();

                log.info("SubChunk version: " + subChunkVersion);

                if (subChunkVersion == 1) {
                    PPBlockStorage storage = new PPBlockStorage();
                    storage.setLegacy(false);
                    storage.setPaletteHeader(buffer.readUnsignedByte());
                    // storage is 16 * 16 * 16 large
                    int blocksPerWord = Integer.SIZE / storage.getBitsPerBlock();
                    int wordsCount = (4096 + blocksPerWord - 1) / blocksPerWord;
                    if (!storage.isPersistent()) {
                        throw new IllegalStateException("SubChunk version 1 does not support runtime storages over network!");
                    }

                    int[] words = new int[wordsCount];
                    for (int a = 0; a < wordsCount; a++) {
                        words[a] = buffer.readIntLE();
                    }
                    storage.setWords(words);

                    int paletteSize = buffer.readIntLE();
                    storage.setPalette(new IntArrayList());
                    try (ByteBufInputStream stream = new ByteBufInputStream(buffer);
                         NBTInputStream nbtInputStream = NbtUtils.createReaderLE(stream)) {
                        for (int b = 0; b < paletteSize; b++) {
                            //int runtimeId = blockPalette.state2RuntimeId((NbtMap) nbtInputStream.readTag());
                            storage.getPalette().add(0);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException("Cannot read persistent block palette", e);
                    }

                    PPBlockStorage[] storages = new PPBlockStorage[2];
                    storages[0] = storage;
                    subChunks[y] = new SubChunkHolder(y, storages);
                } else if (subChunkVersion == 0) {
                    byte[] blockIds = new byte[4096];
                    buffer.readBytes(blockIds);

                    byte[] blockData = new byte[2048];
                    buffer.readBytes(blockData);

                    buffer.skipBytes(4096); // blockLight

                    PPBlockStorage[] storages = new PPBlockStorage[2];
                    PPBlockStorage storage = new PPBlockStorage();
                    storage.setLegacy(true);
                    //storage.setBlockIds(blockIds);
                    storage.setBlockData(blockData);
                    storages[0] = storage;
                }
            }

            // Read level data
            // 1. Biome data
            byte[] biomeData = new byte[256];
            buffer.readBytes(biomeData);

            // 2. Border blocks
            short borderBlocksSize = buffer.readUnsignedByte();
            buffer.skipBytes(borderBlocksSize); // 1 byte per borderBlock

            // 3. Block entities
            byte[] blockEntities = new byte[buffer.readableBytes()];
            ByteBuf nbtbuf = buffer.readBytes(blockEntities);

            try {
                NBTInputStream nbtInputStream = NbtUtils.createReaderLE(new ByteBufInputStream(nbtbuf));
                var tag = nbtInputStream.readTag();
                log.info("LevelChunkPacket tag: {}", tag);
            } catch (Exception e) {
                log.error("Failed to read level chunk packet", e);
            }


        }
        return PacketSignal.UNHANDLED;
    }

    private PaletteHolder readPalettedBiomes(ByteBuf buffer) {
        int index = buffer.readerIndex();
        int size = buffer.readUnsignedByte() >> 1;
        if (size == 127) {
            // This means this paletted storage had the flag pointing to the previous one
            return null;
        }

        buffer.readerIndex(index);
        PaletteHolder palette = new PaletteHolder();
        // deserialize palette
        palette.setPaletteHeader(buffer.readUnsignedByte());
        if (palette.isPersistent()) {
            throw new IllegalStateException("SubChunk version 8 does not support persistent storages over network!");
        }

        int paletteSize = 1;
        if (palette.getBitsPerBlock() != 0) {
            // storage is 16 * 16 * 16 large
            int blocksPerWord = Integer.SIZE / palette.getBitsPerBlock();
            int wordsCount = (4096 + blocksPerWord - 1) / blocksPerWord;
            int[] words = new int[wordsCount];
            for (int i = 0; i < wordsCount; i++) {
                words[i] = buffer.readIntLE();
            }
            palette.setWords(words);
            paletteSize = VarInts.readInt(buffer);
        }

        palette.setPalette(new IntArrayList());
        for (int i = 0; i < paletteSize; i++) {
            palette.getPalette().add(VarInts.readInt(buffer));
        }

        return palette;
    }

    @Override
    public PacketSignal handle(SubChunkPacket packet) {
        for (SubChunkData subChunkData : packet.getSubChunks()) {
            Vector3i centerPos = packet.getCenterPosition();
            int chunkX = centerPos.getX() + subChunkData.getPosition().getX();
            int chunkZ = centerPos.getZ() + subChunkData.getPosition().getZ();
            int subChunkY = centerPos.getY() + subChunkData.getPosition().getY();
            long chunkIndex = chunkIndex(chunkX, chunkZ);

            SubChunkRequestResult result = subChunkData.getResult();
            if (result != SubChunkRequestResult.SUCCESS && result != SubChunkRequestResult.SUCCESS_ALL_AIR) {
                log.debug("CenterPos x={} z={} y={} subChunk [{}] with result {}", centerPos.getX(), centerPos.getZ(), centerPos.getY(), subChunkData.getPosition(), result);
                return PacketSignal.UNHANDLED;
            }

            int offsetY = packet.getDimension() == 0 ? 4 : 0;
            if (result == SubChunkRequestResult.SUCCESS_ALL_AIR) {
                // empty sub chunk
            } else {
                // Read subchunk data
                ByteBuf buffer = subChunkData.getData();
                int subChunkVersion = buffer.readUnsignedByte();

                switch (subChunkVersion) {
                    case 0, 2, 3 -> {
                        log.info("Reading subchunk version {}!", subChunkVersion);
                        // TODO
                    }
                    case 1 -> {
                        log.info("Reading subchunk version 1!");
                        // TODO - a
                    }
                    case 4, 5, 6, 7 -> {
                        log.info("Reading subchunk version {}!", subChunkVersion);
                        // TODO - b
                    }
                    case 8 -> {
                        log.info("Reading subchunk version 8!");
                        // TODO - c
                    }
                    case 9 -> {
                        int storagescount = buffer.readUnsignedByte();
                        buffer.readUnsignedByte(); // sectionY
                        PPBlockStorage[] storages = new PPBlockStorage[storagescount];

                        for (int y = 0; y < storagescount; y++) {
                            PPBlockStorage storage = new PPBlockStorage();
                            storage.setLegacy(false);
                            storage.setPaletteHeader(buffer.readUnsignedByte());
                            if (storage.isPersistent()) {
                                throw new IllegalStateException("SubChunk version 8 does not support persistent storages over network!");
                            }

                            int paletteSize = 1;
                            if (storage.getBitsPerBlock() != 0) {
                                // storage is 16 * 16 * 16 large
                                int blocksPerWord = Integer.SIZE / storage.getBitsPerBlock();
                                int wordsCount = (4096 + blocksPerWord - 1) / blocksPerWord;
                                int[] words = new int[wordsCount];
                                for (int i = 0; i < wordsCount; i++) {
                                    words[i] = buffer.readIntLE();
                                }
                                storage.setWords(words);
                                paletteSize = VarInts.readInt(buffer);
                            }

                            storage.setPalette(new IntArrayList());
                            for (int i = 0; i < paletteSize; i++) {
                                storage.getPalette().add(VarInts.readInt(buffer));
                            }
                            storages[y] = storage;
                        }
                        // storages are done!

                        byte[] blockEntities = blockEntityMap.get(chunkIndex);
                        if (buffer.readableBytes() > 0) {

                            int size = blockEntities == null ? 0 : blockEntities.length;
                            byte[] blockEntities1 = new byte[size + buffer.readableBytes()];

                            if (size > 0) {
                                System.arraycopy(blockEntities, 0, blockEntities1, 0, size);
                            }
                            ByteBuf nbtbuf = buffer.readBytes(blockEntities, size, buffer.readableBytes());

                            // finally, juicy block entity nbt
                            try {
                                NBTInputStream nbtInputStream = new NBTInputStream(new ByteBufInputStream(nbtbuf));
                                var b = nbtInputStream.readTag();
                                log.info("Block entity nbt tag: {}", b);
                            } catch (Exception e) {
                                log.error("Failed to read block entity nbt", e);
                            } finally {
                                nbtbuf.release();
                            }

                            blockEntityMap.put(chunkIndex, blockEntities1);
                        }


                    }
                    default -> {
                        log.warn("Unknown subchunk version {}", subChunkVersion);
                    }
                }
            }

        }

        return PacketSignal.UNHANDLED;
    }

    public static long chunkIndex(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (chunkZ & 0xffffffffL);
    }

    private static Map<String, Integer> sortMap(Map<String, Integer> map) {
        return map.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (oldValue, newValue) -> oldValue,
                        LinkedHashMap::new));
    }

    private static String encodeNbtToString(NbtMap tag) {
        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
             NBTOutputStream stream = new NBTOutputStream(new LittleEndianDataOutputStream(byteArrayOutputStream))) {
            stream.writeTag(tag);
            return Base64.getEncoder().encodeToString(byteArrayOutputStream.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Value
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class CreativeItemEntry {
        private final String id;
        private final Integer damage;
        private final Integer blockRuntimeId;
        @JsonProperty("block_state_b64")
        private final String blockTag;
        @JsonProperty("nbt_b64")
        private final String nbt;
    }

    @Value
    private static class CreativeItems {
        private final List<CreativeItemEntry> items;
    }

    @Value
    private static class RuntimeEntry {
        private static final Comparator<RuntimeEntry> COMPARATOR = Comparator.comparingInt(RuntimeEntry::getId)
                .thenComparingInt(RuntimeEntry::getData);

        private final String name;
        private final int id;
        private final int data;
    }

    @Value
    private static class DataEntry {
        private final String name;
        private final int id;
    }
}
