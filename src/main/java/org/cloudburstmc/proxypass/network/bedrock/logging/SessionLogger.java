package org.cloudburstmc.proxypass.network.bedrock.logging;

import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import lombok.extern.log4j.Log4j2;
import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.bedrock.BedrockSession;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.CreativeContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.SubChunkPacket;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.cloudburstmc.proxypass.ProxyPass;
import org.jose4j.json.internal.json_simple.JSONObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;


@Log4j2
public class SessionLogger {

    private static final String PATTERN_FORMAT = "HH:mm:ss:SSS";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern(PATTERN_FORMAT)
            .withZone(ZoneId.systemDefault());
    private static final String LOG_FORMAT = "[%s] [%s] - %s";

    private static final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    private final ProxyPass proxy;

    private final Path dataPath;

    private final Path logPath;

    private final Deque<String> logBuffer = new ArrayDeque<>();

    public SessionLogger(ProxyPass proxy, Path sessionsDir, String displayName, long timestamp) {
        this.proxy = proxy;
        this.dataPath = sessionsDir.resolve(displayName + '-' + timestamp);
        this.logPath = dataPath.resolve("packets.log");
    }

    public void start() {
        if (proxy.getConfiguration().isLoggingPackets()){
            if (proxy.getConfiguration().getLogTo().logToFile) {
                log.debug("Packets will be logged under " + logPath.toString());
                try {
                    Files.createDirectories(dataPath);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            executor.scheduleAtFixedRate(this::flushLogBuffer, 5, 5, TimeUnit.SECONDS);
        }
    }

    public void saveImage(String name, BufferedImage image) {
        Path path = dataPath.resolve(name + ".png");
        try (OutputStream stream = Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ImageIO.write(image, "png", stream);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void saveJson(String name, JSONObject object) throws IOException {
        if (!logPath.getParent().resolve(name + ".json").toFile().exists()) {
            Files.createFile(logPath.getParent().resolve(name + ".json"));
        }
        ObjectWriter jsonout = ProxyPass.JSON_MAPPER.writer(new DefaultPrettyPrinter());
        jsonout.writeValue(new FileOutputStream(logPath.getParent().resolve(name + ".json").toFile()), object);
    }

    public void saveJson(String name, JsonNode node) throws IOException {
        ObjectWriter jsonout = ProxyPass.JSON_MAPPER.writer(new DefaultPrettyPrinter());
        jsonout.writeValue(new FileOutputStream(logPath.getParent().resolve(name + ".json").toFile()), node);
    }

    public void saveJson(String name, byte[] encodedJsonString) {
        Path geometryPath = dataPath.resolve(name +".json");
        try {
            Files.write(geometryPath, encodedJsonString, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void logPacket(BedrockSession session, BedrockPacket packet, boolean upstream) {

        /*
        if (packet instanceof LevelChunkPacket levelChunkPacket) {
            log.info("LEVEL CHUNK PACKET");

            int subChunksCount = levelChunkPacket.isRequestSubChunks() ? 16 : levelChunkPacket.getSubChunksLength();
            log.info("Subchunks count: " + subChunksCount + " "+ (levelChunkPacket.isRequestSubChunks()));
            ByteBuf payload = levelChunkPacket.getData().copy();
            //payload.resetReaderIndex();
            //payload.resetWriterIndex();

            if (levelChunkPacket.isRequestSubChunks()) {
                // pls no
                log.info("Requesting subchunks");
                return;
            } else {
                // TODO 15 was just ripped, what's the real value?
                for (int y = 0, i = 0; y < 15 && i < subChunksCount; y++, i++) {
                    int subChunkVersion = payload.readByte();
                    log.info("Subchunk version: " + subChunkVersion);
                    // deserialize subchunks
                    // version 9, hive uses 0... hnnng
                    if (subChunkVersion == 9) {
                        int storagesCount = payload.readByte();
                        payload.readByte(); // sectionY
                        for (int z = 0; z < storagesCount; z++) {
                            //palette header
                            int paletteHeader = payload.readByte();
                            int blocksPerWord = Integer.SIZE / paletteHeader >> 1;
                            int wordsCount = (4096 + blocksPerWord - 1) / blocksPerWord;
                            int[] words = new int[wordsCount];
                            for (int q = 0; q < wordsCount; q++) {
                                words[i] = payload.readIntLE();
                            }
                            int palettesize = VarInts.readInt(payload);
                            for (int qq = 0; qq < palettesize; qq++) {
                                VarInts.readInt(payload);
                            }
                        }
                    } else if (subChunkVersion == 0 || subChunkVersion == 2 || subChunkVersion == 3) {
                        // version 0
                        byte[] blockIds = new byte[4096];
                        payload.readBytes(blockIds);

                        byte[] blockData = new byte[2048];
                        payload.readBytes(blockData);

                        payload.skipBytes(4096); // blocklight
                    }
                }

                // read level data: biomes
                byte[] biomeData = new byte[256];
                payload.readBytes(biomeData);

                // border block size
                short borderBlockSize = payload.readUnsignedByte();
                payload.skipBytes(borderBlockSize);

                byte[] blockEntities = new byte[payload.readableBytes()];
                payload.readBytes(blockEntities);
                NBTInputStream nbtInputStream = NbtUtils.createNetworkReader(new ByteArrayInputStream(blockEntities));
                try {
                    var a = nbtInputStream.readTag();
                    log.info(a.toString());

                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    try {
                        nbtInputStream.close();
                    } catch (IOException ignored) {
                    }
                }

            }
        }

         */

        String logPrefix = getLogPrefix(upstream);
        if (!proxy.isIgnoredPacket(packet.getClass())) {
            if (session.isLogging() && log.isTraceEnabled()) {
                log.trace("{} {}: {}", logPrefix, session.getSocketAddress(), packet);
            }

            String logMessage = String.format(LOG_FORMAT, FORMATTER.format(Instant.now()), logPrefix, packet);
            if (proxy.getConfiguration().isLoggingPackets()) {
                logToBuffer(() -> logMessage);
            }

            if (proxy.getConfiguration().isLoggingPackets() && proxy.getConfiguration().getLogTo().logToConsole) {
                System.out.println(logMessage);
            }
        }
    }

    private String getLogPrefix(boolean upstream) {
        return upstream ? "SERVER BOUND" : "CLIENT BOUND";
    }

    private void logToBuffer(Supplier<String> supplier) {
        synchronized (logBuffer) {
            logBuffer.addLast(supplier.get());
        }
    }

    private void flushLogBuffer() {
        synchronized (logBuffer) {
            try {
                if (proxy.getConfiguration().getLogTo().logToFile) {
                    Files.write(logPath, logBuffer, StandardOpenOption.APPEND, StandardOpenOption.CREATE);
                }
                logBuffer.clear();
            } catch (IOException e) {
                log.error("Unable to flush packet log", e);
            }
        }
    }
}
